/**
 * Barangay field report wizard: step indicator, navigation helpers, and the
 * city-road dropdown loader.
 *
 * Extracted verbatim from app.js with improved inline validation, error focus,
 * custom damage verification, and comprehensive summary review presentation.
 */
import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';

// Helper for displaying inline field validation errors
function setInlineError(fieldId, errorMsg) {
  const el = document.getElementById(fieldId);
  if (!el) return;

  el.style.borderColor = errorMsg ? '#dc2626' : '';
  let errSpan = document.getElementById(`${fieldId}-inline-error`);
  if (!errSpan) {
    errSpan = document.createElement('span');
    errSpan.id = `${fieldId}-inline-error`;
    errSpan.className = 'field-inline-error';
    errSpan.style.cssText = 'color: #dc2626; font-size: 11.5px; font-weight: 600; display: block; margin-top: 4px;';
    if (el.parentNode) {
      el.parentNode.appendChild(errSpan);
    }
  }
  errSpan.textContent = errorMsg || '';
  errSpan.style.display = errorMsg ? 'block' : 'none';
}

function clearStepInlineErrors(fieldIds) {
  fieldIds.forEach(id => setInlineError(id, null));
}

// ==========================================
// 📋 BARANGAY FIELD REPORT STEP INDICATOR ENGINE
// ==========================================
window.currentReportStep = 1;

window.goToFormStep = function(stepNum) {
  if (stepNum > window.currentReportStep) {
    if (window.currentReportStep === 1) {
      clearStepInlineErrors(['cityRoadName', 'length', 'width']);

      const roadSelect = document.getElementById('cityRoadName');
      const roadName = roadSelect?.value;
      const length = document.getElementById('length')?.value;
      const width = document.getElementById('width')?.value;

      let firstInvalid = null;

      if (!roadName) {
        setInlineError('cityRoadName', 'Please select a city road.');
        firstInvalid = firstInvalid || roadSelect;
      }
      if (!length || parseFloat(length) <= 0) {
        setInlineError('length', 'Road length must be greater than 0 km.');
        firstInvalid = firstInvalid || document.getElementById('length');
      }
      if (!width || parseFloat(width) <= 0) {
        setInlineError('width', 'Road width must be greater than 0 meters.');
        firstInvalid = firstInvalid || document.getElementById('width');
      }

      if (firstInvalid) {
        firstInvalid.focus();
        showToast("Please fix the highlighted errors before proceeding.", "warning");
        return;
      }
    } else if (window.currentReportStep === 2) {
      clearStepInlineErrors(['damageType', 'otherDamageType']);

      const damageSelect = document.getElementById('damageType');
      const damageType = damageSelect?.value;
      const otherDamageType = document.getElementById('otherDamageType')?.value?.trim();

      let firstInvalid = null;

      if (!damageType) {
        setInlineError('damageType', 'Please select a damage classification.');
        firstInvalid = firstInvalid || damageSelect;
      } else if (damageType === 'Other' && !otherDamageType) {
        setInlineError('otherDamageType', 'Please specify the custom damage description.');
        firstInvalid = firstInvalid || document.getElementById('otherDamageType');
      }

      if (firstInvalid) {
        firstInvalid.focus();
        showToast("Please provide all required damage scope details.", "warning");
        return;
      }
    }
  }

  window.currentReportStep = stepNum;

  for (let i = 1; i <= 4; i++) {
    const panel = document.getElementById(`step-panel-${i}`);
    const node = document.getElementById(`step-node-${i}`);
    if (panel) {
      if (i === stepNum) panel.classList.remove('hidden');
      else panel.classList.add('hidden');
    }
    if (node) {
      if (i === stepNum) {
        node.classList.add('active');
        node.classList.remove('completed');
      } else if (i < stepNum) {
        node.classList.remove('active');
        node.classList.add('completed');
      } else {
        node.classList.remove('active');
        node.classList.remove('completed');
      }
    }
  }

  if (stepNum === 4) {
    const roadSelect = document.getElementById('cityRoadName');
    const roadText = (roadSelect && roadSelect.selectedIndex >= 0) ? roadSelect.options[roadSelect.selectedIndex].text : '-';
    const roadId = document.getElementById('cityRoadId')?.value || '-';
    const len = document.getElementById('length')?.value || '0';
    const wid = document.getElementById('width')?.value || '0';

    const dmgTypeVal = document.getElementById('damageType')?.value || '-';
    const customDmg = document.getElementById('otherDamageType')?.value?.trim();
    const dmgTypeDisplay = (dmgTypeVal === 'Other' && customDmg) ? `Other (${customDmg})` : dmgTypeVal;

    const dmgLen = document.getElementById('damageLength')?.value;
    const dmgWid = document.getElementById('damageWidth')?.value;
    const dmgScopeDisplay = (dmgLen || dmgWid) ? `L: ${dmgLen || 0} m × W: ${dmgWid || 0} m` : 'Not Specified';

    const notes = document.getElementById('damageDescription')?.value?.trim() || 'None provided';

    const lat = document.getElementById('latitude')?.value;
    const lng = document.getElementById('longitude')?.value;
    const fileInput = document.getElementById('damageImageFile');

    const summaryRoad = document.getElementById('summary-road-name');
    const summaryId = document.getElementById('summary-road-id');
    const summaryDims = document.getElementById('summary-dimensions');
    const summaryDmg = document.getElementById('summary-damage-type');
    const summaryScope = document.getElementById('summary-damage-dims');
    const summaryNotes = document.getElementById('summary-notes');
    const summaryGps = document.getElementById('summary-gps');
    const summaryPhoto = document.getElementById('summary-photo-status');

    if (summaryRoad) summaryRoad.textContent = roadText;
    if (summaryId) summaryId.textContent = roadId;
    if (summaryDims) summaryDims.textContent = `L: ${len} km × W: ${wid} m`;
    if (summaryDmg) summaryDmg.textContent = dmgTypeDisplay;
    if (summaryScope) summaryScope.textContent = dmgScopeDisplay;
    if (summaryNotes) summaryNotes.textContent = notes;
    if (summaryGps) summaryGps.textContent = (lat && lng) ? `${parseFloat(lat).toFixed(5)}°, ${parseFloat(lng).toFixed(5)}°` : 'Not Selected';
    if (summaryPhoto) summaryPhoto.textContent = (fileInput && fileInput.files && fileInput.files.length > 0) ? `Attached (${fileInput.files[0].name})` : 'No Photo Attached';
  }
};

