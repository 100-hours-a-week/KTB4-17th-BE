#!/usr/bin/env python3
"""Read existing semantic-release results for the exact source SHA; never issue versions."""
import argparse
import json
import os
from pathlib import Path
import re
import time

from deployment_contract import SOURCE_REPO, SEMVER_TAG, contract_for_branch, revision, validate_image_tag


def branch_revision(api, branch):
    contract_for_branch(branch)
    obj = api.request('GET', '/git/ref/heads/' + branch)['object']
    if obj.get('type') != 'commit':
        raise ValueError('Source branch must resolve directly to a commit')
    return revision(obj['sha'])


def release_tags_at(api, source_commit):
    refs = api.request('GET', '/git/matching-refs/tags/v')
    if not isinstance(refs, list):
        raise ValueError('Unexpected Git tag listing')
    matches = []
    for ref in refs:
        name = ref.get('ref', '').removeprefix('refs/tags/')
        if not re.fullmatch(SEMVER_TAG, name):
            continue
        obj, seen = ref['object'], set()
        for _ in range(10):
            sha = revision(obj['sha'])
            if obj['type'] == 'commit':
                if sha == source_commit:
                    matches.append(name)
                break
            if obj['type'] != 'tag' or sha in seen:
                raise ValueError('Release tag does not resolve to a commit')
            seen.add(sha)
            obj = api.request('GET', '/git/tags/' + sha)['object']
        else:
            raise ValueError('Release tag nesting limit exceeded')
    return matches


def resolve_version(api, branch, source_commit, timeout=600, interval=10,
                    clock=time.monotonic, sleep=time.sleep):
    contract = contract_for_branch(branch)
    revision(source_commit)
    if timeout <= 0 or timeout > 900 or interval <= 0:
        raise ValueError('Invalid release polling timeout')
    deadline = clock() + timeout
    while True:
        if branch_revision(api, branch) != source_commit:
            raise ValueError('Release source must be the latest BE ' + branch + ' revision')
        metadata = {'source_repository': SOURCE_REPO, 'source_branch': branch, 'source_commit': source_commit,
                    'environment': contract.environment, 'git_release_tag': None}
        if branch == 'dev':
            return dict(metadata, image_tag='dev-' + source_commit, release_outcome='development')
        payload = api.request('GET', '/actions/workflows/release.yml/runs?event=push&branch=main&head_sha=' +
                              source_commit + '&per_page=100')
        runs = [run for run in payload['workflow_runs'] if run.get('head_sha') == source_commit
                and run.get('head_branch') == 'main' and run.get('event') == 'push'
                and run.get('path') == '.github/workflows/release.yml']
        if runs:
            run = max(runs, key=lambda item: (int(item['id']), int(item.get('run_attempt', 1))))
            if run['status'] == 'completed':
                if run.get('conclusion') != 'success':
                    raise ValueError('Release workflow did not succeed for source SHA: ' + str(run.get('conclusion')))
                run_id = int(run['id'])
                attempt = int(run.get('run_attempt', 1))
                jobs = api.request('GET', f'/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100')
                semantic = [job for job in jobs['jobs'] if job.get('name') == 'Semantic Release']
                if (len(semantic) != 1 or semantic[0].get('conclusion') != 'success'
                        or not any(step.get('name') == 'Semantic Release' and step.get('conclusion') == 'success'
                                   for step in semantic[0].get('steps', []))):
                    raise ValueError('Semantic Release job/step must have actually succeeded')
                tags = release_tags_at(api, source_commit)
                if len(tags) > 1:
                    raise ValueError('Ambiguous release tags for source SHA')
                tag = tags[0] if tags else 'sha-' + source_commit
                validate_image_tag(branch, source_commit, tag)
                return dict(metadata, image_tag=tag, git_release_tag=tags[0] if tags else None,
                            release_outcome='released' if tags else 'no-release',
                            release_workflow_id=run_id, release_workflow_attempt=attempt,
                            release_workflow_url=f'https://github.com/{SOURCE_REPO}/actions/runs/{run_id}')
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError('Release workflow did not complete for source SHA within the polling limit')
        sleep(min(interval, remaining))


def validate_version(metadata, branch, source_commit):
    contract = contract_for_branch(branch)
    if any(metadata.get(key) != value for key, value in {
            'source_repository': SOURCE_REPO, 'source_branch': branch, 'source_commit': source_commit,
            'environment': contract.environment}.items()):
        raise ValueError('Version metadata does not match the validated source/environment')
    tag = validate_image_tag(branch, source_commit, metadata.get('image_tag'))
    release_tag = tag if re.fullmatch(SEMVER_TAG, tag) else None
    if metadata.get('git_release_tag') != release_tag:
        raise ValueError('Version metadata release tag mismatch')
    return tag


def main():
    from publish_prod_release import GitHubAPI, RemoteError
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-branch', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--timeout-seconds', type=int, default=600)
    parser.add_argument('--output-file', type=Path, default=Path('release/image-version.json'))
    args = parser.parse_args()
    try:
        if not os.environ.get('SOURCE_TOKEN'):
            raise ValueError('Source read credential required (Contents read and Actions read)')
        result = resolve_version(GitHubAPI(SOURCE_REPO, os.environ['SOURCE_TOKEN'], read_only=True),
                                 args.source_branch, args.source_commit, args.timeout_seconds)
        args.output_file.parent.mkdir(parents=True, exist_ok=True)
        args.output_file.write_text(json.dumps(result, indent=2) + '\n')
        print(result['release_outcome'] + ': ' + result['image_tag'])
    except (OSError, ValueError, KeyError, RemoteError, TimeoutError) as failure:
        parser.exit(1, f'{failure}\n')


if __name__ == '__main__':
    main()
