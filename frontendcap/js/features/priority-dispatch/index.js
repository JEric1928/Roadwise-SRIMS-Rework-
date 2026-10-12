/**
 * Priority report masterlist dispatch feature module: handles the CPDO dispatch
 * trigger and API call to transition validated reports to CEO.
 */
import { API_BASE_URL, getAuthOnlyHeaders } from '../../core/api.js';
import { showToast } from '../../components/toast.js';

export function executePriorityDispatch(event) {
  if (event) event.preventDefault();

  const dispatchModal = document.getElementById('dispatch-confirm-modal');
  const btnApproveDispatch = document.getElementById('btn-approve-dispatch');

  if (dispatchModal) {
    dispatchModal.classList.add('hidden');
    dispatchModal.style.display = 'none';
  }

  if (btnApproveDispatch) {
    btnApproveDispatch.innerText = "⏳ Dispatching...";
    btnApproveDispatch.disabled = true;
  }

  function returnToDashboardView() {
    const priorityView = document.getElementById('view-report-priority');
    if (priorityView) {
      priorityView.classList.add('hidden');
      priorityView.style.display = 'none';
    }

    const adminDashboard = document.getElementById('view-admin-dashboard');
    if (adminDashboard) {
      adminDashboard.classList.remove('hidden');
      adminDashboard.style.display = 'block';
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    if (typeof window.loadAdminDashboardData === 'function') {
      window.loadAdminDashboardData();
    }
  }

  const baseUrl = typeof API_BASE_URL !== 'undefined' ? API_BASE_URL : '';
  const currentUserId = sessionStorage.getItem("userId") || '';

  fetch(`${baseUrl}/api/reports/dispatch-masterlist?userId=${currentUserId}`, {
    method: 'PUT',
    headers: {
      ...getAuthOnlyHeaders(),
      'ngrok-skip-browser-warning': 'true'
    }
  })
    .then(async response => {
      const text = await response.text();

      if (!response.ok) {
        if (response.status === 400 || text.toLowerCase().includes("no 'validated' reports")) {
          throw new Error("EMPTY_QUEUE");
        }
        throw new Error(text || "Failed to dispatch");
      }

      return text;
    })
    .then(message => {
      showToast(message || "Masterlist successfully dispatched to CEO!", "success");
      returnToDashboardView();
    })
    .catch(err => {
      console.warn("Dispatch result:", err.message);

      if (err.message === "EMPTY_QUEUE") {
        showToast("No validated reports with photo evidence available to dispatch.", "warning");
      } else {
        showToast("Error dispatching Masterlist. Is the server running?", "error");
      }

      returnToDashboardView();
    })
    .finally(() => {
      if (btnApproveDispatch) {
        btnApproveDispatch.innerText = "🚀 Approve & Dispatch to CEO";
        btnApproveDispatch.disabled = false;
      }
    });
}

document.addEventListener("DOMContentLoaded", () => {
  const btnApproveDispatch = document.getElementById('btn-approve-dispatch');
  if (btnApproveDispatch) {
    btnApproveDispatch.addEventListener('click', () => {
      const dispatchModal = document.getElementById('dispatch-confirm-modal');
      if (dispatchModal) {
        dispatchModal.classList.remove('hidden');
        dispatchModal.style.display = 'flex';
      }
    });
  }
});

// Window Compatibility Bridges
window.executePriorityDispatch = executePriorityDispatch;
