/**
 * Authentication feature: login, MFA verification, and password recovery.
 *
 * Handles step transitions, focus shifting, Enter-to-submit key bindings across
 * active steps, remember-me storage, and API workflows.
 */
import { API_BASE_URL } from '../../core/api.js';
import {
  beginSession,
  writeAuthTokens,
  writeIdentity,
  writeProfile
} from '../../core/auth-storage.js';
import { showToast } from '../../components/toast.js';
import { STRICT_PASSWORD_REGEX } from '../../core/password-policy.js';
import {
  assertionToJson,
  describeCredentialError,
  isWebAuthnSupported,
  prepareRequestOptions
} from '../../core/webauthn.js';

export { STRICT_PASSWORD_REGEX };

let initialized = false;

function setStepVisible(id, visible) {
  const step = document.getElementById(id);
  if (!step) return;

  step.classList.toggle("hidden", !visible);
  step.style.removeProperty("display");
}

/**
 * The Turnstile widget, as exposed by the classic script js/turnstile.js. Absent when that
 * script is missing from the page, which is treated as "no bot check available" rather than
 * as "no bot check required" -- the backend refuses an unverified login either way.
 */
function turnstileWidget() {
  return (typeof window !== "undefined") ? window.RoadWiseTurnstile : null;
}

/**
 * Renders a fresh challenge. Turnstile tokens are single-use and short-lived, so every
 * attempt -- success, refusal or network error -- leaves the current one spent.
 */
function resetTurnstileWidget() {
  const turnstile = turnstileWidget();
  if (turnstile && typeof turnstile.reset === "function") {
    turnstile.reset();
  }
}

/**
 * The token to submit, or null when the check has not been completed. Null is a reason not
 * to send the request at all: the backend is fail-closed, so the only difference between
 * submitting anyway and stopping here is that the user gets a security error instead of an
 * explanation.
 */
function currentTurnstileToken() {
  const turnstile = turnstileWidget();
  return (turnstile && typeof turnstile.getToken === "function") ? turnstile.getToken() : null;
}

/** Why {@link currentTurnstileToken} returned null, phrased for the person on the form. */
function describeTurnstileBlocker() {
  const turnstile = turnstileWidget();
  if (turnstile && typeof turnstile.blockingMessage === "function") {
    return turnstile.blockingMessage();
  }
  return "The security check did not load. Reload the page and try again.";
}

function bindEnterKeyStepSubmit() {
  const stepConfigs = [
    { ids: ['username', 'password'], action: handleLogin },
    { ids: ['mfa-code'], action: handleVerifyMfa },
    { ids: ['reset-email'], action: handleForgotPasswordRequest },
    { ids: ['reset-code', 'new-password', 'confirm-new-password'], action: handlePasswordReset }
  ];

  stepConfigs.forEach(cfg => {
    cfg.ids.forEach(id => {
      document.getElementById(id)?.addEventListener('keypress', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          cfg.action();
        }
      });
    });
  });
}

function restoreRememberedUsername() {
  const rememberedUsername = localStorage.getItem("roadwise_remembered_username");
  const usernameInput = document.getElementById("username");
  const rememberCheckbox = document.getElementById("remember-me");

  if (rememberedUsername && usernameInput) {
    usernameInput.value = rememberedUsername;
    if (rememberCheckbox) rememberCheckbox.checked = true;
  }
}

