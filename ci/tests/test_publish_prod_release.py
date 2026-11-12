import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import unittest
import urllib.error
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_prod_release import GitHubAPI, RemoteError, publish_release


SOURCE = "a" * 40
BASE = "b" * 40
OLD = "sha256:" + "1" * 64
NEW = "sha256:" + "2" * 64
OTHER = "sha256:" + "3" * 64
IMAGE = "975779326240.dkr.ecr.ap-northeast-2.amazonaws.com/star23/be"
ROLES = ("auth-api", "user-api", "onboarding-api", "chat-api", "chat-socket", "recommend-api")
PATHS = ["manifest/helm/star23-service/values/" + role + ".yaml" for role in ROLES]


class MemoryGitHub:
    """External GitHub Git Database boundary; no real repository is written."""

    def __init__(self, source=False):
        self.source = source
        self.head = SOURCE if source else BASE
        values = {path: f"image:\n  digest: {OLD}\n  repository: {IMAGE}\nreplicaCount: 2\n" for path in PATHS}
        values["ai.yaml"] = "keep-ai-and-other-files\n"
        self.snapshots = {self.head: values}
        self.trees = {self.head: values}
        self.parents = {}
        self.conflict = None
        self.deny_updates = False
        self.can_push = True

    def request(self, method, path, body=None):
        parsed = urllib.parse.urlsplit(path)
        route = parsed.path
        if method == "GET" and route == "":
            return {"permissions": {"admin": False, "maintain": False, "push": self.can_push, "triage": False, "pull": True}}
        if method == "GET" and route == "/git/ref/heads/main":
            return {"ref": "refs/heads/main", "object": {"type": "commit", "sha": self.head}}
        if method == "GET" and route.startswith("/git/commits/"):
            sha = route.rsplit("/", 1)[1]
            return {"sha": sha, "tree": {"sha": sha}}
        if method == "GET" and route.startswith("/contents/"):
            sha = urllib.parse.parse_qs(parsed.query)["ref"][0]
            content = self.snapshots[sha][route[len("/contents/"):]]
            return {"type": "file", "encoding": "base64", "content": base64.b64encode(content.encode()).decode()}
        if method == "POST" and route == "/git/trees":
            tree = copy.deepcopy(self.trees[body["base_tree"]])
            for entry in body["tree"]:
                if entry["mode"] != "100644" or entry["type"] != "blob":
                    raise AssertionError("Unexpected file type")
                tree[entry["path"]] = entry["content"]
            sha = hashlib.sha1(json.dumps(tree, sort_keys=True).encode()).hexdigest()
            self.trees[sha] = tree
            return {"sha": sha}
        if method == "POST" and route == "/git/commits":
            sha = hashlib.sha1(json.dumps(body, sort_keys=True).encode()).hexdigest()
            self.snapshots[sha] = copy.deepcopy(self.trees[body["tree"]])
            self.trees[sha] = self.snapshots[sha]
            self.parents[sha] = body["parents"]
            return {"sha": sha}
        if method == "PATCH" and route == "/git/refs/heads/main":
            if body["force"] is not False:
                raise AssertionError("A production update must never force the branch")
            if self.deny_updates:
                raise RemoteError(403, method, path)
            if self.conflict:
                callback, self.conflict = self.conflict, None
                changed = copy.deepcopy(self.snapshots[self.head])
                callback(changed)
                self.head = "d" * 40
                self.snapshots[self.head] = changed
                self.trees[self.head] = changed
            if self.parents[body["sha"]] != [self.head]:
                raise RemoteError(422, method, path)
            self.head = body["sha"]
            return {"object": {"sha": self.head}}
        raise AssertionError((method, path, body))


