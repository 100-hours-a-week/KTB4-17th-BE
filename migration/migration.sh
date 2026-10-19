#!/usr/bin/env bash
# EC2 Blue → Green → CloudFront 전환
set +x
set -Eeuo pipefail
export LC_ALL=C
umask 077

# 실행 설정
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
ROOT=/home/ubuntu/app
IMAGE=''
DB_HOST=''
DB_NAME=''
DB_PORT=3306

# 대기·안정화 기준
HEALTH_TIMEOUT=180
DRAIN_TIMEOUT=0
WARMUP_PENDING_MAX=70
WARMUP_OLDEST_MAX=3
WARMUP_STABLE_SECONDS=30
WARMUP_TIMEOUT=180

CHECK_ONLY=0
RESUME_DRAIN=''

# 연결 주소·게이트
BLUE_URL=''
GREEN_URL=''
NGINX_CONFIG=/etc/nginx/nginx.conf
HOST_NGINX_CONFIG=/home/ubuntu/nginx/nginx.conf
APP_NETWORK=app-network
BLUE_SERVICE=''
GREEN_SERVICE=''
GREEN_IMAGE_VAR=''
GATE_PATH=/run/chat-outbox-gate/publish.ready

# Prometheus 지표
WS=chat_websocket_sessions_active
HTTP=chat_http_requests_active
GATE=chat_outbox_start_gate_open
PUBLISHED=chat_outbox_publish_delay_seconds_count
BROKER=chat_broker_available
SNAPSHOT_OK=chat_outbox_snapshot_success
SNAPSHOT_AT=chat_outbox_snapshot_last_success_unixtime
PENDING=chat_outbox_pending
OLDEST=chat_outbox_oldest_pending_age_seconds
FAILED=chat_outbox_failed

# 실행 상태
WORK=''
STATE_FILE=''
RUN_ID=''
SQL_CONTAINER=''
CANDIDATE_ACTIVE=''
CANDIDATE_CONFIG=''
CONFIG_CHANGED=0
RELOAD_ATTEMPTED=0
SWITCHED=0
ERROR_LINE=0
BLUE_NAME=''
GREEN_NAME=''
NGINX_NAME=''
GATE_DIR=''
DB_USERNAME=''
DB_PASSWORD=''

usage() {
  cat <<'HELP'
Usage on the Linux APP host:
  sudo ./migration.sh --image REGISTRY/green:TAG
  sudo ./migration.sh --image REGISTRY/green:TAG --db-host DB_IP --db-name DB_NAME [options]
  sudo ./migration.sh --resume-drain RUN_ID

Pass --image explicitly. If DB options are omitted, use the existing Blue DB_URL.
CloudFront requires CF_DISTRIBUTION_ID, CF_EC2_ORIGIN_ID and CF_ALB_ORIGIN_ID.
Options:
  --directory PATH            Directory containing docker-compose.yml (default: /home/ubuntu/app)
  --db-port N                 Expected MySQL port (default: 3306)
  --health-timeout N          Green readiness timeout in seconds (default: 180)
  --drain-timeout N           Optional Blue drain limit; 0=unlimited (default: 0)
  --warmup-pending-max N      Maximum pending rows during warmup (default: 70)
  --warmup-oldest-max N       Maximum oldest pending age at DB snapshot, seconds (default: 3)
  --warmup-stable-seconds N   Continuous stable duration, minimum 10 (default: 30)
  --warmup-timeout N          Warmup timeout in seconds (default: 180)
  --check-only                Preflight only; does not start Green/Flyway or change DB/nginx
  --help                      Show this help

Order: pull -> start/Flyway -> health -> DB sync/commit -> gate/warmup -> nginx
reload -> poll every 10 seconds -> stop Blue after 3 consecutive zero readings.
Blue connection drain waits indefinitely unless a positive limit is explicitly set.
Then switch CloudFront /api, /api/*, /ws and /ws/* to the configured ALB origin.
The ALB/ECS destination must already be prepared. Existing Green connections remain.
Exit: 0=complete/check passed; 3=Green active, Blue still draining; 1=failed.
HELP
}

# 공통 함수
now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

epoch() {
  date -u +%s
}

monotonic() {
  local uptime rest

  read -r uptime rest < /proc/uptime
  printf '%s\n' "${uptime%%.*}"
}

random_id() {
  od -An -N4 -tx1 /dev/urandom | tr -d ' \n'
}

log() {
  printf '[%s] %s\n' "$(now)" "$*"
}

die() {
  log "실패: $*" >&2
  exit 1
}

# 명령 출력은 임시 파일에 보관
command_to() {
  local output=$1 seconds=$2

  shift 2
  if timeout "$seconds" "$@" > "$output" 2> "$WORK/command.stderr"; then
    return 0
  else
    log "명령 실패 또는 시간 초과: $1 (상세 출력은 비밀 값 노출 방지를 위해 생략)" >&2
    return 1
  fi
}

compose_to() {
  local output=$1
  local files=(-f docker-compose.yml)
  shift

  if [[ -f "$WORK/migration.override.json" ]]; then
    files+=(-f "$WORK/migration.override.json")
  fi

  if [[ -n "$GREEN_IMAGE_VAR" && -n "$IMAGE" ]]; then
    export "$GREEN_IMAGE_VAR=$IMAGE"
  fi

  command_to "$output" 180 docker compose --env-file .env "${files[@]}" "$@"
}

container_name() {
  case "$1" in
    blue)  printf '%s\n' "$BLUE_NAME" ;;
    green) printf '%s\n' "$GREEN_NAME" ;;
    nginx) printf '%s\n' "$NGINX_NAME" ;;
    *)     return 1 ;;
  esac
}

inspect_to() {
  command_to "$2" 30 docker inspect "$(container_name "$1")"
}

container_env() {
  jq -c '.[0].Config.Env | map(capture("^(?<key>[^=]+)=(?<value>.*)$")) | from_entries' "$1"
}

check_db_identity() {
  local url=$1 pattern

  pattern='^jdbc:mysql://([A-Za-z0-9._-]+)(:([0-9]+))?/([A-Za-z0-9_$-]+)(\?.*)?$'
  [[ "$url" =~ $pattern ]] || return 1
  [[ "${BASH_REMATCH[1]}" == "$DB_HOST" && "${BASH_REMATCH[3]:-3306}" == "$DB_PORT" && "${BASH_REMATCH[4]}" == "$DB_NAME" ]]
}

health() {
  local url=$1

  curl --noproxy '*' -fsS --connect-timeout 3 --max-time 5 -H 'Connection: close' \
    -o "$WORK/health.body" "$url" 2> "$WORK/curl.stderr" || return 1
  jq -e '.status == "UP"' "$WORK/health.body" > /dev/null 2>&1
}

require_live() {
  local expected actual

  case "$1" in
    blue) expected=$BLUE_SERVICE ;;
    green) expected=$GREEN_SERVICE ;;
    *) return 1 ;;
  esac

  command_to "$WORK/nginx-current.conf" 30 docker exec "$NGINX_NAME" cat "$NGINX_CONFIG" \
    || die 'Nginx 설정 조회 실패'
  actual=$(awk '$1=="server" && $2~/^backend-(blue|green):8080;$/ {sub(/:8080;$/, "", $2); print $2}' "$WORK/nginx-current.conf")
  [[ "$actual" == "$expected" ]] || die "Nginx upstream이 $expected 와 다릅니다."
  case "$1" in
    blue) health "$BLUE_URL/actuator/health" ;;
    green) health "$GREEN_URL/actuator/health" ;;
  esac || die 'Backend Health 확인 실패'
}

# 지표를 JSON으로 변환하고 누락·잘못된 값 거부
metrics_json() {
  awk -v required="$1" '
    BEGIN { n=split(required,names," "); for(i=1;i<=n;i++) want[names[i]]=1 }
    /^#/ || /^[[:space:]]*$/ { next }
    {
      name=$0; sub(/[[:space:]{].*/,"",name)
      if (!(name in want)) next
      tail=substr($0,length(name)+1)
      if (substr(tail,1,1)=="{") {
        quoted=0; escaped=0; end=0
        for(j=2;j<=length(tail);j++) {
          c=substr(tail,j,1)
          if(escaped) { escaped=0; continue }
          if(c=="\\" && quoted) { escaped=1; continue }
          if(c=="\"") quoted=!quoted
          if(c=="}" && !quoted) { end=j; break }
        }
        if(!end) { bad=1; next }
        tail=substr(tail,end+1)
      }
      sub(/^[[:space:]]+/,"",tail); split(tail,fields,/[[:space:]]+/); value=fields[1]
      if(value !~ /^[+]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$/ || ++count[name] != 1) { bad=1; next }
      numbers[name]=value+0
    }
    END {
      for(i=1;i<=n;i++) if(count[names[i]]!=1) bad=1
      if(bad) exit 1
      printf "{"
      for(i=1;i<=n;i++) printf "%s\"%s\":%.17g",(i>1?",":""),names[i],numbers[names[i]]
      print "}"
    }'
}

