import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../workflows/deploy.yml', import.meta.url), 'utf8');
const job = workflow.split('  fluxzero-site:\n')[1];
const condition = job.match(/^    if: >-\n((?:      .*\n)+)/m)[1].trim();

// Evaluate the actual job condition against GitHub's dependency results. Keeping
// this matrix tied to the workflow catches regressions in the skipped-job path.
function dispatch({ changes = 'success', build = 'success', release = 'success',
                    prerelease = 'false', docs = 'false', website = 'false', cancelled = false } = {}) {
  const needs = {
    changes: { result: changes, outputs: { documentation_only: docs, website_changed: website } },
    'build-and-test': { result: build, outputs: { prerelease } },
    'github-release': { result: release },
  };
  const expression = condition
    .replace(/cancelled\(\)/g, JSON.stringify(cancelled))
    .replace(/needs\.([\w-]+)\.(result|outputs\.\w+)/g,
      (_, name, field) => JSON.stringify(field.split('.').reduce((value, key) => value[key], needs[name])));
  return Function(`return (${expression})`)();
}

test('website waits for release publication and receives its version and exact commit', () => {
  const dependencies = job.match(/^    needs: \[([^\]]+)\]/m)[1].split(',').map(value => value.trim());
  assert.ok(dependencies.includes('github-release'));
  assert.ok(dependencies.includes('changes'));
  assert.ok(dependencies.includes('build-and-test'));
  assert.match(job, /sdk_version: '\$\{\{ needs\.build-and-test\.outputs\.version-tag \}\}'/);
  assert.match(job, /sdk_sha: context\.sha/);
  assert.equal(dispatch(), true);
});

test('a failed, cancelled, skipped or unfinished release never announces a new changelog entry', () => {
  for (const release of ['failure', 'cancelled', 'skipped', '']) {
    assert.equal(dispatch({ release }), false, release);
  }
  assert.equal(dispatch({ prerelease: 'true' }), false);
  assert.equal(dispatch({ cancelled: true }), false);
  assert.equal(dispatch({ build: 'failure' }), false);
  assert.equal(dispatch({ changes: 'failure' }), false);
});

test('validated website documentation still refreshes when build and release are skipped', () => {
  const docs = { build: 'skipped', release: 'skipped', docs: 'true', website: 'true' };
  assert.equal(dispatch(docs), true);
  assert.equal(dispatch({ ...docs, website: 'false' }), false);
  assert.equal(dispatch({ ...docs, docs: 'false' }), false);
  assert.equal(dispatch({ ...docs, changes: 'failure' }), false);
  assert.equal(dispatch({ ...docs, cancelled: true }), false);
});
