/**
 * Step-up re-authentication prompt.
 *
 * Enrolling a factor, adding a passkey, or rotating recovery codes is a durable
 * change to how an account can be accessed, so the backend refuses those calls
 * with 403 + code STEP_UP_REQUIRED unless the caller re-proved their password
 * within the last few minutes (see StepUpInterceptor on the server).
 *
 * This module is the client half of that contract: one shared password prompt,
 * then POST /api/auth/step-up, then the caller's access token is replaced with
 * the freshly elevated one. api.js replays the original request with it.
 *
 * Kept dependency-free on purpose: it must work on any page (login included)
 * without assuming a CSS framework, and it must not import api.js -- api.js
 * imports this module.
 */


// Not api.js: this module is imported *by* api.js, so importing it back would be a
// cycle. auth-storage.js imports nothing, which is why the token helpers live there.
import { readAuthToken, writeAuthItem } from './auth-storage.js';
const MODAL_ID = "roadwise-stepup-modal";

// Only one prompt at a time: two parallel 403s must not stack two modals, and
// both callers should resume from the same password entry.
let activeStepUp = null;

/** True when a response is the server asking for a recent password entry. */
export function isStepUpChallenge(status, body) {
  return Number(status) === 403 && !!body && body.code === "STEP_UP_REQUIRED";
}

/**
 * Resolves once the session has been re-authenticated (the stored access token
 * is already replaced by then). Rejects with code STEP_UP_CANCELLED when the
 * user dismisses the prompt, or with the server's message on failure.
 *
 * @param {string} apiBaseUrl API origin, e.g. "http://localhost:8080".
 */
export function requestStepUp(apiBaseUrl) {
  if (activeStepUp) {
    return activeStepUp;
  }
  activeStepUp = new Promise((resolve, reject) => {
    openPrompt(apiBaseUrl, resolve, reject);
  }).finally(() => {
    activeStepUp = null;
  });
  return activeStepUp;
}

function openPrompt(apiBaseUrl, resolve, reject) {
  if (typeof document === "undefined") {
    reject(new Error("Cannot prompt for step-up re-authentication here."));
    return;
  }

  const overlay = document.createElement("div");
  overlay.id = MODAL_ID;
  overlay.className = "modal-overlay";
  // Inline layout styles: this prompt renders on pages whose stylesheets may
  // not know about it, and it must never be invisible but still modal.
  Object.assign(overlay.style, {
    position: "fixed",
    inset: "0",
    background: "rgba(15, 23, 42, 0.55)",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    zIndex: "9999",
    padding: "16px"
  });

  const card = document.createElement("div");
  card.className = "modal-content";
  Object.assign(card.style, {
    background: "#ffffff",
    borderRadius: "14px",
    padding: "22px",
    maxWidth: "380px",
    width: "100%",
    boxShadow: "0 24px 60px rgba(15, 23, 42, 0.35)",
    textAlign: "left"
  });
  card.setAttribute("role", "dialog");
  card.setAttribute("aria-modal", "true");
  card.setAttribute("aria-labelledby", "roadwise-stepup-title");

  // Static template: nothing from the server or the user is interpolated here.
  card.innerHTML = `
    <h3 id="roadwise-stepup-title" style="margin: 0 0 6px 0; font-size: 17px; font-weight: 700; color: #0f172a;">Confirm it's you</h3>
    <p style="margin: 0 0 14px 0; font-size: 13px; line-height: 1.5; color: #475569;">
      Changing your sign-in methods needs a recent password entry. Re-enter your password to continue.
    </p>
    <form id="roadwise-stepup-form" novalidate>
      <input id="roadwise-stepup-password" type="password" autocomplete="current-password"
             placeholder="Your password"
             style="width: 100%; box-sizing: border-box; padding: 10px 12px; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 14px;">
      <p id="roadwise-stepup-error" style="margin: 8px 0 0 0; font-size: 12.5px; font-weight: 600; color: #dc2626; display: none;"></p>
      <div style="display: flex; gap: 10px; justify-content: flex-end; margin-top: 16px;">
        <button type="button" id="roadwise-stepup-cancel"
                style="padding: 9px 14px; border-radius: 8px; border: 1px solid #cbd5e1; background: #f8fafc; color: #334155; font-weight: 600; font-size: 13px; cursor: pointer;">Cancel</button>
        <button type="submit" id="roadwise-stepup-submit"
                style="padding: 9px 16px; border-radius: 8px; border: none; background: #1a0ca3; color: #ffffff; font-weight: 700; font-size: 13px; cursor: pointer;">Confirm</button>
      </div>
    </form>
  `;

  const form = card.querySelector("#roadwise-stepup-form");
  const input = card.querySelector("#roadwise-stepup-password");
  const errorLine = card.querySelector("#roadwise-stepup-error");
  const submitBtn = card.querySelector("#roadwise-stepup-submit");
  const cancelBtn = card.querySelector("#roadwise-stepup-cancel");

  function showError(text) {
    errorLine.textContent = text;
    errorLine.style.display = "block";
  }

  function close() {
    document.removeEventListener("keydown", onKeydown);
    overlay.remove();
  }

  function cancel() {
    close();
    const cancelled = new Error("Step-up re-authentication was cancelled.");
    cancelled.code = "STEP_UP_CANCELLED";
    reject(cancelled);
  }

  function onKeydown(event) {
    if (event.key === "Escape") {
      cancel();
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const password = input.value;
    if (!password) {
      showError("Enter your password.");
      input.focus();
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = "Checking...";
    errorLine.style.display = "none";

    try {
      const token = readAuthToken("accessToken");
      const response = await fetch(`${apiBaseUrl}/api/auth/step-up`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify({ password })
      });

      let body = null;
      try {
        body = await response.json();
      } catch (parseError) {
        body = null;
      }

      if (!response.ok || !body || !body.accessToken) {
        const fallback = response.status === 429
          ? "Too many attempts. Please wait a moment before trying again."
          : "That password was not accepted.";
        showError((body && (body.error || body.message)) || fallback);
        submitBtn.disabled = false;
        submitBtn.textContent = "Confirm";
        input.select();
        return;
      }

      // The elevated token replaces the session token in place: every later
      // apiFetch call reads it fresh, including the replay that triggered us.
      try {
        writeAuthItem("accessToken", body.accessToken);
      } catch (storageError) {
        // Storage unavailable (private mode): the replay will still carry the
        // old token and fail the same way, which is the honest outcome.
      }

      close();
      resolve({ accessToken: body.accessToken });
    } catch (networkError) {
      showError("Could not reach the server. Check your connection and try again.");
      submitBtn.disabled = false;
      submitBtn.textContent = "Confirm";
    }
  });

  cancelBtn.addEventListener("click", cancel);
  overlay.addEventListener("click", (event) => {
    if (event.target === overlay) {
      cancel();
    }
  });
  document.addEventListener("keydown", onKeydown);

  document.body.appendChild(overlay);
  input.value = "";
  input.focus();
}
