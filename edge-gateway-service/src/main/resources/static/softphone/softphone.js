/**
 * VoicePulse Web Audio Softphone Client
 *
 * Implements HTML5 microphone capture, AudioWorklet client-side downsampling
 * to 16 kHz Mono Linear PCM, bidirectional binary WebSocket streaming,
 * continuous gapless PCM playback, and real-time telemetry HUD.
 */
class VoicePulseSoftphone {

    constructor() {
        this.ws = null;
        this.audioContext = null;
        this.mediaStream = null;
        this.workletNode = null;
        this.analyserNode = null;
        this.vuAnimId = null;
        this.silentGainNode = null;
        this.pendingTimestamps = [];

        this.callStartTime = null;
        this.callTimerId = null;
        this.isMuted = false;
        this.isConnected = false;
        this.nextPlaybackTime = 0;

        this.metrics = {
            packetsSent: 0,
            packetsRecv: 0,
            bytesSent: 0,
            bytesRecv: 0,
            lastPingTimestamp: null,
            latencyMs: null
        };

        this.dom = {
            callIdInput: document.getElementById('call-id-input'),
            btnGenerateId: document.getElementById('btn-generate-id'),
            btnCall: document.getElementById('btn-call'),
            btnCallLabel: document.getElementById('btn-call-label'),
            btnMute: document.getElementById('btn-mute'),
            btnMuteLabel: document.getElementById('btn-mute-label'),
            connectionBadge: document.getElementById('connection-badge'),
            vuBar: document.getElementById('vu-meter-bar'),
            vuValue: document.getElementById('vu-level-value'),
            metricDuration: document.getElementById('metric-duration'),
            metricLatency: document.getElementById('metric-latency'),
            metricPacketsSent: document.getElementById('metric-packets-sent'),
            metricPacketsRecv: document.getElementById('metric-packets-recv'),
            metricBytesSent: document.getElementById('metric-bytes-sent'),
            metricBytesRecv: document.getElementById('metric-bytes-recv'),
            activityLog: document.getElementById('activity-log'),
            btnClearLog: document.getElementById('btn-clear-log')
        };

        this.bindEvents();
        this.generateRandomCallId();
    }

    bindEvents() {
        this.dom.btnGenerateId.addEventListener('click', () => this.generateRandomCallId());
        this.dom.btnCall.addEventListener('click', () => this.toggleCall());
        this.dom.btnMute.addEventListener('click', () => this.toggleMute());
        this.dom.btnClearLog.addEventListener('click', () => this.clearLog());
    }

    generateRandomCallId() {
        if (this.isConnected) return;
        const uuid = (crypto.randomUUID) ? crypto.randomUUID() : this.fallbackUUID();
        this.dom.callIdInput.value = uuid;
    }

