#!/usr/bin/env python3
"""Prepare a digest-only CLOUD/main patch; never modify CLOUD or commit files."""

import argparse
import difflib
import json
from pathlib import Path
import re


IMAGE = "975779326240.dkr.ecr.ap-northeast-2.amazonaws.com/star23/be"
ROLES = ("auth-api", "user-api", "onboarding-api", "chat-api", "chat-socket", "recommend-api")
VALUES = Path("manifest/helm/star23-service/values")


def prepare_release(cloud_repo, digest, source_commit, cloud_commit, output_dir):
    for value, pattern, label in (
        (digest, r"sha256:[0-9a-f]{64}", "image digest"),
        (source_commit, r"[0-9a-f]{40}", "source commit"),
        (cloud_commit, r"[0-9a-f]{40}", "CLOUD commit"),
    ):
        if not re.fullmatch(pattern, value):
            raise ValueError(f"Invalid {label}: {value!r}")

    patch = []
    changed = []
    paths = [VALUES / (role + ".yaml") for role in ROLES]
    # These repository-owned values use a simple, two-space image block.
    # Fail on a format change rather than silently rewriting unrelated YAML.
    for relative in paths:
        source = (cloud_repo / relative).read_text(encoding="utf-8")
        blocks = list(re.finditer(r"^image:\s*\n(?:[ \t]+[^\n]*\n|\n)*", source, re.MULTILINE))
        if len(blocks) != 1:
            raise ValueError(f"Expected one image block in {relative}")
        block = blocks[0]
        image = block.group()
        repositories = re.findall(r"^  repository: ([^\s]+)\s*$", image, re.MULTILINE)
        if repositories != [IMAGE]:
            raise ValueError(f"Unexpected image repository in {relative}: {repositories}")
        digests = list(re.finditer(r"^  digest: (sha256:[0-9a-f]{64})[ \t]*$", image, re.MULTILINE))
        keys = re.findall(r"^  digest:", image, re.MULTILINE)
        if len(digests) != 1 or len(keys) != 1:
            raise ValueError(f"Expected one image.digest in {relative}")
        start, end = digests[0].span(1)
        updated = source[:block.start() + start] + digest + source[block.start() + end:]
        if source != updated:
            changed.append(relative.as_posix())
            patch.extend(difflib.unified_diff(
                source.splitlines(keepends=True), updated.splitlines(keepends=True),
                fromfile="a/" + relative.as_posix(), tofile="b/" + relative.as_posix(),
            ))

    metadata = {
        "status": "prepared-not-deployed",
        "source_repository": "100-hours-a-week/KTB4-17th-BE",
        "source_commit": source_commit,
        "image": IMAGE + "@" + digest,
        "image_tag": source_commit,
        "image_digest": digest,
        "gitops_repository": "100-hours-a-week/KTB4-17th-CLOUD",
        "gitops_branch": "main",
        "gitops_base_commit": cloud_commit,
        "values_files": [path.as_posix() for path in paths],
        "changed_values_files": changed,
    }
    # Validate all six files before creating any output.
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "cloud-image-digest.patch").write_text("".join(patch), encoding="utf-8")
    (output_dir / "production-release.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
    )
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cloud-repo", type=Path, required=True)
    parser.add_argument("--digest", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--cloud-commit", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = prepare_release(
            args.cloud_repo, args.digest, args.source_commit, args.cloud_commit, args.output_dir
        )
    except (OSError, ValueError) as failure:
        parser.exit(1, f"{failure}\n")
    print(f"Prepared {len(result['changed_values_files'])} BE values updates; production is not deployed.")


if __name__ == "__main__":
    main()