metrics_to() {
  local color=$1 required=$2 output=$3 url

  case "$color" in
    blue)  url=$BLUE_URL ;;
    green) url=$GREEN_URL ;;
    *)     return 1 ;;
  esac
  curl --noproxy '*' -fsS --max-time 5 -H 'Connection: close' "$url/actuator/prometheus" \
    > "$WORK/metrics.body" 2> "$WORK/curl.stderr" || return 1
  metrics_json "$required" < "$WORK/metrics.body" > "$output" || return 1
  jq -e 'all(.[]; type == "number" and . >= 0 and isfinite)' "$output" > /dev/null 2>&1
}

quiet_green() {
  metrics_to green "$GATE $WS $HTTP $PUBLISHED" "$WORK/green-quiet.json" || return 1
  jq -e 'all(.[]; . == 0)' "$WORK/green-quiet.json" > /dev/null
}

record() {
  local phase=$1 patch='{}'

  [[ $# -lt 2 ]] || patch=$2
  jq --arg phase "$phase" --arg at "$(now)" --argjson patch "$patch" \
    '. * $patch | .phase=$phase | .updatedAt=$at | .events=((.events // []) + [{at:$at,phase:$phase}])' \
    "$STATE_FILE" > "$STATE_FILE.new"
  chmod 600 "$STATE_FILE.new"
  mv -- "$STATE_FILE.new" "$STATE_FILE"
}

sql_to() {
  local output=$1 statement=$2 rc

  SQL_CONTAINER="bluegreen-sql-$(random_id)"
  if MYSQL_PWD="$DB_PASSWORD" command_to "$output" 180 docker run --rm -i --name "$SQL_CONTAINER" \
      --network "$APP_NETWORK" --env MYSQL_PWD mysql:8.4 mysql --connect-timeout=8 \
      "--host=$DB_HOST" "--port=$DB_PORT" "--user=$DB_USERNAME" "--database=$DB_NAME" \
      --batch --raw --skip-column-names <<< "$statement"; then
    rc=0
  else
    rc=1
  fi
  # 시간 초과 시 남은 DB 세션 정리
  timeout 20 docker rm -f "$SQL_CONTAINER" > /dev/null 2>&1 || true
  SQL_CONTAINER=''
  return "$rc"
}

nginx_workers_to() {
  command_to "$WORK/nginx-processes.txt" 30 docker top "$NGINX_NAME" -eo pid,args || return 1
  awk '
    $1 ~ /^[0-9]+$/ && $0 ~ /^[[:space:]]*[0-9]+[[:space:]]+nginx: worker process([[:space:]]|$)/ {
      printf "%s\"%s\":%s",(n++?",":"{"),$1,(index($0,"shutting down")?"true":"false")
    }
    END { if(!n) exit 1; print "}" }' "$WORK/nginx-processes.txt" > "$1"
}

# 1단계 · 사전 점검
preflight() {
  local current color service source host_inode mounted_inode

  log '[1단계 사전점검] 운영 Compose·Nginx·Backend 확인'
  [[ -f docker-compose.yml && -f .env && -f "$HOST_NGINX_CONFIG" ]] \
    || die 'Compose·환경 파일·Nginx 설정 경로를 확인하세요.'
  compose_to "$WORK/compose-base.json" config --format json || die 'Compose 설정 조회 실패'
  NGINX_NAME=$(jq -er '.services.nginx.container_name' "$WORK/compose-base.json")

  # 단일 파일 마운트가 예전 inode를 잡고 있으면 reload 전에 중단
  host_inode=$(stat -c '%d:%i' "$HOST_NGINX_CONFIG")
  command_to "$WORK/nginx-inode.txt" 30 docker exec "$NGINX_NAME" stat -c '%d:%i' "$NGINX_CONFIG" \
    || die 'Nginx 마운트 확인 실패'
  mounted_inode=$(cat "$WORK/nginx-inode.txt")
  [[ "$host_inode" == "$mounted_inode" ]] \
    || die '호스트와 Nginx의 설정 파일 inode가 다릅니다. 마운트를 정리한 뒤 실행하세요. 컨테이너를 자동 재생성하지 않습니다.'
  command_to "$WORK/nginx-current.conf" 30 docker exec "$NGINX_NAME" cat "$NGINX_CONFIG" \
    || die 'Nginx 설정 조회 실패'
  current=$(awk '$1=="server" && $2~/^backend-(blue|green):8080;$/ {sub(/:8080;$/, "", $2); print $2}' "$WORK/nginx-current.conf")

  case "$current" in
    backend-blue)
      BLUE_SERVICE=backend-blue
      GREEN_SERVICE=backend-green
      GREEN_IMAGE_VAR=BACKEND_GREEN_IMAGE
      ;;
    backend-green)
      BLUE_SERVICE=backend-green
      GREEN_SERVICE=backend-blue
      GREEN_IMAGE_VAR=BACKEND_BLUE_IMAGE
      ;;
    *) die "활성 Backend를 판별하지 못했습니다: $current" ;;
  esac

  # Blue/Green 변수는 각각 기존/신규 역할을 의미
  BLUE_NAME=$(jq -er --arg s "$BLUE_SERVICE" '.services[$s].container_name' "$WORK/compose-base.json")
  GREEN_NAME=$(jq -er --arg s "$GREEN_SERVICE" '.services[$s].container_name' "$WORK/compose-base.json")
  log "기존 Backend=$BLUE_SERVICE / 신규 Backend=$GREEN_SERVICE"
  inspect_to blue "$WORK/blue.json" || die '기존 Backend 조회 실패'
  jq -e '.[0].State.Running == true' "$WORK/blue.json" >/dev/null || die '기존 Backend가 실행 중이어야 합니다.'
  container_env "$WORK/blue.json" > "$WORK/blue-env.json"
  database_from_blue
  check_db_identity "$(jq -er '.DB_URL' "$WORK/blue-env.json")" || die '기존 Backend DB 대상 불일치'
  DB_USERNAME=$(jq -er '.DB_USERNAME | select(length>0)' "$WORK/blue-env.json")
  DB_PASSWORD=$(jq -er '.DB_PASSWORD | select(length>0)' "$WORK/blue-env.json")
  source=$(jq -er --arg n "$APP_NETWORK" '.[0].NetworkSettings.Networks[$n].IPAddress | select(length>0)' "$WORK/blue.json")
  BLUE_URL="http://$source:8080"
  require_live blue

  # 기존 Compose는 유지하고 신규 Backend에 게이트 마운트만 추가
  GATE_DIR="$ROOT/outbox-gate"
  jq -n --arg service "$GREEN_SERVICE" --arg gate "$GATE_PATH" --arg source "$GATE_DIR" '
    {services:{($service):{environment:{APP_CHAT_OUTBOX_START_GATE_FILE:$gate},
      volumes:[{type:"bind",source:$source,target:"/run/chat-outbox-gate",read_only:true}]}}}
  ' > "$WORK/migration.override.json"
  compose_to "$WORK/compose.json" config --format json || die '신규 Backend 설정 조회 실패'
  check_db_identity "$(jq -er --arg s "$GREEN_SERVICE" '.services[$s].environment.DB_URL' "$WORK/compose.json")" \
    || die '기존·신규 Backend의 DB 대상이 다릅니다.'

  command_to "$WORK/containers.list" 30 docker container ls -a --filter "name=^/$GREEN_NAME$" --format '{{.Names}}' \
    || die '신규 Backend 목록 조회 실패'
  if grep -Fxq "$GREEN_NAME" "$WORK/containers.list"; then
    inspect_to green "$WORK/green-existing.json" || die '신규 Backend 조회 실패'
    if jq -e '.[0].State.Running' "$WORK/green-existing.json" >/dev/null; then
      source=$(jq -er --arg n "$APP_NETWORK" '.[0].NetworkSettings.Networks[$n].IPAddress | select(length>0)' "$WORK/green-existing.json")
      GREEN_URL="http://$source:8080"
      quiet_green || die '대기 Backend에 사용자·처리 이력이 있어 교체할 수 없습니다.'
    fi
  fi

  command_to "$WORK/nginx-test.txt" 30 docker exec "$NGINX_NAME" nginx -t || die '기존 Nginx 설정 오류'
  sql_to "$WORK/sql.tsv" 'SELECT 1;' || die 'DB 접속 실패'
  [[ $(cat "$WORK/sql.tsv") == 1 ]] || die 'DB 조회 결과 오류'
}

