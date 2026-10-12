/**
 * Repair tracking feature module: owns repair tracking data loading, tracking modal,
 * map/timeline toggles, QA approval/rework submission, schedule extension review,
 * and deadline reminder alerts.
 */
import { apiFetch, API_BASE_URL, getAuthHeaders } from '../../core/api.js';
import { resetModalScroll } from '../../core/utils.js';
import { showToast } from '../../components/toast.js';

export let rawTrackedReports = [];
export let currentFilteredTrackedReports = [];

let currentTrackingReportId = null;
let currentTrackingReportStatus = null;

let currentTrackLat = 0;
let currentTrackLng = 0;
let trackModalMap = null;
let trackModalMarker = null;

export function loadTrackingData() {
  const trackingTableBody = document.querySelector('#view-tracking .data-table tbody');
  if (!trackingTableBody) return;

  apiFetch(`/api/reports`)
    .then(reports => {
      trackingTableBody.innerHTML = '';

      const trackedReports = (Array.isArray(reports) ? reports : []).filter(r => {
        const status = String(r.status || '').toLowerCase().trim();
        return status === 'dispatched to ceo' ||
          status === 'in progress' ||
          status === 'completed' ||
          status === 'pending budget';
      });

      if (trackedReports.length === 0) {
        rawTrackedReports = [];
        currentFilteredTrackedReports = [];
        window.rawTrackedReports = [];
        window.currentFilteredTrackedReports = [];
        trackingTableBody.innerHTML = `
          <tr>
            <td colspan="7" style="text-align: center; padding: 40px; color: #64748b;">
              <div style="font-size: 28px; margin-bottom: 8px;">🛠️</div>
              <strong>No active repair projects to track.</strong>
              <p style="font-size: 13px; margin: 4px 0 0 0; color: #94a3b8;">
                All verified and closed projects are securely stored in <strong>Archive & Reports</strong>.
              </p>
            </td>
          </tr>
        `;
        return;
      }

      trackedReports.forEach(report => {
        const status = String(report.status || '').toLowerCase().trim();
        if (status === 'completed') report.statusScore = 4;
        else if (status === 'in progress') report.statusScore = 3;
        else if (status === 'dispatched to ceo') report.statusScore = 2;
        else if (status === 'pending budget') report.statusScore = 1;
        else report.statusScore = 0;

        const rawSeverity = String(report.severity || '').toLowerCase().trim();
        const importance = String(report.roadImportance || '').toLowerCase().trim();

        if (rawSeverity === 'high' || (rawSeverity === 'medium' && importance.includes('core'))) {
          report.priorityScore = 3;
        } else if (rawSeverity === 'medium' || (rawSeverity === 'low' && importance.includes('core'))) {
          report.priorityScore = 2;
        } else if (rawSeverity === 'low') {
          report.priorityScore = 1;
        } else {
          report.priorityScore = 0;
        }
      });

      trackedReports.sort((a, b) => {
        if (b.statusScore !== a.statusScore) return b.statusScore - a.statusScore;
        if (b.priorityScore !== a.priorityScore) return b.priorityScore - a.priorityScore;
        const idA = parseInt(a.id) || 0;
        const idB = parseInt(b.id) || 0;
        return idB - idA;
      });

      rawTrackedReports = trackedReports;
      currentFilteredTrackedReports = [...trackedReports];
      window.rawTrackedReports = rawTrackedReports;
      window.currentFilteredTrackedReports = currentFilteredTrackedReports;

      trackedReports.forEach(report => {
        const formatId = `#PRJ-${String(report.id).padStart(4, '0')}`;
        const formatBrgy = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : 'Unknown';
        const roadName = report.cityRoadName || 'Unknown Road';
        const currentStatus = String(report.status || '').toLowerCase().trim();

        const rawSeverity = String(report.severity || '').toLowerCase().trim();
        const importance = String(report.roadImportance || '').toLowerCase().trim();

        let badgeHtml = '';
        let borderStyle = '4px solid #10b981';

        if (rawSeverity === 'high' || (rawSeverity === 'medium' && importance.includes('core'))) {
          badgeHtml = `<span class="badge high">HIGH</span>`;
          borderStyle = '4px solid #dc3545';
        } else if (rawSeverity === 'medium' || (rawSeverity === 'low' && importance.includes('core'))) {
          badgeHtml = `<span class="badge medium">MEDIUM</span>`;
          borderStyle = '4px solid #ffc107';
        } else if (rawSeverity === 'low') {
          badgeHtml = `<span class="badge low">LOW</span>`;
          borderStyle = '4px solid var(--accent-blue, #2563eb)';
        } else {
          badgeHtml = `<span class="badge" style="background-color: #dcfce7; color: #15803d; border: 1px solid #86efac; font-weight: 700;">UNASSESSED</span>`;
          borderStyle = '4px solid #10b981';
        }

        let statusHtml = '';
        let checkboxHtml = '';

        if (currentStatus === 'completed') {
          statusHtml = `<span class="status-badge validated" style="background-color: #d4edda; color: #155724;">Completed (Pending QA)</span>`;
          borderStyle = '4px solid #28a745';
        } else if (currentStatus === 'in progress') {
          statusHtml = `<span class="status-badge" style="background-color: #cce5ff; color: #004085;">In Progress</span>`;
        } else if (currentStatus === 'pending budget') {
          statusHtml = `<span class="status-badge" style="background-color: #fef08a; color: #854d0e;">⚠️ Pending Budget</span>`;
          borderStyle = '4px solid #eab308';
          checkboxHtml = `<input type="checkbox" class="archive-checkbox" value="${report.id}" onclick="updateBatchArchiveUI()" style="cursor: pointer; transform: scale(1.2);">`;
        } else {
          statusHtml = `<span class="status-badge pending" style="background-color: #e2e3e5; color: #383d41;">Dispatched to CEO</span>`;
        }

        const row = document.createElement('tr');
        row.style.borderLeft = borderStyle;
        if (currentStatus === 'completed') row.style.backgroundColor = '#fafafa';

        row.innerHTML = `
          <td style="text-align: center;">${checkboxHtml}</td>
          <td><strong>${formatId}</strong></td>
          <td>${formatBrgy}</td>
          <td>${roadName}</td>
          <td>${badgeHtml}</td>
          <td>${statusHtml}</td>
          <td><button class="btn-small track-btn" onclick="openTrackingModal(${report.id})">Track</button></td>
        `;
        trackingTableBody.appendChild(row);
      });

      if (typeof window.filterTrackingReports === 'function') {
        window.filterTrackingReports();
      }
    })
    .catch(error => {
      console.error("Error loading tracking data:", error);
      trackingTableBody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: red;">Failed to load tracking data.</td></tr>`;
    });
}

