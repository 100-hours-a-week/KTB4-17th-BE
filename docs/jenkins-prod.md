# BE Jenkins prod CI/CD — EKS / Argo CD

prod 배포 경로는 `Jenkins → ECR → CLOUD/main → Argo CD → EKS`다. Jenkins가 BE 역할 6개의 `image.digest`를 원자적으로 갱신하고 실제 Argo 배포 상태를 확인한다. ECS는 사용하지 않는다.

이번 코드 검증에서는 ECR 게시·CLOUD 배포 설정 커밋·운영 배포를 실행하지 않는다. BE 코드의 커밋·push 및 PR 생성과 운영 배포는 별도 단계다. 아래 CD 코드는 연결과 활성화 이후 Jenkins 빌드가 실행할 자동화다.

## 코드 변경과 GitHub Actions

- 루트 `Jenkinsfile`: 기존 CI와 이미지 빌드, 운영 연결 사전 검사, ECR 게시, GitOps 갱신, 배포 확인.
- `ci/publish_prod_release.py`: GitHub API로 CLOUD/main의 BE digest 6개를 한 커밋으로 갱신. 기본 실행은 미리보기이며 `--execute`를 지정해야 게시한다.
- `ci/watch_prod_release.py`: EKS API에서 Argo Application 6개를 읽고 배포 완료를 확인한다. 클러스터 변경 API를 호출하지 않는다.
- `ci/prepare_prod_release.py`: 기존 수동 검토용 패치 생성 기능을 유지한다.
- 기존 ECS `.github/workflows/prod-cd.yml`은 삭제하고 `release.yml`의 호출도 제거했다. GitHub Actions의 semantic-release는 버전 생성만 담당하며 기존 BE CI는 유지한다.
- staging 준비 기간에는 `dev-cd.yml`의 이미지 게시·배포 job을 `if: ${{ false }}`로 일시중지한다. Jenkins도 dev에서는 운영 배포를 실행하지 않는다. staging 배포 대상과 검증이 정해진 뒤 재개한다. GitHub workflow 자체의 비활성화는 브랜치 병합 전에도 적용할 수 있으며, 코드의 중지 조건은 해당 변경이 들어간 브랜치부터 적용된다.
  - 2026-11-12 GitHub의 `dev-cd.yml` workflow도 즉시 비활성화했고 `disabled_manually` 상태를 확인했다. staging 구성 후 재개할 때는 코드의 중지 조건과 workflow 비활성화 상태를 모두 확인한다.

## 실행 환경과 파라미터

`ci/backend` Multibranch Pipeline이 `main`, `dev`를 5분 주기로 스캔한다. 루트 Jenkinsfile이 해당 브랜치에 있어야 한다.

Linux EC2에 `linux docker` label, 전체 executor 1개, Bash·Git·Docker·Python 3.9 이상·AWS CLI가 필요하다. Java 25 검사와 Dockerfile 빌드는 `linux/amd64`로 실행한다. Docker Pipeline 플러그인은 필요 없다. Pipeline·Git·GitHub Branch Source·Credentials Binding·JUnit 플러그인을 사용한다.

AWS CLI는 EC2 호스트의 instance profile로 실행한다. 테스트 컨테이너에 AWS 토큰이나 Docker socket을 넘기지 않는다. 현재 단일 VM과 기존 분리 agent 모두 동일한 label·경로·권한 조건에서 사용할 수 있다.

| 설정 | 기본값 | 동작 |
| --- | --- | --- |
| `PUSH_IMAGE` | `false` | CI 통과 후 ECR 게시 또는 기존 SHA 이미지 재사용 |
| `PREPARE_PROD_RELEASE` | `false` | main에서 게시한 digest의 검토용 CLOUD 패치 생성 |
| `DEPLOY_PRODUCTION` | `false` | main에서 ECR 게시·CLOUD 갱신·Argo 배포 확인을 모두 실행 |
| `CLOUD_READ_CREDENTIALS_ID` | 빈 문자열 | 수동 패치 생성용 CLOUD checkout credential |
| `GITOPS_CREDENTIALS_ID` | `github-app-star23-gitops` | CLOUD 설정을 게시할 GitHub App credential |
| Jenkins 환경변수 `STAR23_AUTO_DEPLOY_PROD` | 미설정 | `true`로 켜면 이후 main 빌드마다 자동으로 전체 CD 실행 |

