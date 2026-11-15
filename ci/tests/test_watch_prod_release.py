import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from watch_prod_release import check_application, wait_for_release
from publish_prod_release import CLOUD_REPO, IMAGE, RemoteError
from test_publish_prod_release import MemoryGitHub, PATHS, ROLES, OLD, NEW, BASE, SOURCE, TAG


def application(role):
    namespace = {"auth-api": "auth", "user-api": "user", "onboarding-api": "user",
                 "chat-api": "chat", "chat-socket": "chat", "recommend-api": "recommend"}[role]
    app = {
        "metadata": {"name": role, "namespace": "argocd"},
        "spec": {"project": "star23-apps", "source": {
            "repoURL": "https://github.com/100-hours-a-week/KTB4-17th-CLOUD.git",
            "targetRevision": "main", "path": "manifest/helm/star23-service",
            "helm": {"valueFiles": ["values/" + role + ".yaml"]}},
            "destination": {"server": "https://kubernetes.default.svc", "namespace": namespace},
            "syncPolicy": {"automated": {"enabled": True}}},
        "status": {"sync": {"status": "Synced", "revision": BASE},
            "health": {"status": "Healthy"}, "summary": {"images": [IMAGE + "@" + NEW]},
            "operationState": {"phase": "Succeeded", "syncResult": {"revision": BASE}}},
    }

    app['status']['sync']['comparedTo'] = {key: copy.deepcopy(app['spec'][key]) for key in ('source', 'destination')}
    return app


class Applications:
    def __init__(self):
        self.apps = {role: application(role) for role in ROLES}
        self.failure = None
        self.after_read = None

    def application(self, role):
        if self.failure:
            raise self.failure
        result = copy.deepcopy(self.apps[role])
        if self.after_read:
            self.after_read(role)
        return result


class CloudWithHistory(MemoryGitHub):
    def request(self, method, path, body=None):
        if method == "GET" and path.startswith("/compare/"):
            return {"status": "ahead"}
        return super().request(method, path, body)


class Clock:
    def __init__(self):
        self.now = 0
        self.after_sleep = None

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds
        if self.after_sleep:
            self.after_sleep()