export function toggleTrackTimeline() {
  const container = document.getElementById('track-modal-timeline-container');
  const arrow = document.getElementById('track-timeline-arrow');
  if (!container) return;

  const isHidden = container.classList.toggle('hidden');
  if (arrow) arrow.style.transform = isHidden ? 'rotate(0deg)' : 'rotate(180deg)';

  if (!isHidden) {
    container.scrollTop = 0;
    requestAnimationFrame(() => { container.scrollTop = 0; });
  }
}

export function openTrackingModal(reportId) {
  currentTrackingReportId = reportId;
  currentTrackingReportStatus = null;

  const trackingModal = document.getElementById('tracking-modal');
  if (!trackingModal) return;

  const mapContainer = document.getElementById('track-modal-map-container');
  if (mapContainer) mapContainer.style.display = 'none';

  const primaryActions = document.getElementById('tracking-primary-actions');
  const reworkForm = document.getElementById('tracking-rework-form');
  const reworkInput = document.getElementById('rework-remarks-input');

  if (primaryActions) primaryActions.classList.remove('hidden');
  if (reworkForm) reworkForm.classList.add('hidden');
  if (reworkInput) reworkInput.value = '';

  const reminderContainer = document.getElementById('track-modal-reminder-container');
  if (reminderContainer) reminderContainer.style.display = 'none';

  const scheduleInfo = document.getElementById('track-modal-schedule-info');
  if (scheduleInfo) scheduleInfo.style.display = 'none';

  const turnaroundInfo = document.getElementById('track-modal-turnaround-info');
  if (turnaroundInfo) turnaroundInfo.style.display = 'none';

  const targetBadge = document.getElementById('track-modal-target-badge');
  if (targetBadge) targetBadge.style.display = 'none';

  const reworkAlert = document.getElementById('track-modal-rework-alert');
  if (reworkAlert) reworkAlert.style.display = 'none';

  const deferredAlert = document.getElementById('track-modal-deferred-alert');
  if (deferredAlert) deferredAlert.style.display = 'none';

  const extensionAlert = document.getElementById('track-modal-extension-alert');
  if (extensionAlert) extensionAlert.style.display = 'none';
  toggleExtensionRemarksForm(false);

  const timelineContainer = document.getElementById('track-modal-timeline-container');
  if (timelineContainer) {
    timelineContainer.classList.add('hidden');
    timelineContainer.scrollTop = 0;
  }
  const arrow = document.getElementById('track-timeline-arrow');
  if (arrow) arrow.style.transform = 'rotate(0deg)';

  trackingModal.classList.remove('hidden');
  resetModalScroll(trackingModal);

  apiFetch(`/api/reports/${reportId}`)
    .then(report => {
      currentTrackingReportStatus = report.status ? report.status.toLowerCase() : '';
      currentTrackLat = parseFloat(report.latitude) || 0;
      currentTrackLng = parseFloat(report.longitude) || 0;

      const setText = (id, text) => {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
      };

      setText('track-modal-id', `#PRJ-${String(report.id).padStart(4, '0')}`);
      setText('track-modal-brgy', report.barangay?.barangayName || 'Unknown');
      setText('track-modal-road', report.cityRoadName || 'Unknown Road');
      setText('track-modal-road-id', report.cityRoadId || 'N/A');
      setText('track-modal-importance', report.roadImportance || 'N/A');
      setText('track-modal-terrain', report.terrainType || 'N/A');
      setText('track-modal-road-type', report.roadType || 'N/A');
      setText('track-modal-road-length', report.length ? `${report.length} km` : '0 km');
      setText('track-modal-road-width', report.width ? `${report.width} m` : '0 m');
      setText('track-modal-culverts', report.lengthOfCulverts ? `${report.lengthOfCulverts} m` : '0 m');
      setText('track-modal-gps', (report.latitude && report.longitude) ? `${report.latitude.toFixed(5)}° N, ${report.longitude.toFixed(5)}° E` : 'No GPS data');

      let submitterText = `Barangay Official (${report.barangay?.barangayName || 'Unknown'})`;
      if (report.user && report.user.firstName && report.user.lastName) {
        submitterText = `${report.user.firstName} ${report.user.lastName} (${report.barangay?.barangayName || 'Unknown'})`;
      } else if (report.reportedBy) {
        submitterText = report.reportedBy;
      }
      setText('track-modal-submitter', submitterText);

      const sevBox = document.getElementById('track-modal-severity');
      if (sevBox) {
        const rawSev = String(report.severity || '').trim().toLowerCase();
        const confScore = report.cvConfidenceScore || report.cvconfidenceScore;
        const confText = confScore ? ` (${confScore}%)` : '';

        if (rawSev === 'high') {
          sevBox.innerHTML = `<span style="background-color: #dc2626; color: #ffffff; border: 1px solid #b91c1c; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">HIGH${confText}</span>`;
        } else if (rawSev === 'medium') {
          sevBox.innerHTML = `<span style="background-color: #f59e0b; color: #ffffff; border: 1px solid #d97706; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">MEDIUM${confText}</span>`;
        } else if (rawSev === 'low') {
          sevBox.innerHTML = `<span style="background-color: #16a34a; color: #ffffff; border: 1px solid #15803d; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">LOW${confText}</span>`;
        } else {
          sevBox.innerHTML = `<span style="background-color: #ecfdf5; color: #047857; border: 1.5px solid #34d399; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">UNASSESSED</span>`;
        }
      }

      const dmgType = report.damageType || 'Not specified';
      const dmgLen = parseFloat(report.damageLength) || 0;
      const dmgWid = parseFloat(report.damageWidth) || 0;
      const dmgArea = dmgLen * dmgWid;

      setText('track-modal-damage-type', dmgType);
      setText('track-modal-damage-dimensions', (dmgLen > 0 || dmgWid > 0) ? `${dmgLen}m (L) × ${dmgWid}m (W)` : 'Not specified');
      setText('track-modal-damage-area', dmgArea > 0 ? `${dmgArea.toFixed(1)} sq.m` : '0 sq.m');
      setText('track-modal-desc', report.damageDescription || 'No description provided.');

      if (typeof window.loadSecureImage === 'function') {
        window.loadSecureImage('track-modal-image', report.damageImage);
      }

      const statusBox = document.getElementById('track-modal-status');
      const statusText = document.getElementById('track-modal-status-text');

      const targetDateEl = document.getElementById('track-modal-target-date');
      const targetDaysEl = document.getElementById('track-modal-target-days');
      const reworkInstructions = document.getElementById('track-modal-rework-instructions');
      const deferredRemarks = document.getElementById('track-modal-deferred-remarks');

      const extAlert = document.getElementById('track-modal-extension-alert');
      const extDate = document.getElementById('track-modal-extension-date');
      const extReason = document.getElementById('track-modal-extension-reason');

      const reminderHint = document.getElementById('track-modal-reminder-hint');
      const btnReminder = document.getElementById('btn-send-deadline-reminder');
      const btnReminderText = document.getElementById('btn-reminder-text');
      const btnReminderIcon = document.getElementById('btn-reminder-icon');

      const completedDateEl = document.getElementById('track-modal-completed-date');
      const turnaroundBadge = document.getElementById('track-modal-turnaround-badge');

      const proofPlaceholder = document.getElementById('track-modal-proof-placeholder');
      const placeholderText = document.getElementById('track-modal-placeholder-text');
      const resolutionData = document.getElementById('track-modal-resolution-data');
      const proofRemarks = document.getElementById('track-modal-proof-remarks');

      const approveBtn = document.getElementById('btn-approve-project');
      const approveText = document.getElementById('btn-approve-text');
      const reworkBtn = document.getElementById('btn-rework-project');
      const reworkText = document.getElementById('btn-rework-text');

      const status = currentTrackingReportStatus;
      const isRework = status === 'in progress' && report.adminRemarks && report.adminRemarks.trim() !== '';

      if (isRework) {
        if (reworkAlert) reworkAlert.style.display = 'block';
        if (reworkInstructions) reworkInstructions.textContent = report.adminRemarks;
      } else {
        if (reworkAlert) reworkAlert.style.display = 'none';
      }

      if (report.extensionStatus === 'PENDING') {
        if (extAlert) extAlert.style.display = 'block';
        if (extDate) extDate.textContent = report.extensionTargetDate || 'N/A';
        if (extReason) extReason.textContent = report.extensionReason || 'No justification provided.';
      } else {
        if (extAlert) extAlert.style.display = 'none';
      }

      const calculateCountdown = () => {
        if (!report.targetCompletionDate) {
          if (targetBadge) targetBadge.style.display = 'none';
          if (scheduleInfo) scheduleInfo.style.display = 'none';
          if (reminderContainer) reminderContainer.style.display = 'none';
          return;
        }

        if (scheduleInfo) scheduleInfo.style.display = 'block';
        if (targetDateEl) targetDateEl.textContent = report.targetCompletionDate;

        const target = new Date(report.targetCompletionDate + 'T23:59:59');
        const now = new Date();
        const diffMs = target - now;
        const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));

        if (targetBadge) {
          targetBadge.style.display = 'inline-block';
          if (diffDays > 1) {
            targetBadge.textContent = `⏳ Target: ${report.targetCompletionDate} (${diffDays} days left)`;
            targetBadge.style.cssText = 'background: #e0f2fe; color: #0369a1; border: 1px solid #7dd3fc; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `${diffDays} days remaining`;
          } else if (diffDays === 1) {
            targetBadge.textContent = `⏳ Target: Due Tomorrow (${report.targetCompletionDate})`;
            targetBadge.style.cssText = 'background: #fef3c7; color: #b45309; border: 1px solid #fde047; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `1 day remaining (Due Tomorrow)`;
          } else if (diffDays === 0) {
            targetBadge.textContent = `⚠️ Target: Due Today (${report.targetCompletionDate})`;
            targetBadge.style.cssText = 'background: #fef3c7; color: #b45309; border: 1px solid #fde047; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `Due Today`;
          } else {
            const overdue = Math.abs(diffDays);
            targetBadge.textContent = `🚨 Overdue by ${overdue} day${overdue > 1 ? 's' : ''} (${report.targetCompletionDate})`;
            targetBadge.style.cssText = 'background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `Overdue by ${overdue} day${overdue > 1 ? 's' : ''}`;
          }
        }

        if (reminderContainer && btnReminder) {
          if (diffDays <= 2) {
            reminderContainer.style.display = 'flex';

            let inCooldown = false;
            let lastSentFormatted = '';

            if (report.lastReminderSent) {
              const lastSentTime = new Date(report.lastReminderSent);
              const cooldownHours = 12;
              const cooldownMs = cooldownHours * 60 * 60 * 1000;
              const timeSinceSent = now - lastSentTime;

              if (timeSinceSent < cooldownMs) {
                inCooldown = true;
                lastSentFormatted = lastSentTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
              }
            }

            if (inCooldown) {
              btnReminder.disabled = true;
              btnReminder.style.backgroundColor = '#94a3b8';
              btnReminder.style.color = '#ffffff';
              btnReminder.style.cursor = 'not-allowed';
              btnReminder.style.opacity = '0.85';
              if (btnReminderIcon) btnReminderIcon.textContent = '✔️';
              if (btnReminderText) btnReminderText.textContent = 'Reminder Issued Today';
              if (reminderHint) {
                reminderHint.textContent = `Notice already issued to CEO at ${lastSentFormatted}. (12h cooldown active)`;
              }
            } else {
              btnReminder.disabled = false;
              btnReminder.style.cursor = 'pointer';
              btnReminder.style.opacity = '1';

              if (diffDays < 0) {
                btnReminder.style.backgroundColor = '#dc2626';
                btnReminder.style.color = '#ffffff';
                if (btnReminderIcon) btnReminderIcon.textContent = '🚨';
                if (btnReminderText) btnReminderText.textContent = 'Issue Overdue Notice';
                if (reminderHint) reminderHint.textContent = 'Project is overdue. Dispatch an urgent escalation notice to the City Engineer.';
              } else if (diffDays === 0) {
                btnReminder.style.backgroundColor = '#ea580c';
                btnReminder.style.color = '#ffffff';
                if (btnReminderIcon) btnReminderIcon.textContent = '⚠️';
                if (btnReminderText) btnReminderText.textContent = 'Alert CEO: Due Today';
                if (reminderHint) reminderHint.textContent = 'Deadline is today. Send a priority alert to the engineering team.';
              } else {
                btnReminder.style.backgroundColor = '#f59e0b';
                btnReminder.style.color = '#ffffff';
                if (btnReminderIcon) btnReminderIcon.textContent = '⚠️';
                if (btnReminderText) btnReminderText.textContent = 'Send Due Reminder';
                if (reminderHint) reminderHint.textContent = 'Impending deadline. Notify the City Engineer to prioritize on-site completion.';
              }
            }
          } else {
            reminderContainer.style.display = 'none';
          }
        }
      };

      const calculateTurnaround = () => {
        const completedTimeStr = report.actualCompletionDate || report.dateArchived;
        if (!completedTimeStr) {
          if (turnaroundInfo) turnaroundInfo.style.display = 'none';
          return;
        }

        if (turnaroundInfo) turnaroundInfo.style.display = 'block';
        const compDateObj = new Date(completedTimeStr);
        const compFormatted = compDateObj.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
        if (completedDateEl) completedDateEl.textContent = compFormatted;

        if (turnaroundBadge) {
          if (report.targetCompletionDate) {
            const targetObj = new Date(report.targetCompletionDate + 'T23:59:59');
            const diffTime = targetObj - compDateObj;
            const diffDays = Math.round(diffTime / (1000 * 60 * 60 * 24));

            if (diffDays > 0) {
              turnaroundBadge.textContent = `⚡ Finished ${diffDays} day${diffDays > 1 ? 's' : ''} ahead of schedule`;
              turnaroundBadge.style.cssText = 'background: #dcfce7; color: #15803d; border: 1px solid #86efac;';
            } else if (diffDays === 0) {
              turnaroundBadge.textContent = `🎯 Completed on schedule`;
              turnaroundBadge.style.cssText = 'background: #e0f2fe; color: #0369a1; border: 1px solid #7dd3fc;';
            } else {
              const lateDays = Math.abs(diffDays);
              turnaroundBadge.textContent = `⚠️ Completed ${lateDays} day${lateDays > 1 ? 's' : ''} behind schedule`;
              turnaroundBadge.style.cssText = 'background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5;';
            }
          } else {
            turnaroundBadge.textContent = `✅ Completed`;
            turnaroundBadge.style.cssText = 'background: #dcfce7; color: #15803d; border: 1px solid #86efac;';
          }
        }
      };

      if (status.includes('complet') || status.includes('repair')) {
        if (statusBox) {
          statusBox.textContent = 'Repaired (Pending Approval)';
          statusBox.style.backgroundColor = '#d4edda';
          statusBox.style.color = '#155724';
        }
        if (statusText) statusText.textContent = 'CEO has completed the repair and submitted proof. Awaiting your QA decision.';

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) proofPlaceholder.style.display = 'none';
        if (resolutionData) resolutionData.style.display = 'block';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        calculateTurnaround();

        if (proofRemarks) proofRemarks.textContent = report.repairRemarks || "No official remarks provided.";
        if (typeof window.loadSecureImage === 'function') {
          window.loadSecureImage('track-modal-proof-image', report.proofOfRepairImage);
        }

        if (reworkBtn) {
          reworkBtn.classList.remove('hidden');
          if (reworkText) reworkText.textContent = 'Require Rework';
          reworkBtn.style.backgroundColor = '#dc3545';
        }
        if (approveBtn) {
          approveBtn.disabled = false;
          approveBtn.style.backgroundColor = '#28a745';
          approveBtn.style.cursor = 'pointer';
          if (approveText) approveText.textContent = 'Approve & Close Project';
        }

      } else if (status === 'pending budget' || status.includes('defer')) {
        if (statusBox) {
          statusBox.textContent = 'Deferred (Pending Budget)';
          statusBox.style.backgroundColor = '#fef08a';
          statusBox.style.color = '#854d0e';
        }
        if (statusText) statusText.textContent = 'Project is on fiscal hold. Review deferral remarks below.';

        if (deferredAlert) {
          deferredAlert.style.display = 'block';
          if (deferredRemarks) {
            deferredRemarks.textContent = report.repairRemarks || report.adminRemarks || 'Deferred due to budget allocation constraints.';
          }
        }
        if (proofPlaceholder) proofPlaceholder.style.display = 'none';
        if (resolutionData) resolutionData.style.display = 'none';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (turnaroundInfo) turnaroundInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        if (reworkBtn) {
          reworkBtn.classList.remove('hidden');
          if (reworkText) reworkText.textContent = 'Reject Deferral';
          reworkBtn.style.backgroundColor = '#dc3545';
        }
        if (approveBtn) {
          approveBtn.disabled = false;
          approveBtn.style.backgroundColor = '#475569';
          approveBtn.style.cursor = 'pointer';
          if (approveText) approveText.textContent = 'Acknowledge & Archive';
        }

      } else if (status.includes('progress')) {
        if (statusBox) {
          statusBox.textContent = 'In Progress';
          statusBox.style.backgroundColor = '#cce5ff';
          statusBox.style.color = '#004085';
        }
        if (statusText) {
          statusText.textContent = isRework
            ? 'Engineering crew is actively working on your rework instructions.'
            : 'Engineering crew is currently on-site executing repairs.';
        }

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) {
          proofPlaceholder.style.display = 'block';
          if (placeholderText) {
            placeholderText.textContent = isRework
              ? 'Awaiting Corrected Proof Photo from Engineering'
              : 'Awaiting Completion Photo from Engineering';
          }
        }
        if (resolutionData) resolutionData.style.display = 'none';
        if (turnaroundInfo) turnaroundInfo.style.display = 'none';

        calculateCountdown();

        if (reworkBtn) reworkBtn.classList.add('hidden');
        if (approveBtn) {
          approveBtn.disabled = true;
          approveBtn.style.backgroundColor = '#ccc';
          approveBtn.style.cursor = 'not-allowed';
          if (approveText) approveText.textContent = 'Approve & Close Project';
        }

      } else if (status === 'closed' || status === 'archived' || status === 'resolved') {
        if (statusBox) {
          statusBox.textContent = status === 'archived' ? 'Archived' : 'Closed';
          statusBox.style.backgroundColor = '#e2e8f0';
          statusBox.style.color = '#334155';
        }
        if (statusText) statusText.textContent = 'Project ticket is officially closed and finalized.';

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) proofPlaceholder.style.display = 'none';
        if (resolutionData) resolutionData.style.display = 'block';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        calculateTurnaround();

        if (proofRemarks) proofRemarks.textContent = report.repairRemarks || "No official remarks provided.";
        if (typeof window.loadSecureImage === 'function') {
          window.loadSecureImage('track-modal-proof-image', report.proofOfRepairImage);
        }

        if (reworkBtn) reworkBtn.classList.add('hidden');
        if (approveBtn) {
          approveBtn.disabled = true;
          approveBtn.style.backgroundColor = '#ccc';
          approveBtn.style.cursor = 'not-allowed';
          if (approveText) approveText.textContent = 'Project Closed';
        }

      } else {
        if (statusBox) {
          statusBox.textContent = report.status || 'Dispatched to CEO';
          statusBox.style.backgroundColor = '#e2e3e5';
          statusBox.style.color = '#383d41';
        }
        if (statusText) statusText.textContent = 'Awaiting crew dispatch by the City Engineer.';

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) {
          proofPlaceholder.style.display = 'block';
          if (placeholderText) placeholderText.textContent = 'Awaiting Completion Photo from Engineering';
        }
        if (resolutionData) resolutionData.style.display = 'none';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (turnaroundInfo) turnaroundInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        if (reworkBtn) reworkBtn.classList.add('hidden');
        if (approveBtn) {
          approveBtn.disabled = true;
          approveBtn.style.backgroundColor = '#ccc';
          approveBtn.style.cursor = 'not-allowed';
          if (approveText) approveText.textContent = 'Approve & Close Project';
        }
      }

      if (typeof window.loadReportTimeline === 'function') {
        window.loadReportTimeline(report.id, 'track-modal-timeline');
      }

      resetModalScroll(trackingModal);
    })
    .catch(err => {
      console.error("Error loading tracking details:", err);
      showToast("Error loading project details.", "error");
    });
}

