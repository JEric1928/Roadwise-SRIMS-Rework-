/**
 * Authentication feature: login, MFA verification, and password recovery.
 *
 * Handles step transitions, focus shifting, Enter-to-submit key bindings across
 * active steps, remember-me storage, and API workflows.
 */
import { API_BASE_URL } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { STRICT_PASSWORD_REGEX } from '../../core/password-policy.js';

export { STRICT_PASSWORD_REGEX };

let initialized = false;

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

  const loginBtn = document.getElementById("login-btn");
  if (loginBtn) {
    loginBtn.innerHTML = "Authenticating... ⏳";
    loginBtn.disabled = true;
    loginBtn.style.opacity = "0.7";
  }

  fetch(`${API_BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: username, password: password })
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
      sessionStorage.setItem("tempUserId", data.userId);

      const step1 = document.getElementById("login-step-1");
      const step2 = document.getElementById("login-step-2");

      if (step1) step1.style.display = "none";
      if (step2) step2.style.display = "block";

      const toastMsg = data.demoOtp
        ? `🔑 DEMO OTP: [ ${data.demoOtp} ] (Expires in 5 mins)`
        : (data.message || "A 6-digit code has been sent to your email.");

      if (typeof showToast === "function") {
        showToast(toastMsg, "success", 10000);
      }

      const otpField = document.getElementById("mfa-code");
      if (otpField) {
        if (data.demoOtp) {
          otpField.value = data.demoOtp;
        }
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
// 🚀 STEP 2: VERIFY 6-DIGIT CODE & GRANT ACCESS
// ==========================================
export function handleVerifyMfa() {
  const otpField = document.getElementById("mfa-code");
  const otpInput = otpField ? otpField.value.trim() : "";
  const tempUserId = sessionStorage.getItem("tempUserId");

  if (!otpInput || otpInput.length !== 6) {
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
    body: JSON.stringify({ userId: tempUserId, otp: otpInput })
  })
    .then(async response => {
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || errorData.message || 'Invalid verification code');
      }
      return response.json();
    })
    .then(data => {
      sessionStorage.removeItem("tempUserId");

      const cleanMiddleName = (data.middleName && String(data.middleName).toLowerCase() !== "null" && String(data.middleName).toLowerCase() !== "undefined")
        ? String(data.middleName).trim()
        : "";

      sessionStorage.setItem("userId", data.userId);
      sessionStorage.setItem("username", data.username || "N/A");
      sessionStorage.setItem("userRole", data.role || "");
      sessionStorage.setItem("firstName", data.firstName || "");
      sessionStorage.setItem("middleName", cleanMiddleName);
      sessionStorage.setItem("lastName", data.lastName || "");
      sessionStorage.setItem("email", data.email || "");
      sessionStorage.setItem("phoneNumber", data.phoneNumber || "");
      sessionStorage.setItem("birthday", data.birthday || "");
      sessionStorage.setItem("gender", data.gender || "");
      sessionStorage.setItem("profilePicture", data.profilePicture || "no_image.jpg");

      if (data.barangayId) {
        sessionStorage.setItem("barangayId", data.barangayId);
      }
      sessionStorage.setItem("barangayName", data.barangayName || "City Hall Central");

      if (data.accessToken) {
        localStorage.setItem("accessToken", data.accessToken);
      }
      if (data.refreshToken) {
        localStorage.setItem("refreshToken", data.refreshToken);
      }

      const userPayload = {
        id: data.userId,
        username: data.username,
        role: data.role,
        firstName: data.firstName || "",
        middleName: cleanMiddleName,
        lastName: data.lastName || "",
        barangayId: data.barangayId || null,
        barangayName: data.barangayName || "City Hall Central"
      };
      localStorage.setItem("user", JSON.stringify(userPayload));
      localStorage.setItem("currentUser", JSON.stringify(userPayload));

      const rememberCheckbox = document.getElementById("remember-me");
      const usernameInput = document.getElementById("username")?.value.trim();

      if (rememberCheckbox && rememberCheckbox.checked && usernameInput) {
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
    })
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
  document.getElementById("login-step-1").style.display = "none";
  document.getElementById("login-step-2").style.display = "none";
  document.getElementById("forgot-step-1").style.display = "block";

  const emailField = document.getElementById("reset-email");
  if (emailField) emailField.focus();
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

      document.getElementById("forgot-step-1").style.display = "none";
      document.getElementById("forgot-step-2").style.display = "block";

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
  document.getElementById("login-step-2").style.display = "none";
  document.getElementById("forgot-step-1").style.display = "none";
  document.getElementById("forgot-step-2").style.display = "none";

  document.getElementById("login-step-1").style.display = "block";

  if (document.getElementById("mfa-code")) document.getElementById("mfa-code").value = "";
  if (document.getElementById("reset-email")) document.getElementById("reset-email").value = "";
  if (document.getElementById("reset-code")) document.getElementById("reset-code").value = "";
  if (document.getElementById("new-password")) document.getElementById("new-password").value = "";
  if (document.getElementById("confirm-new-password")) document.getElementById("confirm-new-password").value = "";

  sessionStorage.removeItem("tempUserId");
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

  bindEnterKeyStepSubmit();
  restoreRememberedUsername();
}
