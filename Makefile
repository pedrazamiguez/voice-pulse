# ─────────────────────────────────────────────────────────────────────────────
# VoicePulse — Developer Bootstrap & Quality Gates
# ─────────────────────────────────────────────────────────────────────────────

.PHONY: help setup hooks fast-check check arch-check format test docker-infra docker-infra-down verify-infra doctor clean ai-setup rag-index test-rag knowledge-update

CYAN   := \033[0;36m
GREEN  := \033[0;32m
YELLOW := \033[1;33m
RED    := \033[0;31m
NC     := \033[0m

MVN    := ./mvnw

help: ## Display available targets
	@printf "\n$(CYAN)VoicePulse — Developer CLI$(NC)\n\n"
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  $(CYAN)%-20s$(NC) %s\n", $$1, $$2}'
	@printf "\n"

setup: hooks docker-infra ai-setup ## Full first-time developer bootstrap
	@printf "$(GREEN)✅ Setup complete. Ready to develop.$(NC)\n"

hooks: ## Install Git pre-commit hook to .git/hooks/pre-commit
	@mkdir -p .git/hooks
	@cp scripts/pre-commit .git/hooks/pre-commit
	@chmod +x .git/hooks/pre-commit
	@printf "$(GREEN)✅ Git pre-commit hook installed.$(NC)\n"

fast-check: ## Rapid incremental check (Spotless + ArchUnit, < 15s)
	@printf "$(YELLOW)⏳ Running fast quality gate (Spotless + ArchUnit)...$(NC)\n"
	@$(MVN) spotless:check test -Dtest=ArchitectureTest -q
	@printf "$(GREEN)✅ Fast check passed (< 15s).$(NC)\n"

check: ## Full cold check (clean, verify, spotbugs, jacoco)
	@printf "$(YELLOW)⏳ Running full cold verification gate...$(NC)\n"
	@$(MVN) clean verify
	@printf "$(GREEN)✅ Full quality check passed (Ready for PR).$(NC)\n"

arch-check: ## ArchUnit test suite & 600-line file ceiling audit
	@printf "$(YELLOW)⏳ Running ArchUnit tests & 600-line audit...$(NC)\n"
	@$(MVN) test -Dtest=ArchitectureTest
	@printf "$(YELLOW)Auditing Java file line counts (<= 600 lines)...$(NC)\n"
	@VIOLATIONS=$$(find . -type f -name "*.java" ! -path "*/test/*" ! -path "*/target/*" -exec wc -l {} + | awk '$$1 > 600 {print $$0}'); \
	if [ -n "$$VIOLATIONS" ]; then \
		printf "$(RED)❌ 600-line ceiling violated:\n$$VIOLATIONS$(NC)\n"; \
		exit 1; \
	fi
	@printf "$(GREEN)✅ Architecture rules and file size ceiling verified.$(NC)\n"

format: ## Apply Spotless code formatting
	@printf "$(YELLOW)⏳ Applying Spotless code formatting...$(NC)\n"
	@$(MVN) spotless:apply -q
	@printf "$(GREEN)✅ Code formatting applied.$(NC)\n"

test: ## Run unit tests across all modules
	@printf "$(YELLOW)⏳ Running unit tests across all modules...$(NC)\n"
	@$(MVN) test -q
	@printf "$(GREEN)✅ Unit tests passed.$(NC)\n"

docker-infra: ## Start local Kafka KRaft, Redis, and PostgreSQL
	@printf "$(YELLOW)⏳ Starting local Docker infrastructure...$(NC)\n"
	@docker compose -f docker-compose.infra.yml up -d
	@printf "$(GREEN)✅ Core infrastructure running in Docker.$(NC)\n"

docker-infra-down: ## Stop local Docker infrastructure
	@printf "$(YELLOW)⏳ Stopping local Docker infrastructure...$(NC)\n"
	@docker compose -f docker-compose.infra.yml down
	@printf "$(GREEN)✅ Infrastructure stopped.$(NC)\n"

verify-infra: ## Run end-to-end infrastructure health verification
	@./scripts/verify-infra.sh

doctor: ## Diagnose developer environment (Java 21, Maven Wrapper, Docker, uv, Git)
	@printf "\n$(CYAN)VoicePulse — Environment Diagnostics$(NC)\n"
	@printf "──────────────────────────────────────────────\n"
	@if command -v java >/dev/null 2>&1; then \
		printf "$(GREEN)✅ Java:          $$(java -version 2>&1 | head -n 1)$(NC)\n"; \
	else \
		printf "$(RED)❌ Java:          Missing (Java 21 required)$(NC)\n"; \
	fi
	@if [ -x ./mvnw ]; then \
		printf "$(GREEN)✅ Maven Wrapper: $$(./mvnw -version 2>&1 | head -n 1)$(NC)\n"; \
	elif command -v mvn >/dev/null 2>&1; then \
		printf "$(YELLOW)⚠️ Maven Wrapper: Missing (found system $$(mvn -version 2>&1 | head -n 1))$(NC)\n"; \
	else \
		printf "$(RED)❌ Maven Wrapper: Missing (./mvnw required)$(NC)\n"; \
	fi
	@if command -v docker >/dev/null 2>&1; then \
		printf "$(GREEN)✅ Docker:        $$(docker --version 2>&1)$(NC)\n"; \
	else \
		printf "$(RED)❌ Docker:        Missing$(NC)\n"; \
	fi
	@if command -v uv >/dev/null 2>&1; then \
		printf "$(GREEN)✅ uv:            $$(uv --version 2>&1)$(NC)\n"; \
	else \
		printf "$(RED)❌ uv:            Missing$(NC)\n"; \
	fi
	@if command -v git >/dev/null 2>&1; then \
		printf "$(GREEN)✅ Git:           $$(git --version 2>&1)$(NC)\n"; \
	else \
		printf "$(RED)❌ Git:           Missing$(NC)\n"; \
	fi
	@printf "──────────────────────────────────────────────\n\n"

clean: ## Clean build artifacts
	@printf "$(YELLOW)⏳ Cleaning build artifacts...$(NC)\n"
	@$(MVN) clean -q
	@printf "$(GREEN)✅ Build artifacts cleaned.$(NC)\n"

ai-setup: ## Bootstrap AI tooling, MCP servers, and local RAG
	@./scripts/ai-setup.sh

rag-index: ## Build or update local documentation vector database
	@printf "$(YELLOW)⏳ Indexing documentation with FastEmbed CPU ONNX...$(NC)\n"
	@uv run scripts/rag_indexer.py
	@printf "$(GREEN)✅ Documentation RAG index up to date.$(NC)\n"

test-rag: ## Run RAG unit and integration tests
	@printf "$(YELLOW)⏳ Running RAG verification test suite...$(NC)\n"
	@uv run scripts/test_rag.py
	@printf "$(GREEN)✅ RAG tests passed.$(NC)\n"

knowledge-update: ## Refresh all AI indexes (voicepulse-rag, graphify, codebase-memory)
	@printf "$(YELLOW)⏳ Refreshing AI code graphs and RAG databases...$(NC)\n"
	@uv run scripts/rag_indexer.py
	@graphify update . 2>/dev/null || true
	@printf "$(GREEN)✅ All AI knowledge graphs up to date.$(NC)\n"