export function toggleExtensionRemarksForm(show) {
  const form = document.getElementById('track-modal-extension-reject-form');
  const actions = document.getElementById('track-modal-extension-actions');
  const input = document.getElementById('extension-reject-remarks-input');

  if (show) {
    if (form) form.classList.remove('hidden');
    if (actions) actions.style.display = 'none';
    if (input) input.focus();
  } else {
    if (form) form.classList.add('hidden');
    if (actions) actions.style.display = 'flex';
    if (input) input.value = '';
  }
}

export function handleReviewExtension(decision) {
  if (!currentTrackingReportId) return;

  const isApproved = decision === 'APPROVED';
  const remarksInput = document.getElementById('extension-reject-remarks-input');
  const remarks = (!isApproved && remarksInput) ? remarksInput.value.trim() : '';

  if (!isApproved && !remarks) {
    showToast("Please provide a reason for rejecting the extension.", "error");
    return;
  }

  const btnApprove = document.getElementById('btn-approve-extension');
  const btnConfirmReject = document.getElementById('btn-confirm-reject-extension');

  if (isApproved && btnApprove) {
    btnApprove.disabled = true;
    btnApprove.textContent = "⏳ Approving...";
  } else if (!isApproved && btnConfirmReject) {
    btnConfirmReject.disabled = true;
    btnConfirmReject.textContent = "⏳ Rejecting...";
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/review-extension`, {
    method: 'PUT',
    headers: {
      ...getAuthHeaders(),
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      decision: decision,
      adminRemarks: remarks,
      userId: currentUserId
    })
  })
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to process extension review.");
      return data;
    })
    .then(() => {
      const toastMsg = isApproved
        ? "Schedule extension approved! Target completion date updated."
        : "Schedule extension rejected. The active target date remains unchanged.";

      showToast(toastMsg, isApproved ? "success" : "warning");

      toggleExtensionRemarksForm(false);
      openTrackingModal(currentTrackingReportId);

      if (typeof loadTrackingData === 'function') loadTrackingData();
      if (typeof window.loadAdminDashboardData === 'function') window.loadAdminDashboardData();
    })
    .catch(err => {
      console.error("Extension Review Error:", err);
      showToast(err.message || "Error processing extension decision.", "error");
    })
    .finally(() => {
      if (btnApprove) {
        btnApprove.disabled = false;
        btnApprove.textContent = "✅ Approve Extension";
      }
      if (btnConfirmReject) {
        btnConfirmReject.disabled = false;
        btnConfirmReject.textContent = "Confirm Rejection";
      }
    });
}

export function sendDeadlineReminder() {
  if (!currentTrackingReportId) return;

  const btnReminder = document.getElementById('btn-send-deadline-reminder');
  const btnText = document.getElementById('btn-reminder-text');
  const originalText = btnText ? btnText.textContent : "Send Due Reminder";

  if (btnReminder) {
    btnReminder.disabled = true;
    btnReminder.style.opacity = '0.7';
    if (btnText) btnText.textContent = "Sending Alert...";
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/remind-due`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({ userId: currentUserId })
  })
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to send deadline reminder.");
      return data;
    })
    .then(data => {
      showToast(data.message || "Reminder successfully issued to the City Engineer!", "success");
      openTrackingModal(currentTrackingReportId);
    })
    .catch(err => {
      console.error("Reminder Error:", err);
      showToast(err.message || "Failed to issue reminder.", "error");
      if (btnReminder) {
        btnReminder.disabled = false;
        btnReminder.style.opacity = '1';
        if (btnText) btnText.textContent = originalText;
      }
    });
}

