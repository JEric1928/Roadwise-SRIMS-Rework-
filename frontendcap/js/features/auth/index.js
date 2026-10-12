/**
 * Authentication feature: login, MFA verification, and password recovery.
 *
 * Owns the login form workflow, its DOM step transitions, remember-me
 * storage, and the Enter-to-login keyboard bindings for login.html.
 *
 * Entry points:
 * - initAuth(): idempotent startup; installs the temporary window
 *   compatibility bridge for login.html inline onclick handlers, binds
 *   Enter-to-login once, and restores the remembered username.
 *
 * The functions are exported for headless tests. Browser calls from
 * login.html go through the bridge installed by initAuth().
 */
import { API_BASE_URL } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { STRICT_PASSWORD_REGEX } from '../../core/password-policy.js';

// Re-exported so existing tests and callers keep the same import path.
export { STRICT_PASSWORD_REGEX };

let initialized = false;

function bindEnterKeyLogin() {
  ['username', 'password'].forEach(id => {
    document.getElementById(id)?.addEventListener('keypress', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault(); // Prevent form from refreshing the page
        handleLogin();
      }
    });
  });
}

// Restores the "Remember me" username. The original app.js registered this
// twice with equivalent conditions; the superset (username input present)
// is kept here.
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
    // 🚀 MFA TRIGGER: Move to Step 2
    if (data.mfaRequired) {
      sessionStorage.setItem("tempUserId", data.userId);

      const step1 = document.getElementById("login-step-1");
      const step2 = document.getElementById("login-step-2");

      if (step1) step1.style.display = "none";
      if (step2) step2.style.display = "block";

      // 🎯 Show Demo OTP in the toast banner for 10 seconds (10000ms)
      const toastMsg = data.demoOtp
        ? `🔑 DEMO OTP: [ ${data.demoOtp} ] (Expires in 5 mins)`
        : (data.message || "A 6-digit code has been sent to your email.");

      if (typeof showToast === "function") {
        showToast(toastMsg, "success", 10000);
      }

      // Optional convenience: auto-fill the field so you don't even have to type it
      const otpField = document.getElementById("mfa-code");
      if (otpField && data.demoOtp) {
        otpField.value = data.demoOtp;
      }

      if (loginBtn) {
        loginBtn.innerHTML = "Log in ➔";
        loginBtn.disabled = false;
        loginBtn.style.opacity = "1";
      }
    }
  })
    .catch(error => {
      // Clear password field on failed attempt
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
      // 1. Cleanup temp MFA state
      sessionStorage.removeItem("tempUserId");

      // 2. Clean middle name (prevent storing "null" string)
      const cleanMiddleName = (data.middleName && String(data.middleName).toLowerCase() !== "null" && String(data.middleName).toLowerCase() !== "undefined")
        ? String(data.middleName).trim()
        : "";

      // 3. Save individual sessionStorage keys
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

      // Store JWT Tokens
      if (data.accessToken) {
        localStorage.setItem("accessToken", data.accessToken);
      }
      if (data.refreshToken) {
        localStorage.setItem("refreshToken", data.refreshToken);
      }

      // 4. Save synced user object in localStorage for cross-component compatibility
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

      // 5. Remember Me Logic
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

      // 6. Dynamic Routing by Role
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
      // 1. Clear the invalid OTP input and refocus
      if (otpField) {
        otpField.value = "";
        otpField.focus();
      }

      // 2. Display proper error toast
      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        if (typeof showToast === "function") {
          showToast("System is currently offline or unreachable. Please try again later.", "error");
        }
      } else {
        if (typeof showToast === "function") {
          showToast(error.message, "error");
        }
      }

      // 3. Reset the VERIFY button (not loginBtn)
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
}