`DEPLOY_PRODUCTION=true`는 ECR 게시도 포함하므로 `PUSH_IMAGE`를 별도로 켤 필요가 없다. dev·PR 빌드는 운영 배포를 실행할 수 없다. 운영 배포 요청 시 현재 BE main tip과 checkout SHA가 일치하는지도 확인한다. 이전 소스의 빌드를 재실행해 새 운영 버전을 덮어쓰지 않는다.

**자동 배포 환경변수가 true이면 main의 수동 빌드에도 적용된다.** 미리보기나 CI만 실행하려면 먼저 해당 환경변수를 false로 바꾼다. 모든 파라미터와 자동 배포 환경변수가 꺼져 있으면 이미지 빌드까지만 실행한다.

## CD가 수행하는 일

1. 소스 읽기 credential, CLOUD 쓰기 접근, 최신 BE main SHA, EKS 읽기 접근과 Argo 앱 연결을 검사한다.
2. Gitleaks, `unitTest`, `apiTest`, `bootJar`, 실제 Dockerfile 빌드를 통과한다. Gitleaks는 첫 부모 대비 merge diff를 포함한다. 요청에 따라 GitHub CI와 Jenkins의 Spotless 실행은 제외하며, Gradle의 formatter 도구 자체는 유지한다.
3. `975779326240.dkr.ecr.ap-northeast-2.amazonaws.com/star23/be:<GIT_COMMIT>`을 게시한다. 같은 SHA 태그가 이미 있으면 기존 digest를 재사용한다. 계정·권한 오류를 이미지 없음으로 처리하지 않는다.
4. CLOUD/main 최신 스냅샷에서 아래 여섯 파일의 `image.digest`만 변경한다.

```text
manifest/helm/star23-service/values/auth-api.yaml
manifest/helm/star23-service/values/user-api.yaml
manifest/helm/star23-service/values/onboarding-api.yaml
manifest/helm/star23-service/values/chat-api.yaml
manifest/helm/star23-service/values/chat-socket.yaml
manifest/helm/star23-service/values/recommend-api.yaml
```

Git tree·commit 한 개를 만들고 main ref를 fast-forward로 갱신한다. force 갱신은 하지 않는다. 그 사이 AI나 다른 CLOUD 설정이 변경되면 새 기준에서 최대 세 번 시도하며, BE digest가 다른 배포로 바뀌었다면 중단한다. branch protection이 쓰기를 거절하면 실패하며 우회하지 않는다.

5. Argo CD가 CLOUD/main 변경을 감지해 EKS에 적용한다.
6. Jenkins는 `star-EKS-v3`의 `argocd` namespace에서 위 Application 6개를 최대 15분 확인한다. 모두 아래 조건을 충족해야 성공한다.

- 저장소·main 브랜치·차트·역할 values·대상 namespace가 기대 설정과 일치하고 auto-sync가 켜져 있음
- 동기화 revision이 게시한 GitOps 커밋 또는 그 후손이며 해당 revision의 BE digest가 기대값과 일치함
- `Synced / Healthy`, 관련 sync 실패 없음, 이전 BE 이미지가 남아 있지 않음
- 현재 CLOUD/main도 해당 배포를 포함하고 BE digest 6개가 여전히 기대값임

이전 이미지가 Healthy인 경우 성공으로 보지 않는다. 새 배포에 의해 이미 대체된 경우, sync 실패·Degraded·타임아웃도 실패로 기록한다. Jenkins는 Argo 상태를 조회하며 실제 앱 변경은 Argo CD가 수행한다. Argo의 Health/이미지 정보에 기반한 확인이며 실제 서비스 요청을 보내는 별도 smoke test는 포함하지 않는다.

## 최초 연결 작업

### 1. GitHub App credentials

Jenkins의 System/Global credentials에 다음을 등록한다. private key와 토큰은 Git이나 채팅에 넣지 않는다.

| ID | 대상과 권한 |
| --- | --- |
| `github-app-star23` | BE 저장소 checkout·main 읽기. 기존 Multibranch source와 같은 ID |
| `github-app-star23-gitops` | 별도 GitHub App을 CLOUD 저장소에만 설치, Contents Read & write |