# 실행 기록 생성
begin_run() {
  RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(random_id)"
  mkdir -p -- "$ROOT/deployments/$RUN_ID"
  chmod 700 "$ROOT/deployments/$RUN_ID"
  STATE_FILE="$ROOT/deployments/$RUN_ID/state.json"
  jq -n --arg id "$RUN_ID" --arg image "$IMAGE" --arg blue "$BLUE_NAME" --arg green "$GREEN_NAME" \
    --arg nginx "$NGINX_NAME" --arg host "$DB_HOST" --arg name "$DB_NAME" --argjson port "$DB_PORT" \
    --arg previous "$BLUE_SERVICE" --arg next "$GREEN_SERVICE" \
    --slurpfile old "$WORK/blue.json" \
    '{runId:$id,image:$image,containers:{blue:$blue,green:$green,nginx:$nginx},blueId:$old[0][0].Id,blueImageId:$old[0][0].Image,database:{host:$host,port:$port,name:$name},services:{previous:$previous,next:$next},cutoverVerified:false}' > "$STATE_FILE"
  cp -- "$HOST_NGINX_CONFIG" "$ROOT/deployments/$RUN_ID/nginx-before.conf"
  record preflight_passed
  log "실행 기록: $STATE_FILE"
}

# 1단계 · Green 이미지 pull·기동
stage_green() {
  local registry region pattern

  log '[1단계 1/8] Spring v2 이미지 pull'
  record staging_green
  pattern='^([0-9]{12}\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com(\.cn)?)/'
  if [[ "$IMAGE" =~ $pattern ]]; then
    registry=${BASH_REMATCH[1]}
    region=${BASH_REMATCH[2]}
    command -v aws > /dev/null || die 'ECR 이미지에는 AWS CLI가 필요합니다.'
    command_to "$WORK/ecr-password" 30 aws ecr get-login-password --region "$region" || die 'ECR 로그인 토큰 발급 실패'
    command_to "$WORK/registry-login.txt" 30 docker login --username AWS --password-stdin "$registry" \
      < "$WORK/ecr-password" || die 'ECR 로그인 실패'
    rm -f -- "$WORK/ecr-password"
  fi
  compose_to "$WORK/pull.txt" pull "$GREEN_SERVICE" || die 'Green 이미지 pull 실패'
  require_live blue
  command_to "$WORK/containers.list" 30 docker container ls -a --filter "name=^/$GREEN_NAME$" --format '{{.Names}}' \
    || die 'Green 교체 전 컨테이너 목록 조회 실패'
  if grep -Fxq "$GREEN_NAME" "$WORK/containers.list"; then
    inspect_to green "$WORK/green-existing.json" || die 'Green 조회 실패'
    if jq -e '.[0].State.Running' "$WORK/green-existing.json" > /dev/null; then
      quiet_green || die 'Green에 기존 작업이 있어 교체할 수 없습니다.'
    fi
  fi
  awk -v key="$GREEN_IMAGE_VAR" 'index($0,key "=")!=1' .env > "$WORK/env.new"
  printf '%s=%s\n' "$GREEN_IMAGE_VAR" "$IMAGE" >> "$WORK/env.new"
  chmod --reference=.env "$WORK/env.new"
  chown --reference=.env "$WORK/env.new"
  mv -- "$WORK/env.new" .env
  mkdir -p -- "$GATE_DIR"
  chmod 755 "$GATE_DIR"
  rm -f -- "$GATE_DIR/publish.ready"
  log '[1단계 2/8] Spring v2 기동 — 시작 과정에서 Flyway 적용'
  compose_to "$WORK/up.txt" up -d --no-deps --force-recreate "$GREEN_SERVICE" || die 'Green 컨테이너 기동 실패'
  inspect_to green "$WORK/green.json" || die '기동한 Green 조회 실패'
  local ip
  ip=$(jq -er --arg n "$APP_NETWORK" '.[0].NetworkSettings.Networks[$n].IPAddress | select(length>0)' "$WORK/green.json")
  GREEN_URL="http://$ip:8080"
  record green_started "$(jq -cn --slurpfile g "$WORK/green.json" '{greenId:$g[0][0].Id,greenStartedAt:$g[0][0].State.StartedAt}')"
}

