package com.roadwise.backend.service;

import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.MfaFactorRepository;
import com.roadwise.backend.repository.MfaRecoveryCodeRepository;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The single authority on which MFA factors a user may use.
 *
 * <p>Factor selection is a convenience for the user, never a decision they get to
 * make on the server's behalf. The frontend renders whatever
 * {@link #offeredFactorTypes(User)} returns and can only present a factor back;
 * every request is re-checked here with {@link #isFactorAllowed(User, String)}
 * against the user's actual enrolment. Nothing that arrives in a request body is
 * treated as proof that a factor is enrolled.
 */
@Service
public class MfaPolicyService {

    /** Email OTP is the sole acceptable factor. Default for every pre-existing account. */
    public static final String POLICY_EMAIL_OTP = "EMAIL_OTP";

    /** The user may satisfy MFA with any single active factor. */
    public static final String POLICY_ANY_ONE = "ANY_ONE";

    /**
     * Not an enrolled factor: a break-glass alternative that bypasses the normal
     * picker, offered only while unused codes remain.
     */
    public static final String RECOVERY_CODE = "RECOVERY_CODE";

    private final MfaFactorRepository mfaFactorRepository;
    private final MfaRecoveryCodeRepository mfaRecoveryCodeRepository;
    private final UserRepository userRepository;

    public MfaPolicyService(MfaFactorRepository mfaFactorRepository,
                            MfaRecoveryCodeRepository mfaRecoveryCodeRepository,
                            UserRepository userRepository) {
        this.mfaFactorRepository = mfaFactorRepository;
        this.mfaRecoveryCodeRepository = mfaRecoveryCodeRepository;
        this.userRepository = userRepository;
    }

    /** @return true when the account has a usable mailbox, making email OTP possible. */
    public boolean hasUsableEmail(User user) {
        return user != null && user.getEmail() != null && !user.getEmail().isBlank();
    }

    /**
     * The factors this user may currently choose from, in display order.
     *
     * <p>Email OTP leads because it is the baseline every account can already use,
     * which also makes it the natural fallback when a second factor is lost.
     */
    public List<String> offeredFactorTypes(User user) {
        List<String> offered = new ArrayList<>();
        if (hasUsableEmail(user)) {
            offered.add(MfaFactor.TYPE_EMAIL_OTP);
        }
        for (MfaFactor factor : activeSecondFactors(user)) {
            if (!offered.contains(factor.getFactorType())) {
                offered.add(factor.getFactorType());
            }
        }
        if (mfaRecoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId()) > 0) {
            offered.add(RECOVERY_CODE);
        }
        return offered;
    }

    /** @return true when the user has at least one active factor beyond email. */
    public boolean hasSecondFactor(User user) {
        return !activeSecondFactors(user).isEmpty();
    }

    /**
     * @return true when {@code factorType} is genuinely available to this user.
     *         Callers must treat a false here as a rejecting condition regardless of
     *         what the client claims it is allowed to use.
     */
    public boolean isFactorAllowed(User user, String factorType) {
        return factorType != null && offeredFactorTypes(user).contains(factorType);
    }

    /**
     * Effective policy, derived from enrolment rather than trusted from the column at
     * face value: ANY_ONE only once a second factor actually exists.
     */
    public String effectivePolicy(User user) {
        return hasSecondFactor(user) ? POLICY_ANY_ONE : POLICY_EMAIL_OTP;
    }

    /**
     * Re-derives {@code users.mfa_policy} from current enrolment.
     *
     * <p>Without this the column drifts the moment a factor is added or removed, and
     * a stored policy that disagrees with reality is worse than no column at all.
     */
    @Transactional
    public void syncPolicy(User user) {
        String derived = effectivePolicy(user);
        if (derived.equals(user.getMfaPolicy())) {
            return;
        }
        // Callers arrive here with a detached principal: JwtAuthenticationFilter loads
        // the user outside any transaction, and open-in-view is off. Mutating the
        // argument would therefore look like it worked and persist nothing, so the
        // change is applied to a managed instance re-read through the repository.
        userRepository.findById(user.getId()).ifPresent(managed -> managed.setMfaPolicy(derived));
    }

    /**
     * Guards factor removal so a user cannot disarm their own second factor into an
     * account they can no longer authenticate against.
     *
     * @throws IllegalStateException when removal would leave no way to satisfy MFA
     */
    public void assertCanRemove(User user, MfaFactor factor) {
        if (MfaFactor.TYPE_EMAIL_OTP.equals(factor.getFactorType())) {
            throw new IllegalStateException("The email OTP factor cannot be removed.");
        }
        boolean anotherSecondFactor = activeSecondFactors(user).stream()
                .anyMatch(f -> !f.getId().equals(factor.getId()));
        if (!anotherSecondFactor
                && mfaRecoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId()) == 0) {
            throw new IllegalStateException(
                    "This is your only second factor and you have no unused recovery codes. "
                            + "Enrol another factor or generate recovery codes before removing it.");
        }
    }

    private List<MfaFactor> activeSecondFactors(User user) {
        return mfaFactorRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(user.getId(), MfaFactor.STATUS_ACTIVE)
                .stream()
                .filter(f -> !MfaFactor.TYPE_EMAIL_OTP.equals(f.getFactorType()))
                .toList();
    }
}
