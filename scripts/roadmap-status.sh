#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# VoicePulse — Roadmap Status & Capability Briefing
# ─────────────────────────────────────────────────────────────────────────────

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROADMAP_FILE="${PROJECT_ROOT}/ROADMAP.md"

if [ ! -f "$ROADMAP_FILE" ]; then
  printf "${RED}❌ Error: ROADMAP.md not found at %s${NC}\n" "$ROADMAP_FILE" >&2
  exit 1
fi

printf "\n${CYAN}══════════════════════════════════════════════════════════════════════════${NC}\n"
printf "${CYAN}  ${BOLD}VoicePulse — Executive Roadmap Status & Capability Briefing${NC}\n"
printf "${CYAN}══════════════════════════════════════════════════════════════════════════${NC}\n\n"

# 1. Git Context
BRANCH="$(git -C "$PROJECT_ROOT" branch --show-current 2>/dev/null || echo "detached")"
LATEST_COMMIT="$(git -C "$PROJECT_ROOT" log -1 --pretty=format:"%h - %s (%cr)" 2>/dev/null || echo "unknown")"
DIRTY_COUNT="$(git -C "$PROJECT_ROOT" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"

printf "${BOLD}📌 Repository & Branch State${NC}\n"
printf "  ${DIM}• Active Branch:${NC}  ${BLUE}%s${NC}\n" "$BRANCH"
printf "  ${DIM}• Latest Commit:${NC}  %s\n" "$LATEST_COMMIT"
if [ "$DIRTY_COUNT" -gt 0 ]; then
  printf "  ${DIM}• Working Tree:${NC}   ${YELLOW}%s modified/untracked file(s)${NC}\n" "$DIRTY_COUNT"
else
  printf "  ${DIM}• Working Tree:${NC}   ${GREEN}Clean (zero uncommitted changes)${NC}\n"
fi
printf "\n"

# 2. Roadmap Milestone Overview
printf "${BOLD}🗺️ Master Roadmap Epics (${ROADMAP_FILE##*/})${NC}\n"
printf "──────────────────────────────────────────────────────────────────────────\n"

awk '
BEGIN { in_table = 0 }
/^\|[[:space:]]*\*\*Epic/ {
  in_table = 1
  # Split columns
  split($0, parts, "|")
  epic = parts[2]
  title = parts[3]
  status = parts[5]

  # Clean markdown formatting
  gsub(/^[[:space:]]+|[[:space:]]+$/, "", epic)
  gsub(/^[[:space:]]+|[[:space:]]+$/, "", title)
  gsub(/^[[:space:]]+|[[:space:]]+$/, "", status)
  gsub(/\*\*/, "", epic)
  gsub(/\*\*/, "", title)
  gsub(/\*\*/, "", status)

  # Status badge color
  if (status ~ /Completed/) {
    badge = "\033[0;32m[COMPLETED]\033[0m"
  } else if (status ~ /Next|Active|In Progress/) {
    badge = "\033[1;33m[NEXT UP]  \033[0m"
  } else {
    badge = "\033[2m[PLANNED]  \033[0m"
  }

  printf "  %-12s %s  %s\n", badge, epic, title
}
in_table && /^---/ { in_table = 0 }
' "$ROADMAP_FILE"

printf "──────────────────────────────────────────────────────────────────────────\n\n"

# 3. Current Platform Capabilities (Plain English)
printf "${BOLD}🚀 Current Platform Capabilities (What Works Today)${NC}\n"
printf "  ${GREEN}✓${NC} ${BOLD}16 kHz Audio Ingestion Gateway:${NC} Non-blocking Netty WebFlux WebSocket at\n"
printf "    ${CYAN}ws://localhost:8080/ws/calls/{callId}/audio${NC} with strict frame validation (<64 KB).\n"
printf "  ${GREEN}✓${NC} ${BOLD}Browser Softphone Playground:${NC} Responsive HTML5 UI with AudioWorklet downsampling\n"
printf "    to 16 kHz Mono PCM, live VU meter, and connection state HUD at ${CYAN}http://localhost:8080/softphone/index.html${NC}.\n"
printf "  ${GREEN}✓${NC} ${BOLD}Distributed Kafka Event Fabric:${NC} KRaft Kafka streaming with high-throughput custom\n"
printf "    zero-allocation binary serializers on ${CYAN}telephony.calls.raw${NC} and strict ${CYAN}callId${NC} partition affinity.\n"
printf "  ${GREEN}✓${NC} ${BOLD}Ordered Worker Pool Consumer:${NC} Monotonic sequence ordering per call in ${CYAN}conversation-engine${NC}\n"
printf "    with dead-letter queue (${CYAN}telephony.calls.dlq${NC}) fallback and backpressure protection.\n"
printf "  ${GREEN}✓${NC} ${BOLD}Zero Memory Leaks:${NC} Explicit ${CYAN}ByteBuf${NC} reference counting release verified with Netty PARANOID leak detection.\n\n"

# 4. Limitations & Testing Context
printf "${BOLD}⚠️ Platform Limitations & Testing Context${NC}\n"
printf "  ${YELLOW}•${NC} Conversational state machine, VAD barge-in interruption, and NATO phonetic PNR verification are next in Epic 4.\n"
printf "  ${YELLOW}•${NC} Live LLM inference and Model Context Protocol (MCP) airline tool servers will follow in Epic 5.\n"
printf "  ${YELLOW}•${NC} ${BOLD}Why Epic 3 is verified headlessly:${NC} Distributed Kafka streaming runs as asynchronous backend infrastructure.\n"
printf "    End-to-end softphone audio loopback currently demonstrates the gateway echo loop; synthetic\n"
printf "    streaming at high volume is verified via multi-module Testcontainers integration tests.\n\n"

# 5. Playbook Verification Commands
printf "${BOLD}🧪 Quick Verification Playbook${NC}\n"
printf "  ${DIM}• Fast Quality Gate (Spotless + ArchUnit):${NC}\n"
printf "    ${CYAN}make fast-check${NC}\n"
printf "  ${DIM}• Full Pre-PR Analysis (ArchUnit, 600-line ceiling, Spotless, SpotBugs):${NC}\n"
printf "    ${CYAN}make pre-pr${NC}\n"
printf "  ${DIM}• Launch Edge Gateway & Browser Softphone:${NC}\n"
printf "    ${CYAN}./mvnw spring-boot:run -pl edge-gateway-service${NC}  ->  Open ${BLUE}http://localhost:8080/softphone/index.html${NC}\n"
printf "  ${DIM}• Run Epic 3 Kafka Distributed Streaming Integration Test:${NC}\n"
printf "    ${CYAN}./mvnw test -pl conversation-engine -Dtest=KafkaStreamingIntegrationTest -Dio.netty.leakDetection.level=PARANOID${NC}\n"
printf "  ${DIM}• Refresh Local AI Knowledge Graphs & RAG:${NC}\n"
printf "    ${CYAN}make knowledge-update${NC}\n\n"
