#!/usr/bin/env bash
set -euo pipefail
exec 3>&1 4>&2

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly COMPOSE_FILE="$PROJECT_ROOT/compose.kafka.yaml"
readonly CREATE_TOPIC="${NOTIFICATION_CREATE_REQUESTED_TOPIC-local-notification-create-requested-v1}"
readonly DELIVERY_TOPIC="${NOTIFICATION_DELIVERY_TOPIC-local-notification-delivery-v1}"
broker_failure=false
case "${1-}" in
    "") ;;
    --broker-failure) broker_failure=true ;;
    --help)
        printf 'Usage: %s [--broker-failure]\nRequires three running local Kafka nodes. Creates verification topics and retains logs.\n' "$0"
        exit 0
        ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { printf 'Too many arguments.\n' >&2; exit 1; }

compose() {
    docker compose --env-file /dev/null -f "$COMPOSE_FILE" "$@"
}

bootstrap='kafka-1:19092,kafka-2:19092,kafka-3:19092'
kafka_command() {
    local tool="$1"
    shift
    compose exec -T kafka-2 "/opt/kafka/bin/$tool" --bootstrap-server "$bootstrap" "$@"
}

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

escape_topic() {
    printf '%s' "$1" | sed 's/\./\\./g'
}

command -v docker >/dev/null 2>&1 || fail 'Docker CLI is required.'
compose config --quiet
for service in kafka-1 kafka-2 kafka-3; do
    container_id="$(compose ps -q "$service")"
    [[ -n "$container_id" ]] || fail "$service must be running. Start the local Compose first."
    state="$(docker inspect --format '{{.State.Health.Status}}' "$container_id")"
    [[ "$state" == healthy ]] || fail "$service is not healthy: $state"
done

RESULT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dating-kafka-verify.XXXXXX")"
readonly RESULT_DIR
readonly RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
readonly SMOKE_TOPIC="local-notification-verify-$RUN_ID"
readonly TOPIC_PATTERN="^($(escape_topic "$CREATE_TOPIC")|$(escape_topic "$DELIVERY_TOPIC"))$"
broker_stopped=false

