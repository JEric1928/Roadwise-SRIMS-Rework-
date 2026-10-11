/**
 * Administration feature: user management, barangay management, and city road
 * management.
 *
 * Extracted verbatim from app.js. This module self-registers its
 * DOMContentLoaded bindings (preserving the original registration behavior)
 * and exposes the same window.* functions that templates and other app.js
 * code already call.
 */
import { apiFetch, API_BASE_URL } from '../../core/api.js';
import { showToast } from '../../components/toast.js';

// ==========================================
// 👥 USER MANAGEMENT DATA FETCHER
// ==========================================
window.loadUserManagementTable = function() {
  const tbody = document.getElementById('user-management-tbody');
  if (!tbody) return;

  tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 30px; color: #64748b;">Loading officials from database...</td></tr>';

  apiFetch(`/api/users/officials`)
    .then(users => {
      if (!users || users.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 30px; color: #64748b;">No Barangay Officials found. Provision a new account above!</td></tr>';
        return;
      }

      let html = '';
      users.forEach(user => {
        const brgyName = user.barangay ? (user.barangay.barangayName || `Barangay ID: ${user.barangay.id}`) : '<span style="color:red; font-weight: 600;">Pending Assignment</span>';

        let statusBadge = '';
        if (user.status === 'Deactivated') {
          statusBadge = '<span style="background: #fee2e2; color: #dc2626; padding: 4px 10px; border-radius: 20px; font-size: 11px; font-weight: bold;">🔴 Deactivated</span>';
        } else if (user.status === 'Suspended') {
          statusBadge = '<span style="background: #ffedd5; color: #c2410c; padding: 4px 10px; border-radius: 20px; font-size: 11px; font-weight: bold;">🟠 Suspended</span>';
        } else {
          statusBadge = '<span style="background: #dcfce7; color: #16a34a; padding: 4px 10px; border-radius: 20px; font-size: 11px; font-weight: bold;">🟢 Active</span>';
        }

        html += `
          <tr style="border-bottom: 1px solid #e2e8f0; transition: 0.2s;">
              <td style="padding: 15px 20px; font-size: 14px; color: #333;">
                  <strong>${user.firstName || 'N/A'} ${user.lastName || 'N/A'}</strong>
              </td>
              <td style="padding: 15px 20px; font-size: 14px; color: #495057;">
                  ${user.username || 'N/A'}
              </td>
              <td style="padding: 15px 20px; font-size: 14px; color: #495057;">
                  <span style="color: #6c757d; margin-right: 5px;">🏛️</span> ${brgyName}
              </td>
              <td style="padding: 15px 20px; text-align: center;">
                  ${statusBadge}
              </td>
              <td style="padding: 15px 20px; text-align: right;">
                 <button class="btn-small manage-user-btn" onclick="openManageOfficialModal(${user.id})"
                         style="background-color: #1a0ca3; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-weight: bold; font-size: 12px;">
                   ⚙️ Manage
                 </button>
              </td>
          </tr>
        `;
      });
      tbody.innerHTML = html;
    })
    .catch(err => {
      console.error("Error loading officials:", err);
      tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 30px; color: #dc2626;">Failed to load officials from server.</td></tr>';
      if (typeof showToast === 'function') showToast("Failed to load officials from database.", "error");
    });
};