export function toggleTrackMap() {
  const mapContainer = document.getElementById('track-modal-map-container');
  if (!mapContainer) return;

  if (!currentTrackLat || !currentTrackLng || (currentTrackLat === 0 && currentTrackLng === 0)) {
    showToast("No GPS coordinates were provided for this report.", "error");
    return;
  }

  if (mapContainer.style.display === 'none' || mapContainer.style.display === '') {
    mapContainer.style.display = 'block';

    if (typeof L === 'undefined') {
      console.error("Leaflet library (L) is not loaded.");
      return;
    }

    const redIcon = new L.Icon({
      iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png',
      shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41]
    });

    if (!trackModalMap) {
      trackModalMap = L.map('track-modal-map').setView([currentTrackLat, currentTrackLng], 17);

      L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
        attribution: 'Tiles &copy; Esri &mdash; Source: Esri'
      }).addTo(trackModalMap);

      trackModalMarker = L.marker([currentTrackLat, currentTrackLng], { icon: redIcon }).addTo(trackModalMap);
    } else {
      trackModalMap.setView([currentTrackLat, currentTrackLng], 17);
      trackModalMarker.setLatLng([currentTrackLat, currentTrackLng]);
    }

    setTimeout(() => {
      trackModalMap.invalidateSize();
    }, 200);

  } else {
    mapContainer.style.display = 'none';
  }
}

