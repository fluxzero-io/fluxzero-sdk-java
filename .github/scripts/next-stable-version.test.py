#!/usr/bin/env python3
"""Exercise the public resolver in real, isolated Git histories."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RESOLVER = Path(__file__).with_name('resolve-release-version.sh').resolve()


class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = self.temp.name
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'release-test@example.invalid')
        self.git('config', 'user.name', 'Release test')
        self.commit('chore: initial')
        self.git('tag', '2.1.0')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True, stderr=subprocess.DEVNULL).strip()

    def commit(self, message):
        self.git('commit', '-q', '--allow-empty', '-m', message)

    def resolve(self, major='2', requested='', success=True):
        env = os.environ.copy()
        env.pop('AVAILABLE_RELEASE_TAGS', None)
        result = subprocess.run(['bash', str(RESOLVER), 'main', requested, major], cwd=self.repo,
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        return result.stdout.strip() if success else result.stderr

    def test_fix_and_non_release_commits_are_patch(self):
        self.commit('fix(modeling): preserve substeps')
        self.commit('docs: explain replay')
        self.commit('test: cover replay')
        self.assertEqual('2.1.1', self.resolve())

    def test_patch_types(self):
        for kind in ('fix', 'perf', 'deps', 'revert'):
            with self.subTest(kind=kind):
                self.git('reset', '--hard', '2.1.0')
                self.commit(f'{kind}: change')
                self.assertEqual('2.1.1', self.resolve())

    def test_feature_wins_over_fix(self):
        self.commit('feat(modeling): support v8')
        self.commit('fix: correct replay')
        self.assertEqual('2.2.0', self.resolve())

    def test_unscoped_feature(self):
        self.commit('feat: support v8')
        self.assertEqual('2.2.0', self.resolve())

    def test_only_unclassified_commits_retain_minor_fallback(self):
        self.commit('docs: update guide')
        self.assertEqual('2.2.0', self.resolve())

    def test_breaking_changes_require_explicit_major_transition(self):
        for message in ('feat!: remove API', 'fix(api)!: remove API',
                        'fix: remove API\n\nBREAKING CHANGE: old contract removed',
                        'refactor: remove API\n\nBREAKING-CHANGE: old contract removed'):
            with self.subTest(message=message):
                self.git('reset', '--hard', '2.1.0')
                self.commit(message)
                self.assertIn('explicit major-release transition', self.resolve(success=False))
                self.assertEqual('3.0.0', self.resolve(major='3'))

    def test_explicit_override_still_works(self):
        self.commit('feat: feature')
        self.assertEqual('2.1.1', self.resolve(requested='2.1.1'))
        self.resolve(requested='3.0.0', success=False)

    def test_existing_tag_is_reused_on_rerun(self):
        self.assertEqual('2.1.0', self.resolve())

    def test_annotated_tag_is_reused_on_rerun(self):
        self.commit('fix: fix')
        self.git('tag', '-a', '2.1.1', '-m', 'release')
        self.assertEqual('2.1.1', self.resolve())

    def test_commits_before_latest_release_are_excluded(self):
        self.commit('feat: old feature')
        self.git('tag', '2.2.0')
        self.commit('fix: new fix')
        self.assertEqual('2.2.1', self.resolve())

    def test_versions_are_sorted_numerically(self):
        self.git('tag', '2.9.9')
        self.git('tag', '2.10.12')
        self.commit('fix: fix')
        self.assertEqual('2.10.13', self.resolve())

    def test_prerelease_and_other_major_tags_do_not_select_base(self):
        self.git('tag', '2.99.0-rc.1')
        self.git('tag', '1.999.0')
        self.commit('fix: fix')
        self.assertEqual('2.1.1', self.resolve())

    def test_unmerged_tag_does_not_select_base(self):
        self.git('checkout', '-q', '-b', 'other')
        self.commit('feat: unrelated')
        self.git('tag', '2.99.0')
        self.git('checkout', '-q', 'main')
        self.commit('fix: fix')
        self.assertEqual('2.1.1', self.resolve())

    def test_conflicting_unmerged_version_fails(self):
        self.git('checkout', '-q', '-b', 'other')
        self.commit('fix: unrelated')
        self.git('tag', '2.1.1')
        self.git('checkout', '-q', 'main')
        self.commit('fix: fix')
        self.assertIn('already exists', self.resolve(success=False))

    def test_merge_keeps_feature_from_side_branch(self):
        self.git('checkout', '-q', '-b', 'feature')
        self.commit('feat: feature')
        self.git('checkout', '-q', 'main')
        self.commit('fix: fix')
        self.git('merge', '-q', '--no-ff', 'feature', '-m', 'Merge feature')
        self.assertEqual('2.2.0', self.resolve())

    def test_git_failure_is_not_treated_as_first_release(self):
        with tempfile.TemporaryDirectory() as outside:
            r = subprocess.run(['bash', str(RESOLVER), 'main', '', '2'], cwd=outside, capture_output=True)
        self.assertNotEqual(0, r.returncode)


if __name__ == '__main__':
    unittest.main()
