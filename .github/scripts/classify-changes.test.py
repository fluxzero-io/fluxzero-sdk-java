#!/usr/bin/env python3
"""Check CI selection against complete PR histories, including renames and deletions."""
from pathlib import Path
import subprocess
import tempfile
import unittest

CLASSIFIER = Path(__file__).with_name('classify-changes.py').resolve()


class PullRequestChangesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'ci-test@example.invalid')
        self.git('config', 'user.name', 'CI test')
        self.write('README.md')
        self.write('AGENTS.md')
        self.write('sdk/Example.java')
        self.base = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True,
                                       stderr=subprocess.DEVNULL).strip()

    def write(self, path, content='initial\n'):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-q', '--allow-empty', '-m', 'test: change')
        return self.git('rev-parse', 'HEAD')

    def classify(self, expected, base=None, website=False):
        result = subprocess.run(['python3', str(CLASSIFIER), base or self.base,
                                 self.git('rev-parse', 'HEAD')], cwd=self.repo,
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f'documentation_only={str(expected).lower()}\n'
                         f'website_changed={str(website).lower()}\n', result.stdout)

    def test_root_documentation_is_lightweight(self):
        for path in ('AGENTS.md', 'README.md'):
            self.write(path, 'updated\n')
            self.commit()
            self.classify(True)

    def test_code_in_an_earlier_commit_still_requires_full_build(self):
        self.write('sdk/Example.java', 'changed\n')
        self.commit()
        self.write('README.md', 'updated\n')
        self.commit()
        self.classify(False)

    def test_other_paths_require_full_build(self):
        for path in ('docs/config.json', 'docs/run.py',
                     '.github/workflows/deploy.yml', '.github/scripts/policy.py',
                     'pom.xml', 'sdk/src/test/README.md', 'README.md\nOther.java'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.base)
                self.write(path)
                self.commit()
                self.classify(False)

    def test_documentation_formats_assets_and_graph_manifest_are_lightweight(self):
        for path in ('docs/agents/README.md', 'project-files/readme.md',
                     'module/NOTES.MD', 'design/decision.adoc', 'CONTRIBUTING.rst',
                     'docs/notes.txt', 'README.txt', 'guide.markdown', 'LICENSE', 'NOTICE',
                     'docs/agents/manifest.json', 'docs/diagram.svg',
                     'docs/developer/guide.mdx', 'docs/developer/image.png'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.base)
                self.write(path)
                self.commit()
                self.classify(True, website=path.startswith('docs/developer/'))

    def test_resources_configuration_and_scripts_still_require_full_build(self):
        for path in ('sdk/src/test/resources/static/abc.txt',
                     'sdk/src/main/resources/template.md', 'fixtures/response.mdx',
                     'sdk/src/test/resources/logo.svg', '.gitattributes',
                     'application.properties', 'docs/example.java', 'docs/build.sh',
                     'requirements.txt', 'constraints.txt', 'CMakeLists.txt', 'version.txt'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.base)
                self.write(path)
                self.commit()
                self.classify(False)

    def test_mixed_docs_and_code_keep_full_build_and_website_signal(self):
        self.write('docs/developer/guide.mdx')
        self.write('sdk/Example.java', 'changed\n')
        self.commit()
        self.classify(False, website=True)

    def test_renaming_or_deleting_public_docs_keeps_website_signal(self):
        self.write('docs/developer/guide.mdx')
        before = self.commit()
        self.git('mv', 'docs/developer/guide.mdx', 'guide.md')
        self.commit()
        self.classify(True, base=before, website=True)
        self.git('reset', '--hard', before)
        self.git('rm', 'docs/developer/guide.mdx')
        self.commit()
        self.classify(True, base=before, website=True)

    def test_renames_consider_old_and_new_paths(self):
        for source, destination in (('sdk/Example.java', 'README.md'),
                                    ('README.md', 'Example.java')):
            with self.subTest(source=source):
                self.git('reset', '--hard', self.base)
                self.git('mv', '-f', source, destination)
                self.commit()
                self.classify(False)

    def test_deleting_code_requires_full_build(self):
        self.git('rm', 'sdk/Example.java')
        self.commit()
        self.classify(False)

    def test_deleting_root_documentation_is_lightweight(self):
        self.git('rm', 'AGENTS.md')
        self.commit()
        self.classify(True)

    def test_base_branch_changes_are_not_part_of_pr(self):
        self.git('checkout', '-q', '-b', 'topic')
        self.write('README.md', 'updated\n')
        self.commit()
        self.git('checkout', '-q', 'main')
        self.write('sdk/Example.java', 'base changed\n')
        updated_base = self.commit()
        self.git('merge', '-q', '--no-ff', 'topic', '-m', 'Merge PR')
        self.classify(True, updated_base)

    def test_shallow_merge_checkout_contains_enough_history(self):
        self.git('checkout', '-q', '-b', 'topic')
        self.write('README.md', 'updated\n')
        self.commit()
        self.git('checkout', '-q', 'main')
        self.git('merge', '-q', '--no-ff', 'topic', '-m', 'Merge PR')
        merged = self.git('rev-parse', 'HEAD')
        with tempfile.TemporaryDirectory() as checkout:
            subprocess.check_call(['git', 'clone', '-q', '--depth=2', self.repo.as_uri(), checkout])
            result = subprocess.run(['python3', str(CLASSIFIER), self.base, merged],
                                    cwd=checkout, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('documentation_only=true\nwebsite_changed=false\n', result.stdout)

    def test_empty_diff_is_conservatively_full_build(self):
        self.classify(False)

    def test_unknown_or_invalid_base_fails_without_skip_output(self):
        for base in ('0' * 40, '--help'):
            with self.subTest(base=base):
                result = subprocess.run(['python3', str(CLASSIFIER), base, self.base],
                                        cwd=self.repo, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('', result.stdout)


if __name__ == '__main__':
    unittest.main()