export function closeTrackingModal() {
  const trackingModal = document.getElementById('tracking-modal');
  if (trackingModal) trackingModal.classList.add('hidden');

  const mapContainer = document.getElementById('track-modal-map-container');
  if (mapContainer) mapContainer.style.display = 'none';

  const reworkForm = document.getElementById('tracking-rework-form');
  const primaryActions = document.getElementById('tracking-primary-actions');
  if (reworkForm) reworkForm.classList.add('hidden');
  if (primaryActions) primaryActions.classList.remove('hidden');
}

export function openReworkForm() {
  const primaryActions = document.getElementById('tracking-primary-actions');
  const reworkForm = document.getElementById('tracking-rework-form');
  const reworkInput = document.getElementById('rework-remarks-input');
  const formTitle = document.getElementById('rework-form-title');
  const btnConfirm = document.getElementById('btn-confirm-rework');

  if (currentTrackingReportStatus === 'pending budget' || currentTrackingReportStatus.includes('defer')) {
    if (formTitle) formTitle.textContent = "Reason for Rejecting Deferral";
    if (reworkInput) {
      reworkInput.placeholder = "Explain why this project must be repaired immediately (e.g., Emergency contingency budget authorized, high public hazard)...";
    }
    if (btnConfirm) btnConfirm.textContent = "Reject & Return to CEO";
  } else {
    if (formTitle) formTitle.textContent = "Reason for Rework";
    if (reworkInput) {
      reworkInput.placeholder = "Explain what the engineering crew missed or needs to fix...";
    }
    if (btnConfirm) btnConfirm.textContent = "Submit to CEO";
  }

  if (primaryActions) primaryActions.classList.add('hidden');
  if (reworkForm) reworkForm.classList.remove('hidden');
  if (reworkInput) reworkInput.focus();
}

