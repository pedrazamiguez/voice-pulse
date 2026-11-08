# SonarQube Static Analysis & Quality Gate Integration

> [!IMPORTANT]
> **Source of Truth:** Defines configuration parameters, GitHub Actions workflows, repository secrets, and local execution procedures for SonarQube static code analysis and quality gates in VoicePulse (RFC-0001, RFC-0002 §4.5, AGENTS.md §5).

---

## 1. Overview & Architecture

VoicePulse enforces automated static code analysis, security vulnerability scanning, and code quality gate verification across all pull requests and protected branches (`main`, `develop`).

SonarQube analysis runs as part of the CI/CD pipeline via GitHub Actions and can also be executed locally by developers using the Maven Wrapper.

### Key Metadata
* **Project Key:** `VoicePulse`
* **Project Name:** `VoicePulse`
* **Scanner Plugin:** `org.sonarsource.scanner.maven:sonar-maven-plugin:5.0.0.4389`
* **Quality Gate Enforcement:** Synchronous (`sonar.qualitygate.wait=true`, timeout 300s)
* **Coverage Source:** JaCoCo XML reports from all submodules (`target/site/jacoco/jacoco.xml`)

---

## 2. GitHub Repository Secrets

The self-hosted SonarQube instance requires the following GitHub repository secrets:

| Secret Name | Description | Example / Format |
| :--- | :--- | :--- |
| `SONAR_HOST_URL` | Base URL of the SonarQube instance (NAS / private server). | `https://sonar.internal.voicepulse.com` |
| `SONAR_TOKEN` | User or Project Analysis Token generated in SonarQube. | `sqp_0123456789abcdef...` |

Additionally, GitHub Actions automatically provisions the `GITHUB_TOKEN` secret to permit SonarQube pull request decoration and post automated comments if the Quality Gate fails.

### Setting Repository Secrets
1. Navigate to **VoicePulse Repository Settings** > **Secrets and variables** > **Actions**.
2. Click **New repository secret**.
3. Add `SONAR_HOST_URL` with your SonarQube server's base URL.
4. Add `SONAR_TOKEN` with the analysis token created in SonarQube (**User Profile** > **Security** > **Generate Tokens**).

---

## 3. GitHub Actions CI Workflow

The workflow is defined in `.github/workflows/sonar.yml`:

* **Triggers:**
  - Pushes to `main` and `develop`.
  - Pull requests targeting `main` and `develop`.
* **Features:**
  - **Full History:** Uses `actions/checkout@v4` with `fetch-depth: 0` to support accurate Git blame and delta analysis on new code.
  - **Caching:** Caches `~/.sonar/cache` and `~/.m2` dependencies across workflow runs.
  - **Synchronous Gate Polling:** Polls SonarQube until the Quality Gate status is evaluated (`-Dsonar.qualitygate.wait=true`).
  - **Automated PR Failure Commenting:** If the Quality Gate fails on a pull request, `actions/github-script@v7` automatically leaves a markdown comment with a direct link to the SonarQube project dashboard.
  - **Build Failure:** Fails the pull request check run if the Quality Gate fails or coverage thresholds are not met.

---

## 4. Multi-Module JaCoCo Aggregation

VoicePulse is a multi-module Maven reactor:
- `shared-kernel`
- `edge-gateway-service`
- `conversation-engine`
- `routing-handoff-service`

JaCoCo generates individual submodule reports during the `test` phase at:
```
<module>/target/site/jacoco/jacoco.xml
```

The parent `pom.xml` configures the aggregation pattern for the Sonar scanner:
```xml
<sonar.coverage.jacoco.xmlReportPaths>
    ${maven.multiModuleProjectDirectory}/target/site/jacoco/jacoco.xml,
    ${maven.multiModuleProjectDirectory}/**/target/site/jacoco/jacoco.xml
</sonar.coverage.jacoco.xmlReportPaths>
```

### Coverage Exclusions
Standard application entrypoints and Spring Boot configuration classes that contain zero business logic are excluded from coverage calculations:
```xml
<sonar.coverage.exclusions>
    **/VoicePulseApplication.java,
    **/*Application.java,
    **/config/**
</sonar.coverage.exclusions>
```

---

## 5. Local Developer Execution

Developers can run SonarQube analysis against the configured server before pushing or opening a pull request:

### Prerequisites
Export the required environment variables:
```bash
export SONAR_HOST_URL="https://your-sonarqube-host:9000"
export SONAR_TOKEN="sqp_your_personal_token"
```

### Running Analysis via Makefile
```bash
make sonar
```

Or pass the credentials inline:
```bash
SONAR_HOST_URL="https://your-sonarqube-host:9000" SONAR_TOKEN="sqp_xxx" make sonar
```

The target executes:
```bash
./mvnw clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
    -Dsonar.host.url="$SONAR_HOST_URL" \
    -Dsonar.token="$SONAR_TOKEN" \
    -Dsonar.qualitygate.wait=true \
    -Dsonar.qualitygate.timeout=300
```

If `SONAR_HOST_URL` or `SONAR_TOKEN` is unset, the target halts immediately with an informative error message and non-zero exit code.

---

## 6. Integration with AI Tooling (`sonarqube-mcp`)

VoicePulse equips AI coding agents with the `sonarqube-mcp` Model Context Protocol server (see RFC-0002 §4.5).

Agents can query the SonarQube instance to:
- Inspect project quality gate status (`get_project_quality_gate_status`).
- Search for open issues or hotspots in the codebase (`search_sonar_issues_in_projects`, `search_security_hotspots`).
- Retrieve code coverage details by file (`get_file_coverage_details`).
- Check pull request quality status before requesting human review (`list_pull_requests`).
