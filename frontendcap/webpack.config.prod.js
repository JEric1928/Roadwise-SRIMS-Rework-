const { merge } = require('webpack-merge');
const common = require('./webpack.common.js');
const CopyPlugin = require('copy-webpack-plugin');

// Every page is a plain HTML template that loads the bundle as a classic
// script (js/app.js) plus two classic helper scripts (js/config.js,
// js/forecasting_ui.js). The bundle is written to dist/js/app.js, so the
// existing <script src="js/app.js"> tags in the templates resolve to it.
// Pages are copied verbatim (no bundle injection), which preserves the
// original script order and avoids double-loading the bundle.
// ---------------------------------------------------------------------------
// Deployment configuration guard.
//
// This build output is what ships, so the backend it will talk to must be known
// at build time. A production bundle with no configured API base used to fall
// back to a hardcoded Render host, which meant a misconfigured deploy could
// serve a healthy-looking UI backed by a retired environment's data. Fail the
// build instead — it is a one-line fix in the deploy settings, and it is the
// only point at which the mistake is cheap.
//
// Set ROADWISE_API_BASE_URL to the backend origin, e.g.
//   ROADWISE_API_BASE_URL=https://api.roadwise.example.gov.ph
//
// ROADWISE_ALLOW_UNCONFIGURED_API_BASE=1 is an explicit escape hatch for
// throwaway builds (docs screenshots, offline demos). It is deliberately
// awkward to type and is logged loudly on every build that uses it.
// ---------------------------------------------------------------------------
const configuredApiBase = (process.env.ROADWISE_API_BASE_URL || '').trim();
const allowUnconfigured = process.env.ROADWISE_ALLOW_UNCONFIGURED_API_BASE === '1';

if (!configuredApiBase && !allowUnconfigured) {
  throw new Error(
    'ROADWISE_API_BASE_URL is not set.\n' +
      'The production bundle refuses to be built without a backend origin: shipping an ' +
      'unconfigured frontend is how a deployment ends up talking to the wrong environment.\n\n' +
      '  Vercel      : Project → Settings → Environment Variables → ROADWISE_API_BASE_URL, then redeploy\n' +
      '  CI / shell  : ROADWISE_API_BASE_URL=https://your-backend npm run build\n\n' +
      'For a throwaway build that is never deployed, set ' +
      'ROADWISE_ALLOW_UNCONFIGURED_API_BASE=1 to continue with same-origin requests ' +
      '(which will fail at runtime, by design).'
  );
}

if (allowUnconfigured && !configuredApiBase) {
  console.warn(
    '[webpack] ROADWISE_ALLOW_UNCONFIGURED_API_BASE=1 — building without a backend ' +
      'origin. This bundle cannot be deployed; every API call will hit its own origin ' +
      'and fail.'
  );
}

module.exports = merge(common, {
  mode: 'production',
  plugins: [
    new CopyPlugin({
      patterns: [
        { from: 'index.html', to: 'index.html' },
        { from: 'login.html', to: 'login.html' },
        { from: 'admin_dashboard.html', to: 'admin_dashboard.html' },
        { from: 'barangay_dashboard.html', to: 'barangay_dashboard.html' },
        { from: 'ceo_dashboard.html', to: 'ceo_dashboard.html' },
        { from: '404.html', to: '404.html' },
        // js/api-base.js carries the single backend-URL constant. Its
        // placeholder is replaced with the ROADWISE_API_BASE_URL environment
        // value at build time; when the variable is unset the placeholder is
        // left in place and the script falls back (see the file's header).
        {
          from: 'js/api-base.js',
          to: 'js/api-base.js',
          transform(content) {
            const configured = (process.env.ROADWISE_API_BASE_URL || '').trim();
            // CopyPlugin hands the transform a Buffer in current versions.
            if (!configured) return content;
            return content.toString().replace('__ROADWISE_BUILD_API_BASE__', configured);
          },
        },
        { from: 'js/config.js', to: 'js/config.js' },
        { from: 'js/forecasting_ui.js', to: 'js/forecasting_ui.js' },
        { from: 'sjdm_barangays.geojson', to: 'sjdm_barangays.geojson' },
        { from: 'img', to: 'img' },
        { from: 'css', to: 'css' },
        { from: 'js/vendor', to: 'js/vendor' },
        { from: 'icon.svg', to: 'icon.svg' },
        { from: 'favicon.ico', to: 'favicon.ico' },
        { from: 'robots.txt', to: 'robots.txt' },
        { from: 'icon.png', to: 'icon.png' },
        { from: 'site.webmanifest', to: 'site.webmanifest' },
      ],
    }),
  ],
});
