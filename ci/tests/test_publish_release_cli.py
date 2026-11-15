"""Exercise the publication CLI boundary without GitHub/ECR mutations."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_prod_release as cli
from deployment_contract import IMAGE, SOURCE_REPO

SHA = 'a' * 40
DIGEST = 'sha256:' + '2' * 64
VERSION = {'source_repository': SOURCE_REPO, 'source_branch': 'main', 'source_commit': SHA,
           'environment': 'prod', 'image_tag': 'sha-' + SHA, 'git_release_tag': None,
           'release_outcome': 'no-release', 'release_workflow_id': 123}
EVIDENCE = dict(VERSION, status='ecr-tags-verified', image_digest=DIGEST, image=IMAGE + '@' + DIGEST)


class PublicationCLIContractTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.version = copy.deepcopy(VERSION)
        self.evidence = copy.deepcopy(EVIDENCE)

    def invoke(self, omit_ecr=False):
        for name, value in [('version.json', self.version), ('ecr.json', self.evidence)]:
            (self.root / name).write_text(json.dumps(value))
        (self.root / 'digest.txt').write_text(DIGEST)
        args = ['publish_prod_release.py', '--execute', '--source-branch', 'main', '--source-commit', SHA,
                '--version-file', str(self.root / 'version.json'), '--digest-file', str(self.root / 'digest.txt'),
                '--output-dir', str(self.root / 'output')]
        if not omit_ecr:
            args += ['--ecr-file', str(self.root / 'ecr.json')]
        with patch.object(sys, 'argv', args), patch.dict(os.environ, SOURCE_TOKEN='fixture-source', GITOPS_TOKEN='fixture-write'), \
                patch.object(cli, 'GitHubAPI'), patch.object(cli, 'resolve_version', return_value=VERSION), \
                patch.object(cli, 'publish_release', return_value={'status': 'published-waiting-for-argocd',
                                                                 'gitops_commit': 'b' * 40, 'gitops_base_commit': 'b' * 40}) as publish, \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            try:
                cli.main()
            except SystemExit as failure:
                return failure.code, publish.call_count
            return 0, publish.call_count

    def test_matching_resolved_version_and_ecr_evidence_reaches_publication(self):
        self.assertEqual(self.invoke(), (0, 1))

    def test_missing_ecr_evidence_never_publishes(self):
        self.assertEqual(self.invoke(omit_ecr=True), (1, 0))

    def test_injected_version_and_wrong_source_cannot_reach_publication(self):
        for change in [{'image_tag': 'v9.9.9', 'git_release_tag': 'v9.9.9'}, {'source_commit': 'c' * 40}]:
            self.version = dict(VERSION, **change)
            with self.subTest(change=change):
                self.assertEqual(self.invoke(), (1, 0))

    def test_wrong_ecr_source_digest_environment_tag_or_status_never_publishes(self):
        for key, value in [('source_commit', 'c' * 40), ('image_digest', 'sha256:' + '3' * 64),
                           ('environment', 'staging'), ('image_tag', 'v9.9.9'), ('status', 'not-verified')]:
            self.evidence = dict(EVIDENCE, **{key: value})
            with self.subTest(key=key):
                self.assertEqual(self.invoke(), (1, 0))
