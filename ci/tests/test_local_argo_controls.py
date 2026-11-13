import base64
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deployment_contract import ROLES, contract_for_branch
from watch_prod_release import EKSApplications


class LocalArgoControlTest(unittest.TestCase):
    def test_dev_and_main_observe_their_own_cluster_with_existing_application_names(self):
        for branch, cluster in [('dev', 'star-EKS-v3-staging'), ('main', 'star-EKS-v3')]:
            with self.subTest(branch=branch):
                contract = contract_for_branch(branch)
                self.assertEqual(contract.applications, ROLES)
                self.assertEqual(contract.server, 'https://kubernetes.default.svc')
                arn = 'arn:aws:eks:ap-northeast-2:975779326240:cluster/' + cluster

                def aws(*args):
                    self.assertEqual(args, ('eks', 'describe-cluster', '--name', cluster))
                    return {'cluster': {'arn': arn, 'endpoint': 'https://control.example',
                                        'certificateAuthority': {'data': base64.b64encode(b'test-ca').decode()}}}

                with patch('watch_prod_release.aws_json', side_effect=aws), patch('watch_prod_release.ssl.create_default_context'):
                    self.assertEqual(EKSApplications(branch).endpoint, 'https://control.example')

    def test_staging_cannot_accept_the_production_cluster_identity(self):
        cluster = {'arn': 'arn:aws:eks:ap-northeast-2:975779326240:cluster/star-EKS-v3',
                   'endpoint': 'https://wrong.example',
                   'certificateAuthority': {'data': base64.b64encode(b'test-ca').decode()}}
        with patch('watch_prod_release.aws_json', return_value={'cluster': cluster}), patch('watch_prod_release.ssl.create_default_context'):
            with self.assertRaises(ValueError):
                EKSApplications('dev')