// ==========================================
// 🏢 SMART CUSTOM BARANGAY DROPDOWN (ALWAYS DROPS DOWN)
// ==========================================
window.loadBarangayDropdownForAdmin = function() {
  const hiddenInput = document.getElementById('add-user-barangay');
  const labelSpan = document.getElementById('add-user-barangay-label');
  const menuContainer = document.getElementById('add-user-barangay-menu');
  const triggerBtn = document.getElementById('add-user-barangay-btn');

  if (!menuContainer || !triggerBtn) return;

  // Reset to initial state
  hiddenInput.value = '';
  labelSpan.innerText = 'Select Barangay Jurisdiction...';
  labelSpan.style.color = '#64748b';
  menuContainer.innerHTML = '<div style="padding: 12px; text-align: center; color: #64748b; font-size: 12px;">Loading barangays... ⏳</div>';

  Promise.all([
    apiFetch(`/api/barangays`),
    apiFetch(`/api/users/officials`)
  ])
    .then(([barangays, officials]) => {
      // Map occupied barangay IDs to active official names safely
      const occupiedMap = {};
      officials.forEach(u => {
        if (u.barangay && u.barangay.id && (!u.status || u.status !== 'Deactivated')) {
          const fullName = [u.firstName, u.lastName].filter(Boolean).join(' ').trim();
          occupiedMap[u.barangay.id] = fullName || u.username || 'Assigned Official';
        }
      });

      barangays.sort((a, b) => a.barangayName.localeCompare(b.barangayName));

      let itemsHtml = '';
      barangays.forEach(brgy => {
        const isOccupied = !!occupiedMap[brgy.id];

        if (isOccupied) {
          itemsHtml += `
            <div style="padding: 9px 14px; font-size: 13px; color: #94a3b8; background: #f8fafc; border-bottom: 1px solid #f1f5f9; cursor: not-allowed; display: flex; justify-content: space-between;">
              <span>${brgy.barangayName}</span>
              <span style="font-size: 11px; font-style: italic;">Assigned: ${occupiedMap[brgy.id]}</span>
            </div>
          `;
        } else {
          itemsHtml += `
            <div class="custom-brgy-option" data-id="${brgy.id}" data-name="${brgy.barangayName}"
                 style="padding: 9px 14px; font-size: 13px; color: #0f172a; border-bottom: 1px solid #f1f5f9; cursor: pointer; transition: background 0.15s ease;"
                 onmouseover="this.style.background='#eff6ff'; this.style.color='#1d4ed8';"
                 onmouseout="this.style.background='#ffffff'; this.style.color='#0f172a';">
              📍 <strong>${brgy.barangayName}</strong> <span style="font-size: 11px; color: #16a34a; float: right; font-weight: 600;">(Available)</span>
            </div>
          `;
        }
      });

      menuContainer.innerHTML = itemsHtml;

      // Attach click events for available options
      menuContainer.querySelectorAll('.custom-brgy-option').forEach(option => {
        option.addEventListener('click', (e) => {
          e.stopPropagation();
          const brgyId = option.getAttribute('data-id');
          const brgyName = option.getAttribute('data-name');

          hiddenInput.value = brgyId;
          labelSpan.innerText = `📍 ${brgyName}`;
          labelSpan.style.color = '#0f172a';
          labelSpan.style.fontWeight = '700';

          menuContainer.classList.add('hidden');
        });
      });
    })
    .catch(err => {
      console.error("Error loading barangays:", err);
      menuContainer.innerHTML = '<div style="padding: 12px; text-align: center; color: #ef4444; font-size: 12px;">Failed to load barangays.</div>';
    });

  // Toggle dropdown on button click & always scroll to top
  triggerBtn.onclick = function(e) {
    e.stopPropagation();
    const isClosed = menuContainer.classList.contains('hidden');

    if (isClosed) {
      menuContainer.classList.remove('hidden');
      menuContainer.scrollTop = 0; // Guaranteed to start at the top
    } else {
      menuContainer.classList.add('hidden');
    }
  };

  // Close dropdown when clicking outside
  document.addEventListener('click', (e) => {
    if (!triggerBtn.contains(e.target) && !menuContainer.contains(e.target)) {
      menuContainer.classList.add('hidden');
    }
  });
};

