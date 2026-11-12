#!/usr/bin/env python3
"""Preview or explicitly publish six BE image digests to CLOUD/main via GitHub."""

import argparse
import base64
import difflib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

from prepare_prod_release import IMAGE, ROLES, VALUES, replace_image_digest

CLOUD_REPO = "100-hours-a-week/KTB4-17th-CLOUD"
SOURCE_REPO = "100-hours-a-week/KTB4-17th-BE"
CHART = "manifest/helm/star23-service"


class RemoteError(RuntimeError):
    def __init__(self, status, method, path):
        self.status = status
        # Never include credentials, response bodies, or request headers.
        super().__init__(f"Remote API failed: HTTP {status}, {method} {path}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class GitHubAPI:
    def __init__(self, repository, token):
        if repository not in (CLOUD_REPO, SOURCE_REPO):
            raise ValueError("Unexpected GitHub repository")
        self.base = "https://api.github.com/repos/" + repository
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, body=None):
        if path and (not path.startswith("/") or path.startswith("//")):
            raise ValueError("Expected a repository-relative API path")
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "star23-jenkins-cd", "Cache-Control": "no-cache",
                   "X-GitHub-Api-Version": "2026-03-10"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            raise RemoteError(failure.code, method, path) from None
        except (urllib.error.URLError, TimeoutError):
            raise RemoteError(0, method, path) from None


def revision(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Expected a full Git revision")
    return value


def main_revision(api):
    return revision(api.request("GET", "/git/ref/heads/main")["object"]["sha"])


def file_at(api, path, commit):
    payload = api.request("GET", "/contents/" + path + "?ref=" + revision(commit))
    if payload.get("type") != "file" or payload.get("encoding") != "base64":
        raise ValueError("Expected a regular UTF-8 GitHub file: " + path)
    encoded = "".join(payload["content"].split())
    return base64.b64decode(encoded, validate=True).decode("utf-8")


def check_access(cloud, source, source_commit, source_branch):
    revision(source_commit)
    if source_branch != "main":
        raise ValueError("Production publishing requires the main branch")
    if main_revision(source) != source_commit:
        raise ValueError("Production source must be the latest BE main revision")
    if cloud.request("GET", "").get("permissions", {}).get("push") is not True:
        raise ValueError("GitHub credential does not confirm CLOUD write access")


def publish_release(cloud, source, digest, source_commit, source_branch, execute=False, progress=None):
    revision(source_commit)
    if source_branch != "main":
        raise ValueError("Production publishing requires the main branch")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError("Invalid image digest")
    if execute:
        check_access(cloud, source, source_commit, source_branch)
    paths = [(VALUES / (role + ".yaml")).as_posix() for role in ROLES]
    original_digests = None
    for attempt in range(3):
        base = main_revision(cloud)
        tree_base = revision(cloud.request("GET", "/git/commits/" + base)["tree"]["sha"])
        entries, patch, previous = [], [], {}
        for path in paths:
            old = file_at(cloud, path, base)
            updated, previous[path] = replace_image_digest(old, digest, path)
            if updated != old:
                entries.append({"path": path, "mode": "100644", "type": "blob", "content": updated})
                patch.extend(difflib.unified_diff(old.splitlines(True), updated.splitlines(True),
                             fromfile="a/" + path, tofile="b/" + path))
        if entries and original_digests is not None and previous != original_digests:
            raise ValueError("BE digest changed concurrently; refusing to overwrite another release")
        original_digests = previous
        result = {
            "status": "prepared-not-published", "source_repository": SOURCE_REPO,
            "source_commit": source_commit, "image": IMAGE + "@" + digest,
            "image_digest": digest, "gitops_repository": CLOUD_REPO, "gitops_branch": "main",
            "gitops_base_commit": base, "values_files": paths,
            "changed_values_files": [entry["path"] for entry in entries],
            "previous_digests": previous, "patch": "".join(patch),
        }
        if not execute:
            return result
        # Check again after reading the snapshot, before any GitHub write.
        if main_revision(source) != source_commit:
            raise ValueError("Production source must be the latest BE main revision")
        if not entries:
            return dict(result, status="published-waiting-for-argocd", gitops_commit=base)
        tree = revision(cloud.request("POST", "/git/trees", {"base_tree": tree_base, "tree": entries})["sha"])
        commit = revision(cloud.request("POST", "/git/commits", {
            "message": f"deploy(be): {source_commit[:12]}\n\nSource: {SOURCE_REPO}@{source_commit}\nImage: {IMAGE}@{digest}",
            "tree": tree, "parents": [base],
        })["sha"])
        result.update(status="publishing", gitops_commit=commit)
        if progress:
            progress(result)
        try:
            actual = cloud.request("PATCH", "/git/refs/heads/main", {"sha": commit, "force": False})
            if actual["object"]["sha"] != commit:
                raise ValueError("CLOUD branch update did not confirm the intended commit")
            return dict(result, status="published-waiting-for-argocd")
        except RemoteError as failure:
            if failure.status not in (409, 422) or main_revision(cloud) == base:
                raise
            if attempt == 2:
                raise ValueError("CLOUD/main kept changing; rerun the deployment") from None
    raise AssertionError("Unreachable publication state")


def write_release(directory, report):
    directory.mkdir(parents=True, exist_ok=True)
    payload = dict(report)
    patch = payload.pop("patch", None)
    if patch is not None:
        (directory / "cloud-image-digest.patch").write_text(patch, encoding="utf-8")
    (directory / "deployment.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--source-branch", required=True)
    parser.add_argument("--digest-file", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("release"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--execute", action="store_true", help="Create and publish the CLOUD configuration commit")
    mode.add_argument("--check-access", action="store_true", help="Read-only source/credential validation")
    args = parser.parse_args()
    latest = {}

    def record(report):
        latest.update(report)
        write_release(args.output_dir, latest)

    try:
        cloud = GitHubAPI(CLOUD_REPO, os.environ.get("GITOPS_TOKEN"))
        source = GitHubAPI(SOURCE_REPO, os.environ.get("SOURCE_TOKEN"))
        if args.check_access or args.execute:
            if not os.environ.get("GITOPS_TOKEN") or not os.environ.get("SOURCE_TOKEN"):
                raise ValueError("Production CD requires Jenkins source and GitOps credentials")
        if args.check_access:
            check_access(cloud, source, args.source_commit, args.source_branch)
            print("Source main and CLOUD credential read checks passed; no GitHub writes performed.")
            return
        if not args.digest_file:
            raise ValueError("--digest-file is required")
        result = publish_release(cloud, source, args.digest_file.read_text().strip(),
                                 args.source_commit, args.source_branch, args.execute, record)
        record(result)
        print(result["status"] + ": " + result.get("gitops_commit", result["gitops_base_commit"]))
    except (OSError, ValueError, RemoteError, KeyError) as failure:
        record(dict(latest, status="failed", error=str(failure)))
        parser.exit(1, f"{failure}\n")


if __name__ == "__main__":
    main()