# 1단계 · Health·Flyway 확인
ready_green() {
  local deadline expected actual image_id

  log '[1단계 3/8] v2 Health·브로커·Flyway·닫힌 게이트 확인'
  deadline=$(( $(monotonic) + HEALTH_TIMEOUT ))
  while :; do
    if health "$GREEN_URL/actuator/health" && quiet_green && metrics_to green "$BROKER" "$WORK/broker.json" \
        && jq -e --arg k "$BROKER" '.[$k]==1' "$WORK/broker.json" > /dev/null; then
      break
    fi
    (( $(monotonic) < deadline )) || die 'Green Health·브로커·게이트 준비 시간 초과'
    sleep 2
  done
  inspect_to green "$WORK/green.json" || die 'Green 조회 실패'
  expected=$(jq -er '.greenId' "$STATE_FILE")
  [[ $(jq -er '.[0].Id' "$WORK/green.json") == "$expected" ]] || die '기동 중 Green 컨테이너가 교체됐습니다.'
  container_env "$WORK/green.json" > "$WORK/green-env.json"
  check_db_identity "$(jq -er '.DB_URL' "$WORK/green-env.json")" || die '실행 중인 Green DB 대상 불일치'
  [[ $(jq -er '.APP_CHAT_OUTBOX_START_GATE_FILE' "$WORK/green-env.json") == "$GATE_PATH" ]] || die '실행 중인 Green 게이트 설정 불일치'
  command_to "$WORK/image.json" 30 docker image inspect "$IMAGE" || die '다운로드 이미지 조회 실패'
  actual=$(jq -er '.[0].Id' "$WORK/image.json")
  image_id=$actual
  [[ $(jq -er '.[0].Image' "$WORK/green.json") == "$actual" ]] || die '실행 중인 Green 이미지가 배포 대상과 다릅니다.'
  sql_to "$WORK/schema.tsv" "SELECT COUNT(*) FROM flyway_schema_history WHERE success <> 1;
SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE()
AND TABLE_NAME='chat_message_outbox' AND COLUMN_NAME IN
('published_at','rabbit_published_at','rabbit_failed_at','rabbit_next_attempt_at',
 'rabbit_claim_token','rabbit_claim_until','rabbit_published');" || die 'Flyway/컬럼 검증 조회 실패'
  [[ $(head -n 1 "$WORK/schema.tsv") == 0 ]] || die 'Flyway 실패 이력이 있습니다.'
  actual=$(tail -n +2 "$WORK/schema.tsv" | sort)
  expected=$(printf '%s\n' published_at rabbit_published_at rabbit_failed_at rabbit_next_attempt_at rabbit_claim_token rabbit_claim_until | sort)
  [[ "$actual" == "$expected" ]] || die 'v2의 Rabbit Outbox·선점 컬럼이 없거나 구형 Boolean 상태입니다.'
  record green_ready "$(jq -cn --arg id "$image_id" --slurpfile g "$WORK/green-quiet.json" '{greenImageId:$id,greenMetrics:$g[0]}')"
}

# Nginx 전환 설정
target_config() {
  awk -v old="$BLUE_SERVICE" -v next_service="$GREEN_SERVICE" '
    { n+=gsub("server[[:space:]]+" old ":8080;", "server " next_service ":8080;"); print }
    END { if(n!=1) exit 1 }
  ' "$HOST_NGINX_CONFIG"
}

validate_target_config() {
  local candidate="/tmp/nginx-migration-$RUN_ID.conf" rc=0

  CANDIDATE_ACTIVE="$WORK/nginx-next.conf"
  target_config > "$CANDIDATE_ACTIVE" || die '변경할 Backend upstream이 정확히 한 개가 아닙니다.'
  command_to "$WORK/nginx-copy.txt" 30 docker cp "$CANDIDATE_ACTIVE" "$NGINX_NAME:$candidate" \
    || die 'Nginx 검사 설정 복사 실패'
  command_to "$WORK/nginx-candidate.txt" 30 docker exec "$NGINX_NAME" nginx -t -c "$candidate" || rc=1
  docker exec "$NGINX_NAME" rm -f "$candidate" >/dev/null 2>&1 || true
  (( rc==0 )) || die '전환할 Nginx 설정 오류'
  record nginx_config_validated
}

