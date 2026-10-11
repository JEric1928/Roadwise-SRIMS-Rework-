// ==========================================
// 🧠 3-TIER SMART DATE ESTIMATOR (DPWH & LGU BASELINE)
// ==========================================
export function calculateEstimatedDays(damageType, severity, areaSqM) {
  const dt = String(damageType || '').toLowerCase();
  const sev = String(severity || '').toLowerCase();
  const area = parseFloat(areaSqM) || 0;

  // Tier 1: Barangay Official Exact Options & DPWH Road Categories
  if (dt.includes('pothole') || dt.includes('lubak')) {
    return { days: 3, label: 'Pothole Patching (3 days)' };
  }
  if (dt.includes('surface') || dt.includes('cracking') || dt.includes('alligator') || dt.includes('longitudinal')) {
    return { days: 7, label: 'Surface Crack Sealing & Resurfacing (7 days)' };
  }
  if (dt.includes('edge') || dt.includes('deformation') || dt.includes('rutting')) {
    return { days: 10, label: 'Edge Rebuilding & Base Compaction (10 days)' };
  }
  if (dt.includes('washout') || dt.includes('sinkhole') || dt.includes('slab') || dt.includes('concrete')) {
    return { days: 21, label: 'Major Structural Restoration & Curing (21 days)' };
  }

  // Tier 2: Fallback for "Other" using AI Computer Vision Severity
  if (sev === 'high') {
    return { days: 14, label: 'AI High Severity Scope (14 days)' };
  }
  if (sev === 'medium') {
    return { days: 7, label: 'AI Medium Severity Scope (7 days)' };
  }
  if (sev === 'low') {
    return { days: 3, label: 'AI Low Severity Scope (3 days)' };
  }

  // Tier 3: Measurement Fallback (Square Meters)
  if (area > 20) {
    return { days: 14, label: 'Large Damage Area > 20 sq.m (14 days)' };
  }
  if (area > 5) {
    return { days: 7, label: 'Moderate Damage Area (7 days)' };
  }
  return { days: 5, label: 'Standard Maintenance Window (5 days)' };
}