window.goToAddReport = function() {
  if (typeof switchView === 'function') {
    switchView('view-reports');
  }
  goToFormStep(1);
};

// ==========================================
// 0. FETCH ROADS FOR DROPDOWN
// ==========================================
loadRoadsToDropdown();

function loadRoadsToDropdown() {
  const roadDropdown = document.getElementById("cityRoadName");
  if (!roadDropdown) return;

  // 1. Grab the ID from the browser's memory FIRST!
  const loggedInBarangayId = sessionStorage.getItem("barangayId");

  // 2. Safety check: If they aren't logged in, stop the code.
  if (!loggedInBarangayId) {
    console.error("No Barangay ID found. Cannot load roads.");
    roadDropdown.innerHTML = '<option value="" disabled selected>Please log in first...</option>';
    return;
  }

  // 3. NOW fetch the roads using the ID and our new apiFetch wrapper!
  apiFetch(`/api/roads/barangay/${loggedInBarangayId}`)
    .then(roads => {
      roadDropdown.innerHTML = '<option value="" disabled selected>Select a City Road...</option>';

      // 4. Loop directly through the roads (Spring Boot already filtered them for us!)
      roads.forEach(road => {
        const option = document.createElement("option");
        option.value = road.roadName;
        option.textContent = road.roadName;

        option.dataset.roadId = road.roadId || road.id;
        option.dataset.importance = road.roadImportance;
        option.dataset.type = road.roadType;
        option.dataset.terrain = road.terrainType;

        roadDropdown.appendChild(option);
      });
    })
    .catch(error => console.error("Error loading roads:", error));

  // THE AUTO-FILL LISTENER
  roadDropdown.addEventListener("change", function() {
    setInlineError('cityRoadName', null);
    const selectedOption = this.options[this.selectedIndex];

    const idBox = document.getElementById("cityRoadId");
    const importanceBox = document.getElementById("roadImportance");
    const typeBox = document.getElementById("roadType");
    const terrainBox = document.getElementById("terrainType");

    if (idBox) idBox.value = selectedOption.dataset.roadId || "";
    if (importanceBox) importanceBox.innerHTML = `<option value="${selectedOption.dataset.importance}">${selectedOption.dataset.importance}</option>`;
    if (typeBox) typeBox.innerHTML = `<option value="${selectedOption.dataset.type}">${selectedOption.dataset.type}</option>`;
    if (terrainBox) terrainBox.innerHTML = `<option value="${selectedOption.dataset.terrain}">${selectedOption.dataset.terrain}</option>`;
  });
}