export function cancelReworkForm() {
  const primaryActions = document.getElementById('tracking-primary-actions');
  const reworkForm = document.getElementById('tracking-rework-form');
  const reworkInput = document.getElementById('rework-remarks-input');
  if (reworkForm) reworkForm.classList.add('hidden');
  if (primaryActions) primaryActions.classList.remove('hidden');
  if (reworkInput) reworkInput.value = '';
}

export function handleApproveProject() {
  if (!currentTrackingReportId) return;
  const btnApprove = document.getElementById('btn-approve-project');
  if (!btnApprove || btnApprove.disabled) return;

  const isArchiving = btnApprove.innerText.includes('Archive');
  const targetStatus = isArchiving ? "Archived" : "Closed";
  const loadingText = isArchiving ? "⏳ Archiving..." : "⏳ Approving...";
  const successMsg = isArchiving ? "Project safely acknowledged and archived!" : "Project officially approved and closed!";

  const originalText = btnApprove.innerHTML;
  btnApprove.innerHTML = loadingText;
  btnApprove.disabled = true;

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/status`, {
    method: 'PUT',
    headers: {
      ...getAuthHeaders(),
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      status: targetStatus,
      userId: currentUserId
    })
  })
    .then(res => {
      if (!res.ok) throw new Error("Failed to update project status");
      return res.text();
    })
    .then(() => {
      showToast(successMsg, "success");
      closeTrackingModal();
      if (typeof loadTrackingData === 'function') loadTrackingData();
      if (typeof window.loadAdminDashboardData === 'function') window.loadAdminDashboardData();
    })
    .catch(err => {
      console.error(err);
      showToast(`Error ${isArchiving ? 'archiving' : 'closing'} project.`, "error");
    })
    .finally(() => {
      btnApprove.innerHTML = originalText;
      btnApprove.disabled = false;
    });
}

export function submitReworkFeedback() {
  if (!currentTrackingReportId) return;
  const reworkInput = document.getElementById('rework-remarks-input');
  const btnConfirmRework = document.getElementById('btn-confirm-rework');

  const remarks = reworkInput ? reworkInput.value.trim() : '';
  if (!remarks) {
    showToast("Please provide a reason before submitting.", "error");
    return;
  }

  const isRejectingDeferral = currentTrackingReportStatus === 'pending budget' || currentTrackingReportStatus.includes('defer');
  const targetStatus = isRejectingDeferral ? "Dispatched to CEO" : "In Progress";
  const successToast = isRejectingDeferral
    ? "Deferral rejected! Project returned to the CEO active queue."
    : "Project bounced back to CEO with your rework instructions!";

  const originalText = btnConfirmRework ? btnConfirmRework.innerHTML : "Submit";
  if (btnConfirmRework) {
    btnConfirmRework.innerHTML = "⏳ Sending...";
    btnConfirmRework.disabled = true;
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/status`, {
    method: 'PUT',
    headers: {
      ...getAuthHeaders(),
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      status: targetStatus,
      adminRemarks: remarks,
      userId: currentUserId
    })
  })
    .then(res => {
      if (!res.ok) throw new Error("Failed to process feedback");
      return res.text();
    })
    .then(() => {
      showToast(successToast, "success");
      closeTrackingModal();
      if (typeof loadTrackingData === 'function') loadTrackingData();
      if (typeof window.loadAdminDashboardData === 'function') window.loadAdminDashboardData();
    })
    .catch(err => {
      console.error(err);
      showToast("Error submitting feedback.", "error");
    })
    .finally(() => {
      if (btnConfirmRework) {
        btnConfirmRework.innerHTML = originalText;
        btnConfirmRework.disabled = false;
      }
    });
}

// Window Compatibility Bridges
window.rawTrackedReports = rawTrackedReports;
window.currentFilteredTrackedReports = currentFilteredTrackedReports;
window.loadTrackingData = loadTrackingData;
window.toggleTrackTimeline = toggleTrackTimeline;
window.openTrackingModal = openTrackingModal;
window.toggleExtensionRemarksForm = toggleExtensionRemarksForm;
window.handleReviewExtension = handleReviewExtension;
window.sendDeadlineReminder = sendDeadlineReminder;
window.toggleTrackMap = toggleTrackMap;
window.closeTrackingModal = closeTrackingModal;
window.openReworkForm = openReworkForm;
window.cancelReworkForm = cancelReworkForm;
window.handleApproveProject = handleApproveProject;
window.submitReworkFeedback = submitReworkFeedback;
