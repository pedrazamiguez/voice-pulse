# Operational Metrics, SLIs & Service Level Objectives (SLOs)

> [!IMPORTANT]
> **Source of Truth:** Defines the telemetry metrics catalog, Service Level Indicators (SLIs), and Service Level Objectives (SLOs) for VoicePulse.

---

## 1. Core Latency Service Level Objectives (SLOs)

In real-time telephony, latency directly dictates user experience. A half-second delay feels unnatural and prompts callers to speak over the bot.

| Metric (SLI) | Target (p50) | Target (p95) | Target (p99) | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Time-to-First-Audio (TTFA)** | < 180 ms | < 300 ms | < 500 ms | Time elapsed between caller finishing utterance (VAD silence) and edge gateway streaming first TTS audio chunk back to caller. |
| **Barge-in Interrupt Latency** | < 25 ms | < 50 ms | < 80 ms | Time elapsed between caller speech detection and total purge of outbound audio buffer at edge gateway. |
| **Audio Frame Ingestion Latency** | < 5 ms | < 15 ms | < 30 ms | Time from WebSocket frame receipt to Kafka partition commit. |
| **Ticket Handoff Dispatch** | < 100 ms | < 250 ms | < 500 ms | Time from ticket compilation completion to operator UI push notification. |

---

## 2. Telemetry & Prometheus Metrics Catalog

All services expose Prometheus metrics via Spring Boot Actuator (`/actuator/prometheus`):

### Telephony & Audio Ingestion
* `voicepulse_active_calls{gateway_id="gw-01"}`: Current concurrent open WebSockets.
* `voicepulse_audio_frames_ingested_total{codec="pcm_16k"}`: Counter of audio frames processed.
* `voicepulse_audio_frames_dropped_total{reason="out_of_order"}`: Frames dropped due to sequence violations.
* `voicepulse_bargein_events_total`: Total count of caller interruptions detected.

### Latency Histograms
* `voicepulse_ttfa_seconds`: Latency distribution of conversational turn responses.
* `voicepulse_bargein_purge_seconds`: Latency distribution of playback interrupt buffer flush.
* `voicepulse_llm_inference_seconds`: Turn generation time in the Conversation Engine.

### Infrastructure & Memory Health
* `jvm_buffer_total_capacity_bytes{id="direct"}`: Netty direct off-heap memory usage.
* `kafka_consumergroup_lag{topic="telephony.calls.raw"}`: Consumer lag per partition.
* `voicepulse_active_operators{skill="DISRUPTION_DESK_GOLD"}`: Available operators in Redis.

---

## 3. Alerting Thresholds

| Alert Name | Condition | Severity | Action |
| :--- | :--- | :--- | :--- |
| **HighConsumerLag** | `kafka_consumergroup_lag > 500` for 30s | Critical | Auto-scale conversation engine consumer replicas. |
| **NettyDirectMemoryLeak** | `jvm_buffer_total_capacity_bytes{id="direct"} > 80% limit` | Critical | Inspect unreleased `ByteBuf` instances; drain gateway instance. |
| **TTFADegradation** | `p95(voicepulse_ttfa_seconds) > 500ms` for 1m | Warning | Check STT / TTS external API provider latencies. |
| **ExcessiveAudioDrops** | `rate(voicepulse_audio_frames_dropped_total[1m]) > 5` | Warning | Check client network stability and Kafka partition balancing. |
