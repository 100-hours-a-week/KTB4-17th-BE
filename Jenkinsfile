// Private EC2 runner: shell Docker only; AWS CLI stays on the agent host.
// GitHub App github-app-star23 is configured on ci/backend, not in this file.
def runGradle(String task) {
    withEnv(["GRADLE_TASK=${task}"]) {
        sh '''#!/bin/bash
set -euo pipefail
docker run --rm --init --platform linux/amd64 \
  --name "$CI_CONTAINER_PREFIX-gradle" \
  --user "$(id -u):$(id -g)" \
  --volume "$WORKSPACE:$WORKSPACE" --workdir "$WORKSPACE" \
  --env HOME=/tmp --env CI=true --env AWS_EC2_METADATA_DISABLED=true \
  --env GRADLE_USER_HOME="$WORKSPACE/.gradle/ci-user-home" \
  eclipse-temurin:25-jdk \
  sh ./gradlew --no-daemon --console=plain "$GRADLE_TASK"
'''
    }
}

pipeline {
    agent { label 'linux && docker' }
    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }
    parameters {
        booleanParam(name: 'PUSH_IMAGE', defaultValue: false,
            description: 'Publish or reuse the checked-out commit image in star23/be after CI succeeds.')
        booleanParam(name: 'PREPARE_PROD_RELEASE', defaultValue: false,
            description: 'main + PUSH_IMAGE only: archive the CLOUD/main tag + digest patch. No Git commit or deployment.')
        booleanParam(name: 'DEPLOY_PRODUCTION', defaultValue: false,
            description: 'main only: publish ECR, update CLOUD/main tag + digest, and wait for EKS Argo CD deployment.')
        booleanParam(name: 'DEPLOY_STAGING', defaultValue: false,
            description: 'dev only: first staging deployment; automatic builds use STAR23_AUTO_DEPLOY_STAGING.')
        string(name: 'CLOUD_READ_CREDENTIALS_ID', defaultValue: 'github-app-star23-cloud-read',
            description: 'CLOUD Contents read credential, separate from the GitOps write credential.')
        string(name: 'GITOPS_CREDENTIALS_ID', defaultValue: 'github-app-star23-gitops',
            description: 'GitHub App credential with Contents write access restricted to CLOUD.')
    }
    environment {
        AWS_REGION = 'ap-northeast-2'
        AWS_DEFAULT_REGION = 'ap-northeast-2'
        AWS_ACCOUNT_ID = '975779326240'
        AWS_PAGER = ''
        ECR_REGISTRY = '975779326240.dkr.ecr.ap-northeast-2.amazonaws.com'
        ECR_REPOSITORY = 'star23/be'
        SOURCE_CREDENTIALS_ID = 'github-app-star23'
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                script {
                    if (!(env.BRANCH_NAME in ['main', 'dev']) || env.CHANGE_ID) {
                        error('This credentialed runner only builds main and dev branch jobs.')
                    }
                    if (params.DEPLOY_PRODUCTION && env.BRANCH_NAME != 'main') {
                        error('DEPLOY_PRODUCTION requires a main branch build.')
                    }
                    if (params.DEPLOY_STAGING && env.BRANCH_NAME != 'dev') {
                        error('DEPLOY_STAGING requires a dev branch build.')
                    }
                    env.DEPLOYMENT_ENVIRONMENT = env.BRANCH_NAME == 'dev' ? 'staging' : 'prod'
                    // Enable each environment once after its first validated deployment.
                    // This persists for automatic main builds without changing parameter defaults.
                    def automaticProd = (env.STAR23_AUTO_DEPLOY_PROD ?: '').trim().equalsIgnoreCase('true')
                    env.PRODUCTION_REQUESTED = (env.BRANCH_NAME == 'main' && (params.DEPLOY_PRODUCTION || automaticProd)).toString()
                    def automaticStaging = (env.STAR23_AUTO_DEPLOY_STAGING ?: '').trim().equalsIgnoreCase('true')
                    env.STAGING_REQUESTED = (env.BRANCH_NAME == 'dev' && (params.DEPLOY_STAGING || automaticStaging)).toString()
                    env.DEPLOYMENT_REQUESTED = (env.PRODUCTION_REQUESTED == 'true' || env.STAGING_REQUESTED == 'true').toString()
                    env.PUBLICATION_REQUESTED = (params.PUSH_IMAGE || env.DEPLOYMENT_REQUESTED == 'true').toString()
                    if (params.GITOPS_CREDENTIALS_ID != 'github-app-star23-gitops') {
                        error('GitOps writes require the CLOUD-only github-app-star23-gitops credential.')
                    }
                    if ((env.DEPLOYMENT_REQUESTED == 'true' || params.PREPARE_PROD_RELEASE) &&
                        (!params.CLOUD_READ_CREDENTIALS_ID?.trim() || params.CLOUD_READ_CREDENTIALS_ID.trim() == params.GITOPS_CREDENTIALS_ID)) {
                        error('CD requires a separate CLOUD read credential.')
                    }
                    if (params.PREPARE_PROD_RELEASE && (env.BRANCH_NAME != 'main' || env.PUBLICATION_REQUESTED != 'true')) {
                        error('PREPARE_PROD_RELEASE requires main and PUSH_IMAGE=true.')
                    }
                    def revision = checkout scm
                    env.GIT_COMMIT = revision.GIT_COMMIT
                    env.SOURCE_COMMIT = revision.GIT_COMMIT
                    if (!(env.GIT_COMMIT ==~ /[0-9a-f]{40}/)) {
                        error('Checkout did not return a full Git commit SHA.')
                    }
                    env.CI_CONTAINER_PREFIX = "be-${env.BUILD_TAG}".toLowerCase().replaceAll('[^a-z0-9_.-]', '-').take(110)
                    env.LOCAL_IMAGE = "star23-be-ci:${env.CI_CONTAINER_PREFIX}"
                    env.ECR_IMAGE = "${env.ECR_REGISTRY}/${env.ECR_REPOSITORY}:${env.GIT_COMMIT}"
                    currentBuild.description = "${env.BRANCH_NAME} ${env.GIT_COMMIT.take(12)}"
                }
                sh '''#!/bin/bash
set -euo pipefail
command -v docker
command -v python3
docker info >/dev/null
test "$(git rev-parse HEAD)" = "$SOURCE_COMMIT"
case "$(git config --get remote.origin.url)" in
  https://github.com/100-hours-a-week/KTB4-17th-BE.git|https://github.com/100-hours-a-week/KTB4-17th-BE|git@github.com:100-hours-a-week/KTB4-17th-BE.git) ;;
  *) echo 'Unexpected source repository' >&2; exit 1 ;;
esac
python3 -B -m unittest discover -s ci/tests -v
'''
            }
        }
        stage('Deployment preflight') {
            when { expression { env.DEPLOYMENT_REQUESTED == 'true' } }
            steps {
                withCredentials([
                    usernamePassword(credentialsId: env.SOURCE_CREDENTIALS_ID,
                        usernameVariable: 'SOURCE_APP_ID', passwordVariable: 'SOURCE_TOKEN'),
                    usernamePassword(credentialsId: params.GITOPS_CREDENTIALS_ID,
                        usernameVariable: 'GITOPS_APP_ID', passwordVariable: 'GITOPS_TOKEN'),
                    usernamePassword(credentialsId: params.CLOUD_READ_CREDENTIALS_ID.trim(),
                        usernameVariable: 'CLOUD_READ_APP_ID', passwordVariable: 'CLOUD_READ_TOKEN')
                ]) {
                    sh '''#!/bin/bash
set -euo pipefail
python3 -B ci/publish_prod_release.py --check-access \
  --source-commit "$SOURCE_COMMIT" --source-branch "$BRANCH_NAME"
test "$CLOUD_READ_TOKEN" != "$GITOPS_TOKEN"
python3 -B ci/watch_prod_release.py --check-access --source-branch "$BRANCH_NAME" \
  --output-file release/argocd-preflight.json
'''
                }
            }
        }
        stage('Secret scan') {
            steps {
                sh '''#!/bin/bash
set -euo pipefail
mkdir -p build/reports
docker run --rm --platform linux/amd64 \
  --name "$CI_CONTAINER_PREFIX-secrets" --user "$(id -u):$(id -g)" \
  --volume "$WORKSPACE:/repo:ro" \
  --volume "$WORKSPACE/build/reports:/reports" \
  ghcr.io/gitleaks/gitleaks:v8.30.1 \
  git /repo --log-opts="--first-parent -1 $GIT_COMMIT" --redact --no-banner \
  --report-format json --report-path /reports/gitleaks.json
'''
            }
        }
        stage('Unit tests') {
            steps { runGradle('unitTest') }
        }
        stage('API tests') {
            steps { runGradle('apiTest') }
        }
        stage('Build application') {
            // Do not clean here: retain the test reports for post actions.
            steps { runGradle('bootJar') }
        }
        stage('Build image') {
            steps {
                sh '''#!/bin/bash
set -euo pipefail
docker build --platform linux/amd64 \
  --label "org.opencontainers.image.revision=$GIT_COMMIT" \
  --file Dockerfile --tag "$LOCAL_IMAGE" .
test "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$LOCAL_IMAGE")" = linux/amd64
'''
            }
        }
        stage('Resolve source image version') {
            when { expression { env.PUBLICATION_REQUESTED == 'true' } }
            options { timeout(time: 12, unit: 'MINUTES') }
            steps {
                withCredentials([usernamePassword(credentialsId: env.SOURCE_CREDENTIALS_ID,
                    usernameVariable: 'SOURCE_APP_ID', passwordVariable: 'SOURCE_TOKEN')]) {
                    sh '''#!/bin/bash
set -euo pipefail
python3 -B ci/resolve_image_version.py --source-branch "$BRANCH_NAME" \
  --source-commit "$SOURCE_COMMIT" --output-file release/image-version.json --timeout-seconds 600
python3 -B ci/tag_ecr_image.py --check-repository --source-branch "$BRANCH_NAME" \
  --source-commit "$SOURCE_COMMIT" --version-file release/image-version.json
'''
                }
            }
        }
        stage('Publish ECR image') {
            when { expression { env.PUBLICATION_REQUESTED == 'true' } }
            steps {
                sh '''#!/bin/bash
set -euo pipefail
# Instance profile authentication: do not move these AWS calls into Docker.
test "$(aws sts get-caller-identity --query Account --output text)" = "$AWS_ACCOUNT_ID"
mkdir -p release
DOCKER_CONFIG=$(mktemp -d)
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
if digest=$(aws ecr describe-images --region "$AWS_REGION" \
  --repository-name "$ECR_REPOSITORY" --image-ids "imageTag=$SOURCE_COMMIT" \
  --query 'imageDetails[0].imageDigest' --output text 2>"$DOCKER_CONFIG/lookup.err"); then
  echo "Reusing existing commit image $ECR_IMAGE"
else
  case "$(cat "$DOCKER_CONFIG/lookup.err")" in
    *'(ImageNotFoundException)'*) ;;
    *) cat "$DOCKER_CONFIG/lookup.err" >&2; exit 1 ;;
  esac
  aws ecr get-login-password --region "$AWS_REGION" |
    docker login --username AWS --password-stdin "$ECR_REGISTRY"
  docker tag "$LOCAL_IMAGE" "$ECR_IMAGE"
  touch .ecr-local-tag-created
  docker push "$ECR_IMAGE"
  digest=$(aws ecr describe-images --region "$AWS_REGION" \
    --repository-name "$ECR_REPOSITORY" --image-ids "imageTag=$SOURCE_COMMIT" \
    --query 'imageDetails[0].imageDigest' --output text)
fi
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]
printf '%s\n' "$digest" > release/image-digest.txt
printf '%s@%s\n' "$ECR_REGISTRY/$ECR_REPOSITORY" "$digest" > release/image-uri.txt
'''
            }
        }
        stage('Attach and verify ECR version tag') {
            when { expression { env.PUBLICATION_REQUESTED == 'true' } }
            steps {
                sh '''#!/bin/bash
set -euo pipefail
python3 -B ci/tag_ecr_image.py --source-branch "$BRANCH_NAME" --source-commit "$SOURCE_COMMIT" \
  --version-file release/image-version.json --digest-file release/image-digest.txt \
  --output-file release/ecr-image.json
'''
            }
        }
        stage('Prepare production GitOps release') {
            when {
                allOf {
                    branch 'main'
                    expression { params.PREPARE_PROD_RELEASE && env.PUBLICATION_REQUESTED == 'true' && env.PRODUCTION_REQUESTED != 'true' }
                }
            }
            steps {
                dir('cloud-source') {
                    script {
                        def remote = [url: 'https://github.com/100-hours-a-week/KTB4-17th-CLOUD.git']
                        if (params.CLOUD_READ_CREDENTIALS_ID?.trim()) {
                            remote.credentialsId = params.CLOUD_READ_CREDENTIALS_ID.trim()
                        }
                        def cloudRevision = checkout([
                            $class: 'GitSCM', branches: [[name: '*/main']],
                            userRemoteConfigs: [remote],
                            extensions: [[$class: 'CloneOption', shallow: true, depth: 1, noTags: true]]
                        ])
                        env.CLOUD_COMMIT = cloudRevision.GIT_COMMIT
                    }
                }
                sh '''#!/bin/bash
set -euo pipefail
python3 ci/prepare_prod_release.py --cloud-repo cloud-source \
  --digest "$(cat release/image-digest.txt)" --source-commit "$SOURCE_COMMIT" \
  --cloud-commit "$CLOUD_COMMIT" --source-branch "$BRANCH_NAME" \
  --version-file release/image-version.json --output-dir release
if [ -s release/cloud-image-digest.patch ]; then
  git -C cloud-source apply --check "$WORKSPACE/release/cloud-image-digest.patch"
fi
'''
                echo 'Production patch is archived for review. CLOUD/main has not been changed; production is not deployed.'
            }
        }
        stage('Publish GitOps release') {
            when { expression { env.DEPLOYMENT_REQUESTED == 'true' } }
            steps {
                withCredentials([
                    usernamePassword(credentialsId: env.SOURCE_CREDENTIALS_ID,
                        usernameVariable: 'SOURCE_APP_ID', passwordVariable: 'SOURCE_TOKEN'),
                    usernamePassword(credentialsId: params.GITOPS_CREDENTIALS_ID,
                        usernameVariable: 'GITOPS_APP_ID', passwordVariable: 'GITOPS_TOKEN')
                ]) {
                    sh '''#!/bin/bash
set -euo pipefail
python3 -B ci/publish_prod_release.py --execute \
  --source-commit "$SOURCE_COMMIT" --source-branch "$BRANCH_NAME" \
  --digest-file release/image-digest.txt --version-file release/image-version.json \
  --ecr-file release/ecr-image.json --output-dir release
'''
                }
            }
        }
        stage('Wait for EKS Argo CD deployment') {
            when { expression { env.DEPLOYMENT_REQUESTED == 'true' } }
            options { timeout(time: 18, unit: 'MINUTES') }
            steps {
                withCredentials([usernamePassword(credentialsId: params.CLOUD_READ_CREDENTIALS_ID.trim(),
                    usernameVariable: 'CLOUD_READ_APP_ID', passwordVariable: 'CLOUD_READ_TOKEN')]) {
                    sh '''#!/bin/bash
set -euo pipefail
python3 -B ci/watch_prod_release.py --source-branch "$BRANCH_NAME" --deployment-file release/deployment.json \
  --output-file release/argocd-status.json --timeout-seconds 900
'''
                }
                script {
                    currentBuild.description = "${env.DEPLOYMENT_ENVIRONMENT} EKS deployed ${env.SOURCE_COMMIT.take(12)}"
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'build/reports/**,build/libs/*.jar,release/**', allowEmptyArchive: true
            junit testResults: 'build/test-results/**/*.xml', allowEmptyResults: true
        }
        cleanup {
            script {
                if (env.CI_CONTAINER_PREFIX) {
                    sh returnStatus: true, script: '''#!/bin/bash
docker rm -f "$CI_CONTAINER_PREFIX-gradle" "$CI_CONTAINER_PREFIX-secrets" >/dev/null 2>&1 || true
if [ -f .ecr-local-tag-created ]; then
  docker image rm "$ECR_IMAGE" >/dev/null 2>&1 || true
fi
docker image rm "$LOCAL_IMAGE" >/dev/null 2>&1 || true
'''
                }
            }
            deleteDir()
        }
    }
}
