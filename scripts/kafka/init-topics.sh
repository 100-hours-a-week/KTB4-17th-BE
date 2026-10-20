#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly COMPOSE_FILE="$PROJECT_ROOT/compose.kafka.yaml"
readonly ADMIN_SERVICE="${KAFKA_ADMIN_SERVICE:-kafka-1}"
readonly BOOTSTRAP_SERVERS="kafka-1:19092,kafka-2:19092,kafka-3:19092"
readonly CREATE_TOPIC="${NOTIFICATION_CREATE_REQUESTED_TOPIC-local-notification-create-requested-v1}"
readonly DELIVERY_TOPIC="${NOTIFICATION_DELIVERY_TOPIC-local-notification-delivery-v1}"
readonly PARTITIONS=2
readonly REPLICATION_FACTOR=3
readonly MIN_ISR=2

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

validate_topic_name() {
    local topic="$1"
    if [[ ! "$topic" =~ ^[a-zA-Z0-9._-]+$ ]] || [[ ${#topic} -gt 249 ]] \
        || [[ "$topic" == "." || "$topic" == ".." || "$topic" == __* ]]; then
        fail "Invalid notification topic name: $topic"
    fi
}

kafka_command() {
    local tool="$1"
    shift
    docker compose --env-file /dev/null -f "$COMPOSE_FILE" \
        exec -T "$ADMIN_SERVICE" "/opt/kafka/bin/$tool" \
        --bootstrap-server "$BOOTSTRAP_SERVERS" "$@"
}

config_value() {
    local key="$1"
    awk -v key="$key" 'index($1, key "=") == 1 {
        print substr($1, length(key) + 2)
    }'
}

verify_topic() {
    local topic="$1"
    local pattern description configs actual
    pattern="^$(printf '%s' "$topic" | sed 's/\./\\./g')$"
    description="$(kafka_command kafka-topics.sh --describe --topic "$pattern")"
    printf '%s\n' "$description"

    if ! printf '%s\n' "$description" | awk \
        -v topic="$topic" -v partitions="$PARTITIONS" -v replicas="$REPLICATION_FACTOR" '
        $1 == "Topic:" && $2 == topic && /PartitionCount:/ {
            headers++
            for (i = 1; i < NF; i++) {
                if ($i == "PartitionCount:" && $(i + 1) == partitions) count_ok = 1
            }
        }
        $1 == "Topic:" && $2 == topic && $3 == "Partition:" {
            rows++
            for (i = 1; i < NF; i++) {
                if ($i == "Replicas:") {
                    replica_rows++
                    if (split($(i + 1), ids, ",") != replicas) bad = 1
                }
                if ($i == "Adding" || $i == "Removing") bad = 1
            }
        }
        END {
            exit !(headers == 1 && count_ok && rows == partitions && replica_rows == rows && !bad)
        }
    '; then
        fail "$topic must have $PARTITIONS partitions and $REPLICATION_FACTOR replicas per partition, with no reassignment in progress. Existing topics were not altered."
    fi

    configs="$(kafka_command kafka-configs.sh --describe --all \
        --entity-type topics --entity-name "$topic")"
    actual="$(printf '%s\n' "$configs" | config_value min.insync.replicas)"
    [[ "$actual" == "$MIN_ISR" ]] \
        || fail "$topic min.insync.replicas: expected $MIN_ISR, found '${actual:-unknown}'. Existing topics were not altered."
    actual="$(printf '%s\n' "$configs" | config_value cleanup.policy)"
    [[ "$actual" == "delete" ]] \
        || fail "$topic cleanup.policy: expected delete, found '${actual:-unknown}'. Existing topics were not altered."

    printf 'Verified %s: partitions=%s, replication-factor=%s, min.insync.replicas=%s, cleanup.policy=delete\n' \
        "$topic" "$PARTITIONS" "$REPLICATION_FACTOR" "$MIN_ISR"
    printf '%s\n' "$configs" | awk '
        $1 ~ /^(retention.ms|retention.bytes|segment.ms|segment.bytes)=/ { print }
    '
}

validate_topic_name "$CREATE_TOPIC"
validate_topic_name "$DELIVERY_TOPIC"
[[ "$CREATE_TOPIC" != "$DELIVERY_TOPIC" ]] \
    || fail "Creation and delivery topics must have different names."
command -v docker >/dev/null 2>&1 || fail "Docker CLI is required."
docker compose --env-file /dev/null -f "$COMPOSE_FILE" config --quiet

printf 'Initializing local notification topics using Compose service %s.\n' "$ADMIN_SERVICE"
existing_topics="$(kafka_command kafka-topics.sh --list)"

for topic in "$CREATE_TOPIC" "$DELIVERY_TOPIC"; do
    if printf '%s\n' "$existing_topics" | awk -v topic="$topic" '
        $0 == topic { found = 1 }
        END { exit !found }
    '; then
        verify_topic "$topic"
    fi
done

for topic in "$CREATE_TOPIC" "$DELIVERY_TOPIC"; do
    kafka_command kafka-topics.sh --create --if-not-exists \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config "min.insync.replicas=$MIN_ISR" \
        --config cleanup.policy=delete
    verify_topic "$topic"
done

printf 'Local notification topic initialization completed. Retention uses the current cluster/topic configuration; production retention is not defined by this script.\n'
