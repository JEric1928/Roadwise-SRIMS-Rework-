/**
 * Profile & account feature: edit-profile modal, avatar upload/viewer,
 * change-password form, and the CPDO/CEO office turnover workflow.
 *
 * Owns its DOM references, listeners, and window bridge. The profile/handover
 * markup is not present in the current dashboard templates, so most bindings
 * are inert no-ops; they are preserved for when the templates return.
 *
 * Entry point: idempotent initProfileAccount().
 */
import { API_BASE_URL } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { STRICT_PASSWORD_REGEX } from '../../core/password-policy.js';

let initialized = false;

// DOM references assigned by initProfileAccount().
let btnEditProfile = null;
let editProfileModal = null;
let formEditProfile = null;
let phoneInput = null;
let mainAvatar = null;
let profilePicUpload = null;

// Helper Function: Safely Close and Clear the Modal
function closeAndClearEditModal() {
  if (editProfileModal) {
    editProfileModal.classList.add('hidden');
  }
  if (formEditProfile) {
    formEditProfile.reset(); // Wipes all fields completely clean
  }
}

// Helper to visually update the Avatar circle dynamically
export function updateAvatarDisplay(imageName) {
  if (!mainAvatar) return;

  // Ensure we apply the correct fallback gradient based on role
  const applyFallbackTheme = () => {
    mainAvatar.innerHTML = '🏛️';
    const role = String(sessionStorage.getItem('userRole')).toLowerCase();

    if (role.includes('admin') || role.includes('cpdo')) {
      mainAvatar.style.backgroundImage = 'linear-gradient(135deg, #1e40af, #3b82f6)'; // Admin Blue
    } else if (role.includes('ceo') || role.includes('engineer')) {
      mainAvatar.style.backgroundImage = 'linear-gradient(135deg, #ea580c, #f97316)'; // CEO Orange
    } else {
      mainAvatar.style.backgroundImage = 'linear-gradient(135deg, #15803d, #22c55e)'; // Barangay Green
    }
  };

  // 🚀 THE FIX 1: Strict check for bad database data ("null", "undefined", or empty)
  if (!imageName ||
    imageName === 'no_image.jpg' ||
    String(imageName).trim().toLowerCase() === 'null' ||
    String(imageName).trim().toLowerCase() === 'undefined' ||
    String(imageName).trim() === '') {
    applyFallbackTheme();
    return;
  }

  const url = String(imageName).startsWith("http") ? imageName : `${API_BASE_URL}/uploads/${imageName}`;

  // 🚀 THE FIX 2: Check if the file ACTUALLY exists on the server
  fetch(url, { headers: { 'ngrok-skip-browser-warning': 'true' } })
    .then(res => {
      if (!res.ok) throw new Error("Image not found on server");
      return res.blob();
    })
    .then(blob => {
      const objectURL = URL.createObjectURL(blob);
      mainAvatar.innerHTML = ''; // Hide the emoji
      mainAvatar.style.backgroundImage = `url(${objectURL})`;
      mainAvatar.style.backgroundSize = 'cover';
      mainAvatar.style.backgroundPosition = 'center';
    })
    .catch(err => {
      console.error("Failed to load avatar, falling back to default:", err);
      applyFallbackTheme(); // Revert to colored circle if the file is missing
    });
}

// The View Button Logic (Wire this to the 'View' button in your HTML)
export function openProfilePicViewer() {
  const imageName = sessionStorage.getItem('profilePicture');

  // 🚀 THE FIX 3: Stop the modal from opening if data is bad!
  if (!imageName ||
    imageName === 'no_image.jpg' ||
    String(imageName).trim().toLowerCase() === 'null' ||
    String(imageName).trim().toLowerCase() === 'undefined' ||
    String(imageName).trim() === '') {
    showToast("No custom profile picture uploaded.", "info");
    return;
  }

  const modal = document.getElementById('view-profile-pic-modal');
  const fullImg = document.getElementById('full-size-profile-pic');

  if (!modal || !fullImg) return;

  const url = String(imageName).startsWith("http") ? imageName : `${API_BASE_URL}/uploads/${imageName}`;

  // 🚀 THE FIX 4: Only open the modal if the fetch is 100% successful
  fetch(url, { headers: { 'ngrok-skip-browser-warning': 'true' } })
    .then(res => {
      if (!res.ok) throw new Error("Image file missing from server");
      return res.blob();
    })
    .then(blob => {
      fullImg.src = URL.createObjectURL(blob);
      modal.classList.remove('hidden');
    })
    .catch(err => {
      console.error("Failed to load full size profile picture:", err);
      showToast("Image file is missing or corrupted on the server.", "error");

      // Wipe the bad ghost data so it defaults back to the colored circle
      sessionStorage.setItem('profilePicture', 'no_image.jpg');
      updateAvatarDisplay('no_image.jpg');
    });
}