// ==========================================
// FORGOT PASSWORD: SEND OTP TO EMAIL
// ==========================================
export function handleForgotPasswordRequest() {
  const email = document.getElementById("reset-email").value.trim();

  if (!email) {
    showToast("Please enter your registered email address.", "error");
    return;
  }

  const btn = document.getElementById("request-reset-btn");
  btn.innerHTML = "Sending... ⏳";
  btn.disabled = true;

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
      // The backend no longer returns a userId (account-enumeration fix).
      // The submitted email is what the confirm step will carry.
      sessionStorage.setItem("resetEmail", email);
      showToast(data.message, "success");

      document.getElementById("forgot-step-1").style.display = "none";
      document.getElementById("forgot-step-2").style.display = "block";

      btn.innerHTML = "Send Code ➔";
      btn.disabled = false;
    })
    .catch(error => {
      // 🚀 SMART CHECK: User-friendly Network Error
      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        showToast("System is currently offline or unreachable. Please try again later.", "error");
      } else {
        showToast(error.message, "error");
      }

      btn.innerHTML = "Send Code ➔";
      btn.disabled = false;
    });
}

// ==========================================
// FORGOT PASSWORD: VERIFY OTP & SAVE PASSWORD
// ==========================================
export function handlePasswordReset() {
  const otp = document.getElementById("reset-code").value.trim();
  const newPassword = document.getElementById("new-password").value.trim();
  const confirmPassword = document.getElementById("confirm-new-password").value.trim();
  const email = sessionStorage.getItem("resetEmail");

  if (!otp || otp.length !== 6) {
    showToast("Please enter the 6-digit code.", "error");
    return;
  }

  if (!newPassword || !confirmPassword) {
    showToast("Please enter and confirm your new password.", "error");
    return;
  }

  // 🚀 NEW: STRICT ENTERPRISE PASSWORD REGEX
  if (!STRICT_PASSWORD_REGEX.test(newPassword)) {
    showToast("Password must be 8+ characters, with 1 uppercase letter, 1 number, and 1 special character.", "error");
    return;
  }

  // PASSWORD MATCH VALIDATION
  if (newPassword !== confirmPassword) {
    showToast("Passwords do not match!", "error");
    return;
  }

  const btn = document.getElementById("submit-reset-btn");
  btn.innerHTML = "Resetting... ⏳";
  btn.disabled = true;

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
      btn.innerHTML = "Confirm & Reset ➔";
      btn.disabled = false;
    })
    .catch(error => {
      // 🚀 SMART CHECK: User-friendly Network Error
      if (error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
        showToast("System is currently offline or unreachable. Please try again later.", "error");
      } else {
        showToast(error.message, "error");
      }

      btn.innerHTML = "Confirm & Reset ➔";
      btn.disabled = false;
    });
}

// ==========================================
// UI HELPER: GO BACK TO LOGIN SCREEN
// ==========================================
export function backToLogin() {
  // Hide all secondary steps
  document.getElementById("login-step-2").style.display = "none";
  document.getElementById("forgot-step-1").style.display = "none";
  document.getElementById("forgot-step-2").style.display = "none";

  // Show primary login
  document.getElementById("login-step-1").style.display = "block";

  // Wipe inputs clean for security
  if (document.getElementById("mfa-code")) document.getElementById("mfa-code").value = "";
  if (document.getElementById("reset-email")) document.getElementById("reset-email").value = "";
  if (document.getElementById("reset-code")) document.getElementById("reset-code").value = "";
  if (document.getElementById("new-password")) document.getElementById("new-password").value = "";
  if (document.getElementById("confirm-new-password")) document.getElementById("confirm-new-password").value = "";

  // Wipe temporary memory
  sessionStorage.removeItem("tempUserId");
  sessionStorage.removeItem("resetEmail");
}

/**
 * Idempotent feature entry point. Called once on DOMContentLoaded by app.js.
 */
export function initAuth() {
  if (initialized) return;
  initialized = true;

  // Temporary HTML/global compatibility bridge for login.html inline onclick.
  window.handleLogin = handleLogin;
  window.handleVerifyMfa = handleVerifyMfa;
  window.showForgotPassword = showForgotPassword;
  window.handleForgotPasswordRequest = handleForgotPasswordRequest;
  window.handlePasswordReset = handlePasswordReset;
  window.backToLogin = backToLogin;

  bindEnterKeyLogin();
  restoreRememberedUsername();
}