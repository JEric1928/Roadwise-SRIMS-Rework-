// js/api-base.js
//
// Single source of truth for the backend base URL.
//
// Why this file exists: the base URL used to be hardcoded twice, in two files,
// with two *different* hosts — js/config.js pointed at one Render deployment
// (used by js/forecasting_ui.js) and js/core/api.js at another (used by every
// data call). Forecasting therefore called a host that does not serve
// /api/v1/forecasting at all, and js/forecasting_ui.js swallowed the failure and
// rendered a simulated chart. Any future drift like that is now impossible:
// both scripts resolve through this one function.
//
// Resolution order:
//   1. window.__ROADWISE_API_BASE__  - runtime override (inline script or a
//      host-specific config file dropped next to this one).
//   2. Build-time ROADWISE_API_BASE_URL - substituted into BUILD_API_BASE by
//      the CopyPlugin transform in webpack.config.prod.js. A production build
//      refuses to be produced without it (guard in webpack.config.prod.js).
//   3. http://localhost:8080 on loopback (and in a non-browser context).
//   4. Same origin (empty base) plus a console error.
//
// There is deliberately no baked-in production host. An earlier revision fell
// back to the legacy Render backend, so a misconfigured deploy could quietly
// talk to a retired environment, serve stale data and still look healthy.
// Failing visibly beats that: with an empty base the requests go to this
// origin, 404, and the console says exactly why.
//
// This is an ES5 classic script on purpose: the dashboard pages load it with a
// plain <script src> before the webpack bundle, and it must run in browsers that
// never get the bundle (404.html).
(function (global) {
  'use strict';

  // Replaced at build time. The raw placeholder survives in development builds,
  // where it is detected and ignored.
  var BUILD_API_BASE = '__ROADWISE_BUILD_API_BASE__';
  var BUILD_PLACEHOLDER_PREFIX = '__ROADWISE_';

  var LOCAL_API_BASE = 'http://localhost:8080';


  var warned = false;

  function isLoopback(host) {
    return host === '' || host === 'localhost' || host === '127.0.0.1' || host === '::1';
  }

  /** The configured backend base URL, without a trailing slash. */
  function resolve() {
    if (global.__ROADWISE_API_BASE__) {
      return String(global.__ROADWISE_API_BASE__).replace(/\/+$/, '');
    }

    var built = BUILD_API_BASE && BUILD_API_BASE.indexOf(BUILD_PLACEHOLDER_PREFIX) !== 0
      ? String(BUILD_API_BASE)
      : '';
    if (built) return built.replace(/\/+$/, '');

    var host = (global.location && global.location.hostname) || '';
    if (isLoopback(host)) return LOCAL_API_BASE;

    if (!warned) {
      warned = true;
      // Deliberately loud. An unconfigured deployment is a deployment bug: the
      // production build already refuses to run without ROADWISE_API_BASE_URL,
      // so reaching this branch means the page was assembled by hand, an
      // override was removed, or the helper script is missing.
      console.error(
        '[RoadWise] No backend base URL is configured: ROADWISE_API_BASE_URL was ' +
        'not set at build time and no window.__ROADWISE_API_BASE__ override is ' +
        'present. Requests will be sent to this origin and fail. Set the build ' +
        'variable (see frontendcap/BROWSER_BASELINE.md §6) and rebuild.'
      );
    }
    // Same origin, not a guessed host: a visible 404 is a better failure than a
    // silent conversation with a retired environment.
    return '';
  }

  global.RoadWiseApiBase = {
    resolve: resolve,
    LOCAL_API_BASE: LOCAL_API_BASE
  };
})(typeof window !== 'undefined' ? window : globalThis);