class WatchProductionReleaseTest(unittest.TestCase):
    def setUp(self):
        self.reader = Applications()
        self.cloud = CloudWithHistory()
        for path in PATHS:
            self.cloud.snapshots[BASE][path] = self.cloud.snapshots[BASE][path].replace(OLD, NEW).replace("image:\n", "image:\n  tag: " + TAG + "\n")
        self.clock = Clock()
        self.release = {"gitops_repository": CLOUD_REPO, "gitops_branch": "main",
                        "gitops_commit": BASE, "source_commit": SOURCE,
                        "image_digest": NEW, "image": IMAGE + "@" + NEW, "image_tag": TAG,
                        "source_repository": "100-hours-a-week/KTB4-17th-BE",
                        "source_branch": "main", "environment": "prod", "values_files": PATHS}

    def wait(self):
        return wait_for_release(self.reader, self.cloud, self.release, timeout=2, interval=1,
                                clock=self.clock, sleep=self.clock.sleep)

    def test_all_six_apps_must_be_healthy_synced_and_running_expected_image(self):
        result = self.wait()
        self.assertEqual(result["status"], "deployed")
        self.assertEqual(set(result["applications"]), set(ROLES))
        self.assertTrue(all(app["ready"] for app in result["applications"].values()))

    def test_healthy_old_image_is_never_reported_as_deployed(self):
        self.reader.apps["chat-api"]["status"]["summary"]["images"] = [IMAGE + "@" + OLD]
        with self.assertRaises(TimeoutError):
            self.wait()

    def test_mixed_old_and_new_pods_wait_for_full_rollout(self):
        self.reader.apps["chat-api"]["status"]["summary"]["images"].append(IMAGE + "@" + OLD)
        with self.assertRaises(TimeoutError):
            self.wait()

    def test_degraded_expected_revision_fails(self):
        self.reader.apps["chat-api"]["status"]["health"]["status"] = "Degraded"
        with self.assertRaisesRegex(ValueError, "Degraded"):
            self.wait()

    def test_failed_sync_of_expected_revision_fails(self):
        self.reader.apps["chat-api"]["status"]["operationState"]["phase"] = "Failed"
        with self.assertRaisesRegex(ValueError, "Failed"):
            self.wait()

    def test_old_failed_operation_does_not_fail_a_new_successful_rollout(self):
        operation = self.reader.apps["chat-api"]["status"]["operationState"]
        operation.update(phase="Failed", syncResult={"revision": "c" * 40})
        self.assertEqual(self.wait()["status"], "deployed")

    def test_disabled_auto_sync_is_rejected_during_preflight(self):
        app = self.reader.apps["auth-api"]
        app["spec"]["syncPolicy"]["automated"]["enabled"] = False
        with self.assertRaisesRegex(ValueError, "auto-sync"):
            check_application(app, "auth-api")

    def test_wrong_values_file_is_rejected(self):
        app = self.reader.apps["auth-api"]
        app["spec"]["source"]["helm"]["valueFiles"] = ["values/chat-api.yaml"]
        with self.assertRaisesRegex(ValueError, "configuration"):
            check_application(app, "auth-api")

    def test_read_access_denial_fails_instead_of_waiting_or_deploying_directly(self):
        self.reader.failure = RemoteError(403, "GET", "/apis/argoproj.io/v1alpha1/...")
        with self.assertRaises(RemoteError):
            self.wait()
        self.assertEqual(self.clock.now, 0)

    def test_temporary_api_failure_recovers_within_timeout(self):
        self.reader.failure = RemoteError(503, "GET", "/apis/argoproj.io/v1alpha1/...")
        self.clock.after_sleep = lambda: setattr(self.reader, "failure", None)
        self.assertEqual(self.wait()["status"], "deployed")

    def test_changed_gitops_digest_reports_superseded_instead_of_success(self):
        self.advance_cloud_head(replace_digest=True)
        with self.assertRaisesRegex(ValueError, "superseded"):
            self.wait()

    def advance_cloud_head(self, replace_digest=False):
        head = "d" * 40
        self.cloud.snapshots[head] = copy.deepcopy(self.cloud.snapshots[BASE])
        if replace_digest:
            self.cloud.snapshots[head][PATHS[0]] = self.cloud.snapshots[head][PATHS[0]].replace(NEW, OLD)
        else:
            self.cloud.snapshots[head]["ai.yaml"] = "concurrent-ai-release\n"
        self.cloud.head = head

    def test_superseding_cloud_change_during_observation_is_caught_before_success(self):
        self.reader.after_read = lambda role: self.advance_cloud_head(True) if role == "recommend-api" else None
        with self.assertRaisesRegex(ValueError, "superseded"):
            self.wait()

    def test_unrelated_cloud_changes_do_not_block_an_already_deployed_be_release(self):
        self.advance_cloud_head()
        self.assertEqual(self.wait()["status"], "deployed")

    def test_an_application_at_a_later_compatible_revision_can_complete(self):
        self.advance_cloud_head()
        for app in self.reader.apps.values():
            app["status"]["sync"]["revision"] = self.cloud.head
            app["status"]["operationState"]["syncResult"]["revision"] = self.cloud.head
        self.assertEqual(self.wait()["status"], "deployed")

    def test_changed_tag_at_same_digest_is_superseded(self):
        self.advance_cloud_head()
        self.cloud.snapshots[self.cloud.head][PATHS[0]] = self.cloud.snapshots[self.cloud.head][PATHS[0]].replace(TAG, "v9.9.9")
        with self.assertRaisesRegex(ValueError, "superseded"):
            self.wait()

    def test_six_apps_at_different_compatible_revisions_wait_for_common_revision(self):
        self.advance_cloud_head()
        self.reader.apps['chat-api']['status']['sync']['revision'] = self.cloud.head
        with self.assertRaises(TimeoutError):
            self.wait()

    def test_healthy_status_for_a_different_observed_destination_cannot_complete(self):
        self.reader.apps['auth-api']['status']['sync']['comparedTo']['destination']['server'] = 'https://old-cluster'
        with self.assertRaises(TimeoutError):
            self.wait()

    def test_healthy_status_for_old_source_values_cannot_complete(self):
        self.reader.apps['auth-api']['status']['sync']['comparedTo']['source']['helm']['valueFiles'] = ['values/old.yaml']
        with self.assertRaises(TimeoutError):
            self.wait()

    def test_deleting_application_cannot_report_deployed(self):
        self.reader.apps['auth-api']['metadata']['deletionTimestamp'] = '2026-11-12T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'delet'):
            self.wait()

    def test_environment_metadata_cannot_redirect_a_dev_release_to_prod(self):
        self.release.update(source_branch='dev', image_tag='dev-' + SOURCE)
        with self.assertRaises(ValueError):
            self.wait()


