#!/usr/bin/env bash
set -euo pipefail

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CBM_BIN="${HOME}/.local/bin/codebase-memory-mcp"
GEMINI_MCP="${HOME}/.gemini/config/mcp_config.json"

printf "\n${CYAN}══════════════════════════════════════════════${NC}\n"
printf "${CYAN}  VoicePulse — AI Tooling & RAG Bootstrapper  ${NC}\n"
printf "${CYAN}══════════════════════════════════════════════${NC}\n\n"

# 1. Install / verify uv (Python tool manager)
if ! command -v uv &>/dev/null; then
  printf "${YELLOW}Installing uv...${NC}\n"
  curl -LsSf https://astral.sh/uv/install.sh | sh
else
  printf "${GREEN}✅ uv is installed: $(uv --version)${NC}\n"
fi

# 2. Install / verify codebase-memory-mcp (AST Code Graph)
if [ ! -x "$CBM_BIN" ]; then
  printf "${YELLOW}Installing codebase-memory-mcp...${NC}\n"
  curl -LsSf https://raw.githubusercontent.com/DeusData/codebase-memory-mcp/main/install.sh | bash || true
else
  printf "${GREEN}✅ codebase-memory-mcp is installed at ${CBM_BIN}${NC}\n"
fi

# 3. Install / verify Graphify
if ! command -v graphify &>/dev/null; then
  printf "${YELLOW}Installing graphify...${NC}\n"
  uv tool install graphifyy || true
else
  printf "${GREEN}✅ graphify is installed${NC}\n"
fi

# 4. Build / update Local Documentation RAG Index
printf "${YELLOW}Building VoicePulse documentation RAG index...${NC}\n"
uv run "${PROJECT_ROOT}/scripts/rag_indexer.py"

# 5. Register voicepulse-rag in Gemini / Antigravity MCP config
if [ -f "$GEMINI_MCP" ]; then
  python3 -c "
import json
path = '$GEMINI_MCP'
with open(path) as f:
    cfg = json.load(f)
servers = cfg.setdefault('mcpServers', {})
servers['voicepulse-rag'] = {
    'command': 'uv',
    'args': ['run', '${PROJECT_ROOT}/scripts/rag_mcp_server.py']
}
with open(path, 'w') as f:
    json.dump(cfg, f, indent=2)
"
  printf "${GREEN}✅ voicepulse-rag registered in Antigravity MCP config (${GEMINI_MCP}).${NC}\n"
else
  printf "${YELLOW}ℹ️ Antigravity MCP config not found at ${GEMINI_MCP}. Skipping registration.${NC}\n"
fi

printf "\n${GREEN}✅ AI Setup Complete. Ready to develop.${NC}\n\n"