// ==========================================
// 👥 PROVISION NEW OFFICIAL SUBMIT
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const btnOpenAddUser = document.getElementById('btn-open-add-user');
  const addUserModal = document.getElementById('add-user-modal');
  const formAddUser = document.getElementById('form-add-user');

  const firstInput = document.getElementById('add-user-first');
  const lastInput = document.getElementById('add-user-last');
  const userOutput = document.getElementById('add-user-username');
  const emailInput = document.getElementById('add-user-email');

  // Helper: Auto-generate username
  const updateUsername = () => {
    if (!firstInput || !lastInput || !userOutput) return;
    const first = firstInput.value.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
    const last = lastInput.value.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
    userOutput.value = (first || last) ? `${first}.${last}` : '';
  };

  if (firstInput && lastInput) {
    firstInput.addEventListener('input', updateUsername);
    lastInput.addEventListener('input', updateUsername);
  }

  // Helper: Clean Reset of Modal State
  const resetAddUserForm = () => {
    if (formAddUser) formAddUser.reset();
    if (userOutput) userOutput.value = '';
    const submitBtn = formAddUser ? formAddUser.querySelector('button[type="submit"]') : null;
    if (submitBtn) {
      submitBtn.innerHTML = "💾 Provision Account";
      submitBtn.disabled = false;
    }
  };

  // Helper: Modal Closer
  window.closeAddUserModal = function() {
    if (addUserModal) {
      addUserModal.classList.add('hidden');
      addUserModal.style.display = 'none';
    }
    resetAddUserForm();
  };

  // 1. Open Modal & Refresh Options (Clean state guaranteed)
  if (btnOpenAddUser && addUserModal) {
    btnOpenAddUser.addEventListener('click', () => {
      resetAddUserForm(); // 🔄 Clears leftover data before displaying
      if (typeof loadBarangayDropdownForAdmin === 'function') {
        loadBarangayDropdownForAdmin();
      }
      addUserModal.classList.remove('hidden');
      addUserModal.style.display = 'flex';
      if (firstInput) firstInput.focus();
    });
  }

  // Bind close buttons (X icon, Cancel button)
  if (addUserModal) {
    const closeBtns = addUserModal.querySelectorAll('.close-modal-btn, .btn-cancel, #btn-cancel-add-user');
    closeBtns.forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        window.closeAddUserModal();
      });
    });
  }

  // 2. Form Submission with Duplicate Email Validation
  if (formAddUser) {
    formAddUser.addEventListener('submit', async (e) => {
      e.preventDefault();

      const selectedBrgy = document.getElementById('add-user-barangay')?.value;
      if (!selectedBrgy) {
        if (typeof showToast === 'function') showToast("Please select an available Barangay jurisdiction.", "warning");
        return;
      }

      const email = (emailInput?.value || '').trim().toLowerCase();
      const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!email || !emailPattern.test(email)) {
        if (typeof showToast === 'function') showToast("Please provide a valid email address.", "warning");
        return;
      }

      const submitBtn = formAddUser.querySelector('button[type="submit"]');
      if (submitBtn) {
        submitBtn.innerHTML = "⏳ Validating & Saving...";
        submitBtn.disabled = true;
      }

      try {
        // 🔍 STEP 1: Pre-flight check for duplicate emails & usernames in the database
        const existingUsers = await apiFetch(`/api/users`).catch(() => []);
        if (Array.isArray(existingUsers)) {
          const emailDuplicate = existingUsers.some(
            u => String(u.email || '').trim().toLowerCase() === email
          );
          if (emailDuplicate) {
            throw new Error(`The email "${email}" is already registered to another official.`);
          }

          const usernameVal = (userOutput?.value || '').trim().toLowerCase();
          const usernameDuplicate = existingUsers.some(
            u => String(u.username || '').trim().toLowerCase() === usernameVal
          );
          if (usernameDuplicate) {
            throw new Error(`The username "${usernameVal}" is already taken. Please customize it.`);
          }
        }

        // 🚀 STEP 2: Dispatch payload to backend
        const payload = {
          firstName: firstInput ? firstInput.value.trim() : '',
          middleName: document.getElementById('add-user-middle')?.value.trim() || '',
          lastName: lastInput ? lastInput.value.trim() : '',
          email: email,
          username: userOutput ? userOutput.value.trim() : '',
          password: document.getElementById('add-user-password')?.value || '',
          role: "BARANGAY",
          status: "Active",
          barangayId: selectedBrgy,
          adminId: sessionStorage.getItem("userId") || ""
        };

        const response = await apiFetch(`/api/users/register`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        if (response && response.error) {
          throw new Error(response.error);
        }

        if (typeof showToast === 'function') {
          showToast("Official account successfully provisioned!", "success");
        }

        window.closeAddUserModal();

        if (typeof loadUserManagementTable === 'function') {
          loadUserManagementTable();
        }

      } catch (error) {
        console.error("Error creating user:", error);
        const errorMsg = error.message || "Failed to create account. Please check inputs.";
        showToast(errorMsg, "error");
      } finally {
        if (submitBtn) {
          submitBtn.innerHTML = "💾 Provision Account";
          submitBtn.disabled = false;
        }
      }
    });
  }
});

