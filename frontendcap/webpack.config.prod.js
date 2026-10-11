const { merge } = require('webpack-merge');
const common = require('./webpack.common.js');
const CopyPlugin = require('copy-webpack-plugin');

// Every page is a plain HTML template that loads the bundle as a classic
// script (js/app.js) plus two classic helper scripts (js/config.js,
// js/forecasting_ui.js). The bundle is written to dist/js/app.js, so the
// existing <script src="js/app.js"> tags in the templates resolve to it.
// Pages are copied verbatim (no bundle injection), which preserves the
// original script order and avoids double-loading the bundle.
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
