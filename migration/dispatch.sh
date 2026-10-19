#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 1. 실행 설정과 필수 입력 확인
: "${PROD_EC2_INSTANCE_ID:?}" "${IMAGE_URI:?}" "${AWS_REGION:?}"
: "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}" "${GITHUB_STEP_SUMMARY:?}"
for name in CF_DISTRIBUTION_ID CF_EC2_ORIGIN_ID CF_ALB_ORIGIN_ID; do
  [[ -n ${!name:-} && ${!name} != *$'\n'* && ${!name} != *$'\r'* ]] || { echo "Invalid repository variable: $name" >&2; exit 1; }
done
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
APP_DIR=${MIGRATION_APP_DIR:-/home/ubuntu/app}
[[ "$APP_DIR" =~ ^/[A-Za-z0-9_./-]+$ && "$APP_DIR" != *'..'* ]]
[[ "$PROD_EC2_INSTANCE_ID" =~ ^i-[a-f0-9]+$ && "$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+-[0-9]+$ ]]
[[ "$IMAGE_URI" =~ ^[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/[a-zA-Z0-9_./-]+@sha256:[a-f0-9]{64}$ ]]
[[ "$AWS_REGION" =~ ^[a-z0-9-]+$ ]]
UNIT="star23-ws-migration-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
REMOTE="/var/lib/star23-ws-migration/$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT

# 2. SSM 명령 요청과 결과 대기
aws_cli() { aws --region "$AWS_REGION" --no-cli-pager "$@"; }
invoke() {
  local file=$1 id status

  id=$(aws_cli ssm send-command --instance-ids "$PROD_EC2_INSTANCE_ID" --document-name AWS-RunShellScript \
    --timeout-seconds 120 --parameters "file://$file" --query Command.CommandId --output text)

  while :; do
    if aws_cli ssm get-command-invocation --command-id "$id" --instance-id "$PROD_EC2_INSTANCE_ID" \
      --output json > "$WORK/invocation.json" 2>/dev/null; then
      status=$(jq -r .Status "$WORK/invocation.json")
      case "$status" in
        Success) jq -r .StandardOutputContent "$WORK/invocation.json"; return 0;;
        Failed|Cancelled|TimedOut|Cancelling)
          echo "SSM failed: $id ($status)" >&2
          jq -r '.StandardErrorContent // empty' "$WORK/invocation.json" >&2; return 1;;
      esac
    fi
    sleep 3
  done
}

# 3. 스크립트와 실행 설정 묶기
jq -n --arg app "$APP_DIR" --arg image "$IMAGE_URI" '{AWS_REGION:env.AWS_REGION,APP_DIR:$app,IMAGE_URI:$image,
  CF_DISTRIBUTION_ID:env.CF_DISTRIBUTION_ID,CF_EC2_ORIGIN_ID:env.CF_EC2_ORIGIN_ID,CF_ALB_ORIGIN_ID:env.CF_ALB_ORIGIN_ID}' > "$WORK/runtime.json"
tar -czf "$WORK/code.tar.gz" -C "$HERE" migration.sh -C "$WORK" runtime.json
PAYLOAD=$(base64 < "$WORK/code.tar.gz" | tr -d '\n')
WORKER=$(cat <<'SH'
set -eu
exec >> "$1/deploy.log" 2>&1
while IFS= read -r entry; do export "$entry"; done < <(jq -r 'to_entries[] | "\(.key)=\(.value)"' "$1/runtime.json")
exec bash "$1/migration.sh" --directory "$APP_DIR" --image "$IMAGE_URI" --drain-timeout 0
SH
)

# 4. CI 연결이 끊겨도 기존 연결 종료 대기를 이어가도록 systemd에서 실행
jq -n --arg code "$PAYLOAD" --arg remote "$REMOTE" --arg unit "$UNIT" --arg worker "$WORKER" '{executionTimeout:["120"],commands:[
  "set -eu", "umask 077",
  ("if [ \"$(systemctl show " + ($unit+".service"|@sh) + " -p LoadState --value 2>/dev/null)\" = loaded ]; then echo Existing migration unit; exit 0; fi"),
  ("install -d -m 700 " + ($remote|@sh)),
  ("printf %s " + ($code|@sh) + " | base64 --decode | tar -xz -C " + ($remote|@sh)),
  ("systemd-run --unit=" + ($unit|@sh) + " --service-type=exec --remain-after-exit --property=RuntimeMaxSec=infinity --property=TimeoutStartSec=infinity --property=UMask=0077 --property=StandardOutput=null --property=StandardError=null /bin/bash -c " + ($worker|@sh) + " _ " + ($remote|@sh))
]}' > "$WORK/launch.json"
(( $(wc -c < "$WORK/launch.json") < 24000 )) || { echo 'SSM bundle is too large'; exit 1; }
invoke "$WORK/launch.json"
printf '### Migration execution\n- Unit: `%s`\n- Private log: `%s/deploy.log`\n- CI cancellation does not stop the EC2 migration.\n' "$UNIT" "$REMOTE" >> "$GITHUB_STEP_SUMMARY"

# 5. 원격 실행이 끝날 때까지 상태 확인
jq -n --arg unit "$UNIT.service" '{executionTimeout:["60"],commands:[
  ("systemctl show " + ($unit|@sh) + " -p ActiveState -p SubState -p ExecMainStatus -p Result")
]}' > "$WORK/status.json"
while :; do
  state=$(invoke "$WORK/status.json")

  active=$(sed -n 's/^ActiveState=//p' <<< "$state")
  sub=$(sed -n 's/^SubState=//p' <<< "$state")
  rc=$(sed -n 's/^ExecMainStatus=//p' <<< "$state")
  result=$(sed -n 's/^Result=//p' <<< "$state")

  if [[ "$active" == active && "$sub" == exited && "$rc" == 0 && "$result" == success ]]; then
    echo 'Migration complete: CloudFront uses ECS; EC2 Green remains for existing WebSockets.'; exit 0
  fi
  case "$active" in
    failed|inactive) echo "Migration failed; inspect $REMOTE/deploy.log" >&2; exit 1;;
    active|activating) echo "Migration continues on EC2: $active/$sub";;
    *) echo "Unexpected unit state: $state" >&2; exit 1;;
  esac

  sleep 30
done
