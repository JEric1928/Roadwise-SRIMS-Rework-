/**
 * Real-time system clock feature.
 *
 * Owns the `#live-clock-display` updater and its one-second interval. The
 * interval has a single owner and an explicit cleanup path via
 * destroyLiveClock(). No window bridge is needed: no template or other
 * script calls these functions.
 */
let intervalId = null;

/**
 * Idempotent entry point. Called once on DOMContentLoaded by app.js.
 */
export function initLiveClock() {
  if (intervalId !== null) return;

  const clockElement = document.getElementById('live-clock-display');
  if (!clockElement) return;

  function updateClock() {
    const now = new Date();

    // 1. Format the Date (e.g., "Monday, August 17, 2026")
    const dateOptions = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' };
    const dateString = now.toLocaleDateString('en-US', dateOptions);

    // 2. Format the Time (e.g., "10:09:57 AM")
    const timeOptions = { hour: 'numeric', minute: '2-digit', second: '2-digit', hour12: true };
    const timeString = now.toLocaleTimeString('en-US', timeOptions);

    // 3. Inject it into the HTML with themed colors
    clockElement.innerHTML = `
      <span style="font-size: 14px; font-weight: 700; color: #0f172a;">${timeString}</span>
      <span style="color: #cbd5e1; margin: 0 6px;">|</span>
      <span style="font-size: 12px; color: #64748b; font-weight: 500;">${dateString}</span>
    `;
  }

  // Run it once immediately so there is no 1-second delay on load
  updateClock();

  // Keep it ticking every second!
  intervalId = setInterval(updateClock, 1000);
}

/**
 * Stops the clock interval. No route teardown calls it yet; it exists so the
 * timer has an explicit cleanup path.
 */
export function destroyLiveClock() {
  if (intervalId !== null) {
    clearInterval(intervalId);
    intervalId = null;
  }
}