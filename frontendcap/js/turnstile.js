// js/turnstile.js
//
// The browser half of the Cloudflare Turnstile login check. This file is the only place
// that knows how the widget is configured; js/features/auth/index.js asks it for a token and
// nothing else.
//
// Why a separate classic script rather than code inside the bundle: login.html loads its
// scripts as plain <script src> tags and must keep working in browsers that never get the
// bundle (the same reason js/api-base.js exists). It is loaded before js/app.js and exposes
// window.RoadWiseTurnstile.
//
// ---------------------------------------------------------------------------
// Site-key resolution order (mirrors js/api-base.js, deliberately):
//   1. window.__ROADWISE_TURNSTILE_SITE_KEY__ - runtime override / host-specific file.
//   2. <meta name="roadwise-turnstile-site-key" content="..."> - per-page override.
//   3. BUILD_SITE_KEY - substituted at build time from ROADWISE_TURNSTILE_SITE_KEY by the
//      CopyPlugin transform in webpack.config.prod.js.
//   4. On loopback, Cloudflare's documented always-pass test site key. Its tokens only
//      verify against the matching test secret, so local development does not depend on the
//      production widget's hostname allowlist.
//   5. The configured production site key. This public fallback keeps the widget available
//      when a static host does not inject build-time environment variables. There is no
//      "render nothing and carry on" branch: the backend refuses an unverified login.
//
// A site key is public. It is not a credential, and nothing in this file is trusted by the
// backend: the token below proves nothing until TurnstileService exchanges it with
// Cloudflare.
// ---------------------------------------------------------------------------
(function (global) {
  'use strict';

  var BUILD_SITE_KEY = '__ROADWISE_TURNSTILE_SITE_KEY__';
  var ALWAYS_PASS_TEST_SITE_KEY = '1x00000000000000000000AA';
  var DEFAULT_SITE_KEY = '0x4AAAAAAFdkICnlAs5k2LwJ';
  var LOAD_TIMEOUT_MS = 10000;

  // The widget is rendered with action="login" and application.properties sets
  // turnstile.expected-action=login, so a token earned on another form (a password reset,
  // say) is refused even though it verified. The two values must stay in step.
  var WIDGET_ACTION = 'login';

  var scriptLoaded = false;
  var widgetId = null;
  var token = null;
  var status = 'loading';
  var loadTimer = null;

  function isLoopback() {
    var host = '';
    try {
      host = (global.location && global.location.hostname) || '';
    } catch (e) {
      return true;
    }
    return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
  }

  function readMetaSiteKey() {
    try {
      var meta = global.document.querySelector('meta[name="roadwise-turnstile-site-key"]');
      return meta ? (meta.getAttribute('content') || '').trim() : '';
    } catch (e) {
      return '';
    }
  }

  function resolveSiteKey() {
    var override = global.__ROADWISE_TURNSTILE_SITE_KEY__;
    if (typeof override === 'string' && override.trim()) return override.trim();

    var metaKey = readMetaSiteKey();
    if (metaKey) return metaKey;

    var buildKey = (BUILD_SITE_KEY || '').trim();
    if (buildKey && buildKey.indexOf('__ROADWISE_') !== 0) return buildKey;

    if (isLoopback()) return ALWAYS_PASS_TEST_SITE_KEY;

    return DEFAULT_SITE_KEY;
  }

  var siteKey = resolveSiteKey();

  // ---------------------------------------------------------------- status reporting

  function statusElement() {
    try {
      return global.document.getElementById('turnstile-status');
    } catch (e) {
      return null;
    }
  }

  function messageFor(state) {
    switch (state) {
      case 'unconfigured':
        return 'The security check is not configured for this deployment, so login is '
          + 'unavailable. Please contact the administrator.';
      case 'error':
        return 'The security check could not be loaded. Check your connection and reload '
          + 'the page.';
      case 'expired':
        return 'The security check expired. Please complete it again.';
      case 'loading':
        return 'The security check is still loading. Please try again in a moment.';
      case 'challenging':
      case 'solved':
        return '';
      default:
        return 'Please complete the security check before logging in.';
    }
  }

  function setStatus(state, explicitMessage) {
    status = state;
    var el = statusElement();
    if (el) {
      // 'challenging' and 'solved' are the two states that need no message: the widget
      // itself is the instruction, and leaving a stale error on screen under a freshly
      // rendered challenge is worse than saying nothing.
      var text = explicitMessage || messageFor(state);
      el.textContent = text;
      el.className = text ? 'turnstile-status' : 'turnstile-status hidden';
    }
  }

  // ---------------------------------------------------------------- widget lifecycle

  function whenDomReady(fn) {
    if (global.document && global.document.readyState !== 'loading') {
      fn();
      return;
    }
    if (global.document && global.document.addEventListener) {
      global.document.addEventListener('DOMContentLoaded', fn);
    }
  }

  function renderWidget() {
    if (widgetId !== null) return;

    var container = global.document.getElementById('turnstile-widget');
    if (!container) return; // page without a login form

    if (!siteKey) {
      container.className = 'turnstile-widget turnstile-widget-unavailable';
      setStatus('unconfigured');
      return;
    }
    // Called again by reset() and by the DOMContentLoaded tail below, so reaching this point
    // before api.js has run is normal. It must stay 'loading' rather than be reported as a
    // failure -- the onload callback, or the timeout, will speak for the load itself.
    if (!global.turnstile || typeof global.turnstile.render !== 'function') return;

    try {
      widgetId = global.turnstile.render(container, {
        sitekey: siteKey,
        action: WIDGET_ACTION,
        theme: 'auto',
        callback: function (widgetToken) {
          token = widgetToken;
          setStatus('solved');
        },
        'expired-callback': function () {
          // Tokens are short-lived. An expired one must be reported as absent rather than
          // sent, because the backend would refuse it and the user would see a security
          // error instead of "please complete the check again".
          token = null;
          setStatus('expired');
        },
        'error-callback': function () {
          token = null;
          setStatus('error');
          return true; // keep the widget mounted; the user can retry in place
        },
        'timeout-callback': function () {
          token = null;
          setStatus('expired');
        }
      });
      container.className = 'turnstile-widget';
      setStatus('challenging');
    } catch (e) {
      token = null;
      setStatus('error');
    }
  }

  // Cloudflare calls this once api.js has finished loading: login.html passes it as the
  // `onload` query parameter. The timeout below covers the other ending -- a blocked or
  // failed script that never calls it at all.
  global.__roadwiseTurnstileOnload = function () {
    scriptLoaded = true;
    if (loadTimer) {
      global.clearTimeout(loadTimer);
      loadTimer = null;
    }
    if (global.turnstile && typeof global.turnstile.render === 'function') {
      whenDomReady(renderWidget);
    } else {
      setStatus('error');
    }
  };

  if (typeof global.setTimeout === 'function') {
    loadTimer = global.setTimeout(function () {
      if (!scriptLoaded) {
        setStatus('error', 'The security check could not be loaded (network or browser '
          + 'blocked). Reload the page to try again.');
      }
    }, LOAD_TIMEOUT_MS);
  }

  // ---------------------------------------------------------------- public surface

  global.RoadWiseTurnstile = {
    /** The site key in use, or '' when none is configured. */
    getSiteKey: function () {
      return siteKey;
    },

    /** 'loading' | 'solved' | 'challenging' | 'unconfigured' | 'expired' | 'error'. */
    status: function () {
      return status;
    },

    /**
     * The current widget token, or null. Callers must treat null as "no bot check has been
     * completed" and not send the login request -- see js/features/auth/index.js.
     */
    getToken: function () {
      return token;
    },

    /** A user-facing explanation of why getToken() is empty. */
    blockingMessage: function () {
      return messageFor(status);
    },

    /**
     * Discards the current challenge and renders a fresh one. Turnstile tokens are
     * single-use, so every login attempt -- successful or not -- needs a new one before the
     * form can be submitted again.
     */
    reset: function () {
      token = null;
      if (widgetId !== null && global.turnstile && typeof global.turnstile.reset === 'function') {
        try {
          global.turnstile.reset(widgetId);
        } catch (e) {
          // A widget that has been removed (or an api.js that was replaced) is not
          // recoverable here; a page reload is the fix, and the next attempt reports it.
        }
      } else if (widgetId === null) {
        whenDomReady(renderWidget);
      }
      if (status !== 'unconfigured' && status !== 'error') {
        setStatus('challenging');
      }
    }
  };

  whenDomReady(function () {
    // Two ways to get here. The usual one is the onload callback above, which fires after
    // api.js has run. The other is a page where api.js was already sitting in the cache and
    // finished before this file executed: the callback would then have been called on a
    // function that did not exist yet, so the API object itself is checked as well rather
    // than waiting for a signal that has already been sent.
    if (scriptLoaded || (global.turnstile && typeof global.turnstile.render === 'function')) {
      scriptLoaded = true;
      if (loadTimer) {
        global.clearTimeout(loadTimer);
        loadTimer = null;
      }
      renderWidget();
    }
  });
})(window);
