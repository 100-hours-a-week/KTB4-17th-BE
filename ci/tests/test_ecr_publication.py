import os
from pathlib import Path
import subprocess
import tempfile
import unittest

JENKINSFILE = Path(__file__).resolve().parents[2] / 'Jenkinsfile'
source = JENKINSFILE.read_text().split("stage('Publish ECR image')", 1)[1]
PUBLISH = source.split("'''", 2)[1]
DIGEST = 'sha256:' + '3' * 64
FAKE = r'''#!/usr/bin/env python3
import os, pathlib, sys
root = pathlib.Path(os.environ['FIXTURE_ROOT'])
args = sys.argv[1:]
tool = pathlib.Path(sys.argv[0]).name
with (root / 'calls').open('a') as output:
    output.write(tool + ' ' + ' '.join(args) + '\n')
mode = os.environ['MODE']
if tool == 'docker':
    if args[0] == 'login': sys.stdin.read()
    sys.exit(0)
if args[:2] == ['sts', 'get-caller-identity']:
    print('000000000000' if mode == 'wrong-account' else '975779326240')
elif args[:2] == ['ecr', 'describe-images']:
    counter = root / 'counter'
    count = int(counter.read_text()) if counter.exists() else 0
    counter.write_text(str(count + 1))
    if mode == 'denied':
        print('An error occurred (AccessDeniedException)', file=sys.stderr); sys.exit(254)
    if mode == 'absent' and count == 0:
        print('An error occurred (ImageNotFoundException)', file=sys.stderr); sys.exit(254)
    print('None' if mode == 'invalid-digest' else 'sha256:' + '3' * 64)
elif args[:2] == ['ecr', 'get-login-password']:
    (root / 'docker-config-path').write_text(os.environ['DOCKER_CONFIG'])
    print('dummy-test-token')
else:
    sys.exit(2)
'''

class PublicationSafetyTest(unittest.TestCase):
    def execute(self, mode):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'bin').mkdir()
        for tool in ('docker', 'aws'):
            path = root / 'bin' / tool
            path.write_text(FAKE)
            path.chmod(0o755)
        environment = dict(os.environ, FIXTURE_ROOT=str(root), MODE=mode,
            PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
            AWS_ACCOUNT_ID='975779326240', AWS_REGION='ap-northeast-2',
            ECR_REPOSITORY='star23/be', ECR_REGISTRY='975779326240.dkr.ecr.ap-northeast-2.amazonaws.com',
            ECR_IMAGE='fixture:image', LOCAL_IMAGE='fixture:local', GIT_COMMIT='a' * 40, SOURCE_COMMIT='a' * 40)
        result = subprocess.run(['bash'], input=PUBLISH, cwd=root,
            env=environment, capture_output=True, text=True)
        return root, result, (root / 'calls').read_text()

    def test_existing_commit_image_is_reused_without_any_docker_publication(self):
        root, result, calls = self.execute('existing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((root / 'release/image-digest.txt').read_text().strip(), DIGEST)
        self.assertNotIn('docker ', calls)

    def test_missing_image_logs_in_pushes_and_cleans_temporary_credentials(self):
        root, result, calls = self.execute('absent')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('docker push fixture:image', calls)
        self.assertTrue((root / '.ecr-local-tag-created').exists())
        self.assertFalse(Path((root / 'docker-config-path').read_text()).exists())
        self.assertEqual((root / 'release/image-digest.txt').read_text().strip(), DIGEST)

    def test_access_denied_never_triggers_a_push(self):
        root, result, calls = self.execute('denied')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('docker ', calls)
        self.assertFalse((root / 'release/image-digest.txt').exists())

    def test_wrong_account_stops_before_ecr_access(self):
        root, result, calls = self.execute('wrong-account')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('aws ecr', calls)
        self.assertNotIn('docker ', calls)

    def test_invalid_registry_digest_is_not_recorded_as_a_release(self):
        root, result, calls = self.execute('invalid-digest')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((root / 'release/image-digest.txt').exists())

if __name__ == '__main__': unittest.main(verbosity=2)
