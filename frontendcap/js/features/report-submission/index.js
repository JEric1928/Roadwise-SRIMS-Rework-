/**
 * Report submission feature module: handles road inspection report creation,
 * confirmation modal step transitions, single-flight submission locking,
 * form/image/GPS resets, unsaved changes warnings, and online/offline status notifications.
 */
import { API_BASE_URL, getAuthOnlyHeaders } from '../../core/api.js';
import { showToast } from '../../components/toast.js';

let isSubmittingReport = false;

// ==========================================
// DRAFT AUTOSAVE: long report forms are costly to re-enter after an accidental
// navigation or refresh. Values are mirrored to localStorage on every input and
// restored when the form loads again. Only text/select/textarea fields are kept;
// the photo input is intentionally excluded (a stored File handle cannot be
// reconstructed), so a draft remembers everything except the image.
// ==========================================
const DRAFT_STORAGE_KEY = 'roadwise.report-draft.v1';

const DRAFT_FIELD_IDS = [
  'cityRoadName', 'cityRoadId', 'roadImportance', 'roadType', 'terrainType',
  'width', 'length', 'numberOfBridges', 'lengthOfCulverts',
  'damageType', 'otherDamageType', 'damageDescription',
  'damageLength', 'damageWidth', 'latitude', 'longitude'
];

function collectDraft() {
  if (typeof document === 'undefined') return {};
  const draft = {};
  for (const id of DRAFT_FIELD_IDS) {
    const el = document.getElementById(id);
    if (!el) continue;
    const value = el.value;
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      draft[id] = value;
    }
  }
  return draft;
}

function draftIsEmpty(draft) {
  return Object.keys(draft).length === 0;
}

export function saveDraftToStorage() {
  if (typeof localStorage === 'undefined') return;
  const draft = collectDraft();
  try {
    if (draftIsEmpty(draft)) {
      localStorage.removeItem(DRAFT_STORAGE_KEY);
    } else {
      localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(draft));
    }
  } catch (e) {
    // Storage full or blocked (private mode): drafts are best-effort.
  }
}

export function restoreDraftFromStorage() {
  if (typeof localStorage === 'undefined' || typeof document === 'undefined') return false;
  let draft;
  try {
    const raw = localStorage.getItem(DRAFT_STORAGE_KEY);
    if (!raw) return false;
    draft = JSON.parse(raw);
  } catch (e) {
    return false;
  }
  if (!draft || typeof draft !== 'object' || draftIsEmpty(draft)) return false;

  let restoredAny = false;
  for (const [id, value] of Object.entries(draft)) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (el.tagName === 'SELECT') {
      const matching = [...el.options].some(opt => opt.value === String(value));
      if (!matching) continue;
    }
    el.value = value;
    restoredAny = true;
  }
  return restoredAny;
}

export function clearDraftStorage() {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.removeItem(DRAFT_STORAGE_KEY);
  } catch (e) { /* best-effort */ }
}

export function initDraftAutosave() {
  if (typeof document === 'undefined' || typeof window === 'undefined') return;
  const form = document.getElementById('addReportForm');
  if (!form) return;

  const persist = () => saveDraftToStorage();
  form.addEventListener('input', persist);
  form.addEventListener('change', persist);

  const draftRestored = restoreDraftFromStorage();
  if (draftRestored && typeof showToast === 'function') {
    showToast('Draft restored — review before submitting.', 'success');
  }
}

export function isReportFormDirty() {
  const roadName = document.getElementById("cityRoadName")?.value;
  const length = document.getElementById("length")?.value;
  const width = document.getElementById("width")?.value;
  const damageType = document.getElementById("damageType")?.value;
  const desc = document.getElementById("damageDescription")?.value;
  const fileInput = document.getElementById("damageImageFile");
  const hasFile = Boolean(fileInput && fileInput.files && fileInput.files.length > 0);

  return Boolean(roadName || length || width || damageType || desc || hasFile);
}

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('beforeunload', (e) => {
    if (isReportFormDirty()) {
      e.preventDefault();
      e.returnValue = '';
    }
  });

  window.addEventListener('online', () => {
    showToast('Network connection restored.', 'success');
  });

  window.addEventListener('offline', () => {
    showToast('You are currently offline. Changes will sync when reconnected.', 'warning');
  });
}

export function toggleOtherDamageType() {
  const select = document.getElementById('damageType');
  const otherGroup = document.getElementById('otherDamageTypeGroup');
  const otherInput = document.getElementById('otherDamageType');

  if (!select || !otherGroup) return;

  if (select.value === 'Other') {
    otherGroup.classList.remove('hidden');
    otherGroup.style.display = 'block';
    if (otherInput) otherInput.focus();
  } else {
    otherGroup.classList.add('hidden');
    otherGroup.style.display = 'none';
    if (otherInput) otherInput.value = '';
  }
}

