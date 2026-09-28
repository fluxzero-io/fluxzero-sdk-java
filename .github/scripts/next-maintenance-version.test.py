#!/usr/bin/env python3
"""Exercise automatic 1.x patch numbering in isolated Git histories."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


PUBLICATION = Path(__file__).with_name('resolve-release-publication.sh').resolve()


class MaintenanceVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = self.temp.name
        self.git('init', '-q', '-b', 'maintenance')
        self.git('config', 'user.email', 'release-test@example.invalid')
        self.git('config', 'user.name', 'Release test')
        self.commit('chore: initial')
        self.git('tag', '1.292.7')
        self.git('tag', '2.8.0')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True,
                                       stderr=subprocess.DEVNULL).strip()

    def commit(self, message):
        self.git('commit', '-q', '--allow-empty', '-m', message)

    def publication(self, requested='', existing='', success=True):
        env = os.environ.copy()
        env.pop('AVAILABLE_RELEASE_TAGS', None)
        result = subprocess.run(['bash', str(PUBLICATION), '1.x', requested, '1', existing],
                                cwd=self.repo, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        return result.stdout if success else result.stderr

    def test_next_patch_ignores_two_x_and_preserves_maintenance_destinations(self):
        self.commit('deps: bump dependency')
        output = self.publication()
        self.assertIn('version=1.292.8\n', output)
        self.assertIn('package_channel_tag=1.x\n', output)
        self.assertIn('javadoc_destination=javadoc/1.x\n', output)
        self.assertIn('make_latest=false\n', output)
        self.assertIn('notify_site=false\n', output)

    def test_feature_commit_still_advances_only_patch(self):
        self.commit('feat(api): add capability')
        self.assertIn('version=1.292.8\n', self.publication())

    def test_rerun_reuses_existing_tag_then_next_commit_advances(self):
        self.commit('fix(api): repair behavior')
        self.git('tag', '1.292.8')
        self.assertIn('version=1.292.8\n', self.publication(existing='1.292.8'))
        self.assertIn('version=1.292.8\n', self.publication())
        self.commit('deps: bump another dependency')
        self.assertIn('version=1.292.9\n', self.publication())

    def test_numeric_tag_order_and_new_minor(self):
        self.commit('fix: maintenance')
        self.git('tag', '1.292.100')
        self.commit('fix: another maintenance change')
        self.assertIn('version=1.292.101\n', self.publication())
        self.git('tag', '1.293.0')
        self.commit('fix: after minor release')
        self.assertIn('version=1.293.1\n', self.publication())

    def test_unmerged_tag_is_ignored_but_collision_is_rejected(self):
        self.git('checkout', '-q', '-b', 'other')
        self.commit('fix: unrelated')
        self.git('tag', '1.292.8')
        self.git('checkout', '-q', 'maintenance')
        self.commit('fix: maintenance')
        self.assertIn('already exists', self.publication(success=False))

    def test_no_reachable_one_x_tag_is_rejected(self):
        self.git('tag', '-d', '1.292.7')
        self.commit('fix: maintenance')
        self.assertIn('No reachable 1.x release tag', self.publication(success=False))

    def test_breaking_commit_is_rejected(self):
        self.commit('feat(api)!: remove supported method')
        self.assertIn('explicit major-release transition', self.publication(success=False))


if __name__ == '__main__':
    unittest.main()
