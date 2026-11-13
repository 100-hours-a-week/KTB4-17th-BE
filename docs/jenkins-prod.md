# BE Jenkins staging/prod CI/CD — EKS / Argo CD

`dev`는 staging, `main`은 prod로 배포한다. 기존 `ci/backend` Multibranch job, `linux && docker` label, 전체 executor 1개를 재사용한다. Jenkins는 CI 후 ECR 이미지에 태그를 연결하고 CLOUD/main의 BE values 여섯 개를 한 commit으로 갱신한다. Argo CD가 적용하고 Jenkins가 배포 상태를 조회한다.

## 브랜치와 버전 계약

| 소스 | 환경 / 대상 EKS | SHA 이미지 태그 | 추가 ECR 태그 및 CLOUD `image.tag` |
| --- | --- | --- | --- |
| `dev` | staging / `star-EKS-v3-staging` | 전체 source SHA | `dev-<전체 source SHA>` |
| `main`, 정식 릴리스 있음 | prod / `star-EKS-v3` | 전체 source SHA | 같은 commit의 기존 Git 릴리스 태그, 예: `v3.1.0` |
| `main`, workflow 성공·릴리스 없음 | prod / `star-EKS-v3` | 전체 source SHA | `sha-<전체 source SHA>` |

Jenkins가 버전을 증가시키거나 Git 태그를 발급하지 않는다. `.github/workflows/release.yml`과 `release.config.cjs`의 semantic-release를 그대로 사용한다. 기존 BE 버전 이력과 minor/patch/major 판단을 유지한다. merge 메시지 하나나 작업 브랜치 이름으로 버전을 추측하지 않는다.

`ci/resolve_image_version.py`는 정확한 main SHA의 push release workflow를 최대 600초 기다린다. 동일 실행 attempt의 `Semantic Release` job과 step이 실제 성공했는지도 확인한다. Git ref가 lightweight/annotated인 경우 모두 실제 commit까지 해석한다. 동일 SHA의 정식 태그가 하나일 때만 사용하고, 성공했으나 해당 태그가 없을 때만 SHA 태그를 선택한다. 실패·취소·skip·조회 오류·시간 초과·여러 태그의 모호함·오래된 소스 SHA는 중단하며 과거 버전을 재사용하지 않는다. Jenkins stage에도 12분 제한이 있다.

이미지를 버전 태그용으로 다시 빌드하지 않는다. 검증한 `star23/be:<SHA>`의 manifest를 ECR에서 읽어 같은 digest로 추가 태그를 연결한다. `ci/tag_ecr_image.py`는 계정·repository·SHA 태그·manifest hash·추가 태그의 digest를 확인한다. 같은 tag/digest 재실행은 조회만 수행하고, 다른 digest의 기존 태그는 덮어쓰지 않는다. 조회 오류를 이미지 부재로 처리하지 않는다.

ECR `PutImage`에는 mutable 태그의 조건부 생성이 없으므로, 신규 게시 전에 SHA와 버전 태그에 유효한 **immutability**를 검사한다. `IMMUTABLE` 또는 해당 태그를 보호하는 exclusion 설정이 필요하다. mutable이면 실패하며 코드가 저장소 설정을 변경하지 않는다. 이 검사는 조회 후 게시 사이에 다른 게시자가 태그를 차지하는 경합도 막기 위한 조건이다.

배포 이미지 선택은 계속 `repository@digest`다. CLOUD chart는 `image.tag`를 `app.kubernetes.io/version` label에 사용한다. digest를 제거하거나 `repository:tag@digest` 형태로 차트를 바꾸지 않는다.

## 환경별 고정 대상

차트: `manifest/helm/star23-service`, GitOps 저장소: `100-hours-a-week/KTB4-17th-CLOUD`, 브랜치: `main`.