    fallbackUUID() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
            const r = (Math.random() * 16) | 0;
            const v = c === 'x' ? r : (r & 0x3) | 0x8;
            return v.toString(16);
        });
    }

    log(message, type = 'info') {
        const entry = document.createElement('div');
        entry.className = `log-entry log-${type}`;
        const time = new Date().toISOString().substring(11, 19);
        entry.textContent = `[${time}] ${message}`;
        this.dom.activityLog.appendChild(entry);
        this.dom.activityLog.scrollTop = this.dom.activityLog.scrollHeight;
    }

    clearLog() {
        this.dom.activityLog.innerHTML = '';
        this.log('Activity log cleared.', 'info');
    }

    setConnectionState(state) {
        this.dom.connectionBadge.className = 'badge';
        switch (state) {
            case 'DISCONNECTED':
                this.dom.connectionBadge.classList.add('badge-disconnected');
                this.dom.connectionBadge.textContent = 'DISCONNECTED';
                this.dom.btnCall.classList.remove('active-call');
                this.dom.btnCallLabel.textContent = 'Start Call';
                this.dom.btnMute.disabled = true;
                this.dom.callIdInput.disabled = false;
                this.dom.btnGenerateId.disabled = false;
                break;
            case 'CONNECTING':
                this.dom.connectionBadge.classList.add('badge-connecting');
                this.dom.connectionBadge.textContent = 'CONNECTING';
                this.dom.btnCallLabel.textContent = 'Connecting...';
                this.dom.callIdInput.disabled = true;
                this.dom.btnGenerateId.disabled = true;
                break;
            case 'STREAMING':
                this.dom.connectionBadge.classList.add('badge-streaming');
                this.dom.connectionBadge.textContent = 'STREAMING';
                this.dom.btnCall.classList.add('active-call');
                this.dom.btnCallLabel.textContent = 'End Call';
                this.dom.btnMute.disabled = false;
                this.dom.callIdInput.disabled = true;
                this.dom.btnGenerateId.disabled = true;
                break;
        }
    }

    async toggleCall() {
        if (this.isConnected) {
            await this.disconnectCall('Call ended by user.');
        } else {
            await this.connectCall();
        }
    }

    async connectCall() {
        const callId = this.dom.callIdInput.value.trim();
        if (!callId) {
            this.log('Invalid Call Session ID: cannot be blank.', 'error');
            return;
        }

        this.setConnectionState('CONNECTING');
        this.log(`Initiating call session ${callId}...`, 'info');

        try {
            // 1. Capture Microphone First (triggers hardware sample rate negotiation on macOS/Bluetooth)
            this.log('Requesting microphone permission...', 'info');
            this.mediaStream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true
                }
            });

            // 2. Initialize Web Audio Context AFTER mic acquisition
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            this.audioContext = new AudioContextClass({ latencyHint: 'interactive' });
            if (this.audioContext.state === 'suspended') {
                await this.audioContext.resume();
            }

            // Auto-resume if audio hardware changes cause state interruption
            this.audioContext.onstatechange = () => {
                if (this.audioContext && this.audioContext.state === 'suspended' && this.isConnected) {
                    this.audioContext.resume().catch(() => {});
                }
            };

            this.log(`Microphone access granted. Host sample rate: ${this.audioContext.sampleRate} Hz.`, 'success');

            // 3. Load AudioWorklet Processor for 16 kHz Linear PCM downsampling
            await this.audioContext.audioWorklet.addModule('audio-processor.worklet.js');
            this.log('AudioWorklet module loaded: linear-pcm16-downsampler-processor.', 'info');

            // 4. Connect Web Audio Graph
            const micSource = this.audioContext.createMediaStreamSource(this.mediaStream);
            this.workletNode = new AudioWorkletNode(this.audioContext, 'linear-pcm16-downsampler-processor');

            // Chromium Web Audio pull-graph requirement:
            // AudioWorkletNode must terminate at audioContext.destination to be pulled by the audio thread.
            // Route through a muted GainNode (gain = 0.0) so the graph pulls frames without mic audio feedback.
            this.silentGainNode = this.audioContext.createGain();
            this.silentGainNode.gain.value = 0.0;
            this.workletNode.connect(this.silentGainNode);
            this.silentGainNode.connect(this.audioContext.destination);

            // Setup AnalyserNode for visual VU meter
            this.analyserNode = this.audioContext.createAnalyser();
            this.analyserNode.fftSize = 256;
            this.analyserNode.smoothingTimeConstant = 0.3;

            micSource.connect(this.workletNode);
            micSource.connect(this.analyserNode);

            // 4. Connect WebSocket to Edge Gateway
            const protocol = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/ws/calls/${callId}/audio`;
            this.log(`Connecting WebSocket to ${wsUrl}...`, 'info');

            this.ws = new WebSocket(wsUrl);
            this.ws.binaryType = 'arraybuffer';

            this.ws.onopen = () => {
                this.isConnected = true;
                this.setConnectionState('STREAMING');
                this.startDurationTimer();
                this.startVuMeter();
                this.log(`WebSocket connected! Real-time audio streaming active.`, 'success');
            };

            this.ws.onmessage = (event) => {
                if (event.data instanceof ArrayBuffer) {
                    this.handleInboundAudio(event.data);
                } else if (typeof event.data === 'string') {
                    this.handleControlMessage(event.data);
                }
            };

            this.ws.onerror = (err) => {
                this.log(`WebSocket error encountered.`, 'error');
            };

            this.ws.onclose = (event) => {
                this.log(`WebSocket closed (code: ${event.code}, reason: "${event.reason || 'None'}").`, 'warn');
                this.cleanup();
            };

            // 5. Worklet Outbound PCM Audio Streaming
            this.pendingTimestamps = [];
            this.workletNode.port.onmessage = (event) => {
                if (event.data && event.data.type === 'pcm' && this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
                    const chunkBuffer = event.data.buffer;
                    this.pendingTimestamps.push(performance.now());
                    if (this.pendingTimestamps.length > 500) {
                        this.pendingTimestamps.shift();
                    }
                    this.ws.send(chunkBuffer);
                    this.metrics.packetsSent++;
                    this.metrics.bytesSent += chunkBuffer.byteLength;
                    this.updateTelemetryDom();
                }
            };

        } catch (err) {
            this.log(`Failed to start call: ${err.message}`, 'error');
            this.cleanup();
        }
    }

    handleInboundAudio(arrayBuffer) {
        this.metrics.packetsRecv++;
        this.metrics.bytesRecv += arrayBuffer.byteLength;
        if (this.pendingTimestamps && this.pendingTimestamps.length > 0) {
            const sendTime = this.pendingTimestamps.shift();
            const rtt = Math.round(performance.now() - sendTime);
            this.metrics.latencyMs = rtt;
        }
        this.updateTelemetryDom();

        if (!this.audioContext || this.audioContext.state === 'closed') {
            return;
        }

        if (this.audioContext.state === 'suspended') {
            this.audioContext.resume().catch(() => {});
        }

        // Convert 16-bit signed Linear PCM to Float32
        const int16Array = new Int16Array(arrayBuffer);
        const float32Array = new Float32Array(int16Array.length);
        for (let i = 0; i < int16Array.length; i++) {
            float32Array[i] = int16Array[i] / 32768.0;
        }

        // Continuous scheduling on Web Audio Context
        const audioBuffer = this.audioContext.createBuffer(1, float32Array.length, 16000);
        audioBuffer.copyToChannel(float32Array, 0);

        const source = this.audioContext.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(this.audioContext.destination);

        const now = this.audioContext.currentTime;
        if (this.nextPlaybackTime < now || this.nextPlaybackTime > now + 0.1) {
            this.nextPlaybackTime = now + 0.025; // 25ms jitter buffer
        }

        source.start(this.nextPlaybackTime);
        this.nextPlaybackTime += audioBuffer.duration;
    }

    handleControlMessage(data) {
        try {
            const msg = JSON.parse(data);
            if (msg.op === 'INTERRUPT' || msg.type === 'OP_INTERRUPT') {
                this.handleInterrupt();
            }
        } catch (e) {
            if (typeof data === 'string' && data.trim() === 'OP_INTERRUPT') {
                this.handleInterrupt();
            }
        }
    }

    handleInterrupt() {
        if (this.audioContext && this.audioContext.state !== 'closed') {
            this.nextPlaybackTime = this.audioContext.currentTime;
        } else {
            this.nextPlaybackTime = 0;
        }
        this.log('Barge-in interrupt received: local playback buffer flushed.', 'warn');
    }

    toggleMute() {
        if (!this.isConnected || !this.mediaStream) return;
        this.isMuted = !this.isMuted;

        // Mute audio track
        this.mediaStream.getAudioTracks().forEach(track => {
            track.enabled = !this.isMuted;
        });

        // Notify worklet
        if (this.workletNode) {
            this.workletNode.port.postMessage({ type: 'mute', value: this.isMuted });
        }

        if (this.isMuted) {
            this.dom.btnMute.classList.add('active-mute');
            this.dom.btnMute.setAttribute('aria-pressed', 'true');
            this.dom.btnMuteLabel.textContent = 'Unmute Mic';
            this.log('Microphone muted.', 'warn');
        } else {
            this.dom.btnMute.classList.remove('active-mute');
            this.dom.btnMute.setAttribute('aria-pressed', 'false');
            this.dom.btnMuteLabel.textContent = 'Mute Mic';
            this.log('Microphone unmuted.', 'info');
        }
    }

    startVuMeter() {
        if (!this.analyserNode) return;
        const dataArray = new Uint8Array(this.analyserNode.frequencyBinCount);

        const updateVu = () => {
            if (!this.isConnected) {
                this.dom.vuBar.style.width = '0%';
                this.dom.vuValue.textContent = '0%';
                return;
            }

            this.analyserNode.getByteTimeDomainData(dataArray);

            // Calculate Root Mean Square (RMS)
            let sum = 0;
            for (let i = 0; i < dataArray.length; i++) {
                const norm = (dataArray[i] - 128) / 128;
                sum += norm * norm;
            }
            const rms = Math.sqrt(sum / dataArray.length);
            const percent = Math.min(100, Math.round(rms * 250));

            this.dom.vuBar.style.width = `${percent}%`;
            this.dom.vuValue.textContent = `${percent}%`;
            this.dom.vuBar.setAttribute('aria-valuenow', percent);

            this.vuAnimId = requestAnimationFrame(updateVu);
        };

        this.vuAnimId = requestAnimationFrame(updateVu);
    }

    startDurationTimer() {
        this.callStartTime = Date.now();
        this.callTimerId = setInterval(() => {
            if (!this.isConnected) return;
            const elapsedSeconds = Math.floor((Date.now() - this.callStartTime) / 1000);
            const mins = String(Math.floor(elapsedSeconds / 60)).padStart(2, '0');
            const secs = String(elapsedSeconds % 60).padStart(2, '0');
            this.dom.metricDuration.textContent = `${mins}:${secs}`;
        }, 1000);
    }

    updateTelemetryDom() {
        this.dom.metricPacketsSent.textContent = this.metrics.packetsSent.toLocaleString();
        this.dom.metricPacketsRecv.textContent = this.metrics.packetsRecv.toLocaleString();
        this.dom.metricBytesSent.textContent = `${(this.metrics.bytesSent / 1024).toFixed(1)} KB`;
        this.dom.metricBytesRecv.textContent = `${(this.metrics.bytesRecv / 1024).toFixed(1)} KB`;
        if (this.metrics.latencyMs !== null) {
            this.dom.metricLatency.textContent = `${this.metrics.latencyMs} ms`;
        }
    }

    async disconnectCall(reason = 'Disconnected') {
        this.log(reason, 'info');
        if (this.ws) {
            this.ws.close(1000, 'User hung up');
            this.ws = null;
        }
        this.cleanup();
    }

    cleanup() {
        this.isConnected = false;
        this.setConnectionState('DISCONNECTED');

        if (this.callTimerId) {
            clearInterval(this.callTimerId);
            this.callTimerId = null;
        }

        if (this.vuAnimId) {
            cancelAnimationFrame(this.vuAnimId);
            this.vuAnimId = null;
        }

        if (this.silentGainNode) {
            try {
                this.silentGainNode.disconnect();
            } catch (e) {
                // Ignore disconnect error
            }
            this.silentGainNode = null;
        }
        this.pendingTimestamps = [];

        if (this.mediaStream) {
            this.mediaStream.getTracks().forEach(track => track.stop());
            this.mediaStream = null;
        }

        if (this.workletNode) {
            this.workletNode.disconnect();
            this.workletNode = null;
        }

        if (this.analyserNode) {
            this.analyserNode.disconnect();
            this.analyserNode = null;
        }

        if (this.audioContext && this.audioContext.state !== 'closed') {
            this.audioContext.close();
            this.audioContext = null;
        }

        this.dom.vuBar.style.width = '0%';
        this.dom.vuValue.textContent = '0%';
        this.nextPlaybackTime = 0;
        this.isMuted = false;
        this.dom.btnMute.classList.remove('active-mute');
        this.dom.btnMute.setAttribute('aria-pressed', 'false');
        this.dom.btnMuteLabel.textContent = 'Mute Mic';
    }
}

// Instantiate on DOM load
window.addEventListener('DOMContentLoaded', () => {
    window.voicePulseSoftphone = new VoicePulseSoftphone();
});
