'use strict';

/**
 * Builds the production bundle *without* a configured backend origin.
 *
 * `npm run build` deliberately refuses to run without ROADWISE_API_BASE_URL, so
 * that no deployable artefact can be produced by accident. That guard is the
 * right default, but it makes the plain build unusable as a "does the tree still
 * bundle?" check — which is exactly what it caught during review (a syntax error
 * in a copied helper script that no unit test could see).
 *
 * This wrapper sets the documented escape hatch and runs the real build, so
 * `npm run verify` still compiles everything. The artefact it produces is not
 * deployable and says so on every run.
 */

const { execSync } = require('node:child_process');

console.log('[verify] building without ROADWISE_API_BASE_URL (output is not deployable)');
process.env.ROADWISE_ALLOW_UNCONFIGURED_API_BASE = '1';

try {
  execSync('npm run build', { stdio: 'inherit', env: process.env });
} catch (error) {
  console.error('[verify] production build failed');
  process.exit(typeof error.status === 'number' ? error.status : 1);
}
