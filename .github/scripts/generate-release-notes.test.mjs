import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';

const generator = fileURLToPath(new URL('./generate-release-notes.mjs', import.meta.url));

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'fluxzero-release-notes-'));
  t.after(() => rmSync(cwd, {recursive: true, force: true}));
  const git = (...args) => execFileSync('git', args, {cwd, encoding: 'utf8'}).trim();
  git('init', '-q');
  git('config', 'user.name', 'Release test');
  git('config', 'user.email', 'release@example.invalid');
  git('config', 'commit.gpgsign', 'false');
  git('config', 'tag.gpgsign', 'false');
  const commit = (message, tag) => {
    git('commit', '-q', '--allow-empty', '-m', message);
    if (tag) git('tag', tag);
  };
  const notes = (version, env = {}) => execFileSync(process.execPath, [generator], {
    cwd, encoding: 'utf8', env: {
      ...process.env,
      RELEASE_VERSION: version,
      RELEASE_TAG: version,
      CURRENT_REF: 'HEAD',
      PREVIOUS_TAG: '',
      ALLOW_PREVIOUS_TAG_FALLBACK: 'true',
      RELEASE_NOTES_PATH: '',
      RELEASE_NOTES_MAX_CHARACTERS: '120000',
      GITHUB_OUTPUT: '',
      ...env,
    },
  });
  return {git, commit, notes};
}

for (const tagged of [false, true]) {
  test(`stable release excludes RC and GA history (${tagged ? 'rerun' : 'first publication'})`, t => {
    const {commit, notes} = repository(t);
    commit('feat: candidate-only change', '2.0.0-rc.20');
    commit('feat: already released in GA', '2.0.0');
    commit('fix(modeling): preserve replay substeps\n\nRetain exact ordering.', tagged ? '2.1.0' : undefined);
    const result = notes('2.1.0');
    assert.match(result, /\/compare\/2\.0\.0\.\.\.2\.1\.0/);
    assert.match(result, /preserve replay substeps/);
    assert.match(result, /Retain exact ordering/);
    assert.doesNotMatch(result, /already released in GA|candidate-only change/);
  });
}

test('first release of a major compares with the preceding stable major', t => {
  const {commit, notes} = repository(t);
  commit('fix: previous major', '1.247.0');
  commit('feat: next major feature', '2.0.0-rc.20');
  commit('chore: promote GA', '2.0.0');
  const result = notes('2.0.0');
  assert.match(result, /\/compare\/1\.247\.0\.\.\.2\.0\.0/);
  assert.match(result, /next major feature/);
  assert.doesNotMatch(result, /previous major/);
});

test('stable versions sort numerically and ignore unrelated tag names', t => {
  const {git, commit, notes} = repository(t);
  commit('fix: ninth patch', '2.0.9');
  commit('fix: tenth patch', '2.0.10');
  git('tag', 'zzz-backup');
  commit('fix: next patch');
  assert.match(notes('2.0.11'), /\/compare\/2\.0\.10\.\.\.2\.0\.11/);
});

test('tags outside the release history cannot become the baseline', t => {
  const {git, commit, notes} = repository(t);
  commit('feat: stable', '2.0.0');
  commit('feat: other branch', '2.9.0');
  git('checkout', '-q', '--detach', '2.0.0');
  commit('fix: actual release');
  assert.match(notes('2.1.0'), /\/compare\/2\.0\.0\.\.\.2\.1\.0/);
});

test('stable tags newer than the target version cannot become the baseline', t => {
  const {commit, notes} = repository(t);
  commit('feat: initial stable', '2.0.0');
  commit('feat: later minor', '2.2.0');
  commit('fix: maintenance version');
  assert.match(notes('2.0.1'), /\/compare\/2\.0\.0\.\.\.2\.0\.1/);
});

test('a release without a prior stable tag includes the full history', t => {
  const {commit, notes} = repository(t);
  commit('feat: first feature', '2.0.0-rc.20');
  commit('chore: first stable', '2.0.0');
  const result = notes('2.0.0');
  assert.match(result, /\/releases\/tag\/2\.0\.0/);
  assert.match(result, /first feature/);
  assert.doesNotMatch(result, /\/compare\//);
});

test('prerelease selection continues to use the preceding candidate', t => {
  const {commit, notes} = repository(t);
  commit('feat: older candidate', '2.0.0-rc.9');
  commit('feat: preceding candidate', '2.0.0-rc.10');
  commit('fix: next candidate', '2.0.0-rc.11');
  assert.match(notes('2.0.0-rc.11'), /\/compare\/2\.0\.0-rc\.10\.\.\.2\.0\.0-rc\.11/);
});

test('an explicit baseline can still request a candidate comparison', t => {
  const {commit, notes} = repository(t);
  commit('feat: candidate', '2.0.0-rc.20');
  commit('feat: GA', '2.0.0');
  commit('fix: next release');
  const result = notes('2.1.0', {PREVIOUS_TAG: '2.0.0-rc.20'});
  assert.match(result, /\/compare\/2\.0\.0-rc\.20\.\.\.2\.1\.0/);
  assert.match(result, /GA/);
});

test('a missing explicit baseline falls back to the prior stable release', t => {
  const {commit, notes} = repository(t);
  commit('feat: stable', '2.0.0');
  commit('fix: next release');
  assert.match(notes('2.1.0', {PREVIOUS_TAG: 'missing'}), /\/compare\/2\.0\.0\.\.\.2\.1\.0/);
});

test('automatic baseline selection can still be disabled', t => {
  const {commit, notes} = repository(t);
  commit('feat: stable', '2.0.0');
  commit('fix: next release');
  const result = notes('2.1.0', {ALLOW_PREVIOUS_TAG_FALLBACK: 'false'});
  assert.match(result, /\/releases\/tag\/2\.1\.0/);
  assert.doesNotMatch(result, /\/compare\//);
});
