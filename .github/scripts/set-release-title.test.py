#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('titles', Path(__file__).with_name('set-release-title.py'))
titles = importlib.util.module_from_spec(spec)
spec.loader.exec_module(titles)


class ReleaseTitleTest(unittest.TestCase):
    def release(self, **values):
        return dict(id=123, tag_name='2.15.1', name='Fluxzero 2.15.1',
                    published_at='2026-10-05T00:09:08Z', draft=False, prerelease=False,
                    body='Original notes', target_commitish='abc', **values)

    def test_original_publication_date_ignores_created_and_updated_dates(self):
        release = self.release(created_at='2026-10-04T23:59:59Z', updated_at='2027-01-01T00:00:00Z')
        self.assertEqual('2.15.1 – Oct 5, 2026', titles.release_title(release))

    def test_utc_date_across_month_and_year_boundaries(self):
        for published, expected in [('2026-10-01T00:15:00+02:00', 'Sep 30, 2026'),
                                    ('2026-12-31T23:15:00-02:00', 'Jan 1, 2027')]:
            release = self.release()
            release['published_at'] = published
            self.assertEqual('2.15.1 – ' + expected, titles.release_title(release))

    def test_prerelease_and_maintenance_tags_remain_exact(self):
        for tag in ('2.0.0-rc.12', '2.0.0-RC1', '1.90.2', '0.1.0', 'v2.1.0'):
            release = self.release()
            release['tag_name'] = tag
            self.assertEqual(tag + ' – Oct 5, 2026', titles.release_title(release))

    def test_drafts_missing_dates_and_naive_dates_cannot_be_renamed(self):
        for field, value in [('draft', True), ('published_at', None),
                             ('published_at', '2026-10-05T12:00:00')]:
            release = self.release()
            release[field] = value
            with patch.object(titles, 'api', return_value=release) as api:
                with self.assertRaises(ValueError):
                    titles.update_title('example/repo', '2.15.1')
                self.assertEqual(1, api.call_count)

    def test_update_sends_only_name(self):
        release = self.release()
        updated = dict(release, name='2.15.1 – Oct 5, 2026')
        with patch.object(titles, 'api', side_effect=[release, updated]) as api:
            titles.update_title('example/repo', '2.15.1')
        self.assertEqual([(('repos/example/repo/releases/tags/2.15.1',), {}),
                          (('repos/example/repo/releases/123', {'name': updated['name']}), {})], api.call_args_list)

    def test_rerun_does_not_mutate_correct_title(self):
        release = self.release()
        release['name'] = titles.release_title(release)
        with patch.object(titles, 'api', return_value=release) as api:
            titles.update_title('example/repo', '2.15.1')
        self.assertEqual(1, api.call_count)

    def test_wrong_response_or_changed_body_fails(self):
        release = self.release()
        for updated in [release, dict(release, name=titles.release_title(release), body='changed')]:
            with patch.object(titles, 'api', side_effect=[release, updated]):
                with self.assertRaises(ValueError):
                    titles.update_title('example/repo', '2.15.1')

    def test_read_failure_never_mutates(self):
        with patch.object(titles, 'api', side_effect=subprocess.CalledProcessError(1, 'gh')) as api:
            with self.assertRaises(subprocess.CalledProcessError):
                titles.update_title('example/repo', '2.15.1')
            self.assertEqual(1, api.call_count)

    def test_api_serializes_name_as_json_without_shell(self):
        with patch.object(titles.subprocess, 'check_output', return_value='{}') as run:
            titles.api('repos/example/repo/releases/123', {'name': '2.15.1 – Oct 5, 2026'})
        args, kwargs = run.call_args
        self.assertEqual(['gh', 'api', 'repos/example/repo/releases/123', '--method', 'PATCH', '--input', '-'], args[0])
        self.assertEqual({'name': '2.15.1 – Oct 5, 2026'}, json.loads(kwargs['input']))

    def test_workflow_normalizes_after_creation_without_overwriting_existing_title(self):
        workflow = (Path(__file__).parents[1] / 'workflows/deploy.yml').read_text()
        start = workflow.index('      - name: Create GitHub release')
        end = workflow.index('      - name: Set release title from original UTC publication date', start)
        self.assertNotIn('          name:', workflow[start:end])
        self.assertIn('set-release-title.py "$GITHUB_REPOSITORY" "$RELEASE_TAG"', workflow[end:])


if __name__ == '__main__':
    unittest.main()
