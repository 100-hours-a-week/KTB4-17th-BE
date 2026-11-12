#!/usr/bin/env python3
"""Attach a version tag to an already published SHA manifest using host AWS CLI."""
import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from deployment_contract import ACCOUNT, REGION, IMAGE, REPOSITORY, contract_for_branch, image_digest, validate_image_tag
from resolve_image_version import validate_version


class AWSFailure(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__('AWS CLI request failed: ' + code)


class AWSCLI:
    def request(self, service, operation, **args):
        command = ['aws', '--region', REGION, '--no-cli-pager', service, operation,
                   '--output', 'json', '--cli-connect-timeout', '5', '--cli-read-timeout', '20']
        if args:
            command += ['--cli-input-json', json.dumps(args)]
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=40)
        except (OSError, subprocess.SubprocessError):
            raise AWSFailure('NetworkOrProcessError') from None
        if result.returncode:
            match = re.search(r'\(([A-Za-z][A-Za-z0-9]+)\)', result.stderr)
            raise AWSFailure(match.group(1) if match else 'CLIError')
        try:
            return json.loads(result.stdout)
        except ValueError:
            raise AWSFailure('InvalidResponse') from None


def repository_details(aws):
    if aws.request('sts', 'get-caller-identity').get('Account') != ACCOUNT:
        raise ValueError('Unexpected AWS account')
    repositories = aws.request('ecr', 'describe-repositories', registryId=ACCOUNT,
                               repositoryNames=[REPOSITORY]).get('repositories', [])
    if (len(repositories) != 1 or repositories[0].get('registryId') != ACCOUNT
            or repositories[0].get('repositoryName') != REPOSITORY or repositories[0].get('repositoryUri') != IMAGE):
        raise ValueError('Unexpected ECR repository/account')
    return repositories[0]


def require_immutable(repository, tags):
    mode = repository.get('imageTagMutability')
    filters = repository.get('imageTagMutabilityExclusionFilters', [])
    for tag in tags:
        if any(item.get('filterType') != 'WILDCARD' for item in filters):
            raise ValueError('Unknown ECR tag mutability filter')
        excluded = any(fnmatch.fnmatchcase(tag, item['filter']) for item in filters)
        immutable = mode == 'IMMUTABLE' or mode == 'IMMUTABLE_WITH_EXCLUSION' and not excluded
        immutable = immutable or mode == 'MUTABLE_WITH_EXCLUSION' and excluded
        if not immutable:
            raise ValueError('ECR SHA/version tags must be immutable before publication; no settings were changed')


def digest_for_tag(aws, tag):
    try:
        payload = aws.request('ecr', 'describe-images', registryId=ACCOUNT, repositoryName=REPOSITORY,
                              imageIds=[{'imageTag': tag}])
    except AWSFailure as failure:
        if failure.code == 'ImageNotFoundException':
            return None
        raise
    details = payload.get('imageDetails', [])
    if (len(details) != 1 or details[0].get('registryId') != ACCOUNT
            or details[0].get('repositoryName') != REPOSITORY):
        raise ValueError('Unexpected ECR image lookup response')
    return image_digest(details[0].get('imageDigest'))


def tag_image(aws, branch, source_commit, tag, digest):
    contract = contract_for_branch(branch)
    validate_image_tag(branch, source_commit, tag)
    image_digest(digest)
    repository = repository_details(aws)
    if digest_for_tag(aws, source_commit) != digest:
        raise ValueError('ECR SHA tag does not match the validated digest')
    current = digest_for_tag(aws, tag)
    if current is not None and current != digest:
        raise ValueError('ECR version tag already points to a different digest')
    if current is None:
        # PutImage has no conditional create on mutable repositories. Immutability closes the check/write race.
        require_immutable(repository, [source_commit, tag])
        payload = aws.request('ecr', 'batch-get-image', registryId=ACCOUNT, repositoryName=REPOSITORY,
                              imageIds=[{'imageDigest': digest}])
        images = payload.get('images', [])
        if payload.get('failures') or len(images) != 1 or images[0].get('imageId', {}).get('imageDigest') != digest:
            raise ValueError('ECR did not return the validated SHA image manifest')
        manifest = images[0]['imageManifest']
        if 'sha256:' + hashlib.sha256(manifest.encode()).hexdigest() != digest:
            raise ValueError('ECR image manifest digest mismatch')
        options = {'registryId': ACCOUNT, 'repositoryName': REPOSITORY, 'imageTag': tag,
                   'imageDigest': digest, 'imageManifest': manifest}
        if images[0].get('imageManifestMediaType'):
            options['imageManifestMediaType'] = images[0]['imageManifestMediaType']
        try:
            aws.request('ecr', 'put-image', **options)
        except AWSFailure as failure:
            if failure.code not in ('ImageAlreadyExistsException', 'ImageTagAlreadyExistsException'):
                raise
    # Both names must still resolve to the exact digest, even after a racing idempotent PutImage.
    if digest_for_tag(aws, tag) != digest:
        raise ValueError('ECR version tag points to a different digest after publication')
    if digest_for_tag(aws, source_commit) != digest:
        raise ValueError('ECR SHA tag changed during publication')
    return {'status': 'ecr-tags-verified', 'source_repository': '100-hours-a-week/KTB4-17th-BE',
            'source_branch': branch, 'environment': contract.environment, 'source_commit': source_commit,
            'source_image_tag': source_commit, 'image_tag': tag, 'image_digest': digest, 'image': IMAGE + '@' + digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-branch', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--version-file', type=Path, required=True)
    parser.add_argument('--digest-file', type=Path)
    parser.add_argument('--output-file', type=Path, default=Path('release/ecr-image.json'))
    parser.add_argument('--check-repository', action='store_true', help='Read-only account/repository/immutability validation')
    args = parser.parse_args()
    try:
        metadata = json.loads(args.version_file.read_text())
        tag = validate_version(metadata, args.source_branch, args.source_commit)
        aws = AWSCLI()
        if args.check_repository:
            require_immutable(repository_details(aws), [args.source_commit, tag])
            print('ECR account, repository, and immutable SHA/version tags verified; no writes performed.')
            return
        if not args.digest_file:
            raise ValueError('--digest-file is required')
        result = tag_image(aws, args.source_branch, args.source_commit, tag, args.digest_file.read_text().strip())
        result.update({key: value for key, value in metadata.items() if key.startswith(('git_release', 'release_'))})
        args.output_file.parent.mkdir(parents=True, exist_ok=True)
        args.output_file.write_text(json.dumps(result, indent=2) + '\n')
        print(result['status'] + ': ' + tag + ' -> ' + result['image_digest'])
    except (OSError, ValueError, KeyError, AWSFailure) as failure:
        parser.exit(1, f'{failure}\n')


if __name__ == '__main__':
    main()
