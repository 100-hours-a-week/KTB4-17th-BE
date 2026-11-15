from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deployment_contract import contract_for_branch, validate_image_tag

SHA = 'a' * 40


class DeploymentContractTest(unittest.TestCase):
    def test_branch_selects_exact_environment_and_six_paths(self):
        for branch, environment, prefix in [('dev', 'staging', 'values/staging/'), ('main', 'prod', 'values/')]:
            contract = contract_for_branch(branch)
            self.assertEqual(contract.environment, environment)
            self.assertEqual(len(contract.paths), 6)
            self.assertTrue(all(path.startswith('manifest/helm/star23-service/' + prefix) for path in contract.paths))
            self.assertEqual(contract.applications, ('auth-api', 'user-api', 'onboarding-api', 'chat-api', 'chat-socket', 'recommend-api'))
        self.assertTrue(set(contract_for_branch('dev').paths).isdisjoint(contract_for_branch('main').paths))

    def test_feature_fork_and_pr_jobs_cannot_select_a_deployment(self):
        for branch in ['feature/test', 'PR-123', 'prod', 'staging', '', 'refs/heads/dev']:
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                contract_for_branch(branch)
        with self.assertRaises(ValueError):
            contract_for_branch('main', change_id='123')

    def test_tags_cannot_inject_a_different_source_or_environment(self):
        self.assertEqual(validate_image_tag('dev', SHA, 'dev-' + SHA), 'dev-' + SHA)
        self.assertEqual(validate_image_tag('main', SHA, 'sha-' + SHA), 'sha-' + SHA)
        self.assertEqual(validate_image_tag('main', SHA, 'v3.1.0'), 'v3.1.0')
        for branch, tag in [('dev', 'v3.1.0'), ('main', 'dev-' + SHA), ('dev', 'dev-' + 'b'*40), ('main', 'latest')]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate_image_tag(branch, SHA, tag)