// ==========================================
// ⚙️ MANAGE OFFICIAL LOGIC (Edit, Suspend, Reassign)
// ==========================================
window.openManageOfficialModal = function(userId) {
  const modal = document.getElementById('manage-user-modal');
  if (!modal) return;

  document.getElementById('manage-user-id').value = userId;

  Promise.all([
    apiFetch(`/api/barangays`),
    apiFetch(`/api/users/officials`),
    apiFetch(`/api/users/${userId}`)
  ])
    .then(([barangays, officials, currentUser]) => {
      // Map other assigned officials
      const occupiedMap = {};
      officials.forEach(u => {
        if (String(u.id) !== String(userId) && u.barangay && u.barangay.id && (!u.status || u.status !== 'Deactivated')) {
          occupiedMap[u.barangay.id] = `${u.firstName} ${u.lastName}`;
        }
      });

      let optionsHtml = '<option value="" disabled>Select Barangay...</option>';
      barangays.sort((a, b) => a.barangayName.localeCompare(b.barangayName)).forEach(b => {
        const isCurrentBrgy = currentUser.barangay && currentUser.barangay.id === b.id;
        if (occupiedMap[b.id]) {
          optionsHtml += `<option value="${b.id}" disabled style="color: #94a3b8;">${b.barangayName} (Occupied: ${occupiedMap[b.id]})</option>`;
        } else if (isCurrentBrgy) {
          optionsHtml += `<option value="${b.id}" selected>📍 ${b.barangayName} (Currently Assigned)</option>`;
        } else {
          optionsHtml += `<option value="${b.id}">📍 ${b.barangayName} (Available)</option>`;
        }
      });

      document.getElementById('manage-user-barangay').innerHTML = optionsHtml;

      document.getElementById('manage-user-first').value = currentUser.firstName || '';
      document.getElementById('manage-user-middle').value = currentUser.middleName || '';
      document.getElementById('manage-user-last').value = currentUser.lastName || '';
      document.getElementById('manage-user-email').value = currentUser.email || '';
      document.getElementById('manage-user-status').value = currentUser.status || 'Active';

      if (currentUser.barangay) {
        document.getElementById('manage-user-barangay').value = currentUser.barangay.id;
      }

      modal.classList.remove('hidden');
      modal.style.display = 'flex';
    })
    .catch(err => {
      console.error("Error loading user details:", err);
      if (typeof showToast === 'function') showToast("Failed to load official's data.", "error");
    });
};

// Manage Form Submit with Duplicate Email Protection
document.addEventListener("DOMContentLoaded", () => {
  const formManageUser = document.getElementById('form-manage-user');

  if (formManageUser) {
    formManageUser.addEventListener('submit', async (e) => {
      e.preventDefault();

      const submitBtn = formManageUser.querySelector('button[type="submit"]');
      if (submitBtn) {
        submitBtn.innerHTML = "⏳ Validating & Saving...";
        submitBtn.disabled = true;
      }

      const userId = document.getElementById('manage-user-id').value;
      const emailInput = document.getElementById('manage-user-email');
      const email = (emailInput?.value || '').trim().toLowerCase();

      // 1. Email format verification
      const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!email || !emailPattern.test(email)) {
        if (typeof showToast === 'function') showToast("Please provide a valid email address.", "warning");
        if (submitBtn) {
          submitBtn.innerHTML = "💾 Save Profile Changes";
          submitBtn.disabled = false;
        }
        return;
      }

      try {
        // 2. Pre-flight check: Prevent assigning an email owned by ANOTHER user
        const allUsers = await apiFetch(`/api/users`).catch(() => []);
        if (Array.isArray(allUsers)) {
          const emailDuplicate = allUsers.some(
            u => String(u.id) !== String(userId) && String(u.email || '').trim().toLowerCase() === email
          );
          if (emailDuplicate) {
            throw new Error(`The email "${email}" is already registered to another user.`);
          }
        }

        const payload = {
          firstName: document.getElementById('manage-user-first').value.trim(),
          middleName: document.getElementById('manage-user-middle').value.trim(),
          lastName: document.getElementById('manage-user-last').value.trim(),
          email: email,
          barangayId: document.getElementById('manage-user-barangay').value,
          status: document.getElementById('manage-user-status').value,
          adminId: sessionStorage.getItem("userId") || ""
        };

        const response = await apiFetch(`/api/users/${userId}/manage`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        if (response && response.error) {
          throw new Error(response.error);
        }

        if (typeof showToast === 'function') showToast("Official's record successfully updated!", "success");

        const modal = document.getElementById('manage-user-modal');
        if (modal) {
          modal.classList.add('hidden');
          modal.style.display = 'none';
        }

        if (typeof loadUserManagementTable === 'function') loadUserManagementTable();

      } catch (err) {
        console.error("Error updating user:", err);
        const errorMsg = err.message || "Failed to update record.";
        if (typeof showToast === 'function') showToast(errorMsg, "error");
      } finally {
        if (submitBtn) {
          submitBtn.innerHTML = "💾 Save Profile Changes";
          submitBtn.disabled = false;
        }
      }
    });
  }

  // Emergency Password Reset Handler
  const btnConfirmReset = document.getElementById('btn-confirm-reset');
  const resetConfirmModal = document.getElementById('reset-confirm-modal');

  if (btnConfirmReset) {
    btnConfirmReset.addEventListener('click', () => {
      const userId = document.getElementById('manage-user-id').value;

      btnConfirmReset.innerHTML = "⏳ Resetting...";
      btnConfirmReset.disabled = true;

      apiFetch(`/api/users/${userId}/emergency-reset`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ adminId: sessionStorage.getItem("userId") || "" })
      })
        .then(() => {
          if (resetConfirmModal) {
            resetConfirmModal.classList.add('hidden');
            resetConfirmModal.style.display = 'none';
          }
          if (typeof showToast === 'function') showToast("Password successfully reset to default (RoadWise2026!)", "success");
        })
        .catch(err => {
          console.error("Error resetting password:", err);
          if (resetConfirmModal) {
            resetConfirmModal.classList.add('hidden');
            resetConfirmModal.style.display = 'none';
          }
          if (typeof showToast === 'function') showToast("Failed to reset password.", "error");
        })
        .finally(() => {
          btnConfirmReset.innerHTML = "Yes, Reset Password";
          btnConfirmReset.disabled = false;
        });
    });
  }
});

