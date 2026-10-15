package com.roadwise.backend.service;

import com.roadwise.backend.model.MfaRecoveryCode;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.MfaRecoveryCodeRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Issues and redeems single-use recovery codes.
 *
 * <p>These exist because any {@code ANY_ONE} policy without an escape hatch is a
 * lockout waiting to happen: lose the phone and the account is gone. That makes the
 * codes password-equivalent, so they are BCrypt-hashed at rest, single use, and
 * re-issuing replaces the whole set rather than appending to it.
 *
 * <p>Because they are hashes, redemption cannot look a code up by its digest — each
 * unused code has to be compared in turn. The repository read takes a row lock so
 * redeem-and-mark is atomic and the same code cannot be spent twice concurrently.
 */
@Service
public class MfaRecoveryCodeService {

    /**
     * Deliberately excludes 0/O and 1/I/L. Users transcribe these by hand off paper
     * or a screenshot, and an alphabet that invites ambiguity produces support
     * requests that look like failed logins.
     */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final int CODE_LENGTH = 12;
    private static final int CODE_COUNT = 8;

    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaRecoveryCodeService(MfaRecoveryCodeRepository recoveryCodeRepository,
                                  PasswordEncoder passwordEncoder) {
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public long remaining(User user) {
        return recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId());
    }

    /**
     * Replaces the user's entire set with fresh codes.
     *
     * @return the plaintext codes, in display order. This is the only moment they
     *         exist outside a hash, so the caller must show them once and never
     *         persist or log them.
     */
    @Transactional
    public List<String> regenerate(User user) {
        recoveryCodeRepository.deleteByUserId(user.getId());

        List<String> plaintext = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = generate();
            plaintext.add(format(code));
            recoveryCodeRepository.save(new MfaRecoveryCode(user, passwordEncoder.encode(code)));
        }
        return plaintext;
    }

    /**
     * Redeems a recovery code.
     *
     * <p>Every unused code is compared even after a match, so the response time does
     * not reveal how far down the list the match sat.
     */
    @Transactional
    public boolean consume(User user, String submitted) {
        String normalized = normalize(submitted);
        if (normalized.length() != CODE_LENGTH) {
            return false;
        }

        List<MfaRecoveryCode> candidates = recoveryCodeRepository.findUnusedForUpdate(user.getId());
        MfaRecoveryCode matched = null;
        for (MfaRecoveryCode candidate : candidates) {
            if (passwordEncoder.matches(normalized, candidate.getCodeHash()) && matched == null) {
                matched = candidate;
            }
        }
        if (matched == null) {
            return false;
        }

        matched.setUsedAt(LocalDateTime.now());
        recoveryCodeRepository.save(matched);
        return true;
    }

    /** Strips the grouping dashes and case that users type in whatever way feels natural. */
    private static String normalize(String submitted) {
        if (submitted == null) {
            return "";
        }
        return submitted.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    private String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(unbiasedRandomIndex()));
        }
        return sb.toString();
    }

    /**
     * Rejection sampling rather than a bare {@code nextInt(bound)}.
     *
     * <p>31 does not divide 256, so a plain modulo would make the first eight letters
     * marginally likelier than the rest. The skew is tiny, but it costs four lines to
     * not have, and "the bias is negligible" is a harder position to defend than
     * "there is no bias".
     */
    private int unbiasedRandomIndex() {
        int bound = (256 / ALPHABET.length()) * ALPHABET.length();
        int value;
        do {
            value = secureRandom.nextInt(256);
        } while (value >= bound);
        return value % ALPHABET.length();
    }

    private static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12);
    }
}
