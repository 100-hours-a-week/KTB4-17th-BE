import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from watch_prod_release import check_application, wait_for_release
from publish_prod_release import CLOUD_REPO, IMAGE, RemoteError
from test_publish_prod_release import MemoryGitHub, PATHS, ROLES, OLD, NEW, BASE, SOURCE


def application(role):
    namespace = {"auth-api": "auth", "user-api": "user", "onboarding-api": "user",
                 "chat-api": "chat", "chat-socket": "chat", "recommend-api": "recommend"}[role]
    return {
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
            self.cloud.snapshots[BASE][path] = self.cloud.snapshots[BASE][path].replace(OLD, NEW)
        self.clock = Clock()
        self.release = {"gitops_repository": CLOUD_REPO, "gitops_branch": "main",
                        "gitops_commit": BASE, "source_commit": SOURCE,
                        "image_digest": NEW, "image": IMAGE + "@" + NEW}

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


if __name__ == "__main__":
    unittest.main()
