#!/usr/bin/env python3
"""Read six Argo Application CRDs through EKS; never mutate cluster resources."""

import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import time
import urllib.error
import urllib.request

from prepare_prod_release import IMAGE, ROLES, VALUES, replace_image_digest
from publish_prod_release import CLOUD_REPO, CHART, GitHubAPI, NoRedirect, RemoteError, file_at, main_revision, revision

REGION = "ap-northeast-2"
CLUSTER = "star-EKS-v3"
CLUSTER_ARN = "arn:aws:eks:ap-northeast-2:975779326240:cluster/star-EKS-v3"
NAMESPACES = {"auth-api": "auth", "user-api": "user", "onboarding-api": "user",
              "chat-api": "chat", "chat-socket": "chat", "recommend-api": "recommend"}


def aws_json(*arguments):
    try:
        result = subprocess.run(["aws", "--region", REGION, "--no-cli-pager", *arguments,
                                 "--output", "json", "--cli-connect-timeout", "5", "--cli-read-timeout", "15"],
                                check=True, capture_output=True, text=True, timeout=30)
        return json.loads(result.stdout)
    except (subprocess.SubprocessError, ValueError, OSError):
        raise ValueError("AWS read failed; check the Jenkins instance profile and EKS read access") from None


class EKSApplications:
    def __init__(self):
        cluster = aws_json("eks", "describe-cluster", "--name", CLUSTER)["cluster"]
        if cluster["arn"] != CLUSTER_ARN or not cluster["endpoint"].startswith("https://"):
            raise ValueError("Unexpected EKS cluster")
        self.endpoint = cluster["endpoint"]
        ca = base64.b64decode(cluster["certificateAuthority"]["data"]).decode()
        context = ssl.create_default_context(cadata=ca)
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))
        self.token = None
        self.expires = 0

    def application(self, name):
        if name not in ROLES:
            raise ValueError("Unexpected Argo Application")
        if not self.token or time.time() >= self.expires - 60:
            payload = aws_json("eks", "get-token", "--cluster-name", CLUSTER)["status"]
            self.token = payload["token"]
            self.expires = datetime.fromisoformat(payload["expirationTimestamp"].replace("Z", "+00:00")).timestamp()
        path = "/apis/argoproj.io/v1alpha1/namespaces/argocd/applications/" + name
        request = urllib.request.Request(self.endpoint + path, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=15) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            raise RemoteError(failure.code, "GET", path) from None
        except (urllib.error.URLError, TimeoutError):
            raise RemoteError(0, "GET", path) from None


def check_application(app, role):
    spec = app.get("spec", {})
    source = spec.get("source", {})
    destination = spec.get("destination", {})
    if (app.get("metadata", {}).get("name") != role
            or app.get("metadata", {}).get("namespace") != "argocd"
            or spec.get("project") != "star23-apps"
            or source.get("repoURL", "").removesuffix(".git") != "https://github.com/" + CLOUD_REPO
            or source.get("targetRevision") != "main" or source.get("path") != CHART
            or source.get("helm", {}).get("valueFiles") != ["values/" + role + ".yaml"]
            or destination.get("server") != "https://kubernetes.default.svc"
            or destination.get("namespace") != NAMESPACES[role]):
        raise ValueError("Unexpected Argo Application configuration: " + role)
    automated = spec.get("syncPolicy", {}).get("automated")
    if not isinstance(automated, dict) or automated.get("enabled") is False:
        raise ValueError("Argo auto-sync must be enabled: " + role)