// ==========================================
// 8. BARANGAY MANAGEMENT: LOAD MAIN TABLE (WITH ROAD SEARCH)
// ==========================================
window.loadBarangayManagement = function() {

  const tableBody = document.getElementById('barangay-table-body');
  if (!tableBody) return;

  tableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 15px;">Loading Barangays... <span class="icon">⏳</span></td></tr>';

  // Concurrently fetch summary data and all city roads
  Promise.all([
    apiFetch('/api/barangays/dashboard-summary'),
    apiFetch('/api/roads').catch(() => [])
  ])
    .then(([data, roads]) => {
      tableBody.innerHTML = '';

      if (!data || data.length === 0) {
        tableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 15px;">No barangays found in the system.</td></tr>';
        return;
      }

      const allRoads = Array.isArray(roads) ? roads : [];

      data.forEach((brgy, index) => {
        let badgeHtml = `<span class="badge" style="background-color: #e9ecef; color: #6c757d;">0 Active</span>`;
        if (brgy.activeReportCount >= 5) {
          badgeHtml = `<span class="badge high">${brgy.activeReportCount} Active</span>`;
        } else if (brgy.activeReportCount > 0) {
          badgeHtml = `<span class="badge medium">${brgy.activeReportCount} Active</span>`;
        }

        // 1. Gather all road names registered under this barangay
        const brgyRoads = allRoads.filter(r => {
          const rBrgyId = r.barangay?.id || r.barangayId;
          return String(rBrgyId) === String(brgy.id);
        });
        const roadNamesString = brgyRoads.map(r => r.roadName || '').filter(Boolean).join(', ');

        const row = document.createElement('tr');

        // 2. Attach road names to data-roads for the search engine
        row.setAttribute('data-roads', roadNamesString);

        row.innerHTML = `
          <td style="text-align: center; font-weight: bold; color: #6c757d;">${index + 1}</td>
          <td><strong>${brgy.name}</strong></td>
          <td>${brgy.contactName || 'Unassigned'}</td>
          <td>${brgy.roadCount || 0} Roads</td>
          <td>${badgeHtml}</td>
          <td>
            <button class="btn-small manage-brgy-btn" onclick="openManageBarangayModal(${brgy.id})">
              Manage Barangay
            </button>
          </td>
        `;
        tableBody.appendChild(row);
      });

      // 3. Re-run search in case the user typed before the data finished loading
      const searchInput = document.getElementById('search-barangay-input');
      if (searchInput && searchInput.value.trim() !== '' && typeof window.executeGlobalSearch === 'function') {
        window.executeGlobalSearch('search-barangay-input', 'barangay-table-body');
      }
    })
    .catch(err => {
      console.error('Error loading barangays:', err);
      tableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: red; padding: 15px;">Failed to load database.</td></tr>';
    });
};

// ==========================================
// BARANGAY MANAGEMENT: OPEN MODAL & FETCH DATA
// ==========================================
let currentManageBarangayId = null;

