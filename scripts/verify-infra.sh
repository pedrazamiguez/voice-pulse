#!/usr/bin/env bash
set -euo pipefail

# VoicePulse Local Infrastructure Verification Script (VP-03)
# Validates container health, Kafka topic topologies, Redis connectivity, and PostgreSQL JSONB operations.

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[PASS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    exit 1
}

# 1. Ensure docker compose stack is up
log_info "Ensuring local infrastructure containers are running..."
docker compose -f docker-compose.infra.yml up -d

# 2. Wait for Healthcheck Status
wait_for_healthy() {
    local container_name=$1
    local max_wait_sec=${2:-60}
    local elapsed=0

    log_info "Awaiting healthy status for ${container_name} (max ${max_wait_sec}s)..."
    while [ $elapsed -lt "$max_wait_sec" ]; do
        local health_status
        health_status=$(docker inspect --format='{{json .State.Health.Status}}' "$container_name" 2>/dev/null || echo '"unknown"')
        if [ "$health_status" = '"healthy"' ]; then
            log_success "${container_name} is healthy (${elapsed}s elapsed)"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    log_fail "${container_name} failed to become healthy within ${max_wait_sec}s (last status: ${health_status})"
}

wait_for_healthy "voicepulse-kafka" 60
wait_for_healthy "voicepulse-redis" 30
wait_for_healthy "voicepulse-postgres" 30

# 3. Verify Kafka Topic Provisioning
log_info "Verifying Kafka init container completion..."
wait_for_init() {
    local max_wait=30
    local elapsed=0
    while [ $elapsed -lt "$max_wait" ]; do
        local exit_code
        exit_code=$(docker inspect --format='{{.State.ExitCode}}' "voicepulse-kafka-init" 2>/dev/null || echo "1")
        local running
        running=$(docker inspect --format='{{.State.Running}}' "voicepulse-kafka-init" 2>/dev/null || echo "true")
        if [ "$running" = "false" ] && [ "$exit_code" = "0" ]; then
            log_success "voicepulse-kafka-init completed with exit code 0"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    log_fail "voicepulse-kafka-init did not exit with code 0 within ${max_wait}s"
}
wait_for_init

verify_topic_partitions() {
    local topic=$1
    local expected_partitions=$2

    log_info "Checking topic '${topic}' partition count (expected: ${expected_partitions})..."
    local describe_output
    describe_output=$(docker exec voicepulse-kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic "$topic" 2>/dev/null)
    
    local partition_count
    partition_count=$(echo "$describe_output" | grep -m1 "PartitionCount:" | sed -E 's/.*PartitionCount:[[:space:]]*([0-9]+).*/\1/')

    if [ "$partition_count" = "$expected_partitions" ]; then
        log_success "Topic '${topic}' verified with ${partition_count} partitions"
    else
        log_fail "Topic '${topic}' expected ${expected_partitions} partitions, but found: '${partition_count}'"
    fi
}

verify_topic_partitions "telephony.calls.raw" 12
verify_topic_partitions "telephony.calls.control" 12
verify_topic_partitions "telephony.tickets.intent" 6
verify_topic_partitions "telephony.calls.dlq" 3

# 4. Redis Connectivity and Key Write/Read Check
log_info "Verifying Redis ping and key read/write..."
REDIS_PING=$(docker exec voicepulse-redis redis-cli ping | tr -d '\r')
if [ "$REDIS_PING" != "PONG" ]; then
    log_fail "Redis ping returned unexpected response: ${REDIS_PING}"
fi

docker exec voicepulse-redis redis-cli set operator:op-101:presence "AVAILABLE" ex 60 >/dev/null
REDIS_GET=$(docker exec voicepulse-redis redis-cli get operator:op-101:presence | tr -d '\r')
if [ "$REDIS_GET" != "AVAILABLE" ]; then
    log_fail "Redis key read returned unexpected response: ${REDIS_GET}"
fi
docker exec voicepulse-redis redis-cli del operator:op-101:presence >/dev/null
log_success "Redis connectivity and session key storage verified"

# 5. PostgreSQL DDL and JSONB Operator Verification
log_info "Verifying PostgreSQL schema tables and JSONB queries..."

TABLE_COUNT=$(docker exec -i voicepulse-postgres psql -U voicepulse -d voicepulse -t -A -c \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('call_sessions', 'disruption_tickets', 'operator_handoff_logs');")

if [ "$TABLE_COUNT" != "3" ]; then
    log_fail "Expected 3 tables in PostgreSQL, found: ${TABLE_COUNT}"
fi
log_success "Found 3 required PostgreSQL schema tables"

TEST_CALL_ID="11111111-1111-1111-1111-111111111111"
TEST_TICKET_ID="TKT-TEST-001"

docker exec -i voicepulse-postgres psql -U voicepulse -d voicepulse >/dev/null <<EOSQL
DELETE FROM disruption_tickets WHERE ticket_id = '${TEST_TICKET_ID}';
DELETE FROM call_sessions WHERE call_id = '${TEST_CALL_ID}';

INSERT INTO call_sessions (call_id, pnr, caller_number, status)
VALUES ('${TEST_CALL_ID}', 'A8F9X2', '+34600000000', 'CONNECTED');

INSERT INTO disruption_tickets (
    ticket_id, call_id, pnr, passenger_name, original_flight_number,
    disruption_reason, rebooking_preference, hotel_required, special_assistance,
    status, priority, summary, payload
) VALUES (
    '${TEST_TICKET_ID}', '${TEST_CALL_ID}', 'A8F9X2', 'Andres Pedraza', 'SW-104',
    'CANCELED_WEATHER', 'NEXT_AVAILABLE', TRUE, FALSE,
    'OPEN', 'HIGH', 'Test cancellation ticket',
    '{"party_size": 2, "requires_accessibility": false, "meal_voucher_issued": true}'::jsonb
);
EOSQL

JSONB_QUERY_RESULT=$(docker exec -i voicepulse-postgres psql -U voicepulse -d voicepulse -t -A -c \
    "SELECT ticket_id FROM disruption_tickets WHERE payload->>'party_size' = '2';")

if [ "$JSONB_QUERY_RESULT" != "$TEST_TICKET_ID" ]; then
    log_fail "PostgreSQL JSONB query returned '${JSONB_QUERY_RESULT}', expected '${TEST_TICKET_ID}'"
fi
log_success "PostgreSQL JSONB operators query verified (party_size = 2 matched)"

# Cleanup test records
docker exec -i voicepulse-postgres psql -U voicepulse -d voicepulse >/dev/null <<EOSQL
DELETE FROM disruption_tickets WHERE ticket_id = '${TEST_TICKET_ID}';
DELETE FROM call_sessions WHERE call_id = '${TEST_CALL_ID}';
EOSQL
log_success "PostgreSQL test record cleaned up"

echo ""
log_success "All VoicePulse infrastructure services verified successfully!"