| 역할 | 앱 namespace | prod Application / values | staging Application / values |
| --- | --- | --- | --- |
| auth-api | auth | `auth-api` / `values/auth-api.yaml` | `auth-api` / `values/staging/auth-api.yaml` |
| user-api | user | `user-api` / `values/user-api.yaml` | `user-api` / `values/staging/user-api.yaml` |
| onboarding-api | user | `onboarding-api` / `values/onboarding-api.yaml` | `onboarding-api` / `values/staging/onboarding-api.yaml` |
| chat-api | chat | `chat-api` / `values/chat-api.yaml` | `chat-api` / `values/staging/chat-api.yaml` |
| chat-socket | chat | `chat-socket` / `values/chat-socket.yaml` | `chat-socket` / `values/staging/chat-socket.yaml` |
| recommend-api | recommend | `recommend-api` / `values/recommend-api.yaml` | `recommend-api` / `values/staging/recommend-api.yaml` |

values는 위 차트 기준 상대 경로다. 두 클러스터는 각자의 기존 Argo CD와 `star23-apps` AppProject를 사용한다. observer도 dev는 `star-EKS-v3-staging`, main은 `star-EKS-v3`의 `argocd` Application CRD를 조회한다. Application과 Helm release 이름은 환경마다 같으며 클러스터와 values 경로로 분리한다.

- prod 목적지: `https://kubernetes.default.svc`
- staging 목적지: staging Argo CD의 로컬 `https://kubernetes.default.svc`
- staging ARN: `arn:aws:eks:ap-northeast-2:975779326240:cluster/star-EKS-v3-staging`

`ci/deployment_contract.py`가 브랜치별 경로·Application·클러스터 계약을 고정한다. 환경 문자열 파라미터는 없다. feature/fork/PR job, `CHANGE_ID`, dev에서 prod 배포 요청, main에서 staging 요청은 거절한다. staging 파일이 없으면 실패하며 prod 파일로 대체하지 않는다.

## CI와 GitOps 동작

1. `checkout scm` 후 BE repository와 전체 source SHA를 확인한다. CLOUD checkout이 소스 SHA를 덮어쓰지 않도록 `SOURCE_COMMIT`을 보존한다.
2. 배포 요청이면 소스 최신 tip, CLOUD 쓰기/읽기 credential, 여섯 values와 Argo 설정을 사전 조회한다.
3. Gitleaks v8.30.1, `unitTest`, `apiTest`, `bootJar`, Dockerfile의 `linux/amd64` 빌드를 실행한다. Java 25는 빌드 컨테이너에서 준비하며 Docker Pipeline 플러그인은 필요 없다. 사용자가 제거한 Spotless 검사는 재도입하지 않는다.
4. source 버전을 확정하고 ECR SHA 이미지를 게시하거나 기존 digest를 재사용한다. AWS CLI는 EC2 호스트의 instance profile로 실행한다. Docker bridge에 AWS 토큰이나 IMDS 접근을 주지 않는다.
5. 같은 manifest에 추가 태그를 연결하고 SHA/버전 태그의 동일 digest를 조회로 검증한다.
6. `ci/publish_prod_release.py --execute`가 버전 결과를 다시 조회하고 검증된 ECR 산출물과 대조한다. 해당 환경의 여섯 파일에서 `image.tag`와 `image.digest`만 같은 CLOUD commit으로 변경한다. 기존 values에 tag가 없으면 image 블록에 추가한다. 다른 repository·환경·역할·시크릿은 유지한다.
7. CLOUD/main을 non-force로 갱신한다. 최대 세 번 새 HEAD로 재검토하며 다른 환경·AI 변경은 보존한다. 자신의 tag 또는 digest가 다른 릴리스로 바뀌면 중단한다. 두 필드 모두 같을 때만 commit 없이 멱등 처리한다. source tip은 읽기 직후와 **CLOUD ref 갱신 직전**에도 검사한다. 보호 브랜치 거절은 실패이며 정책 우회나 수동 CLOUD PR로 대체하지 않는다.
8. `ci/watch_prod_release.py`가 최대 15분 여섯 앱을 조회한다. 현재 spec과 `status.sync.comparedTo`의 source/destination이 일치하고 삭제 중이 아니어야 한다. repo·main·chart·values·project·대상 클러스터·namespace·자동 sync를 검증한다.
9. 여섯 앱 모두 **동일한 CLOUD revision**, `Synced / Healthy`, 의도한 digest만 실행 중이어야 한다. revision은 게시 commit 또는 그 후손이며 해당 revision의 tag/digest와 현재 CLOUD/main의 여섯 필드가 모두 기대값이어야 한다. 이전/혼합 이미지, superseded release, 현재 revision의 sync 실패, Degraded, timeout은 성공으로 판정하지 않는다.