# 1단계 · DB 동기화·게이트 개방
synchronize() {
  local rows pending expected identity deadline

  log '[1단계 4/8] DB 완료 상태 동기화 → COMMIT → v2 Outbox 게이트 열기'
  require_live blue
  quiet_green || die '동기화 전 Green은 사용자·발행 이력이 없고 게이트가 닫혀 있어야 합니다.'
  record sync_started

  sql_to "$WORK/sync.tsv" "SET SESSION innodb_lock_wait_timeout=5;
SET SESSION lock_wait_timeout=5;
START TRANSACTION;
UPDATE chat_message_outbox SET rabbit_published_at=published_at
WHERE published_at IS NOT NULL AND rabbit_published_at IS NULL;
SELECT ROW_COUNT();
COMMIT;
SELECT COUNT(*) FROM chat_message_outbox WHERE rabbit_published_at IS NULL AND rabbit_failed_at IS NULL;" || die 'Outbox 동기화 실패: 게이트를 열지 않았습니다.'
  [[ $(wc -l < "$WORK/sync.tsv" | tr -d ' ') == 2 ]] || die '동기화 결과 형식 오류'
  rows=$(sed -n '1p' "$WORK/sync.tsv")
  pending=$(sed -n '2p' "$WORK/sync.tsv")
  [[ "$rows" =~ ^[0-9]+$ && "$pending" =~ ^[0-9]+$ ]] || die '동기화 결과가 숫자가 아닙니다.'
  record sync_committed "$(jq -cn --argjson n "$rows" --argjson p "$pending" '{syncedRows:$n,pendingAfterSync:$p}')"
  inspect_to green "$WORK/green.json" || die '게이트 개방 전 Green 조회 실패'
  expected=$(jq -er '.greenId' "$STATE_FILE")
  jq -e --arg id "$expected" '.[0].Id==$id and .[0].State.Running==true' "$WORK/green.json" > /dev/null || die '동기화 중 Green이 교체/종료됐습니다.'
  container_env "$WORK/green.json" > "$WORK/green-env.json"
  identity=$(jq -r '.HOSTNAME // empty' "$WORK/green-env.json")
  [[ -n "$identity" ]] || identity=$(jq -er '.[0].Config.Hostname' "$WORK/green.json")
  [[ "$identity" =~ ^[A-Za-z0-9_.-]+$ ]] || die '잘못된 Green 게이트 인스턴스 ID'
  record gate_release_started

  printf '%s\n' "$identity" > "$GATE_DIR/publish.ready.new"
  chmod 644 "$GATE_DIR/publish.ready.new"
  mv -- "$GATE_DIR/publish.ready.new" "$GATE_DIR/publish.ready"
  deadline=$(( $(monotonic) + 12 ))
  while :; do
    if metrics_to green "$GATE" "$WORK/gate.json" && jq -e --arg k "$GATE" '.[$k]==1' "$WORK/gate.json" > /dev/null; then
      break
    fi
    (( $(monotonic) < deadline )) || die 'Green이 게이트 개방을 인식하지 못했습니다.'
    sleep 1
  done
  record gate_open "$(jq -cn --argjson at "$(epoch)" '{gateOpenedEpoch:$at}')"
}

# 안정화 기준 판정
warmup_sample() {
  jq -c --arg ws "$WS" --arg http "$HTTP" --arg gate "$GATE" --arg broker "$BROKER" \
    --arg ok "$SNAPSHOT_OK" --arg at "$SNAPSHOT_AT" --arg p "$PENDING" --arg old "$OLDEST" --arg failed "$FAILED" \
    --argjson now "$(epoch)" --argjson opened "$(jq '.gateOpenedEpoch' "$STATE_FILE")" \
    --argjson max "$WARMUP_PENDING_MAX" --argjson oldest "$WARMUP_OLDEST_MAX" '
      . as $m | ($now-.[$at]) as $age | ([0, .[$old]-([0,$age-1]|max)]|max) as $upper |
      {stable:false,oldestAtSnapshotUpperSeconds:$upper,snapshot:$m[$at],metrics:$m} +
      (if .[$ws]!=0 or .[$http]!=0 then {reason:"green_already_accepting_users"}
       elif .[$at]<=$opened or $age<0 or $age>15 then {reason:"stale_or_pre_gate_snapshot"}
       elif .[$gate]!=1 or .[$broker]!=1 or .[$ok]!=1 then {reason:"gate_broker_or_snapshot_unhealthy"}
       elif .[$failed]!=0 then {reason:"failed_outbox"}
       elif .[$p]>$max then {reason:"pending_above_limit"}
       elif $upper>$oldest then {reason:"oldest_above_limit"}
       else {stable:true,reason:"stable"} end)' "$1"
}

# 1단계 · Outbox 안정화
warmup() {
  local began stable_since=-1 snapshots='[]' current continuous distinct reason sample patch

  log '[1단계 5/8] v2 Outbox 워밍업 — Blue가 계속 사용자 트래픽 처리'
  began=$(monotonic)
  record warming "$(jq -cn --argjson p "$WARMUP_PENDING_MAX" --argjson o "$WARMUP_OLDEST_MAX" \
    --argjson s "$WARMUP_STABLE_SECONDS" --argjson t "$WARMUP_TIMEOUT" \
    '{warmupCriteria:{pendingMax:$p,oldestMaxSeconds:$o,stableSeconds:$s,timeoutSeconds:$t,minimumDistinctSnapshots:3,snapshotMaxAgeSeconds:15,snapshotsAfterGateRequired:true}}')"
  while :; do
    require_live blue
    if health "$GREEN_URL/actuator/health" && metrics_to green "$GATE $WS $HTTP $PUBLISHED $BROKER $SNAPSHOT_OK $SNAPSHOT_AT $PENDING $OLDEST $FAILED" "$WORK/warmup-metrics.json"; then
      sample=$(warmup_sample "$WORK/warmup-metrics.json")
    else
      sample='{"stable":false,"reason":"metrics_or_health_unavailable","metrics":{}}'
    fi
    reason=$(jq -r '.reason' <<< "$sample")
    [[ "$reason" != green_already_accepting_users ]] || die '워밍업 완료 전에 Green에 사용자가 들어왔습니다.'
    current=$(monotonic)
    if jq -e '.stable' <<< "$sample" > /dev/null; then
      (( stable_since >= 0 )) || stable_since=$current
      snapshots=$(jq -c --argjson sample "$sample" '. + [$sample.snapshot] | unique' <<< "$snapshots")
    else
      stable_since=-1
      snapshots='[]'
    fi
    continuous=0
    (( stable_since < 0 )) || continuous=$((current-stable_since))
    distinct=$(jq 'length' <<< "$snapshots")
    patch=$(jq -cn --argjson sample "$sample" --argjson s "$continuous" --argjson n "$distinct" \
      '{lastWarmupSample:($sample+{continuousStableSeconds:$s,distinctStableSnapshots:$n})}')
    record warming "$patch"
    log "v2 warmup: $reason, 연속 안정=${continuous}s, 서로 다른 DB 표본=$distinct"
    if jq -e '.stable' <<< "$sample" > /dev/null && (( continuous >= WARMUP_STABLE_SECONDS && distinct >= 3 )); then
      record warmup_ready "$(jq -cn --argjson n "$((current-began))" '{warmupElapsedSeconds:$n}')"
      return
    fi
    (( current-began < WARMUP_TIMEOUT )) || die 'Outbox 워밍업 시간 초과: 실제 Nginx는 Blue를 유지합니다.'
    sleep 5
  done
}

