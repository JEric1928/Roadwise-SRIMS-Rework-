/**
 * Shared password complexity policy.
 *
 * Min 8 chars, 1 uppercase, 1 number, 1 special character (@$!%*?&#).
 * Used by the password-reset flow and the change-password form.
 */
export const STRICT_PASSWORD_REGEX = /^(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])[A-Za-z\d@$!%*?&#]{8,}$/;
