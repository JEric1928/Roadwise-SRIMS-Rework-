/**
 * Passkey management: enrolment, listing, and removal.
 *
 * Lives in the same place for every role: the dashboard's Settings page, inside
 * the "Account Security" card. That card is personal sign-in security on
 * purpose -- it sits beside whatever role-level tools the Settings page also
 * holds (exports, system cycle) and never changes them. The panel is any
 * #passkey-settings-card in the page; pages without one are left untouched.
 *
 * Every request goes through apiFetch, which is what makes the feature usable
 * at all: the server refuses factor changes without a recent password entry
 * (403 + STEP_UP_REQUIRED), and apiFetch answers that with the step-up prompt
 * and replays the call. Session expiry (401) is handled there too.
 */
import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import {
  describeCredentialError,
  isWebAuthnSupported,
  prepareCreationOptions,
  registrationToJson
} from '../../core/webauthn.js';

const FACTOR_TYPE = "WEBAUTHN";

let initialized = false;

function $(id) {
  return document.getElementById(id);
}

/** Idempotent entry point, called once on DOMContentLoaded by app.js. */
export function initPasskeySettings() {
  if (initialized) return;
  const card = $("passkey-settings-card");
  if (!card) {
    // This page has no passkey panel; do not mark as initialised so a later
    // navigation that loads one can still bind.
    return;
  }
  initialized = true;

  const addBtn = $("btn-add-passkey");
  if (addBtn) {
    addBtn.addEventListener("click", addPasskey);
  }

  refreshPasskeys();
}

/** Re-reads the account's factors and re-renders the panel. */
export async function refreshPasskeys() {
  const status = $("passkey-status");
  const list = $("passkey-list");
  if (!list) return;

  if (status) status.textContent = "Loading your passkeys...";

  try {
    const data = await apiFetch("/api/auth/mfa/overview");
    const factors = Array.isArray(data && data.enrolledFactors) ? data.enrolledFactors : [];
    renderPasskeys(factors);
  } catch (error) {
    if (status) status.textContent = error.message || "Could not load your passkeys.";
  }
}

function renderPasskeys(factors) {
  const list = $("passkey-list");
  const status = $("passkey-status");
  if (!list) return;

  const passkeys = factors.filter((factor) => factor && factor.factorType === FACTOR_TYPE);

  list.textContent = "";
  for (const factor of passkeys) {
    const row = document.createElement("li");
    row.style.cssText = "display: flex; align-items: center; justify-content: space-between; gap: 12px;"
      + " padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 10px; margin-bottom: 8px;";

    const details = document.createElement("div");
    const name = document.createElement("strong");
    name.style.cssText = "display: block; font-size: 13.5px; color: #0f172a;";
    name.textContent = factor.label || "Passkey";
    details.appendChild(name);

    const when = describeDates(factor);
    if (when) {
      const meta = document.createElement("span");
      meta.style.cssText = "font-size: 12px; color: var(--color-muted);";
      meta.textContent = when;
      details.appendChild(meta);
    }
    row.appendChild(details);

    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "btn-secondary";
    removeBtn.textContent = "Remove";
    removeBtn.addEventListener("click", () => removePasskey(factor, removeBtn));
    row.appendChild(removeBtn);

    list.appendChild(row);
  }

  if (status) {
    status.textContent = passkeys.length
      ? `${passkeys.length} passkey${passkeys.length === 1 ? "" : "s"} on this account. Passkeys work in addition to your other sign-in methods.`
      : "No passkeys yet. Add one to sign in with your fingerprint, face, or security key.";
  }
}

/** Jackson may render LocalDateTime as an ISO string or as [y, m, d, h, min]. */
function describeDates(factor) {
  const added = formatDate(factor.createdAt);
  const used = formatDate(factor.lastUsedAt);
  const parts = [];
  if (added) parts.push(`Added ${added}`);
  if (used) parts.push(`last used ${used}`);
  return parts.join(" · ");
}

function formatDate(value) {
  if (!value) return "";
  let date = null;
  if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0] = value;
    date = new Date(year, month - 1, day, hour, minute);
  } else {
    const parsed = new Date(value);
    if (!Number.isNaN(parsed.getTime())) date = parsed;
  }
  return date ? date.toLocaleDateString() : "";
}

async function addPasskey() {
  if (!isWebAuthnSupported()) {
    showToast("This browser cannot create passkeys. Use a current browser over HTTPS (or localhost).", "error");
    return;
  }

  const addBtn = $("btn-add-passkey");
  const labelInput = $("passkey-label");
  setAddBusy(addBtn, true);

  try {
    // 1. The server issues a challenge bound to a one-time ceremony key.
    const options = await apiFetch("/api/auth/mfa/factors/passkeys/options", { method: "POST" });
    if (!options || !options.ceremonyKey || !options.options) {
      throw new Error("The server did not return a passkey challenge.");
    }

    // 2. The authenticator creates the credential. This is the step the user
    //    sees: fingerprint / face / security-key prompt.
    const credential = await navigator.credentials.create({
      publicKey: prepareCreationOptions(options.options)
    });

    // 3. The attestation completes the ceremony and enrols the factor.
    const label = labelInput && labelInput.value.trim() ? labelInput.value.trim() : null;
    await apiFetch("/api/auth/mfa/factors/passkeys", {
      method: "POST",
      body: JSON.stringify({
        ceremonyKey: options.ceremonyKey,
        credential: registrationToJson(credential),
        label: label
      })
    });

    if (labelInput) labelInput.value = "";
    showToast("Passkey added. You can use it the next time you sign in.", "success");
    await refreshPasskeys();
  } catch (error) {
    if (error && typeof error.status === "number") {
      // A server refusal (bad attestation, step-up declined, not configured).
      showToast(error.message || "That passkey could not be registered.", "error");
    } else {
      showToast(describeCredentialError(error), "error");
    }
  } finally {
    setAddBusy(addBtn, false);
  }
}

async function removePasskey(factor, button) {
  const name = factor.label || "this passkey";
  if (typeof window !== "undefined" && typeof window.confirm === "function"
    && !window.confirm(`Remove "${name}"? You will no longer be able to sign in with it.`)) {
    return;
  }

  button.disabled = true;
  try {
    await apiFetch(`/api/auth/mfa/factors/${factor.id}`, { method: "DELETE" });
    showToast("Passkey removed.", "success");
    await refreshPasskeys();
  } catch (error) {
    showToast(error.message || "That passkey could not be removed.", "error");
    button.disabled = false;
  }
}

function setAddBusy(button, busy) {
  if (!button) return;
  button.disabled = busy;
  button.textContent = busy ? "Waiting for your device..." : "Add a passkey";
}

if (typeof window !== "undefined") {
  // Compatibility bridge, matching the other feature modules: inline handlers
  // and other scripts can refresh the panel after their own changes.
  window.refreshPasskeys = refreshPasskeys;
}