GitOps credential의 repository access strategy는 **Specify accessible repositories**로 CLOUD를 지정하고, 발급 토큰의 Contents 권한에도 write가 포함되게 설정한다. BE용 credential의 저장소 추론을 그대로 쓰면 CLOUD에 접근하지 못할 수 있다. Jenkins GitHub App credential은 `usernamePassword` binding에서 비밀번호 값으로 단기 설치 토큰을 제공한다.

CLOUD/main의 ruleset·branch protection도 이 자동화 App의 갱신을 허용해야 한다. 필수 PR·서명·체크 정책으로 직접 갱신을 금지하고 있다면 정책에 맞는 별도 PR 방식을 선택해야 한다. 이 코드는 정책을 강제로 우회하지 않는다.

### 2. Jenkins의 EKS 읽기 권한

현재 단일 VM 역할 `star23-ci-controller`에 다음 **조회 전용** IAM policy와 EKS access entry/RBAC가 필요하다. 적용 코드는 준비했으며 이번 작업에서는 권한을 부여하거나 클러스터에 적용하지 않는다.

```sh
aws iam put-role-policy \
  --role-name star23-ci-controller \
  --policy-name observe-prod-argocd \
  --policy-document file://ci/jenkins-eks-observer-policy.json

aws eks create-access-entry \
  --region ap-northeast-2 --cluster-name star-EKS-v3 \
  --principal-arn arn:aws:iam::975779326240:role/star23-ci-controller \
  --type STANDARD --kubernetes-groups star23-jenkins-observers

kubectl apply -f ci/jenkins-argocd-observer.yaml
```

이미 access entry가 있다면 기존 그룹을 확인해 병합한다. 별도 agent 모드에서는 실제 빌드 instance profile 역할에 적용한다.

IAM은 해당 cluster의 `DescribeCluster`만, Kubernetes Role은 Argo Application 6개의 `get`만 허용한다. Secret 조회, 앱 수정, sync, kubectl 배포 권한은 필요 없다. 호스트에서 EKS API endpoint의 443에 연결할 수 있어야 하며, private 경로가 막히면 CI SG에서 EKS control-plane SG로의 해당 통신을 확인한다.

Jenkins 호스트에서 연결만 확인할 때:

```sh
python3 -B ci/watch_prod_release.py --check-access \
  --output-file /tmp/jenkins-argocd-preflight.json
```

### 3. 업로드·첫 실행·자동 배포 활성화

1. 이번 변경을 앱 저장소에 올리고 main에 PR로 병합한다.
2. Jenkins `ci/backend`에서 **Scan Multibranch Pipeline Now**를 실행한다.
3. 처음에는 자동 배포 환경변수와 배포 파라미터를 꺼두고 CI·이미지 빌드를 확인한다.
4. credential과 조회 권한을 준비한 뒤 main에서 `DEPLOY_PRODUCTION=true`로 첫 배포를 검증한다. 이 실행은 실제 ECR 게시와 CLOUD 설정 커밋 및 EKS 배포를 수행한다.
5. 성공한 뒤 Jenkins **Manage Jenkins → System → Global properties → Environment variables**에서 `STAR23_AUTO_DEPLOY_PROD=true`를 설정한다. 이후 main 변경마다 Jenkins 검사·ECR 게시·GitOps 갱신·Argo 확인이 자동 실행된다.

GitHub Actions의 ECS workflow는 제거했으므로 별도 ECS 배포 전환은 필요 없다. Jenkins 자동 배포를 활성화하기 전에는 main에 병합하는 것만으로 EKS 배포가 시작되지는 않는다.

## 보고서·장애 처리

배포 확인 순서:

1. Jenkins `ci/backend/main`의 `Publish production GitOps release`와 `Wait for EKS Argo CD deployment`가 실제 실행됐고 SUCCESS인지 확인한다. 배포 파라미터가 꺼져 해당 단계가 SKIPPED라면 CI 성공만으로 배포 완료가 아니다.
2. build artifact의 `release/deployment.json`에서 `gitops_commit`과 `image_digest`를 확인하고, `release/argocd-status.json`의 status가 `deployed`이며 Application 6개가 모두 ready인지 확인한다.
3. Argo CD UI 또는 아래 읽기 명령으로 여섯 앱의 상태를 확인한다.

```sh
kubectl -n argocd get applications \
  auth-api user-api onboarding-api chat-api chat-socket recommend-api \
  -o custom-columns=NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status,REVISION:.status.sync.revision
```