// ==========================================
// 🚀 STEP 1: INITIATE LOGIN & REQUEST MFA CODE
// ==========================================
export function handleLogin() {
  const usernameInput = document.getElementById("username");
  const passwordInput = document.getElementById("password");

  if (!usernameInput || !passwordInput) return;

  const username = usernameInput.value.trim();
  const password = passwordInput.value;

  if (!username || !password) {
    if (typeof showToast === "function") {
      showToast("Please enter both your Official ID/Email and password.", "error");
    }
    return;
  }

  const turnstileToken = currentTurnstileToken();
  if (!turnstileToken) {
    if (typeof showToast === "function") {
      showToast(describeTurnstileBlocker(), "error", 6000);
    }
    // Reset even on this path: a stale or expired challenge must not be left on screen
    // looking solvable when the token behind it is gone.
    resetTurnstileWidget();
    return;
  }

  const loginBtn = document.getElementById("login-btn");
  if (loginBtn) {
    loginBtn.innerHTML = "Authenticating... ⏳";
    loginBtn.disabled = true;
    loginBtn.style.opacity = "0.7";
  }

  fetch(`${API_BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: username, password: password, turnstileToken: turnstileToken })
  })
    .then(async response => {
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || 'Invalid credentials');
      }
      return response.json();
    })
    .then(data => {
    if (data.mfaRequired) {
      // The opaque token is the only handle to this login attempt. The previous code
      // stashed a userId here, which the server then trusted to look up the challenge.
      sessionStorage.setItem("mfaToken", data.mfaToken);

      setStepVisible("login-step-1", false);
      setStepVisible("login-step-2", true);

      applyMfaFactors(data.factors || [], data.factorSelectionRequired === true);

      if (typeof showToast === "function") {
        showToast(data.message || "A 6-digit code has been sent to your email.", "success", 10000);
      }

      const otpField = document.getElementById("mfa-code");
      if (otpField) {
        otpField.value = "";
        otpField.focus();
      }

      if (loginBtn) {
        loginBtn.innerHTML = "Log in ➔";
        loginBtn.disabled = false;
        loginBtn.style.opacity = "1";
      }
    }
  })
    .catch(error => {
      if (passwordInput) passwordInput.value = "";
      // The token was spent on this attempt regardless of why it failed, so the challenge
      // behind it has to be replaced before the form can be submitted again.
      resetTurnstileWidget();

      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        if (typeof showToast === "function") {
          showToast("System is currently offline or unreachable. Please try again later.", "error");
        }
      } else {
        if (typeof showToast === "function") {
          showToast(error.message, "error");
        }
      }

      if (loginBtn) {
        loginBtn.innerHTML = "Log in ➔";
        loginBtn.disabled = false;
        loginBtn.style.opacity = "1";
      }
    });
}

// ==========================================
// 🚪 STEP 1b: CHOOSE A VERIFICATION METHOD
// ==========================================
// Presentation only. Everything here comes from the server's factor list, and the
// server re-checks whatever gets chosen against the account's real enrolment -- so a
// tampered DOM cannot promote a caller to a factor the account does not hold.

const MFA_FACTOR_LABELS = {
  EMAIL_OTP: "Email code",
  TOTP: "Authenticator app",
  RECOVERY_CODE: "Recovery code",
  WEBAUTHN: "Passkey"
};

const MFA_FACTOR_HINTS = {
  EMAIL_OTP: "Enter the 6-digit authentication code dispatched to your official email.",
  TOTP: "Enter the 6-digit code from your authenticator app.",
  RECOVERY_CODE: "Enter one of the recovery codes you saved when you enrolled.",
  WEBAUTHN: "Follow your device's prompt to confirm with your passkey."
};

function applyMfaFactors(factors, selectionRequired) {
  lastMfaFactors = Array.isArray(factors) ? factors : [];
  const picker = document.getElementById("mfa-factor-picker");
  const desc = document.getElementById("mfa-step-desc");
  const otpField = document.getElementById("mfa-code");
  const resendBtn = document.getElementById("mfa-resend-btn");

  if (!picker) return;

  const selectable = factors || [];
  const hasChoice = selectionRequired && selectable.length > 1;

  picker.innerHTML = "";

  if (!hasChoice) {
    // A single option is not a choice: keep the original one-step flow untouched.
    picker.classList.add("hidden");
    if (desc) desc.textContent = MFA_FACTOR_HINTS.EMAIL_OTP;
    if (otpField) {
      otpField.maxLength = 6;
      otpField.value = "";
    }
    if (resendBtn) resendBtn.classList.add("hidden");
    return;
  }

  selectable.forEach(factor => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn-login";
    btn.style.marginBottom = "8px";
    btn.textContent = MFA_FACTOR_LABELS[factor.type] || factor.label || factor.type;
    btn.addEventListener("click", () => handleMfaFactorSelect(factor.type));
    picker.appendChild(btn);
  });
  picker.classList.remove("hidden");

  if (desc) desc.textContent = "Choose how you would like to verify.";
  if (otpField) otpField.value = "";
  if (resendBtn) resendBtn.classList.add("hidden");
}

export function handleMfaFactorSelect(factorType) {
  const mfaToken = sessionStorage.getItem("mfaToken");
  if (!mfaToken) return;

  // A new selection supersedes any passkey challenge still waiting to be used.
  pendingPasskey = null;

  const picker = document.getElementById("mfa-factor-picker");
  const desc = document.getElementById("mfa-step-desc");
  const otpField = document.getElementById("mfa-code");
  const resendBtn = document.getElementById("mfa-resend-btn");

  fetch(`${API_BASE_URL}/api/auth/mfa/select`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfaToken: mfaToken, factorType: factorType })
  })
    .then(async response => {
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        // Surfaces the server's own refusal, including 429 once the shared attempt
        // budget for this challenge is spent.
        throw new Error(payload.error || "That verification method is unavailable.");
      }
      return payload;
    })
    .then(payload => {
      if (picker) {
        picker.classList.add("hidden");
        picker.innerHTML = "";
      }
      if (factorType === "WEBAUTHN") {
        // A passkey is answered by the authenticator, not by typing: there is
        // no code to enter, and the ceremony starts as soon as the server has
        // issued its challenge.
        return startPasskeyAssertion(payload.passkey, mfaToken, { desc, otpField, resendBtn });
      }
      if (desc) desc.textContent = MFA_FACTOR_HINTS[factorType] || payload.message || "Enter your verification code.";
      showCodeEntry(otpField);
      const verifyBtn = document.getElementById("verify-btn");
      if (verifyBtn) verifyBtn.style.display = "";
      if (otpField) {
        // A recovery code is longer than a 6-digit OTP, so the field follows the
        // factor rather than the other way round.
        otpField.maxLength = factorType === "RECOVERY_CODE" ? 14 : 6;
        otpField.value = "";
        otpField.focus();
      }
      if (resendBtn) {
        resendBtn.classList.toggle("hidden", factorType !== "EMAIL_OTP");
      }
      if (typeof showToast === "function") {
        showToast(payload.message || "Verification method selected.", "success");
      }
    })
    .catch(error => {
      if (typeof showToast === "function") {
        showToast(error.message, "error");
      }
    });
}

/** Re-issuing is just re-selecting email OTP; the server re-mints onto the same challenge. */
export function handleMfaResend() {
  handleMfaFactorSelect("EMAIL_OTP");
}

// ==========================================
// 🚀 STEP 2: VERIFY 6-DIGIT CODE & GRANT ACCESS
// ==========================================
export function handleVerifyMfa() {
  if (pendingPasskey) {
    // A dismissed passkey prompt leaves the issued challenge unconsumed, and
    // the code field hidden. The verify button retries the ceremony instead of
    // validating an empty input.
    return runPasskeyAssertion();
  }
  const otpField = document.getElementById("mfa-code");
  const otpInput = otpField ? otpField.value.trim() : "";
  const mfaToken = sessionStorage.getItem("mfaToken");

  if (!otpInput || otpInput.length < 6) {
    if (typeof showToast === "function") {
      showToast("Please enter a valid 6-digit code.", "error");
    }
    return;
  }

  const verifyBtn = document.getElementById("verify-btn");
  const originalBtnText = verifyBtn ? verifyBtn.innerHTML : "Verify Code ➔";

  if (verifyBtn) {
    verifyBtn.innerHTML = "Verifying... ⏳";
    verifyBtn.disabled = true;
    verifyBtn.style.opacity = "0.7";
  }

  fetch(`${API_BASE_URL}/api/auth/verify-mfa`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfaToken: mfaToken, code: otpInput })
  })
    .then(async response => {
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || errorData.message || 'Invalid verification code');
      }
      return response.json();
    })
    .then(completeLoginWithSession)
    .catch(error => {
      if (otpField) {
        otpField.value = "";
        otpField.focus();
      }

      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        if (typeof showToast === "function") {
          showToast("System is currently offline or unreachable. Please try again later.", "error");
        }
      } else {
        if (typeof showToast === "function") {
          showToast(error.message, "error");
        }
      }

      if (verifyBtn) {
        verifyBtn.innerHTML = originalBtnText;
        verifyBtn.disabled = false;
        verifyBtn.style.opacity = "1";
      }
    });
}

// ==========================================
// FORGOT PASSWORD: SHOW SCREEN
// ==========================================
export function showForgotPassword() {
  setStepVisible("login-step-1", false);
  setStepVisible("login-step-2", false);
  setStepVisible("forgot-step-2", false);
  setStepVisible("forgot-step-1", true);

  const emailField = document.getElementById("reset-email");
  if (emailField) emailField.focus();
}

// ==========================================
// PASSKEY (WEBAUTHN) ASSERTION
// ==========================================

/**
 * The ceremony issued by /mfa/select for the passkey factor, kept so a
 * dismissed prompt can be retried. A ceremony stays valid until it is
 * consumed, expires, or a newer selection supersedes it -- rerunning
 * navigator.credentials.get() therefore does not need a new challenge.
 */
let pendingPasskey = null;

/** The factors the server offered, so the picker can be rebuilt after a spent ceremony. */
let lastMfaFactors = [];

function showCodeEntry(otpField) {
  if (!otpField) return;
  const group = otpField.closest ? otpField.closest(".form-group") : null;
  if (group) group.classList.remove("hidden");
  if (otpField.classList) otpField.classList.remove("hidden");
}

function hideCodeEntry(otpField) {
  if (!otpField) return;
  const group = otpField.closest ? otpField.closest(".form-group") : null;
  if (group) group.classList.add("hidden");
  if (otpField.classList) otpField.classList.add("hidden");
}

function startPasskeyAssertion(passkey, mfaToken, refs) {
  const { desc, otpField, resendBtn } = refs || {};
  if (desc) desc.textContent = MFA_FACTOR_HINTS.WEBAUTHN;
  hideCodeEntry(otpField);
  if (resendBtn) resendBtn.classList.add("hidden");

  const verifyBtn = document.getElementById("verify-btn");
  if (verifyBtn) verifyBtn.style.display = "";

  if (!passkey || !passkey.options) {
    showToast("The server did not issue a passkey challenge.", "error");
    return;
  }
  if (!isWebAuthnSupported()) {
    showToast("This browser cannot use passkeys. Choose another verification method.", "error");
    return;
  }

  pendingPasskey = { ceremonyKey: passkey.ceremonyKey, options: passkey.options, mfaToken: mfaToken };
  return runPasskeyAssertion();
}

/**
 * Runs navigator.credentials.get() against the pending ceremony and submits the
 * assertion. Two failure shapes need different treatment:
 *  - the browser produced no credential (dismissed/timeout/unsupported): the
 *    ceremony was never used, so the verify button retries it;
 *  - the server answered a submitted assertion, however it ruled: the ceremony
 *    is spent, so the picker is offered again for a fresh challenge.
 */
async function runPasskeyAssertion() {
  const verifyBtn = document.getElementById("verify-btn");
  if (verifyBtn) {
    verifyBtn.innerHTML = "Waiting for passkey... 🔑";
    verifyBtn.disabled = true;
    verifyBtn.style.opacity = "0.7";
  }

  try {
    const credential = await navigator.credentials.get({
      publicKey: prepareRequestOptions(pendingPasskey.options)
    });

    const response = await fetch(`${API_BASE_URL}/api/auth/verify-mfa`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mfaToken: pendingPasskey.mfaToken,
        ceremonyKey: pendingPasskey.ceremonyKey,
        assertion: assertionToJson(credential)
      })
    });
    const data = await response.json().catch(() => ({}));

    if (!response.ok) {
      const rejected = new Error(data.error || data.message || "That passkey could not be verified.");
      rejected.ceremonySpent = true;
      throw rejected;
    }

    pendingPasskey = null;
    completeLoginWithSession(data);
  } catch (error) {
    if (error && error.ceremonySpent) {
      showToast(error.message, "error");
      pendingPasskey = null;
      // The challenge cannot be reused; the picker issues a fresh one when the
      // user chooses a factor again.
      applyMfaFactors(lastMfaFactors, true);
      if (verifyBtn) {
        verifyBtn.disabled = false;
        verifyBtn.style.opacity = "1";
        verifyBtn.style.display = "none";
      }
      return;
    }

    showToast(describeCredentialError(error), "error");
    if (verifyBtn) {
      verifyBtn.innerHTML = "Try passkey again 🔑";
      verifyBtn.disabled = false;
      verifyBtn.style.opacity = "1";
      verifyBtn.style.display = "";
    }
  }
}

/**
 * Stores the verified session and routes the user, exactly once. Shared by the
 * code path and the passkey path so a completed login can never mean two
 * different things.
 */
function completeLoginWithSession(data) {
  sessionStorage.removeItem("mfaToken");

  const cleanMiddleName = (data.middleName && String(data.middleName).toLowerCase() !== "null" && String(data.middleName).toLowerCase() !== "undefined")
    ? String(data.middleName).trim()
    : "";

  // "Remember me" decides where the session is written: localStorage when ticked,
  // sessionStorage when not. It is read before storing anything because every write
  // below targets the scope it selects — previously the checkbox only saved the
  // username, so an unticked login still left a 7-day refresh token on disk.
  const rememberCheckbox = document.getElementById("remember-me");
  const usernameInput = document.getElementById("username")?.value.trim();
  const rememberMe = !!(rememberCheckbox && rememberCheckbox.checked);

  beginSession(rememberMe);

  writeIdentity({
    userId: data.userId,
    username: data.username || "N/A",
    userRole: data.role || "",
    firstName: data.firstName || "",
    middleName: cleanMiddleName,
    lastName: data.lastName || "",
    email: data.email || "",
    phoneNumber: data.phoneNumber || "",
    birthday: data.birthday || "",
    gender: data.gender || "",
    profilePicture: data.profilePicture || "no_image.jpg",
    barangayId: data.barangayId,
    barangayName: data.barangayName || "City Hall Central"
  });

  writeAuthTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken });

  writeProfile({
    id: data.userId,
    username: data.username,
    role: data.role,
    firstName: data.firstName || "",
    middleName: cleanMiddleName,
    lastName: data.lastName || "",
    barangayId: data.barangayId || null,
    barangayName: data.barangayName || "City Hall Central"
  });


  if (rememberMe && usernameInput) {
    localStorage.setItem("roadwise_remembered_username", usernameInput);
  } else {
    localStorage.removeItem("roadwise_remembered_username");
  }

  if (typeof showToast === "function") {
    showToast("Access Granted!", "success");
  }

  setTimeout(() => {
    const userRole = String(data.role).toLowerCase();
    if (userRole.includes("admin") || userRole.includes("cpdo")) {
      window.location.replace("admin_dashboard.html");
    } else if (userRole.includes("ceo") || userRole.includes("engineer")) {
      window.location.replace("ceo_dashboard.html");
    } else {
      window.location.replace("barangay_dashboard.html");
    }
  }, 1000);
}

// ==========================================
// FORGOT PASSWORD: SEND OTP TO EMAIL
// ==========================================
export function handleForgotPasswordRequest() {
  const emailInput = document.getElementById("reset-email");
  const email = emailInput ? emailInput.value.trim() : "";

  if (!email) {
    showToast("Please enter your registered email address.", "error");
    if (emailInput) emailInput.focus();
    return;
  }

  const btn = document.getElementById("request-reset-btn");
  if (btn) {
    btn.innerHTML = "Sending... ⏳";
    btn.disabled = true;
  }

  fetch(`${API_BASE_URL}/api/auth/forgot-password/request`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: email })
  })
    .then(async response => {
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || 'Failed to request reset.');
      }
      return response.json();
    })
    .then(data => {
      sessionStorage.setItem("resetEmail", email);
      showToast(data.message, "success");

      setStepVisible("forgot-step-1", false);
      setStepVisible("forgot-step-2", true);

      const resetCodeInput = document.getElementById("reset-code");
      if (resetCodeInput) resetCodeInput.focus();

      if (btn) {
        btn.innerHTML = "Send Code ➔";
        btn.disabled = false;
      }
    })
    .catch(error => {
      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        showToast("System is currently offline or unreachable. Please try again later.", "error");
      } else {
        showToast(error.message, "error");
      }

      if (btn) {
        btn.innerHTML = "Send Code ➔";
        btn.disabled = false;
      }
    });
}

// ==========================================
// FORGOT PASSWORD: VERIFY OTP & SAVE PASSWORD
// ==========================================
export function handlePasswordReset() {
  const otpInput = document.getElementById("reset-code");
  const otp = otpInput ? otpInput.value.trim() : "";
  const newPassword = document.getElementById("new-password")?.value.trim();
  const confirmPassword = document.getElementById("confirm-new-password")?.value.trim();
  const email = sessionStorage.getItem("resetEmail");

  if (!otp || otp.length !== 6) {
    showToast("Please enter the 6-digit code.", "error");
    if (otpInput) otpInput.focus();
    return;
  }

  if (!newPassword || !confirmPassword) {
    showToast("Please enter and confirm your new password.", "error");
    return;
  }

  if (!STRICT_PASSWORD_REGEX.test(newPassword)) {
    showToast("Password must be 8+ characters, with 1 uppercase letter, 1 number, and 1 special character.", "error");
    return;
  }

  if (newPassword !== confirmPassword) {
    showToast("Passwords do not match!", "error");
    return;
  }

  const btn = document.getElementById("submit-reset-btn");
  if (btn) {
    btn.innerHTML = "Resetting... ⏳";
    btn.disabled = true;
  }

  fetch(`${API_BASE_URL}/api/auth/forgot-password/reset`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: email, otp: otp, newPassword: newPassword })
  })
    .then(async response => {
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || 'Failed to reset password.');
      }
      return response.json();
    })
    .then(data => {
      showToast(data.message, "success");
      backToLogin();
      if (btn) {
        btn.innerHTML = "Confirm & Reset ➔";
        btn.disabled = false;
      }
    })
    .catch(error => {
      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        showToast("System is currently offline or unreachable. Please try again later.", "error");
      } else {
        showToast(error.message, "error");
      }

      if (btn) {
        btn.innerHTML = "Confirm & Reset ➔";
        btn.disabled = false;
      }
    });
}

// ==========================================
// UI HELPER: GO BACK TO LOGIN SCREEN
// ==========================================
export function backToLogin() {
  // Returning from MFA (or the reset flow) means the previous challenge was consumed by the
  // login request. Without a new one the form would look ready and then refuse to submit.
  resetTurnstileWidget();
  setStepVisible("login-step-2", false);
  setStepVisible("forgot-step-1", false);
  setStepVisible("forgot-step-2", false);
  setStepVisible("login-step-1", true);

  if (document.getElementById("mfa-code")) document.getElementById("mfa-code").value = "";
  if (document.getElementById("reset-email")) document.getElementById("reset-email").value = "";
  if (document.getElementById("reset-code")) document.getElementById("reset-code").value = "";
  if (document.getElementById("new-password")) document.getElementById("new-password").value = "";
  if (document.getElementById("confirm-new-password")) document.getElementById("confirm-new-password").value = "";

  sessionStorage.removeItem("mfaToken");
  sessionStorage.removeItem("resetEmail");

  const usernameInput = document.getElementById("username");
  if (usernameInput) usernameInput.focus();
}

/**
 * Idempotent feature entry point. Called once on DOMContentLoaded by app.js.
 */
export function initAuth() {
  if (initialized) return;
  initialized = true;

  window.handleLogin = handleLogin;
  window.handleVerifyMfa = handleVerifyMfa;
  window.showForgotPassword = showForgotPassword;
  window.handleForgotPasswordRequest = handleForgotPasswordRequest;
  window.handlePasswordReset = handlePasswordReset;
  window.backToLogin = backToLogin;
  window.handleMfaFactorSelect = handleMfaFactorSelect;
  window.handleMfaResend = handleMfaResend;

  bindEnterKeyStepSubmit();
  restoreRememberedUsername();
}
