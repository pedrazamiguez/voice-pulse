# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- feat(ai-workflow): introduce vp-manage-roadmap skill for project progress briefing, testing guidance, and roadmap governance ([#94](https://github.com/pedrazamiguez/voice-pulse/issues/94))

## [0.4.0] - 2026-11-10
### Added
- feat(kafka): define domain audio event envelopes, control schemas, and binary serializers ([#63](https://github.com/pedrazamiguez/voice-pulse/issues/63))
- feat(tooling): overhaul vp-start-issue definition of done with automated PR creation and CI verification gate ([#74](https://github.com/pedrazamiguez/voice-pulse/issues/74))
- feat(arch): standardize object mapping with MapStruct and establish agent directives ([#73](https://github.com/pedrazamiguez/voice-pulse/issues/73))
- feat(tooling): establish testing standards with Instancio, Mockito, Awaitability, and enforce import hygiene ([#77](https://github.com/pedrazamiguez/voice-pulse/issues/77))
- feat(gateway): implement reactive KafkaAudioFramePublisher adapter with partition key mandate ([#64](https://github.com/pedrazamiguez/voice-pulse/issues/64))
- feat(infra): provision Kafka KRaft streaming topics, dead-letter queue, and reactive backpressure ([#65](https://github.com/pedrazamiguez/voice-pulse/issues/65))
- feat(conversation): implement in-order sequential Kafka worker pool and monotonic sequence verifier ([#66](https://github.com/pedrazamiguez/voice-pulse/issues/66))
- test(kafka): implement end-to-end multi-module Kafka streaming integration test with Testcontainers ([#67](https://github.com/pedrazamiguez/voice-pulse/issues/67))

### Changed
- refactor(tooling): introduce Lombok for infrastructure logging and constructor injection with domain purity guardrails ([#59](https://github.com/pedrazamiguez/voice-pulse/issues/59))
- refactor(gateway): decompose LoopbackAudioEchoAdapter and extract dedicated telemetry tracker and URI parser ([#85](https://github.com/pedrazamiguez/voice-pulse/issues/85))
- Epic 3: High-Throughput Kafka Event Bus & In-Order Worker Pool ([#68](https://github.com/pedrazamiguez/voice-pulse/issues/68))

### Fixed
- fix(ci): resolve webhook race condition and unhandled 422 errors in branch provisioning workflow ([#87](https://github.com/pedrazamiguez/voice-pulse/issues/87))

### Tooling & Infrastructure
- ci(tooling): automate dependency updates with versions-maven-plugin and weekly PR workflow ([#78](https://github.com/pedrazamiguez/voice-pulse/issues/78))

## [0.3.0] - 2026-11-08
### Added
- feat(gateway): define domain models, audio frame records, and hexagonal ports ([#23](https://github.com/pedrazamiguez/voice-pulse/issues/23))
- feat(ai-workflow): add vp-review-pr skill for automated PR compliance and plan validation ([#51](https://github.com/pedrazamiguez/voice-pulse/issues/51))
- feat(gateway): implement reactive Netty AudioWebSocketHandler with ByteBuf memory safety ([#24](https://github.com/pedrazamiguez/voice-pulse/issues/24))
- feat(softphone): implement browser AudioWorklet PCM downsampler and WebSocket client UI ([#25](https://github.com/pedrazamiguez/voice-pulse/issues/25))
- feat(gateway): implement loopback audio echo adapter with RTT latency tracking ([#26](https://github.com/pedrazamiguez/voice-pulse/issues/26))
- test(gateway): implement end-to-end WebSocket audio integration test with Paranoid leak check ([#27](https://github.com/pedrazamiguez/voice-pulse/issues/27))

### Changed
- Epic 2: Ingestion Voice Gateway & Softphone Echo Loop ([#20](https://github.com/pedrazamiguez/voice-pulse/issues/20))

### Tooling & Infrastructure
- ci(changelog): exclude release lifecycle issues from automated changelog updates ([#49](https://github.com/pedrazamiguez/voice-pulse/issues/49))

## [0.2.0] - 2026-11-08

### Fixed
- fix(release): remove version prompt from template and drop legacy VP prefix from PR titles ([#42](https://github.com/pedrazamiguez/voice-pulse/pull/42))
- fix(release): make release workflows trigger reliably on PR merge and remove skip-ci tags ([#44](https://github.com/pedrazamiguez/voice-pulse/pull/44))

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