REVISION은 CLOUD의 GitOps 커밋이며 BE 소스 커밋과 다르다. 단순히 기존 버전이 Synced/Healthy인 것만 확인하지 말고 Jenkins의 게시 커밋과 이미지 확인 결과를 함께 본다. 실제 서비스 확인은 배포 후 기존 prod URL에서 로그인·주요 API·채팅 연결을 확인하는 별도 단계다.

실패 시에도 가능한 범위에서 `build/reports/**`, JUnit XML, JAR와 `release/**`를 보관한다. 이후 해당 빌드의 workspace·임시 컨테이너·로컬 이미지 태그를 정리한다. 공유 Docker cache 전체를 prune하지 않는다.

- `release/image-digest.txt`, `image-uri.txt`: ECR의 실제 digest와 이미지 참조
- `release/deployment.json`: 소스 SHA, GitOps 기준/게시 SHA, 대상 파일, 이전 digest, 게시 상태
- `release/cloud-image-digest.patch`: 실제 게시 대상 digest 변경
- `release/argocd-preflight.json`, `argocd-status.json`: 읽기 연결 검사와 역할별 배포 상태
- 수동 패치 모드의 `production-release.json`: 미리보기 정보

CLOUD 게시 후 배포 확인이 실패해도 자동으로 Git을 되돌리거나 클러스터를 직접 수정하지 않는다. Argo의 자동 재시도와 실제 상태를 확인한다. GitHub ref 요청이 네트워크 오류로 끝난 경우에도 게시됐을 수 있으므로 deployment.json의 후보 커밋과 CLOUD/main을 확인한 뒤 재실행한다. 같은 digest의 재실행은 중복 설정 커밋을 만들지 않는다. 롤백은 CLOUD에서 이전 digest로 되돌리는 별도 GitOps 변경으로 진행한다.

## 검증 기록

- Python 행동 테스트 32개: digest 전용 변경, 여섯 역할 원자적 갱신, 충돌·stale source·보호 브랜치 거절, 배포 완료·실패·이미지 교체·새 배포 추월 방지.
- Bash 블록 10개와 Python/IAM JSON 구문 검사 통과.
- 실제 Jenkins controller에서 Declarative 문법 검사 통과. 필요한 JUnit 플러그인 설치와 active 상태 확인 완료.
- 수정한 backend-ci.yml·release.yml·dev-cd.yml은 공식 actionlint 1.7.7 실행 파일로 검사 통과. Docker 검사 명령이 응답하지 않아 해당 명령을 종료하고 로컬 실행 파일로 대체했다.
- RBAC YAML 로컬 파싱과 조회 전용 범위 검증 통과. kubectl의 OpenAPI를 사용하는 client dry-run은 운영자 인증 오류로 완료되지 않았다. RBAC의 실제 생성·Jenkins 역할 권한은 아직 적용·검증하지 않았다.
- 새 EKS 읽기 코드로 실제 Application 6개의 설정 조회 검증 통과. 이 검사는 로컬 운영자 AWS 자격으로 수행했으며 Jenkins 역할의 권한 검증을 대신하지 않는다.
- 기존 기준 소스의 unit/API 테스트 1,822개, formatter·Gitleaks·JAR·linux/amd64 실제 Dockerfile 빌드는 앞선 구현 단계에서 통과했다. 이번에는 앱 코드를 변경하지 않았다.
- 기존 미추적 SQS 소스까지 포함한 사본은 SQS SDK 의존성 누락으로 compileJava 오류 10개가 있었다. 해당 소스와 build.gradle은 수정하지 않았다.
- CLOUD 설정 게시·새 운영 배포는 실행하지 않았다. 실제 Jenkins CD 전체 실행은 credentials·조회 권한·원격 반영 이후 별도 검증이 필요하다.

로컬 테스트:

```sh
python3 -B -m unittest discover -s ci/tests -v
```

근거: [GitHub Git reference API](https://docs.github.com/en/rest/git/refs), [Argo CD 자동 동기화](https://argo-cd.readthedocs.io/en/stable/user-guide/auto_sync/), [Jenkins GitHub App token binding](https://www.jenkins.io/blog/2020/04/16/github-app-authentication/), [EKS access entries와 RBAC](https://docs.aws.amazon.com/eks/latest/userguide/access-entries.html).