# 1단계 · 신규 트래픽 전환
switch_traffic() {
  local deadline new_workers

  require_live blue
  log '[1단계 6/8] Nginx reload → 신규 Backend 전환'
  nginx_workers_to "$WORK/nginx-workers.json" || die '기존 Nginx 워커 조회 실패'
  inspect_to nginx "$WORK/nginx.json" || die 'Nginx 조회 실패'
  record reload_started "$(jq -cn --slurpfile w "$WORK/nginx-workers.json" --slurpfile n "$WORK/nginx.json" '{oldNginxWorkerPids:($w[0]|keys|map(tonumber)),nginxId:$n[0][0].Id}')"

  # 파일을 교체하지 않고 덮어써 bind mount의 inode 유지
  CONFIG_CHANGED=1
  cat "$CANDIDATE_ACTIVE" > "$HOST_NGINX_CONFIG"
  command_to "$WORK/nginx-test.txt" 30 docker exec "$NGINX_NAME" nginx -t || die '전환 설정 오류'
  RELOAD_ATTEMPTED=1
  command_to "$WORK/nginx-reload.txt" 30 docker exec "$NGINX_NAME" nginx -s reload || die 'Nginx reload 실패'

  deadline=$(( $(monotonic) + 60 ))
  while :; do
    nginx_workers_to "$WORK/new-workers.json" || die '새 Nginx 워커 조회 실패'
    new_workers=$(jq --slurpfile s "$STATE_FILE" '[keys[] | . as $k | select(($s[0].oldNginxWorkerPids | map(tostring) | index($k))==null)] | length' "$WORK/new-workers.json")
    if (( new_workers>0 )) && health "$GREEN_URL/actuator/health"; then
      break
    fi
    (( $(monotonic) < deadline )) || die 'Nginx 전환 확인 시간 초과'
    sleep 1
  done

  SWITCHED=1
  record cutover_verified '{"cutoverVerified":true}'
}

# 연결·컨테이너 확인
verify_container_ids() {
  local color id

  for color in blue green nginx; do
    inspect_to "$color" "$WORK/identity.json" || die "$color 컨테이너 조회 실패"
    id=$(jq -er --arg key "${color}Id" '.[$key]' "$STATE_FILE")
    [[ $(jq -er '.[0].Id' "$WORK/identity.json") == "$id" ]] || die "$color 컨테이너가 다른 배포로 교체됐습니다."
  done
}

blue_counts() {
  local pid tcp old

  inspect_to blue "$WORK/drain-blue.json" || die '기존 Backend 조회 실패'
  if ! jq -e '.[0].State.Running' "$WORK/drain-blue.json" > /dev/null; then
    printf 'null\n'
    return
  fi

  # HTTP·WebSocket을 포함한 기존 Backend의 전체 TCP 연결
  pid=$(jq -er '.[0].State.Pid | select(.>0)' "$WORK/drain-blue.json")
  command_to "$WORK/blue-connections.txt" 30 nsenter -t "$pid" -n ss -Hnt state established '( sport = :8080 )' \
    || die '기존 연결 조회 실패: 0으로 간주하지 않습니다.'
  tcp=$(awk 'NF {n++} END {print n+0}' "$WORK/blue-connections.txt")

  # 아직 요청을 처리하는 이전 Nginx 워커도 함께 대기
  nginx_workers_to "$WORK/nginx-workers.json" || die 'Nginx 워커 조회 실패'
  old=$(jq --slurpfile s "$STATE_FILE" '[to_entries[] | select(.value==true or (.key as $k | ($s[0].oldNginxWorkerPids | map(tostring) | index($k))!=null))] | length' "$WORK/nginx-workers.json")
  jq -cn --argjson tcp "$tcp" --argjson old "$old" '{tcp:$tcp,oldNginxWorkers:$old}'
}

