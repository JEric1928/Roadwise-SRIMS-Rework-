package com.roadwise.backend.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single source of truth for password strength on the server.
 *
 * <p>The frontend policy is advisory only (client checks are bypassable), so
 * every password-setting path must validate here as well.</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    // BCrypt only considers the first 72 bytes; reject longer input instead of
    // silently truncating it.
    public static final int MAX_LENGTH = 72;

    private static final Pattern HAS_UPPER = Pattern.compile(".*[A-Z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern HAS_SPECIAL = Pattern.compile(".*[^A-Za-z0-9].*");

    // Small offline breach/guess list. A full breached-password service (HIBP)
    // can be plugged in later without changing callers.
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "passw0rd", "p@ssword",
            "12345678", "123456789", "1234567890", "qwerty123", "qwertyuiop",
            "letmein1", "iloveyou", "admin123", "welcome1", "roadwise",
            "roadwise2026!", "changeme1", "abc12345", "monkey123", "dragon123"
    );

    private PasswordPolicy() {
    }

    /**
     * @return {@code null} when the password is acceptable, otherwise a
     *         user-facing error message.
     */
    public static String validate(String password) {
        if (password == null || password.isEmpty()) {
            return "Password is required.";
        }
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters.";
        }
        if (password.length() > MAX_LENGTH) {
            return "Password must be at most " + MAX_LENGTH + " characters.";
        }
        if (!HAS_UPPER.matcher(password).matches()
                || !HAS_DIGIT.matcher(password).matches()
                || !HAS_SPECIAL.matcher(password).matches()) {
            return "Password must include at least one uppercase letter, one number and one special character.";
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            return "This password is too common. Choose a stronger password.";
        }
        return null;
    }
}