window.openManageBarangayModal = function(id) {
  currentManageBarangayId = id;
  const modal = document.getElementById('barangay-modal');
  if (!modal) return;

  // Crash-proof helper
  const safeSetText = (elementId, text) => {
    const el = document.getElementById(elementId);
    if (el) el.innerText = text;
  };

  // Set temporary loading text
  safeSetText('manage-brgy-name', "Loading...");
  safeSetText('manage-brgy-kapitan', "Loading...");
  safeSetText('manage-brgy-contact', "Loading...");
  safeSetText('manage-brgy-email', "Loading...");

  const roadsBody = document.getElementById('manage-brgy-roads-body');
  if (roadsBody) {
    roadsBody.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 15px;">Loading roads... ⏳</td></tr>';
  }

  // Show modal instantly
  modal.classList.remove('hidden');

  // 🚀 FETCH 1: THE BARANGAY INFO FIRST
  apiFetch(`/api/barangays/${id}`)
    .then(brgy => {
      safeSetText('manage-brgy-name', brgy.barangayName || "Unknown");
      safeSetText('manage-brgy-kapitan', brgy.brgyCaptain || 'Unassigned');
      safeSetText('manage-brgy-contact', brgy.contactNumber || 'N/A');
      safeSetText('manage-brgy-email', brgy.emailAddress || 'N/A');
    })
    .catch(err => {
      console.error("Error fetching barangay info:", err);
      safeSetText('manage-brgy-name', "Error Fetching Data");
      safeSetText('manage-brgy-kapitan', "Error");
    });

  // 🚀 FETCH 2: THE CITY ROADS
  apiFetch(`/api/roads/barangay/${id}`)
    .then(roads => {
      if (!roadsBody) return;
      roadsBody.innerHTML = '';

      if (roads.length === 0) {
        roadsBody.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 15px; color: #6c757d;">No city roads registered to this jurisdiction yet.</td></tr>';
        return;
      }

      roads.forEach(road => {
        const row = document.createElement('tr');
        row.innerHTML = `
  <td style="padding: 10px; font-size: 12px;">${road.roadId || 'N/A'}</td>
  <td style="padding: 10px; font-size: 12px;"><strong>${road.roadName || 'Unnamed Road'}</strong></td>
  <td style="padding: 10px; font-size: 12px;">${road.roadImportance || 'Unknown'}</td>
  <td style="padding: 10px; text-align: right;">

    <!-- 🚀 THE ENHANCED EDIT BUTTON -->
    <button class="btn-edit-road" onclick="openEditRoadModal(${road.id})"
            style="background-color: #f8fafc; color: #3b82f6; border: 1px solid #bfdbfe; padding: 6px 12px; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; display: inline-flex; align-items: center; gap: 5px; box-shadow: 0 1px 2px rgba(0,0,0,0.05);">
      <span style="font-size: 14px;">✏️</span> Edit
    </button>

  </td>
`;
        roadsBody.appendChild(row);
      });
    })
    .catch(err => {
      console.error("Error loading roads:", err);
      if (roadsBody) {
        roadsBody.innerHTML = '<tr><td colspan="4" style="text-align: center; color: red; padding: 15px;">Failed to load roads.</td></tr>';
      }
    });
};

// ==========================================
// BARANGAY MANAGEMENT: EVENT LISTENERS
// ==========================================
document.addEventListener('DOMContentLoaded', () => {

  // 🚀 THE FIX: Tell the table to load its data automatically when the page opens!
  if (document.getElementById('barangay-table-body')) {
    loadBarangayManagement();
  }

  // Open Add Barangay Modal
  const btnAddBarangay = document.getElementById('btn-add-barangay');
  const addBrgyModal = document.getElementById('add-brgy-modal');
  if (addBrgyModal && btnAddBarangay) {
    const closeBtns = addBrgyModal.querySelectorAll('.close-add-brgy-btn');
    btnAddBarangay.addEventListener('click', () => addBrgyModal.classList.remove('hidden'));
    closeBtns.forEach(btn => btn.addEventListener('click', () => addBrgyModal.classList.add('hidden')));
  }

  // Close Manage Barangay Modal
  const barangayModal = document.getElementById('barangay-modal');
  if (barangayModal) {
    const closeBtns = barangayModal.querySelectorAll('.close-brgy-btn');
    closeBtns.forEach(btn => btn.addEventListener('click', () => barangayModal.classList.add('hidden')));
  }

  // Open "Add Road" Modal from inside Manage Modal
  const btnOpenAddRoad = document.getElementById('btn-open-add-road');
  const addRoadModal = document.getElementById('add-road-modal');
  if (btnOpenAddRoad && addRoadModal) {
    btnOpenAddRoad.addEventListener('click', (e) => {
      e.preventDefault();
      addRoadModal.classList.remove('hidden');
    });

    const closeAddRoadBtns = addRoadModal.querySelectorAll('.close-add-road-btn');
    closeAddRoadBtns.forEach(btn => btn.addEventListener('click', () => addRoadModal.classList.add('hidden')));
  }
});


