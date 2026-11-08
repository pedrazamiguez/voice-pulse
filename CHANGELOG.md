# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Tooling & Infrastructure
- chore(release): prepare release v0.1.0 ([#40](https://github.com/pedrazamiguez/voice-pulse/issues/40))
- chore(release): prepare release ([#46](https://github.com/pedrazamiguez/voice-pulse/issues/46))

## [0.1.0] - 2026-11-08

### Added
- feat(arch): initial project scaffolding and hexagonal architecture foundation ([#1](https://github.com/pedrazamiguez/voice-pulse/issues/1))
- feat(qa): implement archunit architectural guardrails & spotless formatting ([#2](https://github.com/pedrazamiguez/voice-pulse/issues/2))
- feat(infra): provision local infrastructure with compose & testcontainers ([#3](https://github.com/pedrazamiguez/voice-pulse/issues/3))
- feat(dx): implement dev-cli for unified environment management ([#4](https://github.com/pedrazamiguez/voice-pulse/issues/4))
- feat(ai): define rig-core client adapter & gemini 2.5 flash integration ([#5](https://github.com/pedrazamiguez/voice-pulse/issues/5))
- feat(ai): implement deepseek-r1:7b fallback provider with testcontainers ([#6](https://github.com/pedrazamiguez/voice-pulse/issues/6))
- feat(rag): scaffold fastembed-rs native vector embedding provider ([#7](https://github.com/pedrazamiguez/voice-pulse/issues/7))
- feat(tooling): configure Maven Wrapper for deterministic builds ([#15](https://github.com/pedrazamiguez/voice-pulse/issues/15))
- feat(ai-workflow): introduce vp-follow-up skill for in-flight scope amendments and post-merge follow-ups ([#34](https://github.com/pedrazamiguez/voice-pulse/issues/34))
- feat(ai-workflow): automate AI knowledge index synchronization across agent workflow and CLI ([#35](https://github.com/pedrazamiguez/voice-pulse/issues/35))

### Tooling & Infrastructure
- ci: configure GitHub Actions CI pipeline ([#8](https://github.com/pedrazamiguez/voice-pulse/issues/8))
- ci(sonar): integrate SonarQube static analysis and quality gates ([#28](https://github.com/pedrazamiguez/voice-pulse/issues/28))
- ci(changelog): automate CHANGELOG.md updates upon issue completion ([#29](https://github.com/pedrazamiguez/voice-pulse/issues/29))
- ci(release): implement automated release pipeline, branch enforcement, and post-release sync ([#32](https://github.com/pedrazamiguez/voice-pulse/issues/32))
- ci(release): configure issue templates with auto-labeling and event trigger support ([#37](https://github.com/pedrazamiguez/voice-pulse/issues/37))