# 1단계 · Blue 연결 대기·종료
drain_blue() {
  local deadline=0 quiet=0 counts remaining

  log '[1단계 7/8] v1 연결을 10초마다 확인 — 연속 3회 모두 0이면 종료'
  verify_container_ids
  if (( DRAIN_TIMEOUT > 0 )); then
    deadline=$(( $(monotonic) + DRAIN_TIMEOUT ))
    log "연결 대기 제한: 운영자가 지정한 ${DRAIN_TIMEOUT}초"
  else
    log '연결 대기 시간 제한 없음 — 기존 연결이 종료될 때까지 계속 기다립니다.'
  fi
  record draining "$(jq -cn --argjson seconds "$DRAIN_TIMEOUT" '{drainTimeoutSeconds:$seconds}')"
  while :; do
    require_live green
    verify_container_ids
    counts=$(blue_counts)
    [[ "$counts" != null ]] || break
    log "기존 Backend $BLUE_SERVICE: $(jq -r '"TCP=\(.tcp), 이전 Nginx 워커=\(.oldNginxWorkers)"' <<< "$counts")"
    record draining "$(jq -cn --argjson c "$counts" '{lastBlueConnections:$c}')"
    if jq -e 'all(.[]; .==0)' <<< "$counts" > /dev/null; then
      quiet=$((quiet+1))
    else
      quiet=0
    fi
    if (( quiet >= 3 )); then
      require_live green
      verify_container_ids
      command_to "$WORK/stop.txt" 80 docker stop -t 60 "$(jq -er '.blueId' "$STATE_FILE")" || die 'Blue 종료 명령 실패'
      inspect_to blue "$WORK/drain-blue.json" || die 'Blue 종료 상태 조회 실패'
      jq -e '.[0].State.Running==false' "$WORK/drain-blue.json" > /dev/null || die 'Blue가 종료되지 않았습니다.'
      break
    fi
    if (( DRAIN_TIMEOUT > 0 )); then
      remaining=$((deadline-$(monotonic)))
      if (( remaining <= 0 )); then
        record drain_pending
        log '지정한 연결 대기 제한에 도달: Green이 신규 트래픽을 받고 Blue는 계속 실행 중입니다.'
        printf '무제한 대기 재개: sudo bash %q --directory %q --resume-drain %q\n' \
          "$SCRIPT_DIR/migration.sh" "$ROOT" "$RUN_ID"
        exit 3
      fi
    fi
    sleep 10
  done
  log '[1단계 8/8] v1 종료 및 v2 정상 응답 최종 확인'
  require_live green
  record stage1_complete "$(jq -cn --arg at "$(now)" '{finishedAt:$at,completedStage:1,nextStage:2,stage2:{status:"not_started",apiPaths:["/api","/api/*"],transition:"CloudFront API origin EC2 -> ALB/ECS",keepEc2V2:true}}')"
  log '1단계 완료: v2 정상 / v1 종료. 다음 단계는 CloudFront API 원본의 ALB/ECS 전환입니다.'
}

# 연결 대기 재개
resume_drain() {
  [[ "$RESUME_DRAIN" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8}$ ]] || die '잘못된 실행 ID'
  RUN_ID=$RESUME_DRAIN
  STATE_FILE="$ROOT/deployments/$RUN_ID/state.json"
  jq -e '.cutoverVerified==true' "$STATE_FILE" > /dev/null || die 'Green 전환이 검증된 실행만 연결 대기를 재개할 수 있습니다.'
  BLUE_NAME=$(jq -er '.containers.blue' "$STATE_FILE")
  GREEN_NAME=$(jq -er '.containers.green' "$STATE_FILE")
  NGINX_NAME=$(jq -er '.containers.nginx' "$STATE_FILE")
  BLUE_SERVICE=$(jq -er '.services.previous' "$STATE_FILE")
  GREEN_SERVICE=$(jq -er '.services.next' "$STATE_FILE")
  inspect_to green "$WORK/resume-green.json" || die '신규 Backend 조회 실패'
  local ip
  ip=$(jq -er --arg n "$APP_NETWORK" '.[0].NetworkSettings.Networks[$n].IPAddress | select(length>0)' "$WORK/resume-green.json")
  GREEN_URL="http://$ip:8080"
  SWITCHED=1
  require_live green
  log '연결 대기만 재개합니다. 이미지 기동·동기화·워밍업·reload는 반복하지 않습니다.'
  drain_blue
}

# 종료 처리
on_exit() {
  local rc=$1 rollback_ok=1

  trap - EXIT ERR INT TERM HUP
  set +e
  if [[ -n "$SQL_CONTAINER" ]]; then
    timeout 20 docker rm -f "$SQL_CONTAINER" > /dev/null 2>&1
  fi
  if (( rc != 0 && rc != 3 && CONFIG_CHANGED == 1 && SWITCHED == 0 )); then
    cat "$ROOT/deployments/$RUN_ID/nginx-before.conf" > "$HOST_NGINX_CONFIG" || rollback_ok=0
    if (( RELOAD_ATTEMPTED == 1 )); then
      command_to "$WORK/restore.txt" 30 docker exec "$NGINX_NAME" nginx -s reload -c "$NGINX_CONFIG" || rollback_ok=0
    fi
    health "$BLUE_URL/actuator/health" || rollback_ok=0
    if (( rollback_ok == 1 )); then
      log 'Nginx는 Blue를 유지/복구했습니다. DB 변경과 Green 발행 게이트는 되돌리지 않았습니다.'
    else
      log 'Blue Nginx 복구를 확인하지 못했습니다. 실제 라우팅을 확인해야 합니다.' >&2
    fi
  fi
  if (( rc != 0 && rc != 3 )); then
    log "실패 지점: line=$ERROR_LINE. Blue를 자동 강제 종료하지 않습니다." >&2
    if [[ -n "$STATE_FILE" && -f "$STATE_FILE" ]]; then
      record failed "$(jq -cn --argjson n "$ERROR_LINE" '{errorLine:$n}')"
      log "확인할 기록: $STATE_FILE"
    fi
  fi
  [[ -z "$CANDIDATE_ACTIVE" ]] || rm -f -- "$CANDIDATE_ACTIVE"
  [[ -z "$CANDIDATE_CONFIG" ]] || rm -f -- "$CANDIDATE_CONFIG"
  [[ -z "$WORK" ]] || rm -rf -- "$WORK"
  exit "$rc"
}

# 실행 옵션
parse_args() {
  while (( $# )); do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --check-only)
        CHECK_ONLY=1
        shift
        continue
        ;;
      --directory|--image|--db-host|--db-name|--db-port|--health-timeout|--drain-timeout|--warmup-pending-max|--warmup-oldest-max|--warmup-stable-seconds|--warmup-timeout|--resume-drain)
        (( $# >= 2 )) && [[ -n "$2" && "$2" != --* ]] || die "$1 값이 필요합니다.";;
      *) die "알 수 없는 옵션: $1";;
    esac
    case "$1" in
      --directory) ROOT=$2;;
      --image) IMAGE=$2;;
      --db-host) DB_HOST=$2;;
      --db-name) DB_NAME=$2;;
      --db-port) DB_PORT=$2;;
      --health-timeout) HEALTH_TIMEOUT=$2;;
      --drain-timeout) DRAIN_TIMEOUT=$2;;
      --warmup-pending-max) WARMUP_PENDING_MAX=$2;;
      --warmup-oldest-max) WARMUP_OLDEST_MAX=$2;;
      --warmup-stable-seconds) WARMUP_STABLE_SECONDS=$2;;
      --warmup-timeout) WARMUP_TIMEOUT=$2;;
      --resume-drain) RESUME_DRAIN=$2;;
    esac
    shift 2
  done
  local number

  for number in "$DB_PORT" "$HEALTH_TIMEOUT" "$DRAIN_TIMEOUT" "$WARMUP_PENDING_MAX" "$WARMUP_STABLE_SECONDS" "$WARMUP_TIMEOUT"; do
    [[ "$number" =~ ^(0|[1-9][0-9]{0,8})$ ]] || die '포트·시간·건수는 0 이상의 정수여야 합니다.'
  done
  (( DB_PORT >= 1 && DB_PORT <= 65535 && HEALTH_TIMEOUT > 0 )) || die '포트/시간 설정 오류'
  (( DRAIN_TIMEOUT == 0 || DRAIN_TIMEOUT >= 20 )) || die '연결 대기는 0(무제한) 또는 20초 이상이어야 합니다.'
  (( WARMUP_STABLE_SECONDS >= 10 && WARMUP_TIMEOUT > WARMUP_STABLE_SECONDS )) || die '워밍업 시간 설정 오류'
  [[ "$WARMUP_OLDEST_MAX" =~ ^(0|[1-9][0-9]{0,8})([.][0-9]{1,6})?$ ]] || die '워밍업 메시지 나이는 0 이상의 숫자여야 합니다.'
  if [[ -n "$RESUME_DRAIN" ]]; then
    [[ -z "$IMAGE$DB_HOST$DB_NAME" && "$CHECK_ONLY" == 0 ]] || die '연결 대기 재개와 신규 배포 옵션을 함께 쓸 수 없습니다.'
  else
    [[ -n "$IMAGE" ]] || die '--image 값이 필요합니다.'
    [[ -z "$DB_HOST$DB_NAME" || ( -n "$DB_HOST" && -n "$DB_NAME" ) ]] || die '--db-host와 --db-name은 함께 지정하세요.'
    [[ "$IMAGE" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@-]*$ ]] || die '잘못된 이미지 주소'
  fi
}

# 실행 순서
main() {
  parse_args "$@"
  [[ $(uname -s) == Linux && $(id -u) == 0 ]] || die '로컬 PC가 아닌 Linux APP 서버에서 sudo로 실행하세요.'
  local executable

  for executable in docker curl jq awk timeout flock nsenter ss realpath od; do
    command -v "$executable" > /dev/null || die "필수 명령이 없습니다: $executable"
  done

  # 작업 경로·중복 실행 방지
  ROOT=$(cd -- "$ROOT" && pwd -P)
  cd -- "$ROOT"

  exec 9>.bluegreen.lock
  flock -n 9 || die '다른 배포가 실행 중입니다.'

  # 임시 파일·종료 처리
  WORK=$(mktemp -d "$ROOT/.deploy-work.XXXXXX")
  trap 'ERROR_LINE=$LINENO' ERR
  trap 'on_exit $?' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP

  # 연결 대기 재개
  if [[ -n "$RESUME_DRAIN" ]]; then
    resume_drain
    return
  fi

  # 사전 점검
  preflight

  if (( CHECK_ONLY == 1 )); then
    log '사전 점검 통과. Green 기동·DB 변경·Nginx reload·Blue 종료는 실행하지 않았습니다.'
    return
  fi

  begin_run

  # Green 기동·Flyway
  stage_green

  # Green 준비 확인
  ready_green

  # 전환할 Nginx 설정 검사
  validate_target_config

  # DB 동기화·게이트 개방
  synchronize

  # Outbox 안정화
  warmup

  # 신규 트래픽 전환
  switch_traffic

  # 기존 연결 대기·Blue 종료
  drain_blue
}

# 기존 Blue의 DB 주소 사용
database_from_blue() {
  [[ -z "$DB_HOST$DB_NAME" ]] || return 0
  local url pattern

  url=$(jq -er '.DB_URL' "$WORK/blue-env.json")
  pattern='^jdbc:mysql://([A-Za-z0-9._-]+)(:([0-9]+))?/([A-Za-z0-9_$-]+)(\?.*)?$'
  [[ "$url" =~ $pattern ]] || die 'DB_URL 형식을 확인하세요.'
  DB_HOST=${BASH_REMATCH[1]} DB_PORT=${BASH_REMATCH[3]:-3306} DB_NAME=${BASH_REMATCH[4]}
}

# 2단계 · CloudFront 경로 전환
switch_cloudfront() {
  local name paths etag status

  for name in CF_DISTRIBUTION_ID CF_EC2_ORIGIN_ID CF_ALB_ORIGIN_ID; do
    [[ -n ${!name:-} ]] || die "CloudFront 설정이 없습니다: $name"
  done
  command -v aws >/dev/null || die 'AWS CLI가 필요합니다.'
  [[ "$CF_EC2_ORIGIN_ID" != "$CF_ALB_ORIGIN_ID" ]] || die 'EC2와 ALB origin은 달라야 합니다.'
  paths='["/api","/api/*","/ws","/ws/*"]'

  # 현재 설정 조회·백업
  log '[2단계] CloudFront API/WS 원본을 ALB로 전환'
  aws --no-cli-pager cloudfront get-distribution-config --id "$CF_DISTRIBUTION_ID" \
    --output json > "$WORK/cloudfront-before.json"
  jq -e --arg ec2 "$CF_EC2_ORIGIN_ID" --arg alb "$CF_ALB_ORIGIN_ID" --argjson paths "$paths" '
    .DistributionConfig as $c |
    any($c.Origins.Items[]; .Id==$ec2) and any($c.Origins.Items[]; .Id==$alb) and
    ([$c.CacheBehaviors.Items[] | select(.PathPattern as $p | $paths | index($p))] as $selected |
      ([$selected[].PathPattern] | sort)==($paths | sort) and
      all($selected[]; (.TargetOriginId==$ec2 or .TargetOriginId==$alb) and
        .CachePolicyId=="4135ea2d-6df8-44a3-9df3-4b5a84be39ad"))
  ' "$WORK/cloudfront-before.json" >/dev/null || die 'CloudFront origin·경로·캐시 정책을 확인하세요.'
  cp -- "$WORK/cloudfront-before.json" "$ROOT/deployments/$RUN_ID/cloudfront-before.json"

  # 지정 경로의 원본 변경
  jq --arg alb "$CF_ALB_ORIGIN_ID" --argjson paths "$paths" '
    .DistributionConfig | .CacheBehaviors.Items |= map(
      if (.PathPattern as $p | $paths | index($p))!=null
      then .TargetOriginId=$alb | .OriginRequestPolicyId="b689b0a8-53d0-40ab-baf2-68738e2966ac"
      else . end)
  ' "$WORK/cloudfront-before.json" > "$WORK/cloudfront-after.json"

  # 변경된 경우에만 적용
  if ! jq -e -s '.[0].DistributionConfig==.[1]' "$WORK/cloudfront-before.json" "$WORK/cloudfront-after.json" >/dev/null; then
    etag=$(jq -er '.ETag' "$WORK/cloudfront-before.json")
    aws --no-cli-pager cloudfront update-distribution --id "$CF_DISTRIBUTION_ID" \
      --if-match "$etag" --distribution-config "file://$WORK/cloudfront-after.json" \
      --output json > "$WORK/cloudfront-update.json"
  fi

  # CloudFront 반영 대기
  while :; do
    status=$(aws --no-cli-pager cloudfront get-distribution --id "$CF_DISTRIBUTION_ID" \
      --query Distribution.Status --output text)
    [[ "$status" != Deployed ]] || break
    log 'CloudFront 반영 대기 중'
    sleep 30
  done
  record migration_complete "$(jq -cn --arg id "$CF_DISTRIBUTION_ID" '{completedStage:2,stage2:{status:"complete",distributionId:$id,keepEc2V2:true}}')"
  log '이전 완료. 기존 WebSocket 연결을 위해 EC2 Green은 유지합니다.'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  # 1단계: EC2 이전
  main "$@"

  # 2단계: CloudFront 전환
  if (( CHECK_ONLY == 0 )); then
    switch_cloudfront
  fi
fi