// ==========================================
// BARANGAY MANAGEMENT: ADD NEW BARANGAY
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  const formAddBarangay = document.getElementById('form-add-barangay');

  if (formAddBarangay) {
    formAddBarangay.addEventListener('submit', function(e) {
      e.preventDefault();

      const submitBtn = this.querySelector('button[type="submit"]');
      const originalText = submitBtn.innerHTML;
      submitBtn.innerHTML = "⏳ Saving...";
      submitBtn.disabled = true;

      // Build the JSON payload matching your Barangay.java model
      const payload = {
        barangayName: document.getElementById('add-brgy-name').value.trim()
      };

      // Send to the Java backend
      fetch(`${API_BASE_URL}/api/barangays`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
        .then(async res => {
          // 🚀 NEW: If the backend throws our 400 Bad Request error, catch the text!
          if (!res.ok) {
            const errText = await res.text();
            throw new Error(errText || "Failed to save Barangay");
          }
          return res.json();
        })
        .then(() => {
          showToast("Barangay successfully registered!", "success");

          // Hide modal and clear form
          document.getElementById('add-brgy-modal').classList.add('hidden');
          formAddBarangay.reset();

          // 🚀 INSTANT REFRESH: Reload the main table so the new Barangay appears!
          if (typeof loadBarangayManagement === 'function') {
            loadBarangayManagement();
          }
        })
        .catch(err => {
          console.error(err);
          // 🚀 NEW: Show the EXACT error message from Java in the Toast!
          showToast(err.message, "error");
        })
        .finally(() => {
          submitBtn.innerHTML = originalText;
          submitBtn.disabled = false;
        });
    });
  }
});

// ==========================================
// BARANGAY MANAGEMENT: ADD CITY ROAD
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  const formAddCityRoad = document.getElementById('form-add-city-road');

  if (formAddCityRoad) {
    formAddCityRoad.addEventListener('submit', function (e) {
      e.preventDefault();

      // Ensure we have an active Barangay ID from the Manage Modal
      if (!currentManageBarangayId) {
        showToast("Error: No Barangay selected.", "error");
        return;
      }

      const submitBtn = this.querySelector('button[type="submit"]');
      const originalText = submitBtn.innerHTML;
      submitBtn.innerHTML = "⏳ Saving...";
      submitBtn.disabled = true;

      // 🚀 We DO NOT send the Road ID. The Backend handles it!
      const payload = {
        roadName: document.getElementById('add-road-name').value.trim(),
        roadImportance: document.getElementById('add-road-importance').value,
        roadType: document.getElementById('add-road-type').value,
        terrainType: document.getElementById('add-road-terrain').value,
        barangay: { id: currentManageBarangayId }
      };

      fetch(`${API_BASE_URL}/api/roads`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
        .then(async res => {
          if (!res.ok) {
            const errText = await res.text();
            throw new Error(errText || "Failed to save city road.");
          }
          return res.json();
        })
        .then(() => {
          showToast("City Road successfully registered!", "success");

          // Hide modal and reset form
          document.getElementById('add-road-modal').classList.add('hidden');
          formAddCityRoad.reset();

          // 🚀 Refresh the open Barangay Modal to show the new road!
          openManageBarangayModal(currentManageBarangayId);
        })
        .catch(err => {
          console.error(err);
          showToast(err.message, "error");
        })
        .finally(() => {
          submitBtn.innerHTML = originalText;
          submitBtn.disabled = false;
        });
    });
  }
});


// ==========================================
// ✏️ EDIT CITY ROAD LOGIC
// ==========================================

// 1. FUNCTION TO OPEN AND POPULATE THE MODAL
window.openEditRoadModal = function(roadId) {
  apiFetch(`/api/roads/${roadId}`)
    .then(road => {
      // Store the database ID
      document.getElementById('edit-db-id').value = road.id;

      // Populate the visible fields
      document.getElementById('edit-road-sequence-id').value = road.roadId || 'N/A';
      document.getElementById('edit-road-name').value = road.roadName || '';
      document.getElementById('edit-road-importance').value = road.roadImportance || 'Secondary';
      document.getElementById('edit-road-type').value = road.roadType || 'Concrete';
      document.getElementById('edit-road-terrain').value = road.terrainType || 'Flat';

      const modal = document.getElementById('edit-road-modal');

      // 1. Move to document body so it escapes the Manage Modal's stacking container
      document.body.appendChild(modal);

      // 2. Override CSS !important rules to guarantee it sits on top
      modal.style.setProperty('position', 'fixed', 'important');
      modal.style.setProperty('z-index', '999999', 'important');

      // 3. Show the modal
      modal.classList.remove('hidden');
    })
    .catch(err => {
      console.error("Failed to fetch road details", err);
      showToast("Error: Failed to load road details.", "error");
    });
};