def wait_for_release(reader, cloud, release, timeout=900, interval=10,
                     clock=time.monotonic, sleep=time.sleep, progress=None):
    commit = revision(release["gitops_commit"])
    revision(release["source_commit"])
    digest = release["image_digest"]
    if (release.get("gitops_repository") != CLOUD_REPO or release.get("gitops_branch") != "main"
            or release.get("image") != IMAGE + "@" + digest):
        raise ValueError("Unexpected production deployment metadata")
    # Reuse digest validation before any observer API requests.
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest) or timeout <= 0 or interval <= 0:
        raise ValueError("Invalid deployment digest or timeout")
    expected_image = IMAGE + "@" + digest
    deadline = clock() + timeout
    comparisons, checked_values, checked_heads = {}, set(), set()

    def verify_current_cloud_head():
        head = main_revision(cloud)
        if head in checked_heads:
            return
        if head != commit:
            relationship = cloud.request("GET", f"/compare/{commit}...{head}")["status"]
            if relationship not in ("ahead", "identical"):
                raise ValueError("CLOUD/main no longer contains the published production release")
        for role in ROLES:
            path = (VALUES / (role + ".yaml")).as_posix()
            _, desired = replace_image_digest(file_at(cloud, path, head), digest, path)
            if desired != digest:
                raise ValueError("Production release was superseded in CLOUD/main: " + role)
        checked_heads.add(head)

    while True:
        verify_current_cloud_head()
        applications = {}
        for role in ROLES:
            state = {"ready": False}
            applications[role] = state
            try:
                app = reader.application(role)
                check_application(app, role)
                status = app.get("status", {})
                synced = status.get("sync", {})
                current = synced.get("revision", "")
                health = status.get("health", {}).get("status")
                images = status.get("summary", {}).get("images", [])
                state.update(sync=synced.get("status"), health=health, revision=current, images=images)
                if not current:
                    continue
                revision(current)
                if current == commit:
                    contains_commit = True
                else:
                    if current not in comparisons:
                        comparisons[current] = cloud.request("GET", f"/compare/{commit}...{current}")["status"]
                    relationship = comparisons[current]
                    if relationship == "diverged":
                        raise ValueError("Argo revision diverged from the published release: " + role)
                    contains_commit = relationship in ("ahead", "identical")
                if not contains_commit:
                    continue
                if (current, role) not in checked_values:
                    path = (VALUES / (role + ".yaml")).as_posix()
                    _, desired = replace_image_digest(file_at(cloud, path, current), digest, path)
                    if desired != digest:
                        raise ValueError("Production release was superseded: " + role)
                    checked_values.add((current, role))
                if health == "Degraded":
                    raise ValueError("Argo Application Degraded: " + role)
                operation = status.get("operationState", {})
                phase = operation.get("phase")
                if (phase in ("Failed", "Error")
                        and operation.get("syncResult", {}).get("revision") == current):
                    raise ValueError("Argo sync " + phase + ": " + role)
                for condition in status.get("conditions", []):
                    if condition.get("type") in ("InvalidSpecError", "ComparisonError", "SyncError"):
                        raise ValueError("Argo " + condition["type"] + ": " + role)
                be_images = {image for image in images if image.startswith((IMAGE + "@", IMAGE + ":"))}
                state["ready"] = (synced.get("status") == "Synced" and health == "Healthy"
                                  and phase not in ("Running", "Terminating") and be_images == {expected_image})
            except RemoteError as failure:
                if failure.status not in (0, 429, 500, 502, 503, 504):
                    raise
                state["api_error"] = str(failure)
        ready = sum(state["ready"] for state in applications.values())
        if ready == len(ROLES):
            # Argo status may be stale while a newer CLOUD/main is already published.
            verify_current_cloud_head()
        result = {"status": "deployed" if ready == len(ROLES) else "waiting-for-argocd",
                  "gitops_commit": commit, "image": expected_image, "applications": applications,
                  "observed_at": datetime.now(timezone.utc).isoformat()}
        if progress:
            progress(result)
        if ready == len(ROLES):
            return result
        remaining = deadline - clock()
        if remaining <= 0:
            pending = ", ".join(role for role, state in applications.items() if not state["ready"])
            raise TimeoutError("Argo rollout timed out: " + pending)
        sleep(min(interval, remaining))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-access", action="store_true")
    parser.add_argument("--deployment-file", type=Path, default=Path("release/deployment.json"))
    parser.add_argument("--output-file", type=Path, default=Path("release/argocd-status.json"))
    parser.add_argument("--timeout-seconds", type=int, default=900)
    args = parser.parse_args()
    latest = {}

    def record(report):
        latest.update(report)
        args.output_file.parent.mkdir(parents=True, exist_ok=True)
        args.output_file.write_text(json.dumps(latest, indent=2) + "\n", encoding="utf-8")
        if "applications" in report:
            ready = sum(app["ready"] for app in report["applications"].values())
            print(f"Argo CD: {ready}/{len(ROLES)} applications deployed", flush=True)

    try:
        reader = EKSApplications()
        if args.check_access:
            for role in ROLES:
                check_application(reader.application(role), role)
            record({"status": "read-access-verified", "applications_checked": list(ROLES)})
            print("EKS read access and six Argo Application configurations verified; no cluster writes performed.")
            return
        if not os.environ.get("GITOPS_TOKEN"):
            raise ValueError("Argo rollout observation requires the CLOUD read token")
        release = json.loads(args.deployment_file.read_text())
        result = wait_for_release(reader, GitHubAPI(CLOUD_REPO, os.environ["GITOPS_TOKEN"]), release,
                                  timeout=args.timeout_seconds, progress=record)
        record(result)
    except (OSError, ValueError, RemoteError, KeyError, TimeoutError) as failure:
        record(dict(latest, status="failed", error=str(failure)))
        parser.exit(1, f"{failure}\n")


if __name__ == "__main__":
    main()
