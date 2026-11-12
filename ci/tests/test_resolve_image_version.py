import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from resolve_image_version import resolve_version
from publish_prod_release import RemoteError

SHA = 'a' * 40
OTHER = 'b' * 40
TAG_OBJECT = 'c' * 40


class Source:
    def __init__(self):
        self.head = SHA
        self.calls = []
        self.runs = [{'id': 123, 'run_attempt': 1, 'head_sha': SHA, 'head_branch': 'main', 'event': 'push',
                      'path': '.github/workflows/release.yml', 'status': 'completed', 'conclusion': 'success',
                      'html_url': 'https://github.com/100-hours-a-week/KTB4-17th-BE/actions/runs/123'}]
        self.refs = []
        self.objects = {}
        self.step_conclusion = 'success'
        self.error = None

    def request(self, method, path, body=None):
        self.calls.append((method, path))
        if self.error:
            raise self.error
        if path.startswith('/git/ref/heads/'):
            return {'object': {'type': 'commit', 'sha': self.head}}
        if path.startswith('/actions/workflows/release.yml/runs?'):
            return {'workflow_runs': copy.deepcopy(self.runs)}
        if path.startswith('/actions/runs/123') and '/jobs' in path:
            return {'total_count': 1, 'jobs': [{'name': 'Semantic Release', 'conclusion': self.step_conclusion,
                    'steps': [{'name': 'Semantic Release', 'conclusion': self.step_conclusion}]}]}
        if path == '/git/matching-refs/tags/v':
            return copy.deepcopy(self.refs)
        if path.startswith('/git/tags/'):
            return {'object': self.objects[path.rsplit('/', 1)[1]]}
        raise AssertionError(path)

    def tag(self, name='v3.1.0', commit=SHA, annotated=False):
        self.refs.append({'ref': 'refs/tags/' + name, 'object': {'type': 'tag' if annotated else 'commit',
                          'sha': TAG_OBJECT if annotated else commit}})
        if annotated:
            self.objects[TAG_OBJECT] = {'type': 'commit', 'sha': commit}


class ResolveVersionTest(unittest.TestCase):
    def setUp(self):
        self.source = Source()

    def resolve(self, **kwargs):
        return resolve_version(self.source, 'main', SHA, timeout=2, interval=1, **kwargs)

    def test_dev_does_not_issue_a_version_or_wait_for_main_release(self):
        result = resolve_version(self.source, 'dev', SHA)
        self.assertEqual(result['image_tag'], 'dev-' + SHA)
        self.assertIsNone(result['git_release_tag'])
        self.assertFalse(any('/actions/' in path for _, path in self.source.calls))

    def test_lightweight_and_annotated_tags_resolve_to_exact_commit(self):
        for annotated in [False, True]:
            self.source = Source()
            self.source.tag(annotated=annotated)
            result = self.resolve()
            self.assertEqual(result['image_tag'], 'v3.1.0')
            self.assertEqual(result['git_release_tag'], 'v3.1.0')
            self.assertEqual(result['release_workflow_id'], 123)

    def test_success_without_a_release_uses_source_specific_sha_tag(self):
        self.source.tag(commit=OTHER)
        result = self.resolve()
        self.assertEqual(result['image_tag'], 'sha-' + SHA)
        self.assertEqual(result['release_outcome'], 'no-release')

    def test_pending_workflow_is_polled_until_success_before_reading_tags(self):
        self.source.runs[0].update(status='in_progress', conclusion=None)
        now = [0]
        def sleep(seconds):
            now[0] += seconds
            self.source.runs[0].update(status='completed', conclusion='success')
            self.source.tag()
        result = self.resolve(clock=lambda: now[0], sleep=sleep)
        self.assertEqual(result['image_tag'], 'v3.1.0')
        self.assertEqual(now[0], 1)

    def test_failed_release_and_skipped_semantic_step_are_not_no_release(self):
        for conclusion in ['failure', 'cancelled', 'skipped', 'timed_out']:
            self.source.runs[0]['conclusion'] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                self.resolve()
        self.source.runs[0]['conclusion'] = 'success'
        self.source.step_conclusion = 'skipped'
        with self.assertRaises(ValueError):
            self.resolve()

    def test_missing_workflow_times_out_without_fallback(self):
        self.source.runs = []
        now = [0]
        with self.assertRaises(TimeoutError):
            self.resolve(clock=lambda: now[0], sleep=lambda seconds: now.__setitem__(0, now[0]+seconds))

    def test_wrong_run_sha_and_stale_source_are_rejected(self):
        self.source.runs[0]['head_sha'] = OTHER
        now = [0]
        with self.assertRaises(TimeoutError):
            self.resolve(clock=lambda: now[0], sleep=lambda s: now.__setitem__(0, now[0]+s))
        self.source.head = OTHER
        with self.assertRaisesRegex(ValueError, 'latest BE main'):
            self.resolve()

    def test_actions_read_denial_is_not_treated_as_no_release(self):
        self.source.error = RemoteError(403, 'GET', '/actions/workflows/release.yml/runs')
        with self.assertRaises(RemoteError):
            self.resolve()

    def test_multiple_same_commit_release_tags_are_ambiguous(self):
        self.source.tag()
        self.source.tag(name='v3.2.0')
        with self.assertRaises(ValueError):
            self.resolve()
