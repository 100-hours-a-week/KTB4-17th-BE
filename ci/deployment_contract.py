"""Fixed BE deployment boundaries. Only trusted main/dev jobs select an environment."""
from dataclasses import dataclass
from pathlib import Path
import re

ACCOUNT = '975779326240'
REGION = 'ap-northeast-2'
REGISTRY = ACCOUNT + '.dkr.ecr.' + REGION + '.amazonaws.com'
REPOSITORY = 'star23/be'
IMAGE = REGISTRY + '/' + REPOSITORY
SOURCE_REPO = '100-hours-a-week/KTB4-17th-BE'
CLOUD_REPO = '100-hours-a-week/KTB4-17th-CLOUD'
CHART = 'manifest/helm/star23-service'
VALUES = Path(CHART) / 'values'
ROLES = ('auth-api', 'user-api', 'onboarding-api', 'chat-api', 'chat-socket', 'recommend-api')
NAMESPACES = dict(zip(ROLES, ('auth', 'user', 'user', 'chat', 'chat', 'recommend')))
CONTROL_CLUSTER = 'star-EKS-v3'
CONTROL_CLUSTER_ARN = f'arn:aws:eks:{REGION}:{ACCOUNT}:cluster/{CONTROL_CLUSTER}'
STAGING_SERVER = 'https://FD1D5E0955692E6EA1E41FADBE848111.gr7.ap-northeast-2.eks.amazonaws.com'
SEMVER_TAG = r'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'


@dataclass(frozen=True)
class Contract:
    branch: str
    environment: str
    cluster: str
    server: str
    prefix: str

    @property
    def values(self):
        return VALUES / 'staging' if self.environment == 'staging' else VALUES

    @property
    def paths(self):
        return tuple((self.values / (role + '.yaml')).as_posix() for role in ROLES)

    @property
    def applications(self):
        return tuple(self.prefix + role for role in ROLES)

    @property
    def project(self):
        # CLOUD reuses the existing AppProject for staging and prod.
        return 'star23-apps'


def revision(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{40}', value):
        raise ValueError('Expected a full Git revision')
    return value


def image_digest(value):
    if not isinstance(value, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', value):
        raise ValueError('Invalid image digest')
    return value


def contract_for_branch(branch, change_id=None):
    if change_id or branch not in ('main', 'dev'):
        raise ValueError('Deployment requires a trusted main or dev branch job; PR/fork jobs are excluded')
    if branch == 'dev':
        return Contract('dev', 'staging', 'star-EKS-v3-staging', 'https://kubernetes.default.svc', '')
    return Contract('main', 'prod', CONTROL_CLUSTER, 'https://kubernetes.default.svc', '')


def validate_image_tag(branch, source_commit, tag):
    contract_for_branch(branch)
    revision(source_commit)
    allowed = tag == 'dev-' + source_commit if branch == 'dev' else (
        tag == 'sha-' + source_commit or isinstance(tag, str) and re.fullmatch(SEMVER_TAG, tag))
    if not allowed:
        raise ValueError('Image tag does not match the source branch/commit release contract')
    return tag
