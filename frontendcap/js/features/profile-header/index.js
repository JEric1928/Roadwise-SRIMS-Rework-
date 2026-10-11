/**
 * Master profile header/sidebar population.
 *
 * Extracted verbatim from app.js. The original code ran inside the main
 * DOMContentLoaded listener, so the slice is wrapped in its own
 * DOMContentLoaded listener to preserve timing.
 */
document.addEventListener('DOMContentLoaded', () => {
// ==========================================
// 9. MASTER PROFILE LOGIC (HEADER, SIDEBAR & PHOTO)
// ==========================================
  window.populateProfileData = function() {
    try {
      const getSafeStr = (key, fallback) => {
        const val = sessionStorage.getItem(key);
        return (val && val !== 'null' && val !== 'undefined' && val.trim() !== '') ? val : fallback;
      };

      const firstName = getSafeStr('firstName', 'Unknown');
      const middleName = getSafeStr('middleName', '');
      const lastName = getSafeStr('lastName', 'User');
      const email = getSafeStr('email', 'Not Provided');
      const phone = getSafeStr('phoneNumber', 'Not Provided');
      const birthday = getSafeStr('birthday', '');
      const gender = getSafeStr('gender', 'Not Specified');
      const username = getSafeStr('username', 'N/A');
      const role = getSafeStr('userRole', 'BARANGAY');
      const barangayName = getSafeStr('barangayName', 'Not Assigned');
      const profilePic = getSafeStr('profilePicture', '');

      // 🚀 1. BUILD MIDDLE INITIAL FOR DISPLAY (e.g., "L.")
      let mi = '';
      if (middleName && middleName.trim().length > 0) {
        const cleanedMiddle = middleName.trim().replace(/\./g, '');
        if (cleanedMiddle.length > 0) {
          mi = `${cleanedMiddle.charAt(0).toUpperCase()}.`;
        }
      }

      // Name Builders:
      // • displayName -> For Header & Sidebar Badges (First M.I. Last)
      // • fullName    -> For Full Profile Detail Tab (First Middle Last)
      const displayName = [firstName, mi, lastName].filter(Boolean).join(' ');
      const fullName = [firstName, middleName, lastName].filter(Boolean).join(' ');

      // Age & Birthday Calculation
      let displayAge = "N/A";
      let displayBirthday = "Not Provided";

      if (birthday) {
        displayBirthday = birthday;
        const birthDate = new Date(birthday);
        if (!isNaN(birthDate.getTime())) {
          const today = new Date();
          let ageCalc = today.getFullYear() - birthDate.getFullYear();
          const m = today.getMonth() - birthDate.getMonth();
          if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) {
            ageCalc--;
          }
          displayAge = `${ageCalc} years old`;
        }
      }

      // 🏷️ Role & Header Subtitle Formatter
      let displayRole = "Barangay Official";
      let headerRole = "Barangay Official";
      const userRoleLower = String(role).toLowerCase();

      if (userRoleLower.includes('admin') || userRoleLower.includes('cpdo')) {
        displayRole = "CPDO Admin";
        headerRole = "CPDO Admin";
      } else if (userRoleLower.includes('ceo') || userRoleLower.includes('engineer')) {
        displayRole = "City Engineer";
        headerRole = "City Engineer";
      } else {
        displayRole = "Barangay Official";
        // 🏛️ Dynamic display: "Barangay Kaypian"
        if (barangayName && barangayName !== 'Not Assigned') {
          const cleanBrgy = barangayName.trim();
          headerRole = cleanBrgy.toLowerCase().startsWith('barangay')
            ? cleanBrgy
            : `Barangay ${cleanBrgy}`;
        } else {
          headerRole = "Barangay Official";
        }
      }

      const setElText = (id, text) => {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
      };

      // 🚀 2. TEXT INJECTIONS (Uses displayName & formatted headerRole)
      setElText('header-display-name', displayName);
      setElText('header-display-role', headerRole);
      setElText('sidebar-display-name', displayName);
      setElText('sidebar-display-role', headerRole);
      setElText('side-profile-name', displayName);
      setElText('side-profile-role', headerRole);
      setElText('side-profile-brgy', barangayName);

      // Profile Tab Details (Detailed View)
      setElText('profile-full-name', fullName);
      setElText('profile-email', email);
      setElText('profile-phone', phone);
      setElText('profile-birthday', displayBirthday);
      setElText('profile-age', displayAge);
      setElText('profile-gender', gender);
      setElText('profile-username', username);
      setElText('profile-role', displayRole);

      const profileBarangayEl = document.getElementById('profile-barangay');
      if (profileBarangayEl) {
        if (userRoleLower.includes('admin') || userRoleLower.includes('cpdo')) {
          profileBarangayEl.textContent = 'City Planning and Development Office';
        } else if (userRoleLower.includes('ceo') || userRoleLower.includes('engineer')) {
          profileBarangayEl.textContent = 'City Engineering Office';
        } else {
          profileBarangayEl.textContent = barangayName;
        }
      }

      // 🖼️ 3. RENDER PROFILE PICTURE IN HEADER & PROFILE TAB
      const headerImg = document.getElementById('header-profile-img');
      const headerFallback = document.getElementById('header-profile-fallback');
      const mainAvatar = document.getElementById('main-profile-avatar');

      const isValidImage = profilePic &&
        profilePic !== 'no_image.jpg' &&
        profilePic.toLowerCase() !== 'null' &&
        profilePic.toLowerCase() !== 'undefined';

      if (isValidImage) {
        if (headerImg) {
          if (typeof window.loadSecureImage === 'function') {
            window.loadSecureImage('header-profile-img', profilePic);
          } else {
            headerImg.src = profilePic;
          }
          headerImg.style.display = 'block';
        }
        if (headerFallback) headerFallback.style.display = 'none';

        if (mainAvatar) {
          mainAvatar.innerHTML = `<img id="sidebar-avatar-img" src="" alt="Profile" style="width: 100%; height: 100%; object-fit: cover; border-radius: 50%;">`;
          if (typeof window.loadSecureImage === 'function') {
            window.loadSecureImage('sidebar-avatar-img', profilePic);
          } else {
            const imgEl = document.getElementById('sidebar-avatar-img');
            if (imgEl) imgEl.src = profilePic;
          }
        }
      } else {
        if (headerImg) headerImg.style.display = 'none';
        if (headerFallback) headerFallback.style.display = 'inline';

        if (mainAvatar) {
          const defaultIcon = userRoleLower.includes('admin') ? '🏢' : (userRoleLower.includes('ceo') ? '⚙️' : '🏛️');
          mainAvatar.innerHTML = defaultIcon;
        }
      }

    } catch (error) {
      console.error("🚨 Profile Population Error:", error);
    }
  };

// ==========================================
// 🚀 AUTO-LOAD PROFILE HEADER GLOBALLY
// ==========================================
  document.addEventListener("DOMContentLoaded", () => {
    if (typeof window.populateProfileData === 'function') {
      window.populateProfileData();
    }
  });

// Fallback execution
  setTimeout(() => {
    if (typeof window.populateProfileData === 'function') {
      window.populateProfileData();
    }
  }, 300);

// --- PROFILE BUTTON TRIGGERS (WITH STATE RESET FIX) ---
  const profileBtn = document.querySelector('.header-profile-btn');
  if (profileBtn) {
    profileBtn.addEventListener('click', (e) => {
      e.preventDefault();
      history.pushState({ target: 'view-profile' }, "", "#view-profile");
      if (typeof switchView === 'function') switchView('view-profile');
    });
  }

// --- PROFILE TAB NAVIGATION ---
  const profileMenuLinks = document.querySelectorAll('#profile-nav-menu li:not(.logout-btn)');
  const profileTabs = document.querySelectorAll('.profile-tab');

  if (profileMenuLinks.length > 0) {
    profileMenuLinks.forEach(link => {
      link.addEventListener('click', () => {
        profileMenuLinks.forEach(l => l.classList.remove('active'));
        link.classList.add('active');

        profileTabs.forEach(tab => tab.classList.add('hidden'));
        const targetId = link.getAttribute('data-target');
        const targetTab = document.getElementById(targetId);
        if (targetTab) targetTab.classList.remove('hidden');
      });
    });
  }

// --- SECURE LOGOUT LOGIC (WITH CONFIRMATION MODAL) ---
  const logoutBtn = document.querySelector('.logout-btn');
  const logoutConfirmModal = document.getElementById('logout-confirm-modal');
  const btnConfirmLogout = document.getElementById('btn-confirm-logout');

  if (logoutBtn && logoutConfirmModal) {
    logoutBtn.addEventListener('click', (e) => {
      e.preventDefault();
      logoutConfirmModal.classList.remove('hidden');
    });
  }

  if (btnConfirmLogout) {
    btnConfirmLogout.addEventListener('click', () => {
      sessionStorage.clear();
      window.location.replace('login.html');
    });
  }

// --- BACK TO DASHBOARD BUTTON ---
  const backToDashBtn = document.getElementById('btn-back-dashboard');
  if (backToDashBtn) {
    backToDashBtn.addEventListener('click', () => {
      const currentPath = window.location.pathname.toLowerCase();
      const dashId = currentPath.includes("admin") ? 'view-admin-dashboard' : 'view-dashboard';

      history.pushState({ target: dashId }, "", "#" + dashId);
      if (typeof switchView === 'function') switchView(dashId);
    });
  }

});
