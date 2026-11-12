import hashlib
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tag_ecr_image import AWSFailure, tag_image
from deployment_contract import ACCOUNT, IMAGE, REPOSITORY

SHA = 'a' * 40
TAG = 'dev-' + SHA
MANIFEST = '{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json"}'
DIGEST = 'sha256:' + hashlib.sha256(MANIFEST.encode()).hexdigest()
OTHER = 'sha256:' + '9' * 64


class ECR:
    def __init__(self):
        self.calls = []
        self.tags = {SHA: DIGEST}
        self.account = ACCOUNT
        self.mutability = 'IMMUTABLE'
        self.lookup_error = None
        self.put_error = None
        self.on_put = None

    def request(self, service, operation, **args):
        self.calls.append((service, operation, args))
        if service == 'sts':
            return {'Account': self.account}
        if operation == 'describe-repositories':
            return {'repositories': [{'registryId': ACCOUNT, 'repositoryName': REPOSITORY,
                    'repositoryUri': IMAGE, 'imageTagMutability': self.mutability}]}
        if operation == 'describe-images':
            if self.lookup_error:
                raise AWSFailure(self.lookup_error)
            tag = args['imageIds'][0]['imageTag']
            if tag not in self.tags:
                raise AWSFailure('ImageNotFoundException')
            return {'imageDetails': [{'registryId': ACCOUNT, 'repositoryName': REPOSITORY, 'imageDigest': self.tags[tag]}]}
        if operation == 'batch-get-image':
            return {'failures': [], 'images': [{'imageId': {'imageDigest': DIGEST}, 'imageManifest': MANIFEST,
                    'imageManifestMediaType': 'application/vnd.docker.distribution.manifest.v2+json'}]}
        if operation == 'put-image':
            if self.on_put:
                self.on_put(self)
            if self.put_error:
                raise AWSFailure(self.put_error)
            self.tags[args['imageTag']] = args['imageDigest']
            return {'image': {'imageId': {'imageDigest': args['imageDigest']}}}
        raise AssertionError((service, operation))


class TagECRImageTest(unittest.TestCase):
    def setUp(self):
        self.ecr = ECR()

    def tag(self):
        return tag_image(self.ecr, 'dev', SHA, TAG, DIGEST)

    def puts(self):
        return [call for call in self.ecr.calls if call[1] == 'put-image']

    def test_attach_existing_manifest_without_build_or_layer_push_and_confirm_both_tags(self):
        result = self.tag()
        self.assertEqual(result['status'], 'ecr-tags-verified')
        self.assertEqual(result['image_tag'], TAG)
        self.assertEqual(result['image_digest'], DIGEST)
        self.assertEqual(self.ecr.tags, {SHA: DIGEST, TAG: DIGEST})
        self.assertEqual(len(self.puts()), 1)
        self.assertEqual(self.puts()[0][2]['imageManifest'], MANIFEST)

    def test_same_tag_and_digest_rerun_is_idempotent(self):
        self.ecr.tags[TAG] = DIGEST
        self.assertEqual(self.tag()['status'], 'ecr-tags-verified')
        self.assertFalse(self.puts())

    def test_same_tag_different_digest_cannot_be_overwritten(self):
        self.ecr.tags[TAG] = OTHER
        with self.assertRaisesRegex(ValueError, 'different digest'):
            self.tag()
        self.assertFalse(self.puts())

    def test_access_denied_and_repository_absence_are_not_tag_absence(self):
        for code in ['AccessDeniedException', 'RepositoryNotFoundException', 'NetworkError']:
            self.ecr.lookup_error = code
            with self.subTest(code=code), self.assertRaises(AWSFailure):
                self.tag()
            self.assertFalse(self.puts())

    def test_wrong_account_stops_before_ecr_and_wrong_sha_digest_stops_before_retag(self):
        self.ecr.account = '000000000000'
        with self.assertRaises(ValueError):
            self.tag()
        self.assertEqual(len(self.ecr.calls), 1)
        self.ecr.account = ACCOUNT
        self.ecr.tags[SHA] = OTHER
        with self.assertRaisesRegex(ValueError, 'SHA tag'):
            self.tag()
        self.assertFalse(self.puts())

    def test_mutable_repository_is_rejected_before_a_new_tag_can_race(self):
        self.ecr.mutability = 'MUTABLE'
        with self.assertRaisesRegex(ValueError, 'immutable'):
            self.tag()
        self.assertFalse(self.puts())

    def test_racing_immutable_put_is_only_idempotent_when_digest_matches(self):
        self.ecr.on_put = lambda ecr: ecr.tags.update({TAG: DIGEST})
        self.ecr.put_error = 'ImageTagAlreadyExistsException'
        self.assertEqual(self.tag()['status'], 'ecr-tags-verified')
        self.ecr.tags.pop(TAG)
        self.ecr.on_put = lambda ecr: ecr.tags.update({TAG: OTHER})
        with self.assertRaisesRegex(ValueError, 'different digest'):
            self.tag()

    def test_invalid_source_tag_or_digest_is_rejected_without_aws_calls(self):
        for sha, tag, digest in [('main', TAG, DIGEST), (SHA, 'latest', DIGEST), (SHA, TAG, 'latest')]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                tag_image(self.ecr, 'dev', sha, tag, digest)
        self.assertFalse(self.ecr.calls)
