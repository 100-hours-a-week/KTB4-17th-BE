#!/usr/bin/env python3
"""Prepare an allowlisted BE tag+digest CLOUD/main patch without modifying CLOUD."""
import argparse
import difflib
import json
from pathlib import Path
import re

from deployment_contract import (IMAGE, ROLES, VALUES, CLOUD_REPO, SOURCE_REPO, contract_for_branch,
                                 image_digest, revision, validate_image_tag)


def replace_image(source, tag, digest, relative):
    """Change only image.tag/digest, preserving YAML formatting and every other field."""
    image_digest(digest)
    blocks = list(re.finditer(r'^image:[ \t]*\n(?:[ \t]+[^\n]*\n|\n)*', source, re.MULTILINE))
    if len(blocks) != 1:
        raise ValueError(f'Expected one image block in {relative}')
    block = blocks[0]
    image = block.group()
    repositories = re.findall(r'^  repository: ([^\s]+)[ \t]*$', image, re.MULTILINE)
    if repositories != [IMAGE]:
        raise ValueError(f'Unexpected image repository in {relative}: {repositories}')
    digests = list(re.finditer(r'^  digest: (sha256:[0-9a-f]{64})[ \t]*$', image, re.MULTILINE))
    if len(digests) != 1 or len(re.findall(r'^  digest:', image, re.MULTILINE)) != 1:
        raise ValueError(f'Expected one image.digest in {relative}')
    tags = list(re.finditer(r'^  tag:[ \t]*(?:"([A-Za-z0-9_.-]*)"|\'([A-Za-z0-9_.-]*)\'|([A-Za-z0-9_.-]+))[ \t]*$',
                            image, re.MULTILINE))
    if len(re.findall(r'^  tag:', image, re.MULTILINE)) != len(tags) or len(tags) > 1:
        raise ValueError(f'Expected at most one scalar image.tag in {relative}')
    previous = {'tag': next((value for value in tags[0].groups() if value is not None), None) if tags else None,
                'digest': digests[0].group(1)}
    # Work on lines so insertion cannot accidentally shift the digest replacement span.
    lines = image.splitlines(keepends=True)
    updated = []
    for line in lines:
        if line.startswith('  digest:'):
            updated.append('  digest: ' + digest + '\n')
            if not tags:
                updated.append('  tag: ' + tag + '\n')
        elif line.startswith('  tag:'):
            updated.append(line if previous['tag'] == tag else '  tag: ' + tag + '\n')
        else:
            updated.append(line)
    return source[:block.start()] + ''.join(updated) + source[block.end():], previous


def prepare_release(cloud_repo, digest, source_commit, cloud_commit, output_dir, image_tag, source_branch='main'):
    contract = contract_for_branch(source_branch)
    image_digest(digest)
    revision(source_commit)
    revision(cloud_commit)
    validate_image_tag(source_branch, source_commit, image_tag)
    patch, changed = [], []
    for relative in contract.paths:
        source = (cloud_repo / relative).read_text(encoding='utf-8')
        updated, _ = replace_image(source, image_tag, digest, relative)
        if source != updated:
            changed.append(relative)
            patch.extend(difflib.unified_diff(source.splitlines(True), updated.splitlines(True),
                         fromfile='a/' + relative, tofile='b/' + relative))
    metadata = {'status': 'prepared-not-deployed', 'source_repository': SOURCE_REPO,
                'source_branch': source_branch, 'environment': contract.environment, 'source_commit': source_commit,
                'image': IMAGE + '@' + digest, 'image_tag': image_tag, 'source_image_tag': source_commit,
                'image_digest': digest, 'gitops_repository': CLOUD_REPO, 'gitops_branch': 'main',
                'gitops_base_commit': cloud_commit, 'values_files': list(contract.paths), 'changed_values_files': changed}
    # Validate all six files before creating any output.
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / 'cloud-image-digest.patch').write_text(''.join(patch), encoding='utf-8')
    (output_dir / 'production-release.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cloud-repo', type=Path, required=True)
    parser.add_argument('--digest', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--source-branch', required=True, choices=['main', 'dev'])
    parser.add_argument('--cloud-commit', required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    version = parser.add_mutually_exclusive_group(required=True)
    version.add_argument('--image-tag', help='Preview only; never creates or publishes a release')
    version.add_argument('--version-file', type=Path)
    args = parser.parse_args()
    try:
        from resolve_image_version import validate_version
        tag = validate_version(json.loads(args.version_file.read_text()), args.source_branch, args.source_commit) if args.version_file else args.image_tag
        result = prepare_release(args.cloud_repo, args.digest, args.source_commit, args.cloud_commit,
                                 args.output_dir, tag, args.source_branch)
    except (OSError, ValueError, KeyError) as failure:
        parser.exit(1, f'{failure}\n')
    print(f"Prepared {len(result['changed_values_files'])} BE tag/digest updates; {result['environment']} is not deployed.")


if __name__ == '__main__':
    main()