// 🚀 Helper: Interactive Password Field Eye Toggle
export function togglePasswordVisibility(inputId, iconElement) {
  const input = document.getElementById(inputId);
  if (!input) return;

  if (input.type === "password") {
    input.type = "text";
    iconElement.textContent = "🙈"; // Change icon to blind monkey / hidden state
  } else {
    input.type = "password";
    iconElement.textContent = "👁️"; // Back to eye
  }
}

// Helper: Attach Real-Time Numeric & 11-Digit Length Mask
function attachPhoneInputMask(inputId) {
  const el = document.getElementById(inputId);
  if (el) {
    el.addEventListener('input', function () {
      this.value = this.value.replace(/[^0-9]/g, '');
      if (this.value.length > 11) {
        this.value = this.value.slice(0, 11);
      }
    });
  }
}

// ------------------------------------------
// A. CPDO ADMIN TURNOVER
// ------------------------------------------

export function openAdminHandoverModal() {
  const modal = document.getElementById('admin-handover-modal');
  const form = document.getElementById('form-admin-handover');
  if (form) form.reset();
  attachPhoneInputMask('succ-phone');
  if (modal) {
    modal.classList.remove('hidden');
    modal.style.display = 'flex';
  }
}

export function closeAdminHandoverModal() {
  const modal = document.getElementById('admin-handover-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
}

export function submitAdminHandover() {
  const currentAdminId = sessionStorage.getItem('userId');
  if (!currentAdminId) {
    showToast("Session expired. Please log in again.", "error");
    return;
  }

  const currentPassword = document.getElementById('succ-current-pass')?.value;
  const firstName = document.getElementById('succ-first-name')?.value.trim();
  const middleName = document.getElementById('succ-middle-name')?.value.trim();
  const lastName = document.getElementById('succ-last-name')?.value.trim();
  const email = document.getElementById('succ-email')?.value.trim();
  const phoneNumber = document.getElementById('succ-phone')?.value.trim();
  const username = document.getElementById('succ-username')?.value.trim();
  const memoNumber = document.getElementById('succ-memo')?.value.trim();

  // Strict Validation Checks
  if (!firstName || !lastName || !email || !username) {
    showToast("Please complete all required successor details.", "error");
    return;
  }

  if (!phoneNumber || phoneNumber.length !== 11) {
    showToast("Contact number must be exactly 11 digits (e.g. 09123456789).", "error");
    document.getElementById('succ-phone')?.focus();
    return;
  }

  if (!memoNumber) {
    showToast("Office Memorandum / Special Order number is required.", "error");
    document.getElementById('succ-memo')?.focus();
    return;
  }

  if (!currentPassword) {
    showToast("Please re-enter your current password to authorize turnover.", "error");
    document.getElementById('succ-current-pass')?.focus();
    return;
  }

  const submitBtn = document.getElementById('btn-confirm-handover');
  const originalText = submitBtn ? submitBtn.innerText : 'Confirm & Execute Handover ➔';
  if (submitBtn) {
    submitBtn.innerText = "Executing Turnover... ⏳";
    submitBtn.disabled = true;
  }

  const baseUrl = typeof API_BASE_URL !== 'undefined' ? API_BASE_URL : '';

  fetch(`${baseUrl}/api/users/handover-admin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      currentAdminId: currentAdminId,
      currentPassword: currentPassword,
      firstName: firstName,
      middleName: middleName,
      lastName: lastName,
      email: email,
      phoneNumber: phoneNumber,
      username: username,
      memoNumber: memoNumber
    })
  })
    .then(async response => {
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(data.error || "Turnover processing failed.");
      }
      return data;
    })
    .then(data => {
      closeAdminHandoverModal();

      const dispName = document.getElementById('disp-succ-name');
      const dispUser = document.getElementById('disp-succ-user');
      const dispPass = document.getElementById('disp-succ-pass');

      if (dispName) dispName.innerText = data.successorName || `${firstName} ${lastName}`;
      if (dispUser) dispUser.innerText = data.successorUsername || username;
      if (dispPass) dispPass.innerText = data.tempPassword || 'Check registered email';

      const succModal = document.getElementById('handover-success-modal');
      if (succModal) {
        succModal.classList.remove('hidden');
        succModal.style.display = 'flex';
      }

      showToast("Office successfully turned over! Session ended.", "success", 10000);
    })
    .catch(err => {
      console.error("Turnover Error:", err);
      showToast(err.message, "error", 6000);
    })
    .finally(() => {
      if (submitBtn) {
        submitBtn.innerText = originalText;
        submitBtn.disabled = false;
      }
    });
}

export function terminateHandoverSession() {
  sessionStorage.clear();
  localStorage.removeItem('user');
  localStorage.removeItem('currentUser');
  window.location.replace('login.html');
}

// ------------------------------------------
// B. CEO TURNOVER
// ------------------------------------------

export function openCeoHandoverModal() {
  const modal = document.getElementById('ceo-handover-modal');
  const form = document.getElementById('form-ceo-handover');
  if (form) form.reset();
  attachPhoneInputMask('ceo-succ-phone');
  if (modal) {
    modal.classList.remove('hidden');
    modal.style.display = 'flex';
  }
}

export function closeCeoHandoverModal() {
  const modal = document.getElementById('ceo-handover-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
}

export function submitCeoHandover() {
  const currentCeoId = sessionStorage.getItem('userId');
  if (!currentCeoId) {
    showToast("Session expired. Please log in again.", "error");
    return;
  }

  const currentPassword = document.getElementById('ceo-current-pass')?.value;
  const firstName = document.getElementById('ceo-succ-first')?.value.trim();
  const middleName = document.getElementById('ceo-succ-middle')?.value.trim();
  const lastName = document.getElementById('ceo-succ-last')?.value.trim();
  const email = document.getElementById('ceo-succ-email')?.value.trim();
  const phoneNumber = document.getElementById('ceo-succ-phone')?.value.trim();
  const username = document.getElementById('ceo-succ-user')?.value.trim();
  const memoNumber = document.getElementById('ceo-succ-memo')?.value.trim();

  // Strict Validation Checks
  if (!firstName || !lastName || !email || !username) {
    showToast("Please complete all required successor details.", "error");
    return;
  }

  if (!phoneNumber || phoneNumber.length !== 11) {
    showToast("Contact number must be exactly 11 digits (e.g. 09123456789).", "error");
    document.getElementById('ceo-succ-phone')?.focus();
    return;
  }

  if (!memoNumber) {
    showToast("Appointment Memo / Special Order number is required.", "error");
    document.getElementById('ceo-succ-memo')?.focus();
    return;
  }

  if (!currentPassword) {
    showToast("Please re-enter your current password to authorize turnover.", "error");
    document.getElementById('ceo-current-pass')?.focus();
    return;
  }

  const submitBtn = document.getElementById('btn-confirm-ceo-handover');
  const originalText = submitBtn ? submitBtn.innerText : 'Confirm & Execute Handover ➔';
  if (submitBtn) {
    submitBtn.innerText = "Executing Turnover... ⏳";
    submitBtn.disabled = true;
  }

  const baseUrl = typeof API_BASE_URL !== 'undefined' ? API_BASE_URL : '';

  fetch(`${baseUrl}/api/users/handover-ceo`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      currentCeoId: currentCeoId,
      currentPassword: currentPassword,
      firstName: firstName,
      middleName: middleName,
      lastName: lastName,
      email: email,
      phoneNumber: phoneNumber,
      username: username,
      memoNumber: memoNumber
    })
  })
    .then(async response => {
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(data.error || "Turnover processing failed.");
      }
      return data;
    })
    .then(data => {
      closeCeoHandoverModal();

      const dispName = document.getElementById('disp-ceo-succ-name');
      const dispUser = document.getElementById('disp-ceo-succ-user');
      const dispPass = document.getElementById('disp-ceo-succ-pass');

      if (dispName) dispName.innerText = data.successorName || `${firstName} ${lastName}`;
      if (dispUser) dispUser.innerText = data.successorUsername || username;
      if (dispPass) dispPass.innerText = data.tempPassword || 'Check registered email';

      const succModal = document.getElementById('ceo-handover-success-modal');
      if (succModal) {
        succModal.classList.remove('hidden');
        succModal.style.display = 'flex';
      }

      showToast("Engineering office successfully turned over! Session ended.", "success", 10000);
    })
    .catch(err => {
      console.error("Turnover Error:", err);
      showToast(err.message, "error", 6000);
    })
    .finally(() => {
      if (submitBtn) {
        submitBtn.innerText = originalText;
        submitBtn.disabled = false;
      }
    });
}

export function terminateCeoHandoverSession() {
  sessionStorage.clear();
  localStorage.removeItem('user');
  localStorage.removeItem('currentUser');
  window.location.replace('login.html');
}

// ------------------------------------------
// DOM binding helpers
// ------------------------------------------

function bindEditProfileModal() {
  // 1. Strict Phone Validation
  if (phoneInput) {
    phoneInput.addEventListener('input', function (e) {
      this.value = this.value.replace(/[^0-9]/g, '');
      if (this.value.length > 11) {
        this.value = this.value.slice(0, 11);
      }
    });
  }

  // 2. Open Modal and Pre-fill Fresh Data
  if (btnEditProfile && editProfileModal) {
    btnEditProfile.removeAttribute('onclick');

    btnEditProfile.addEventListener('click', () => {
      // Clear any old values first
      if (formEditProfile) formEditProfile.reset();

      editProfileModal.classList.remove('hidden');
      window.scrollTo({ top: 0, behavior: 'smooth' });

      // Pre-fill Locked Records
      document.getElementById('edit-prof-first').value = sessionStorage.getItem('firstName') || '';
      document.getElementById('edit-prof-middle').value = sessionStorage.getItem('middleName') || '';
      document.getElementById('edit-prof-last').value = sessionStorage.getItem('lastName') || '';
      document.getElementById('edit-prof-brgy').value = sessionStorage.getItem('barangayName') || '';

      // 🏷️ Format and Pre-fill User Role (using 'userRole')
      const rawRole = sessionStorage.getItem('userRole') || sessionStorage.getItem('role') || '';
      const roleLower = rawRole.toLowerCase();
      let displayRole = "Barangay Official";

      if (roleLower.includes('admin') || roleLower.includes('cpdo')) {
        displayRole = "CPDO Admin";
      } else if (roleLower.includes('ceo') || roleLower.includes('engineer')) {
        displayRole = "City Engineer";
      } else if (roleLower.includes('barangay')) {
        displayRole = "Barangay Official";
      } else if (rawRole) {
        displayRole = rawRole;
      }

      const roleField = document.getElementById('edit-prof-role');
      if (roleField) roleField.value = displayRole;

      // Pre-fill Editable Details
      document.getElementById('edit-prof-phone').value = sessionStorage.getItem('phoneNumber') || '';
      document.getElementById('edit-prof-email').value = sessionStorage.getItem('email') || '';
      document.getElementById('edit-prof-birthday').value = sessionStorage.getItem('birthday') || '';

      const genderVal = sessionStorage.getItem('gender');
      if (genderVal) document.getElementById('edit-prof-gender').value = genderVal;
    });
  }

  // 3. Wire the Cancel / Close buttons to use the new clear function
  const closeEditBtns = document.querySelectorAll('#edit-profile-modal .close-modal-btn, #edit-profile-modal button[type="button"]');
  closeEditBtns.forEach(btn => {
    btn.addEventListener('click', closeAndClearEditModal);
  });

  // 4. Handle the Save Button
  if (formEditProfile) {
    formEditProfile.addEventListener('submit', (e) => {
      e.preventDefault();

      const userId = sessionStorage.getItem('userId');
      if (!userId) {
        showToast("Session expired. Please log in again.", "error");
        return;
      }

      const phoneVal = phoneInput ? phoneInput.value : '';
      if (phoneVal && phoneVal.length < 11) {
        showToast("Phone number must be exactly 11 digits.", "error");
        return;
      }

      const updatedData = {
        phoneNumber: phoneVal,
        email: document.getElementById('edit-prof-email').value,
        birthday: document.getElementById('edit-prof-birthday').value,
        gender: document.getElementById('edit-prof-gender').value
      };

      const submitBtn = formEditProfile.querySelector('button[type="submit"]');
      const originalText = submitBtn.innerHTML;
      submitBtn.innerHTML = "Saving... ⏳";
      submitBtn.disabled = true;

      fetch(`${API_BASE_URL}/api/users/${userId}/profile`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updatedData)
      })
        .then(response => {
          if (!response.ok) throw new Error("Failed to update profile");

          // Update browser memory
          sessionStorage.setItem('phoneNumber', updatedData.phoneNumber);
          sessionStorage.setItem('email', updatedData.email);
          sessionStorage.setItem('birthday', updatedData.birthday);
          sessionStorage.setItem('gender', updatedData.gender);

          // Force update profile tab display
          const pPhone = document.getElementById('profile-phone');
          if (pPhone) pPhone.textContent = updatedData.phoneNumber;

          const pEmail = document.getElementById('profile-email');
          if (pEmail) pEmail.textContent = updatedData.email;

          const pGender = document.getElementById('profile-gender');
          if (pGender) pGender.textContent = updatedData.gender;

          const pBirthday = document.getElementById('profile-birthday');
          if (pBirthday) pBirthday.textContent = updatedData.birthday;

          // Recalculate Age
          if (updatedData.birthday) {
            const birthDate = new Date(updatedData.birthday);
            const today = new Date();
            let ageCalc = today.getFullYear() - birthDate.getFullYear();
            const m = today.getMonth() - birthDate.getMonth();
            if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) ageCalc--;

            const pAge = document.getElementById('profile-age');
            if (pAge) pAge.textContent = `${ageCalc} years old`;
          }

          closeAndClearEditModal();
          showToast("Profile updated successfully!", "success");
        })
        .catch(error => {
          console.error('Error:', error);
          showToast("Failed to save profile changes.", "error");
        })
        .finally(() => {
          submitBtn.innerHTML = originalText;
          submitBtn.disabled = false;
        });
    });
  }
}

function bindProfilePicture() {
  // 1. The Upload Logic (Triggered when they pick a photo)
  if (profilePicUpload) {
    profilePicUpload.addEventListener('change', function (e) {
      const file = e.target.files[0];
      if (!file) return;

      // Strict 5MB file size limit to protect your server
      if (file.size > 5 * 1024 * 1024) {
        showToast("Please choose an image smaller than 5MB", "error");
        this.value = '';
        return;
      }

      const userId = sessionStorage.getItem('userId');
      if (!userId) return;

      const formData = new FormData();
      formData.append('profilePicture', file);

      showToast("Uploading new profile picture... ⏳", "info");

      // Send to the new Spring Boot endpoint
      fetch(`${API_BASE_URL}/api/users/${userId}/profile-picture`, {
        method: 'POST',
        body: formData
      })
        .then(response => {
          if (!response.ok) throw new Error("Upload failed");
          return response.json();
        })
        .then(data => {
          showToast("Profile picture updated successfully!", "success");
          // Save the new filename to browser memory and instantly update the UI
          sessionStorage.setItem('profilePicture', data.profilePicture);
          updateAvatarDisplay(data.profilePicture);
        })
        .catch(error => {
          console.error('Error:', error);
          showToast("Failed to upload picture.", "error");
        });
    });
  }

  // 2. Ensure the "View" button calls the function
  const viewPicBtn = document.querySelector('button[onclick*="View Picture"]');
  if (viewPicBtn) {
    viewPicBtn.setAttribute('onclick', 'openProfilePicViewer()');
  }

  // 3. Ensure the avatar updates every time the profile modal is opened
  updateAvatarDisplay(sessionStorage.getItem('profilePicture'));
}

function bindPasswordForm() {
  const formChangePassword = document.getElementById('form-change-password');
  if (!formChangePassword) return;

  formChangePassword.addEventListener('submit', (e) => {
    e.preventDefault();

    const currentPass = document.getElementById('sec-current-pass').value;
    const newPass = document.getElementById('sec-new-pass').value;
    const confirmPass = document.getElementById('sec-confirm-pass').value;
    const submitBtn = document.getElementById('btn-submit-password');

    // 1. Check if passwords match
    if (newPass !== confirmPass) {
      showToast("New passwords do not match!", "error");
      return;
    }

    // 2. Strict Enterprise Complexity Regex:
    // Min 8 chars, 1 Uppercase, 1 Number, 1 Special Character (@$!%*?&#)
    if (!STRICT_PASSWORD_REGEX.test(newPass)) {
      showToast("Password must be 8+ characters, with 1 uppercase letter, 1 number, and 1 special character (@$!%*?&#).", "error");
      return;
    }

    const userId = sessionStorage.getItem('userId');
    if (!userId) {
      showToast("Session expired. Please log in again.", "error");
      return;
    }

    // Button Loading State
    const originalText = submitBtn.innerHTML;
    submitBtn.innerHTML = "Verifying & Saving... ⏳";
    submitBtn.disabled = true;

    // 3. Send Request to Spring Boot Backend
    fetch(`${API_BASE_URL}/api/users/${userId}/password`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentPassword: currentPass,
        newPassword: newPass
      })
    })
      .then(async response => {
        const data = await response.json();
        if (!response.ok) {
          throw new Error(data.error || "Failed to update password.");
        }
        return data;
      })
      .then(data => {
        showToast("Password successfully updated! 🔒", "success");
        formChangePassword.reset(); // Reset form fields

        // Revert all password input types back to hidden 'password'
        ['sec-current-pass', 'sec-new-pass', 'sec-confirm-pass'].forEach(id => {
          const el = document.getElementById(id);
          if (el) el.type = 'password';
        });
        // Revert icons back to eye
        document.querySelectorAll('#form-change-password span').forEach(span => {
          span.textContent = '👁️';
        });
      })
      .catch(error => {
        console.error('Error:', error);
        showToast(error.message, "error");
      })
      .finally(() => {
        submitBtn.innerHTML = originalText;
        submitBtn.disabled = false;
      });
  });
}

function bindProfileNavMenus() {
  // The original app.js attached two equivalent listeners to the same menu
  // (admin and CEO blocks). Both are preserved.
  const profileNavMenu = document.getElementById('profile-nav-menu');
  if (profileNavMenu) {
    profileNavMenu.addEventListener('click', (e) => {
      const targetLi = e.target.closest('li[data-target]');
      if (!targetLi) return;

      profileNavMenu.querySelectorAll('li').forEach(li => li.classList.remove('active'));
      targetLi.classList.add('active');

      document.querySelectorAll('.profile-tab').forEach(tab => {
        tab.classList.add('hidden');
        tab.classList.remove('active');
      });

      const targetTabId = targetLi.getAttribute('data-target');
      const selectedTab = document.getElementById(targetTabId);
      if (selectedTab) {
        selectedTab.classList.remove('hidden');
        selectedTab.classList.add('active');
      }
    });
  }

  const ceoProfileNavMenu = document.getElementById('profile-nav-menu');
  if (ceoProfileNavMenu) {
    ceoProfileNavMenu.addEventListener('click', (e) => {
      const targetLi = e.target.closest('li[data-target]');
      if (!targetLi) return;

      ceoProfileNavMenu.querySelectorAll('li').forEach(li => li.classList.remove('active'));
      targetLi.classList.add('active');

      document.querySelectorAll('.profile-tab').forEach(tab => {
        tab.classList.add('hidden');
        tab.classList.remove('active');
      });

      const targetTabId = targetLi.getAttribute('data-target');
      const selectedTab = document.getElementById(targetTabId);
      if (selectedTab) {
        selectedTab.classList.remove('hidden');
        selectedTab.classList.add('active');
      }
    });
  }
}

/**
 * Idempotent feature entry point. Called once on DOMContentLoaded by app.js.
 */
export function initProfileAccount() {
  if (initialized) return;
  initialized = true;

  // Temporary HTML/global compatibility bridge.
  window.updateAvatarDisplay = updateAvatarDisplay;
  window.openProfilePicViewer = openProfilePicViewer;
  window.togglePasswordVisibility = togglePasswordVisibility;
  window.openAdminHandoverModal = openAdminHandoverModal;
  window.closeAdminHandoverModal = closeAdminHandoverModal;
  window.submitAdminHandover = submitAdminHandover;
  window.terminateHandoverSession = terminateHandoverSession;
  window.openCeoHandoverModal = openCeoHandoverModal;
  window.closeCeoHandoverModal = closeCeoHandoverModal;
  window.submitCeoHandover = submitCeoHandover;
  window.terminateCeoHandoverSession = terminateCeoHandoverSession;

  // Cache DOM references.
  btnEditProfile = document.getElementById('btn-edit-profile');
  editProfileModal = document.getElementById('edit-profile-modal');
  formEditProfile = document.getElementById('form-edit-profile');
  phoneInput = document.getElementById('edit-prof-phone');
  mainAvatar = document.getElementById('main-profile-avatar');
  profilePicUpload = document.getElementById('profile-pic-upload');

  bindEditProfileModal();
  bindProfilePicture();
  bindPasswordForm();
  bindProfileNavMenus();

  // Auto-bind input listeners once DOM finishes loading
  attachPhoneInputMask('succ-phone');
  attachPhoneInputMask('ceo-succ-phone');
}