observer는 Application 조회만 한다. 클러스터 변경·sync 명령·직접 kubectl 배포는 하지 않는다. Argo Health/이미지 정보를 이용한 검증이며 실제 로그인·API·채팅 smoke test는 배포 이후 별도 확인한다.

## Jenkins 최초 연결과 한 번의 활성화

호스트에 Bash·Git·Docker·Python 3.9 이상·AWS CLI, Pipeline·Git·GitHub Branch Source·Credentials Binding·JUnit 플러그인이 필요하다. 현재 private EC2는 `i-068114cc973f72fdc`, private IP `10.1.64.105`다.

| credential ID | 설치 범위 / 권한 |
| --- | --- |
| `github-app-star23` | BE Contents/Metadata read, **Actions read**. checkout·branch/tag·workflow runs/jobs 조회 |
| `github-app-star23-gitops` | CLOUD에 한정한 Contents write 및 Metadata read. Git tree/commit/ref 갱신 |
| `github-app-star23-cloud-read` | CLOUD Contents/Metadata read. observer와 수동 preview checkout용 별도 읽기 credential |

GitHub App은 접근할 저장소에 설치해야 한다. `usernamePassword` binding이 발급한 설치 토큰을 사용하며 private key·토큰을 코드·로그·산출물에 저장하지 않는다. CLOUD 읽기 credential ID는 `CLOUD_READ_CREDENTIALS_ID`로 지정할 수 있지만 쓰기 credential과 달라야 하며 observer 사전 검사에서 read-only 접근을 확인한다. GitOps 쓰기는 `github-app-star23-gitops`만 허용한다. App의 설치 범위·accessible repositories와 발급 토큰 권한도 함께 맞춰야 한다.

CLOUD/main의 ruleset/branch protection이 해당 App의 직접 non-force 갱신을 허용해야 한다. 허용되지 않으면 연결 차단 항목으로 보고하고 정책 변경은 CLOUD 담당이 결정한다.

Jenkins 역할에는 ECR 조회/게시 권한과 아래 조회 권한이 필요하다. 제안 파일을 커밋하는 것만으로 IAM·EKS에 적용되지는 않는다.

- ECR `star23/be`: `DescribeRepositories`, `DescribeImages`, `BatchGetImage`, Docker push의 `BatchCheckLayerAvailability`, `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`, `PutImage`; 로그인 `GetAuthorizationToken`.
- `ci/jenkins-eks-observer-policy.json`: 두 환경 제어 클러스터의 `eks:DescribeCluster`만 허용하는 제안 정책.
- 각 클러스터 access entry에 Jenkins 역할과 `star23-jenkins-observers` 그룹 연결 필요.
- `ci/jenkins-argocd-observer.yaml`: 각 클러스터의 `argocd`에서 기존 BE Application 6개에 대한 `get`만 제안한다. Secret 조회·앱 수정·sync 권한은 없다.
- 호스트에서 제어 EKS endpoint 443 접근 필요. 각 Argo CD가 자기 클러스터에 배포하며 Jenkins는 조회만 한다.

| 설정 | 초기값 | 용도 |
| --- | --- | --- |
| `PUSH_IMAGE` | false | CI 후 SHA 및 버전 태그 게시·검증 |
| `PREPARE_PROD_RELEASE` | false | main에서 게시 이미지의 검토용 patch 생성, CLOUD 변경 없음 |
| `DEPLOY_PRODUCTION` | false | main 최초 배포 검증 요청 |
| `DEPLOY_STAGING` | false | dev 최초 배포 검증 요청 |
| `CLOUD_READ_CREDENTIALS_ID` | github-app-star23-cloud-read | CLOUD 읽기 credential |
| `GITOPS_CREDENTIALS_ID` | github-app-star23-gitops | 고정 CLOUD 쓰기 credential |
| `STAR23_AUTO_DEPLOY_PROD` | 미설정 = false | main 자동 CD 활성화 |
| `STAR23_AUTO_DEPLOY_STAGING` | 미설정 = false | dev 자동 CD 활성화 |