class PublishProductionReleaseTest(unittest.TestCase):
    def setUp(self):
        self.cloud = MemoryGitHub()
        self.source = MemoryGitHub(source=True)

    def publish(self, **options):
        return publish_release(self.cloud, self.source, NEW, SOURCE, "main", **options)

    def test_default_preview_leaves_main_unchanged_and_produces_six_digest_changes(self):
        result = self.publish()
        self.assertEqual(self.cloud.head, BASE)
        self.assertEqual(len(self.cloud.snapshots), 1)
        self.assertEqual(result["status"], "prepared-not-published")
        self.assertEqual(result["patch"].count("+  digest: " + NEW), 6)

    def test_publish_atomically_updates_six_roles_preserving_every_other_value(self):
        result = self.publish(execute=True)
        self.assertEqual(result["status"], "published-waiting-for-argocd")
        self.assertEqual(result["gitops_commit"], self.cloud.head)
        self.assertEqual(self.cloud.parents[self.cloud.head], [BASE])
        for path in PATHS:
            self.assertEqual(self.cloud.snapshots[self.cloud.head][path],
                             f"image:\n  digest: {NEW}\n  repository: {IMAGE}\nreplicaCount: 2\n")
        self.assertEqual(self.cloud.snapshots[self.cloud.head]["ai.yaml"], "keep-ai-and-other-files\n")
        self.assertEqual(result["source_commit"], SOURCE)

    def test_retry_preserves_concurrent_changes_to_other_applications(self):
        self.cloud.conflict = lambda snapshot: snapshot.update({"ai.yaml": "new-ai-deployment\n"})
        result = self.publish(execute=True)
        self.assertEqual(result["status"], "published-waiting-for-argocd")
        self.assertEqual(self.cloud.snapshots[self.cloud.head]["ai.yaml"], "new-ai-deployment\n")
        self.assertEqual(self.cloud.parents[self.cloud.head], ["d" * 40])

    def test_concurrent_be_release_is_never_overwritten(self):
        self.cloud.conflict = lambda snapshot: snapshot.update({PATHS[0]: snapshot[PATHS[0]].replace(OLD, OTHER)})
        with self.assertRaisesRegex(ValueError, "BE digest changed concurrently"):
            self.publish(execute=True)
        self.assertEqual(self.cloud.head, "d" * 40)
        self.assertIn(OTHER, self.cloud.snapshots[self.cloud.head][PATHS[0]])

    def test_stale_be_revision_cannot_publish(self):
        self.source.head = "e" * 40
        with self.assertRaisesRegex(ValueError, "latest BE main"):
            self.publish(execute=True)
        self.assertEqual(self.cloud.head, BASE)
        self.assertEqual(len(self.cloud.snapshots), 1)

    def test_dev_branch_cannot_publish(self):
        with self.assertRaisesRegex(ValueError, "main branch"):
            publish_release(self.cloud, self.source, NEW, SOURCE, "dev", execute=True)
        self.assertEqual(self.cloud.head, BASE)

    def test_already_published_digest_does_not_create_another_commit(self):
        for path in PATHS:
            self.cloud.snapshots[BASE][path] = self.cloud.snapshots[BASE][path].replace(OLD, NEW)
        result = self.publish(execute=True)
        self.assertEqual(self.cloud.head, BASE)
        self.assertEqual(len(self.cloud.snapshots), 1)
        self.assertEqual(result["gitops_commit"], BASE)

    def test_protected_branch_denial_fails_without_force_or_fallback(self):
        self.cloud.deny_updates = True
        with self.assertRaises(RemoteError):
            self.publish(execute=True)
        self.assertEqual(self.cloud.head, BASE)

    def test_wrong_repository_in_last_role_cannot_partially_publish(self):
        self.cloud.snapshots[BASE][PATHS[-1]] = self.cloud.snapshots[BASE][PATHS[-1]].replace("/star23/be", "/star23/ai")
        with self.assertRaisesRegex(ValueError, "Unexpected image repository"):
            self.publish(execute=True)
        self.assertEqual(len(self.cloud.snapshots), 1)

    def test_read_only_credential_is_rejected_before_writing_objects(self):
        self.cloud.can_push = False
        with self.assertRaisesRegex(ValueError, "CLOUD write access"):
            self.publish(execute=True)
        self.assertEqual(len(self.cloud.snapshots), 1)

    def test_api_error_does_not_expose_credentials_or_response_body(self):
        class DeniedOpener:
            def open(self, request, timeout):
                raise urllib.error.HTTPError(request.full_url, 403, "canary-secret", {}, io.BytesIO(b"canary-secret"))
        client = GitHubAPI("100-hours-a-week/KTB4-17th-CLOUD", "canary-secret")
        client.opener = DeniedOpener()
        with self.assertRaises(RemoteError) as error:
            client.request("GET", "")
        self.assertNotIn("canary-secret", str(error.exception))


if __name__ == "__main__":
    unittest.main()