export function submitRoadReport() {
  if (isSubmittingReport) return;

  const roadName = document.getElementById("cityRoadName")?.value;
  const widthVal = document.getElementById("width")?.value;
  const lengthVal = document.getElementById("length")?.value;

  if (!roadName || !widthVal || !lengthVal) {
    showToast("Please fill in all required fields (Road Name, Width, and Length).", "error");
    return;
  }

  const damageType = document.getElementById("damageType")?.value;
  if (damageType === "Other" && !document.getElementById("otherDamageType")?.value.trim()) {
    showToast("Please specify the 'Other' damage type.", "error");
    return;
  }

  const width = parseFloat(widthVal);
  const length = parseFloat(lengthVal);
  const bridges = parseInt(document.getElementById("numberOfBridges")?.value) || 0;
  const culverts = parseFloat(document.getElementById("lengthOfCulverts")?.value) || 0;

  if (width < 0 || length < 0 || bridges < 0 || culverts < 0) {
    showToast("Measurements cannot be negative numbers! Please correct them.", "error");
    return;
  }

  const modal = document.getElementById('confirm-modal');
  if (modal) {
    modal.classList.remove('hidden');
    modal.style.display = 'flex';
  }
}

export function closeConfirmModal() {
  const modal = document.getElementById('confirm-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
}

export function executeFinalSubmission() {
  if (isSubmittingReport) return;
  isSubmittingReport = true;

  closeConfirmModal();

  const modalConfirmBtn = document.querySelector("#confirm-modal .btn-submit, #confirm-modal [onclick*='executeFinalSubmission']");
  if (modalConfirmBtn) {
    modalConfirmBtn.disabled = true;
    modalConfirmBtn.innerText = "Submitting... ⏳";
  }

  const submitBtn = document.getElementById("submit-report-btn");
  if (submitBtn) {
    submitBtn.innerHTML = "⏳ Submitting...";
    submitBtn.disabled = true;
    submitBtn.style.opacity = "0.7";
  }

  const formData = new FormData();

  const loggedInBarangayId = sessionStorage.getItem("barangayId");
  if (loggedInBarangayId) {
    formData.append("barangayId", loggedInBarangayId);
  }

  const loggedInUserId = sessionStorage.getItem("userId");
  if (loggedInUserId) {
    formData.append("userId", loggedInUserId);
  }

  function getVal(id) {
    const el = document.getElementById(id);
    if (!el) return "";

    if (el.tagName === "SELECT") {
      if (el.selectedIndex === -1) return "";
      const opt = el.options[el.selectedIndex];
      if (opt.disabled) return "";
      return (opt.value && opt.value.trim() !== "") ? opt.value : opt.text;
    }
    return el.value || "";
  }

  formData.append("cityRoadName", getVal("cityRoadName"));
  formData.append("cityRoadId", getVal("cityRoadId"));
  formData.append("roadImportance", getVal("roadImportance"));
  formData.append("roadType", getVal("roadType"));
  formData.append("terrainType", getVal("terrainType"));

  formData.append("width", parseFloat(getVal("width")) || 0.0);
  formData.append("length", parseFloat(getVal("length")) || 0.0);
  formData.append("numberOfBridges", parseInt(getVal("numberOfBridges")) || 0);
  formData.append("lengthOfCulverts", parseFloat(getVal("lengthOfCulverts")) || 0.0);
  formData.append("damageDescription", getVal("damageDescription"));

  let finalDamageType = getVal("damageType");
  if (!finalDamageType || finalDamageType.includes("Select Damage")) {
    finalDamageType = "None";
  } else if (finalDamageType === "Other") {
    finalDamageType = getVal("otherDamageType") || "Other";
  }

  formData.append("damageType", finalDamageType);
  formData.append("damageLength", parseFloat(getVal("damageLength")) || 0.0);
  formData.append("damageWidth", parseFloat(getVal("damageWidth")) || 0.0);

  formData.append("latitude", parseFloat(getVal("latitude")) || 0.0);
  formData.append("longitude", parseFloat(getVal("longitude")) || 0.0);
  formData.append("inventoryYear", new Date().getFullYear());

  const imageInput = document.getElementById("damageImageFile");
  const hasImage = imageInput && imageInput.files && imageInput.files.length > 0;
  if (hasImage) {
    formData.append("imageFile", imageInput.files[0]);
  }

  fetch(`${API_BASE_URL}/api/reports`, {
    method: "POST",
    headers: getAuthOnlyHeaders(),
    body: formData
  })
    .then(async response => {
      if (response.ok) return response.json();
      const errData = await response.json().catch(() => ({}));
      throw new Error(errData.error || errData.message || 'Network response was not ok.');
    })
    .then(data => {
      const rawSev = String(data.severity || '').trim().toLowerCase();
      const aiConfidence = data.cvConfidenceScore ? data.cvConfidenceScore : 0;

      if (!hasImage) {
        showToast("📋 Report saved successfully! Severity is UNASSESSED (No damage photo attached).", "success");
      } else if (rawSev === "unassessed" || rawSev === "") {
        showToast(" Report saved! AI could not detect valid pavement distress (Marked as UNASSESSED).", "warning");
      } else if (rawSev === "high") {
        showToast(`🚨 Report saved! AI graded this as HIGH Severity (${aiConfidence}% confidence).`, "success");
      } else if (rawSev === "medium") {
        showToast(` Report saved! AI graded this as MEDIUM Severity (${aiConfidence}% confidence).`, "success");
      } else if (rawSev === "low") {
        showToast(` Report saved! AI graded this as LOW Severity (${aiConfidence}% confidence).`, "success");
      } else {
        showToast("📋 Report saved successfully! Severity is UNASSESSED.", "success");
      }

      resetAddReportForm();
      clearDraftStorage();

      if (typeof window.loadBarangayReports === 'function') {
        const brgyId = sessionStorage.getItem("barangayId");
        if (typeof Chart !== 'undefined') {
          let existingChart = Chart.getChart('severityChart');
          if (existingChart) existingChart.destroy();
        }
        if (brgyId) window.loadBarangayReports(brgyId);
      }
    })
    .catch(error => {
      console.error("Error submitting report:", error);
      showToast(error.message || "Failed to upload report. Check your internet connection.", "error");
    })
    .finally(() => {
      isSubmittingReport = false;

      if (modalConfirmBtn) {
        modalConfirmBtn.disabled = false;
        modalConfirmBtn.innerText = "Yes, Submit";
      }

      if (submitBtn) {
        submitBtn.innerHTML = "Submit Report";
        submitBtn.disabled = false;
        submitBtn.style.opacity = "1";
      }
    });
}

export function resetAddReportForm() {
  const form = document.getElementById("addReportForm");
  if (form) {
    form.reset();
  }

  const selectIds = ["cityRoadName", "roadImportance", "roadType", "terrainType", "damageType"];
  selectIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.selectedIndex = 0;
  });

  const inputIds = [
    "cityRoadId", "width", "length", "numberOfBridges",
    "lengthOfCulverts", "damageLength", "damageWidth",
    "damageDescription", "otherDamageType"
  ];
  inputIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.value = "";
  });

  const otherGroup = document.getElementById("otherDamageTypeGroup");
  const otherInput = document.getElementById("otherDamageType");
  if (otherGroup) {
    otherGroup.classList.add("hidden");
    otherGroup.style.display = "none";
  }
  if (otherInput) {
    otherInput.value = "";
  }

  const latEl = document.getElementById("latitude");
  const lngEl = document.getElementById("longitude");
  if (latEl) latEl.value = "";
  if (lngEl) lngEl.value = "";

  const coordsDisplay = document.getElementById("coords-display");
  if (coordsDisplay) {
    coordsDisplay.textContent = "Not Selected";
    coordsDisplay.style.background = "#ecfdf5";
    coordsDisplay.style.color = "#047857";
    coordsDisplay.style.borderColor = "#34d399";
  }

  const btnDefineMap = document.getElementById("btn-define-map");
  if (btnDefineMap) {
    btnDefineMap.innerHTML = '<span class="icon">📍</span> Define on Map';
    btnDefineMap.style.backgroundColor = '#16a34a';
  }

  if (typeof window.reportMarker !== "undefined" && window.reportMarker && typeof window.map !== "undefined" && window.map) {
    try { window.map.removeLayer(window.reportMarker); } catch (e) {}
    window.reportMarker = null;
  }

  const fileInput = document.getElementById("damageImageFile");
  if (fileInput) fileInput.value = "";

  const preview = document.getElementById("imagePreview");
  if (preview) {
    preview.style.display = 'none';
    preview.src = "";
  }

  const fileNameDisplay = document.getElementById("fileNameDisplay");
  if (fileNameDisplay) fileNameDisplay.textContent = "";
}

// Draft autosave initialization (guarded: no-op on non-browser runs).
initDraftAutosave();

// Window Compatibility Bridges
window.toggleOtherDamageType = toggleOtherDamageType;
window.submitRoadReport = submitRoadReport;
window.closeConfirmModal = closeConfirmModal;
window.executeFinalSubmission = executeFinalSubmission;
window.resetAddReportForm = resetAddReportForm;