자동 flag는 Jenkins **Manage Jenkins → System → Global properties → Environment variables**에 설정한다. credentials·ECR·CLOUD·Argo 준비 후 해당 브랜치에서 최초 배포를 별도로 승인받아 검증하고, 성공한 환경만 flag를 `true`로 한 번 켠다. 이후에는 dev/main push마다 배포 체크박스를 누르지 않아도 해당 환경 CD가 실행된다. 배포 요청은 이미지 게시를 포함한다.

자동 flag는 수동 build에도 적용된다. CI/preview만 수행하려면 해당 flag를 먼저 false로 바꿔야 한다. 두 flag와 배포/게시 파라미터가 모두 꺼져 있으면 이미지 빌드까지만 수행한다. Jenkinsfile은 각 브랜치에 병합한 뒤 기존 job을 scan한다. 동일 브랜치의 중복 job이나 새 VM을 만들지 않는다.

## 기존 Actions 전환과 CLOUD 준비 항목

ECS `prod-cd.yml` 삭제와 release workflow의 ECS 호출 제거는 기존 main 변경을 유지한다. GitHub Actions semantic-release는 버전 발급만 계속 담당한다. 기존 BE GitHub CI도 유지한다. DEV EC2 `dev-cd.yml`의 이미지 게시·배포는 코드의 `if: ${{ false }}`와 GitHub workflow의 `disabled_manually`로 중지돼 있다. staging 추가 후에도 이 EC2/ECR 경로를 다시 활성화하지 않는다. dev에 Jenkins 후속 변경을 병합할 때 중지 조건도 유지해 이중 게시·배포를 막는다.

CLOUD 담당이 준비해야 할 항목:

- `manifest/helm/star23-service/values/staging/{auth-api,user-api,onboarding-api,chat-api,chat-socket,recommend-api}.yaml` 여섯 파일. staging DB·messaging·시크릿·Ingress·환경 설정과 초기 image digest/tag 필요.
- staging의 기존 Argo CD에 `argocd/staging`을 등록한다. 로컬 `star23-apps`와 기존 이름의 Application을 유지하고 source는 CLOUD/main + staging values를 사용한다. prod Argo CD에는 `argocd` 루트만 등록한다.
- staging 앱 namespace와 workload, 외부 시크릿 연결. `team17-v2`의 로컬 조회 성공은 Jenkins/Argo 권한을 대신하지 않는다.
- DB `10.2.21.10`, messaging `10.2.21.20`의 포트·DB 이름·시크릿 내부 키는 추측하지 않는다. prod 주소나 `/star23/prod/` 값을 staging 대체값으로 사용하지 않는다.

## 산출물과 실제 배포 확인

`release/`에 다음 대응이 남는다.

```text
SOURCE_COMMIT
 → image-version.json (dev-SHA / Git release tag / sha-SHA, workflow ID/attempt)
 → ecr-image.json (SHA tag + version tag → 동일 image_digest)
 → deployment.json + cloud-image-digest.patch (환경, CLOUD commit, 이전/새 tag+digest)
 → argocd-status.json (환경, tag, digest, 여섯 Application의 동일 revision / Synced / Healthy)
```

`image-digest.txt`, `image-uri.txt`도 유지한다. `cloud-image-digest.patch`는 기존 이름을 유지하지만 tag와 digest 두 필드의 변경을 담는다. 수동 preview의 `production-release.json`은 미배포 산출물이다.

성공 증거는 `dev SHA → Jenkins build URL → ECR tag/digest → CLOUD commit → staging 여섯 앱의 동일 revision Synced/Healthy`다. 동시에 prod values·Application·실행 digest가 유지됐는지 비교한다. CI 성공이나 Argo의 기존 Healthy 상태만으로 배포 완료를 판단하지 않는다. 현재 stage가 SKIPPED이면 실제 배포 증거가 아니다.