class WatchStagingReleaseTest(unittest.TestCase):
    def setUp(self):
        from deployment_contract import contract_for_branch
        self.contract = contract_for_branch('dev')
        self.reader = Applications()
        self.reader.apps = {role: application(role) for role in ROLES}
        for role in ROLES:
            app = self.reader.apps[role]
            app['metadata']['name'] = role
            app['spec']['source']['helm']['valueFiles'] = ['values/staging/' + role + '.yaml']
            app['spec']['destination']['server'] = self.contract.server
            app['status']['sync']['comparedTo'] = {key: copy.deepcopy(app['spec'][key]) for key in ('source', 'destination')}
        self.cloud = CloudWithHistory()
        for path in self.contract.paths:
            self.cloud.snapshots[BASE][path] = f'image:\n  digest: {NEW}\n  tag: dev-{SOURCE}\n  repository: {IMAGE}\n'
        self.clock = Clock()
        self.release = {'gitops_repository': CLOUD_REPO, 'gitops_branch': 'main', 'gitops_commit': BASE,
                        'source_commit': SOURCE, 'source_repository': '100-hours-a-week/KTB4-17th-BE',
                        'source_branch': 'dev', 'environment': 'staging', 'values_files': list(self.contract.paths),
                        'image_digest': NEW, 'image_tag': 'dev-' + SOURCE, 'image': IMAGE + '@' + NEW}

    def wait(self):
        return wait_for_release(self.reader, self.cloud, self.release, timeout=2, interval=1,
                                clock=self.clock, sleep=self.clock.sleep)

    def test_staging_verifies_only_its_six_apps_and_preserves_prod(self):
        before = copy.deepcopy(self.cloud.snapshots[BASE])
        result = self.wait()
        self.assertEqual(result['status'], 'deployed')
        self.assertEqual(set(result['applications']), set(self.contract.applications))
        self.assertEqual(result['image_tag'], 'dev-' + SOURCE)
        self.assertEqual(before, self.cloud.snapshots[BASE])

    def test_prod_destination_values_project_or_names_cannot_pass_staging(self):
        changes = [lambda app: app['spec']['destination'].update(server='https://wrong-cluster.example'),
                   lambda app: app['spec']['source']['helm'].update(valueFiles=['values/auth-api.yaml']),
                   lambda app: app['spec'].update(project='default'),
                   lambda app: app['metadata'].update(name='staging-auth-api')]
        for change in changes:
            app = copy.deepcopy(self.reader.apps['auth-api'])
            change(app)
            with self.subTest(change=change), self.assertRaises(ValueError):
                check_application(app, 'auth-api', 'dev')

    def test_missing_staging_application_fails_without_querying_prod(self):
        self.reader.failure = RemoteError(404, 'GET', '/applications/staging-auth-api')
        with self.assertRaises(RemoteError):
            self.wait()


class EKSControlClusterTest(unittest.TestCase):
    def test_staging_reads_its_own_control_cluster_and_rejects_unknown_app_names(self):
        from watch_prod_release import EKSApplications
        from deployment_contract import CONTROL_CLUSTER, CONTROL_CLUSTER_ARN
        ca = 'test-ca'
        import base64
        def aws(*args):
            self.assertEqual(args, ('eks', 'describe-cluster', '--name', 'star-EKS-v3-staging'))
            return {'cluster': {'arn': CONTROL_CLUSTER_ARN + '-staging', 'endpoint': 'https://control.example',
                               'certificateAuthority': {'data': base64.b64encode(ca.encode()).decode()}}}
        with patch('watch_prod_release.aws_json', side_effect=aws), patch('watch_prod_release.ssl.create_default_context'):
            reader = EKSApplications('dev')
            self.assertEqual(reader.endpoint, 'https://control.example')
            with self.assertRaises(ValueError):
                reader.application('staging-auth-api')

    def test_another_account_or_prod_as_staging_control_cluster_is_rejected(self):
        from watch_prod_release import EKSApplications
        for arn in ['arn:aws:eks:ap-northeast-2:000000000000:cluster/star-EKS-v3',
                    'arn:aws:eks:ap-northeast-2:975779326240:cluster/star-EKS-v3']:
            with patch('watch_prod_release.aws_json', return_value={'cluster': {'arn': arn, 'endpoint': 'https://other'}}):
                with self.subTest(arn=arn), self.assertRaises(ValueError):
                    EKSApplications('dev')


if __name__ == "__main__":
    unittest.main()