document.addEventListener('DOMContentLoaded', () => {

  // 2. CLOSE MODAL BUTTONS
  document.querySelectorAll('.close-edit-road-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const modal = document.getElementById('edit-road-modal');
      if (modal) {
        modal.classList.add('hidden');
      }
    });
  });

  // 3. SUBMIT UPDATED DATA
  const formEditRoad = document.getElementById('form-edit-city-road');
  if (formEditRoad) {
    formEditRoad.addEventListener('submit', function(e) {
      e.preventDefault();

      const dbId = document.getElementById('edit-db-id').value;

      const payload = {
        roadName: document.getElementById('edit-road-name').value.trim(),
        roadImportance: document.getElementById('edit-road-importance').value,
        roadType: document.getElementById('edit-road-type').value,
        terrainType: document.getElementById('edit-road-terrain').value,
        barangay: { id: currentManageBarangayId }
      };

      const submitBtn = this.querySelector('.btn-submit') || this.querySelector('button[type="submit"]');
      const originalText = submitBtn ? submitBtn.innerHTML : "Save";
      if (submitBtn) {
        submitBtn.innerHTML = "⏳ Saving...";
        submitBtn.disabled = true;
      }

      fetch(`${API_BASE_URL}/api/roads/${dbId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
        .then(async res => {
          if (!res.ok) {
            const errText = await res.text();
            throw new Error(errText || "Failed to update city road.");
          }
          return res.json();
        })
        .then(() => {
          showToast("City Road successfully updated!", "success");

          // Hide modal and reset form
          document.getElementById('edit-road-modal').classList.add('hidden');
          formEditRoad.reset();

          // Refresh the Manage Barangay table
          if (typeof openManageBarangayModal === 'function') {
            openManageBarangayModal(currentManageBarangayId);
          }
        })
        .catch(err => {
          console.error(err);
          showToast(err.message, "error");
        })
        .finally(() => {
          if (submitBtn) {
            submitBtn.innerHTML = originalText;
            submitBtn.disabled = false;
          }
        });
    });
  }
});

// ==========================================
// ✏️ RENAME BARANGAY LOGIC
// ==========================================

window.openRenameBarangayModal = function() {
  // Grab the current name from the header and put it in the input box
  const currentName = document.getElementById('manage-brgy-name').textContent;
  document.getElementById('rename-brgy-input').value = currentName;

  // Teleport trick to avoid CSS traps
  const modal = document.getElementById('rename-brgy-modal');
  document.body.appendChild(modal);
  modal.classList.remove('hidden');
};

document.addEventListener('DOMContentLoaded', () => {
  const formRename = document.getElementById('form-rename-barangay');

  if (formRename) {
    formRename.addEventListener('submit', function(e) {
      e.preventDefault();

      const newName = document.getElementById('rename-brgy-input').value.trim();
      const submitBtn = this.querySelector('.btn-submit');
      const originalText = submitBtn.innerHTML;

      submitBtn.innerHTML = "⏳ Saving...";
      submitBtn.disabled = true;

      const payload = { barangayName: newName };

      // currentManageBarangayId is the global variable tracking which Manage modal is open
      fetch(`${API_BASE_URL}/api/barangays/${currentManageBarangayId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
        .then(async res => {
          if (!res.ok) {
            const errText = await res.text();
            throw new Error(errText || "Failed to rename Barangay.");
          }
          return res.json();
        })
        .then(() => {
          showToast("Barangay successfully renamed!", "success");

          // 1. Close the tiny rename modal
          document.getElementById('rename-brgy-modal').classList.add('hidden');

          // 2. Instantly update the text on the Manage Modal header
          document.getElementById('manage-brgy-name').textContent = newName;

          // 3. Refresh the main background table so it reflects there too
          if (typeof window.loadBarangayManagement === 'function') {
            window.loadBarangayManagement();
          }
        })
        .catch(err => {
          console.error(err);
          showToast(err.message, "error");
        })
        .finally(() => {
          submitBtn.innerHTML = originalText;
          submitBtn.disabled = false;
        });
    });
  }
});