게시/확인 실패 시 가능한 `build/reports/**`, JUnit XML, JAR, `release/**`를 보관한다. 현재 build의 workspace·컨테이너·로컬 이미지 태그만 정리하며 공유 cache를 prune하지 않는다. GitOps 게시 후 실패했다고 CLOUD를 자동 되돌리거나 클러스터를 직접 변경하지 않는다. ref 요청이 네트워크 오류면 후보 commit이 이미 반영됐는지 먼저 확인한다. rollback은 이전 tag/digest로 되돌리는 별도 GitOps 변경이다.

## 2026-11-12 검증 기록과 한계

이번 staging/version 후속 작업은 main의 기존 prod 변경을 기준으로 별도 worktree와 CI 전용 검증 사본에서 수행했다. 원본 작업 폴더의 미추적 SQS/Valkey 앱 파일은 포함하지 않았다.

- 로컬 Python 행동 테스트 **81개 통과**: 기존 prod 회귀, staging 분리·파일 부재, 최신 source/ref 경합, 허용 파일/필드, tag/digest 동시 변경·충돌·멱등성, workflow 실패/무릴리스/태그 commit 확인, ECR retag/권한/overwrite 거절, Argo 현재 관찰 spec·삭제·동일 revision 검사.
- 실제 controller의 Declarative linter: `Jenkinsfile successfully validated.`
- Python 3.9 구문 14개 파일·Bash 블록 12개·IAM JSON·RBAC 12개 get-only 대상 파싱·기존 Actions 3개 actionlint·CI 사본 Gitleaks 검사 통과.
- 실제 main `e6f91c8a7c5996a4be4cf11a603b558eeb6d30a5`에 대해 [release run 43144094902](https://github.com/100-hours-a-week/KTB4-17th-BE/actions/runs/43144094902) 성공/무릴리스를 읽어 `sha-e6f91c8a7c5996a4be4cf11a603b558eeb6d30a5` 선택을 확인했다. 로컬 GitHub CLI 읽기 전송을 사용했으며 Jenkins App 권한 확인을 대신하지 않는다. 로컬 Python의 직접 GitHub HTTP 연결은 실패했고 보안 검증을 완화하지 않았다.
- 기존 executor에서 게시 없는 `ci/backend-contract-validation` **#3 SUCCESS**, Python **3.9.25**, 보조 도구 테스트 **81개 통과**. CI 전용 사본을 업로드한 검사이며 앱 branch build나 실제 이미지 게시를 대신하지 않는다. #1은 테스트 73개 통과 후 검증 wrapper 줄바꿈 오류로 실패했다. wrapper 수정 후 #2에서 77개, 최종 #3에서 CLI 경계 검사 포함 81개를 통과했다.
- Jenkins `ci/backend` main/dev job과 executor 1개 확인. 전역·ci·backend context credential ID 목록은 모두 비어 있었으며 자동 flag 두 개는 미설정이다.
- Jenkins instance role `star23-ci-controller`로 AWS 계정 확인 성공. ECR `DescribeRepositories`와 제어 EKS `DescribeCluster`는 **AccessDeniedException**. ECR manifest/tag/immutability 실제 조회·게시와 Jenkins 역할의 Argo 조회는 아직 검증할 수 없다.
- 위 기록은 2026-11-12 시점이다. 2026-11-13에는 실제 staging의 독립 Argo CD와 12개 기존 앱을 확인했으며, observer 계약을 각 환경 로컬 제어로 수정했다. 실제 활성화·배포 결과는 작업 당시 Jenkins 산출물로 확인한다.
- 기존 단계에서 기준 앱 소스 unit/API 1,822개와 JAR/amd64 이미지 빌드를 검증한 기록은 유지한다. 이번에는 앱 소스를 변경하거나 그 전체 검사를 재실행하지 않았다. 원본 미추적 SQS 코드의 SDK 누락 compile 오류는 별도 앱 작업이다.

로컬 실행:

```sh
python3 -B -m unittest discover -s ci/tests -v
```

근거: [GitHub workflow runs/Actions read](https://docs.github.com/en/rest/actions/workflow-runs), [Git refs/Contents read](https://docs.github.com/en/rest/git/refs), [annotated tags](https://docs.github.com/en/rest/git/tags), [ECR manifest retagging](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-retag.html), [Jenkins GitHub App token binding](https://www.jenkins.io/blog/2020/04/16/github-app-authentication/).