finish() {
    local status=$?
    trap - EXIT
    set +e
    if [[ "$broker_stopped" == true ]]; then
        printf 'Restoring kafka-1 after an interrupted or failed check.\n' >&4
        compose up -d --wait --wait-timeout 120 kafka-1 >> "$RESULT_DIR/restore.log" 2>&1
        if [[ $? -ne 0 ]]; then
            printf 'FAIL: kafka-1 recovery needs attention; see restore.log.\n' >&4
            status=1
        fi
    fi
    printf 'Result: exit=%s; evidence=%s\n' "$status" "$RESULT_DIR" >&3
    printf 'Verification topics are retained: %s*\n' "$SMOKE_TOPIC" >&3
    exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'Evidence directory: %s\n' "$RESULT_DIR"

wait_isr() {
    local pattern="$1" rows="$2" isr="$3" label="$4"
    local deadline=$((SECONDS + 90))
    while [[ $SECONDS -lt $deadline ]]; do
        if kafka_command kafka-topics.sh --describe --topic "$pattern" \
            > "$RESULT_DIR/$label-topics.txt" 2> "$RESULT_DIR/$label-topics.err"; then
            if awk -v expected="$rows" -v isr="$isr" '
                $3 == "Partition:" {
                    rows++; leader_ok = 0; replicas_ok = 0; isr_ok = 0
                    for (i = 1; i < NF; i++) {
                        value = $(i + 1)
                        if ($i == "Leader:") leader_ok = (value ~ /^[123]$/ && (isr == 3 || value != 1))
                        if ($i == "Replicas:") replicas_ok = (split(value, ids, ",") == 3)
                        if ($i == "Isr:") {
                            count = split(value, ids, ","); isr_ok = (count == isr)
                            for (j = 1; j <= count; j++) {
                                if (ids[j] !~ /^[123]$/ || (isr == 2 && ids[j] == 1)) isr_ok = 0
                            }
                        }
                    }
                    if (!leader_ok || !replicas_ok || !isr_ok) bad = 1
                }
                END { exit !(rows == expected && !bad) }
            ' "$RESULT_DIR/$label-topics.txt"; then
                printf 'PASS: %s, partitions=%s, ISR=%s\n' "$label" "$rows" "$isr"
                return
            fi
        fi
        sleep 2
    done
    fail "$label did not reach the expected leader/replica/ISR state."
}

topic_ids() {
    kafka_command kafka-topics.sh --describe --topic "$TOPIC_PATTERN" | awk '
        /PartitionCount:/ {
            for (i = 1; i < NF; i++) if ($i == "TopicId:") print $2, $(i + 1)
        }
    ' | LC_ALL=C sort
}

KAFKA_ADMIN_SERVICE=kafka-2 "$SCRIPT_DIR/init-topics.sh" > "$RESULT_DIR/init-first.log" 2>&1
topic_ids > "$RESULT_DIR/topic-ids-before.txt"
KAFKA_ADMIN_SERVICE=kafka-2 "$SCRIPT_DIR/init-topics.sh" > "$RESULT_DIR/init-repeat.log" 2>&1
topic_ids > "$RESULT_DIR/topic-ids-after.txt"
[[ $(wc -l < "$RESULT_DIR/topic-ids-before.txt") -eq 2 ]] || fail 'Expected two notification Topic IDs.'
cmp -s "$RESULT_DIR/topic-ids-before.txt" "$RESULT_DIR/topic-ids-after.txt" \
    || fail 'Notification Topic IDs changed during repeated initialization.'
printf 'PASS: topic initialization and repeat initialization preserve Topic IDs.\n'
wait_isr "$TOPIC_PATTERN" 4 3 notification-initial

for mismatch in partitions min-isr; do
    fixture="$SMOKE_TOPIC-$mismatch"
    partitions=2
    min_isr=2
    expected_error='min.insync.replicas: expected 2'
    if [[ "$mismatch" == partitions ]]; then
        partitions=1
        expected_error='must have 2 partitions'
    else
        min_isr=1
    fi
    kafka_command kafka-topics.sh --create --topic "$fixture" \
        --partitions "$partitions" --replication-factor 3 \
        --config "min.insync.replicas=$min_isr" --config cleanup.policy=delete \
        > "$RESULT_DIR/$mismatch-create.log" 2>&1
    kafka_command kafka-configs.sh --describe --all --entity-type topics --entity-name "$fixture" \
        > "$RESULT_DIR/$mismatch-config-before.txt" 2>&1
    if NOTIFICATION_CREATE_REQUESTED_TOPIC="$fixture" KAFKA_ADMIN_SERVICE=kafka-2 \
        "$SCRIPT_DIR/init-topics.sh" > "$RESULT_DIR/$mismatch-rejection.log" 2>&1; then
        fail "Initialization accepted the $mismatch mismatch."
    fi
    grep -F "$expected_error" "$RESULT_DIR/$mismatch-rejection.log" >/dev/null \
        || fail "Initialization failed for an unexpected reason in $mismatch check."
    kafka_command kafka-configs.sh --describe --all --entity-type topics --entity-name "$fixture" \
        > "$RESULT_DIR/$mismatch-config-after.txt" 2>&1
    cmp -s "$RESULT_DIR/$mismatch-config-before.txt" "$RESULT_DIR/$mismatch-config-after.txt" \
        || fail "Initialization changed the $mismatch fixture configuration."
    kafka_command kafka-topics.sh --describe --topic "$fixture" \
        > "$RESULT_DIR/$mismatch-description.txt" 2>&1
    awk -v expected="$partitions" '
        /PartitionCount:/ { for (i = 1; i < NF; i++) if ($i == "PartitionCount:") found = ($(i + 1) == expected) }
        END { exit !found }
    ' "$RESULT_DIR/$mismatch-description.txt" || fail 'Fixture partition count changed.'
    printf 'PASS: %s mismatch rejected without changing the existing topic.\n' "$mismatch"
done

kafka_command kafka-topics.sh --create --topic "$SMOKE_TOPIC" --partitions 2 \
    --replication-factor 3 --config min.insync.replicas=2 --config cleanup.policy=delete \
    > "$RESULT_DIR/smoke-create.log" 2>&1
wait_isr "^$SMOKE_TOPIC$" 2 3 smoke-initial
: > "$RESULT_DIR/expected.txt"

publish_and_compare() {
    local phase="$1" count
    for index in 1 2 3 4; do
        printf '%s-%s-%s\n' "$RUN_ID" "$phase" "$index"
    done > "$RESULT_DIR/$phase-published.txt"
    kafka_command kafka-console-producer.sh --topic "$SMOKE_TOPIC" --sync \
        --command-property acks=all --command-property enable.idempotence=true \
        --command-property delivery.timeout.ms=30000 --command-property request.timeout.ms=10000 \
        --command-property max.block.ms=30000 \
        --command-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner \
        < "$RESULT_DIR/$phase-published.txt" > "$RESULT_DIR/$phase-producer.log" 2>&1
    cat "$RESULT_DIR/$phase-published.txt" >> "$RESULT_DIR/expected.txt"
    count="$(awk 'END { print NR }' "$RESULT_DIR/expected.txt")"
    kafka_command kafka-get-offsets.sh --topic "^$SMOKE_TOPIC$" --time earliest \
        > "$RESULT_DIR/$phase-start-offsets.txt" 2> "$RESULT_DIR/$phase-offsets.err"
    kafka_command kafka-get-offsets.sh --topic "^$SMOKE_TOPIC$" --time latest \
        > "$RESULT_DIR/$phase-end-offsets.txt" 2>> "$RESULT_DIR/$phase-offsets.err"
    awk -F: -v topic="$SMOKE_TOPIC" '
        $1 == topic && $2 ~ /^[01]$/ && $3 == 0 { rows++ }
        END { exit !(rows == 2) }
    ' "$RESULT_DIR/$phase-start-offsets.txt" || fail 'Unexpected starting offsets in the fresh smoke topic.'
    awk -F: -v topic="$SMOKE_TOPIC" -v expected="$count" '
        $1 == topic && $2 ~ /^[01]$/ && $3 ~ /^[0-9]+$/ {
            rows++; total += $3; if ($3 == 0) empty = 1
        }
        END { exit !(rows == 2 && total == expected && !empty) }
    ' "$RESULT_DIR/$phase-end-offsets.txt" || fail 'Recorded offsets do not match the expected message count across both partitions.'
    kafka_command kafka-console-consumer.sh --topic "$SMOKE_TOPIC" --from-beginning \
        --group "local-verify-$RUN_ID-$phase" --max-messages "$count" --timeout-ms 20000 \
        --command-property enable.auto.commit=false \
        > "$RESULT_DIR/$phase-received.txt" 2> "$RESULT_DIR/$phase-consumer.log"
    awk '
        NR == FNR { expected[$0]++; next }
        { actual[$0]++; received++ }
        END {
            for (id in expected) {
                wanted += expected[id]
                if (actual[id] < expected[id]) missing += expected[id] - actual[id]
            }
            for (id in actual) {
                if (!(id in expected)) unexpected += actual[id]
                if (actual[id] > 1) duplicates += actual[id] - 1
            }
            printf "expected=%d received=%d missing=%d duplicates=%d unexpected=%d\n", wanted, received, missing, duplicates, unexpected
            exit (missing || duplicates || unexpected || wanted != received)
        }
    ' "$RESULT_DIR/expected.txt" "$RESULT_DIR/$phase-received.txt" \
        > "$RESULT_DIR/$phase-summary.txt" || fail "Message identity comparison failed during $phase."
    printf 'PASS: %s: %s\n' "$phase" "$(cat "$RESULT_DIR/$phase-summary.txt")"
}

publish_and_compare baseline
offset_partitions="$(kafka_command kafka-topics.sh --describe --topic '^__consumer_offsets$' | awk '
    /PartitionCount:/ {
        for (i = 1; i < NF; i++) if ($i == "PartitionCount:") print $(i + 1)
    }
')"
[[ "$offset_partitions" =~ ^[1-9][0-9]*$ ]] || fail 'Could not determine Consumer Offset partition count.'
wait_isr '^__consumer_offsets$' "$offset_partitions" 3 offsets-initial
kafka_command kafka-configs.sh --describe --all --entity-type topics --entity-name __consumer_offsets \
    > "$RESULT_DIR/offsets-config.txt" 2>&1
awk '$1 == "min.insync.replicas=2" { found = 1 } END { exit !found }' \
    "$RESULT_DIR/offsets-config.txt" || fail 'Consumer Offset topic minimum ISR must be 2.'
if [[ "$broker_failure" == true ]]; then
    broker_stopped=true
    compose stop kafka-1 > "$RESULT_DIR/stop.log" 2>&1
    bootstrap='kafka-2:19092,kafka-3:19092'
    wait_isr "$TOPIC_PATTERN" 4 2 notification-during-stop
    wait_isr "^$SMOKE_TOPIC$" 2 2 smoke-during-stop
    wait_isr '^__consumer_offsets$' "$offset_partitions" 2 offsets-during-stop
    kafka_command kafka-metadata-quorum.sh describe --status > "$RESULT_DIR/quorum-during-stop.txt" 2>&1
    publish_and_compare during-stop
    compose up -d --wait --wait-timeout 120 kafka-1 > "$RESULT_DIR/restore.log" 2>&1
    bootstrap='kafka-1:19092,kafka-2:19092,kafka-3:19092'
    wait_isr "$TOPIC_PATTERN" 4 3 notification-restored
    wait_isr "^$SMOKE_TOPIC$" 2 3 smoke-restored
    wait_isr '^__consumer_offsets$' "$offset_partitions" 3 offsets-restored
    broker_stopped=false
    kafka_command kafka-metadata-quorum.sh describe --status > "$RESULT_DIR/quorum-restored.txt" 2>&1
    publish_and_compare restored
fi
printf 'PASS: local Kafka verification completed; application DTO, DB, Retry/DLT and SSE checks remain separate.\n'
