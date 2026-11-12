import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "prepare_prod_release.py"
ROLES = ("auth-api", "user-api", "onboarding-api", "chat-api", "chat-socket", "recommend-api")
VALUES = Path("manifest/helm/star23-service/values")
IMAGE = "975779326240.dkr.ecr.ap-northeast-2.amazonaws.com/star23/be"
OLD_DIGEST = "sha256:" + "1" * 64
NEW_DIGEST = "sha256:" + "2" * 64


class PrepareProductionReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cloud = self.root / "cloud"
        self.output = self.root / "release"
        (self.cloud / VALUES).mkdir(parents=True)
        for role in ROLES:
            (self.cloud / VALUES / (role + ".yaml")).write_text(
                "# Preserve formatting and other values.\nreplicaCount: 2\nimage:\n"
                f"  digest: {OLD_DIGEST}\n  tag: sha-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n  repository: {IMAGE}\n"
                "env:\n  SOME_VALUE: unchanged\n"
            )
        (self.cloud / VALUES / "chat-ai-practice.yaml").write_text("image:\n  digest: keep-ai\n")

    def run_generator(self, digest=NEW_DIGEST, source_commit="a" * 40):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--cloud-repo", str(self.cloud),
             "--digest", digest, "--image-tag", "sha-" + source_commit, "--source-branch", "main", "--source-commit", source_commit,
             "--cloud-commit", "b" * 40, "--output-dir", str(self.output)],
            capture_output=True, text=True, check=False,
        )

    def test_generates_applicable_digest_only_patch_for_all_six_roles_without_changing_cloud(self):
        before = {p: p.read_bytes() for p in self.cloud.rglob("*.yaml")}
        result = self.run_generator()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        patch = (self.output / "cloud-image-digest.patch").read_text()
        self.assertEqual(patch.count("--- a/"), 6)
        self.assertEqual(patch.count("-  digest: " + OLD_DIGEST), 6)
        self.assertEqual(patch.count("+  digest: " + NEW_DIGEST), 6)
        self.assertNotIn("chat-ai-practice", patch)
        applied = subprocess.run(
            ["git", "apply", "--check", str(self.output / "cloud-image-digest.patch")],
            cwd=self.cloud, capture_output=True, text=True, check=False,
        )
        self.assertEqual(applied.returncode, 0, applied.stderr)
        metadata = json.loads((self.output / "production-release.json").read_text())
        self.assertEqual(metadata["image"], IMAGE + "@" + NEW_DIGEST)
        self.assertEqual(metadata["source_commit"], "a" * 40)
        self.assertEqual(metadata["gitops_base_commit"], "b" * 40)
        self.assertEqual(len(metadata["values_files"]), 6)
        self.assertEqual(metadata["status"], "prepared-not-deployed")

    def test_rejects_invalid_digest_without_writing_artifacts(self):
        result = self.run_generator(digest="latest; echo unsafe")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid image digest", result.stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_invalid_source_commit(self):
        result = self.run_generator(source_commit="main")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Git revision", result.stderr)

    def test_wrong_repository_in_last_role_produces_no_partial_release(self):
        target = self.cloud / VALUES / "recommend-api.yaml"
        target.write_text(target.read_text().replace(IMAGE, IMAGE.replace("/be", "/ai")))
        result = self.run_generator()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unexpected image repository", result.stderr)
        self.assertFalse(self.output.exists())

    def test_missing_role_produces_no_partial_release(self):
        (self.cloud / VALUES / "recommend-api.yaml").unlink()
        result = self.run_generator()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

    def test_ambiguous_digest_is_rejected(self):
        target = self.cloud / VALUES / "chat-api.yaml"
        target.write_text(target.read_text().replace(
            f"  digest: {OLD_DIGEST}", f"  digest: {OLD_DIGEST}\n  digest: {OLD_DIGEST}"
        ))
        result = self.run_generator()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Expected one image.digest", result.stderr)
        self.assertFalse(self.output.exists())

    def test_already_current_digest_is_an_explicit_noop(self):
        result = self.run_generator(digest=OLD_DIGEST)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.output / "cloud-image-digest.patch").read_text(), "")
        metadata = json.loads((self.output / "production-release.json").read_text())
        self.assertEqual(metadata["changed_values_files"], [])


if __name__ == "__main__":
    unittest.main()
