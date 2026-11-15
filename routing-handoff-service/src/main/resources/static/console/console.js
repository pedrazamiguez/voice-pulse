/**
 * VoicePulse Operator Web Console Client
 *
 * Manages operator presence registration, reactive WebSocket ticket/transcript/queue dispatch,
 * REST manual queue claiming and handoff completion, and 16 kHz Mono Linear PCM AudioWorklet
 * bridging to edge-gateway-service.
 */
class OperatorWebConsole {

    constructor() {
        this.config = {
            gatewayWsBaseUrl: 'ws://localhost:8080',
            heartbeatIntervalMs: 10000,
            skillGroups: [
                'DISRUPTION_DESK_GOLD',
                'DISRUPTION_DESK_ACCESSIBILITY',
                'DISRUPTION_DESK_PRIORITY',
                'DISRUPTION_DESK_STANDARD'
            ],
            availabilityStatuses: ['AVAILABLE', 'PAUSED', 'OFFLINE', 'BUSY']
        };

        this.operatorId = null;
        this.signalingWs = null;
        this.heartbeatTimerId = null;
        this.activeAssignment = null;

        this.audioWs = null;
        this.audioContext = null;
        this.mediaStream = null;
        this.workletNode = null;
        this.analyserNode = null;
        this.silentGainNode = null;
        this.vuAnimId = null;
        this.isAudioConnected = false;
        this.isMuted = false;
        this.nextPlaybackTime = 0;
        this.audioPacketsSent = 0;
        this.audioPacketsRecv = 0;

        this.dom = {
            operatorIdInput: document.getElementById('operator-id-input'),
            displayNameInput: document.getElementById('display-name-input'),
            skillCheckboxes: document.getElementById('skill-checkboxes'),
            btnConnect: document.getElementById('btn-connect'),
            btnDisconnect: document.getElementById('btn-disconnect'),
            presenceBadge: document.getElementById('presence-badge'),
            wsStatusBadge: document.getElementById('ws-status-badge'),
            heartbeatIndicator: document.getElementById('heartbeat-indicator'),
            audioStatusBadge: document.getElementById('audio-status-badge'),
            presenceButtons: Array.from(document.querySelectorAll('.btn-presence')),
            queueSkillFilter: document.getElementById('queue-skill-filter'),
            btnRefreshQueue: document.getElementById('btn-refresh-queue'),
            btnClaimNext: document.getElementById('btn-claim-next'),
            queueDepthPills: document.getElementById('queue-depth-pills'),
            queueTableBody: document.getElementById('queue-table-body'),
            ticketPriorityBadge: document.getElementById('ticket-priority-badge'),
            ticketEmptyState: document.getElementById('ticket-empty-state'),
            ticketDetails: document.getElementById('ticket-details'),
            ticketPassenger: document.getElementById('ticket-passenger'),
            ticketPnr: document.getElementById('ticket-pnr'),
            ticketFlight: document.getElementById('ticket-flight'),
            ticketReason: document.getElementById('ticket-reason'),
            ticketPreference: document.getElementById('ticket-preference'),
            ticketSkill: document.getElementById('ticket-skill'),
            ticketHotel: document.getElementById('ticket-hotel'),
            ticketAssistance: document.getElementById('ticket-assistance'),
            ticketCallerNumber: document.getElementById('ticket-caller-number'),
            ticketCategory: document.getElementById('ticket-category'),
            ticketIds: document.getElementById('ticket-ids'),
            ticketSummary: document.getElementById('ticket-summary'),
            resolutionNotesInput: document.getElementById('resolution-notes-input'),
            btnCompleteHandoff: document.getElementById('btn-complete-handoff'),
            transcriptTurnCount: document.getElementById('transcript-turn-count'),
            transcriptPanel: document.getElementById('transcript-panel'),
            audioBridgeUrl: document.getElementById('audio-bridge-url'),
            btnAudioConnect: document.getElementById('btn-audio-connect'),
            btnAudioMute: document.getElementById('btn-audio-mute'),
            audioVuBar: document.getElementById('audio-vu-bar'),
            audioVuValue: document.getElementById('audio-vu-value'),
            metricAudioSent: document.getElementById('metric-audio-sent'),
            metricAudioRecv: document.getElementById('metric-audio-recv'),
            consoleLog: document.getElementById('console-log'),
            btnClearLog: document.getElementById('btn-clear-log')
        };

        this.bindEvents();
        this.initialize();
    }

    bindEvents() {
        this.dom.btnConnect.addEventListener('click', () => this.connectConsole());
        this.dom.btnDisconnect.addEventListener('click', () => this.disconnectConsole());
        this.dom.presenceButtons.forEach((btn) => {
            btn.addEventListener('click', () => this.updatePresence(btn.dataset.status));
        });
        this.dom.queueSkillFilter.addEventListener('change', () => this.refreshQueue());
        this.dom.btnRefreshQueue.addEventListener('click', () => this.refreshQueue());
        this.dom.btnClaimNext.addEventListener('click', () => this.claimCall(null));
        this.dom.btnCompleteHandoff.addEventListener('click', () => this.completeActiveHandoff());
        this.dom.btnAudioConnect.addEventListener('click', () => this.toggleAudioBridge());
        this.dom.btnAudioMute.addEventListener('click', () => this.toggleAudioMute());
        this.dom.btnClearLog.addEventListener('click', () => {
            this.dom.consoleLog.textContent = '';
            this.log('Console log cleared.', 'info');
        });
    }

    async initialize() {
        this.log('Loading console bootstrap configuration...', 'info');
        try {
            const response = await fetch('/api/operators/console/config');
            if (response.ok) {
                const loaded = await response.json();
                this.config = { ...this.config, ...loaded };
                this.log(
                    `Config loaded (gatewayWsBaseUrl=${this.config.gatewayWsBaseUrl}, heartbeat=${this.config.heartbeatIntervalMs}ms).`,
                    'success'
                );
            }
        } catch (err) {
            this.log(`Using fallback console config: ${err.message}`, 'warn');
        }
        this.renderSkillInputs();
        await this.refreshQueue();
    }

    renderSkillInputs() {
        this.dom.skillCheckboxes.textContent = '';
        this.dom.queueSkillFilter.textContent = '';

        const allOption = document.createElement('option');
        allOption.value = '';
        allOption.textContent = 'ALL SKILLS';
        this.dom.queueSkillFilter.appendChild(allOption);

        this.config.skillGroups.forEach((skill) => {
            const label = document.createElement('label');
            label.className = 'skill-checkbox-item';

            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.value = skill;
            checkbox.checked = true;

            const span = document.createElement('span');
            span.textContent = skill;

            label.appendChild(checkbox);
            label.appendChild(span);
            this.dom.skillCheckboxes.appendChild(label);

            const opt = document.createElement('option');
            opt.value = skill;
            opt.textContent = skill;
            this.dom.queueSkillFilter.appendChild(opt);
        });
    }

    selectedSkillGroups() {
        const inputs = Array.from(this.dom.skillCheckboxes.querySelectorAll('input[type="checkbox"]'));
        return inputs.filter((el) => el.checked).map((el) => el.value);
    }

    async connectConsole() {
        const operatorId = this.dom.operatorIdInput.value.trim();
        const displayName = this.dom.displayNameInput.value.trim();
        const skills = this.selectedSkillGroups();

        if (!operatorId || !displayName) {
            this.log('Operator ID and Display Name are required.', 'error');
            return;
        }
        if (skills.length === 0) {
            this.log('Select at least one skill group.', 'error');
            return;
        }

        this.operatorId = operatorId;
        this.setConnectedUiState(true);
        await this.updatePresence('AVAILABLE');
        this.openSignalingWebSocket(operatorId);
    }

    async disconnectConsole() {
        if (this.operatorId) {
            try {
                await this.updatePresence('OFFLINE');
            } catch (ignored) {
                // Ignore disconnect presence failure
            }
        }
        await this.disconnectAudioBridge();
        this.closeSignalingWebSocket();
        this.setConnectedUiState(false);
        this.log('Operator console disconnected.', 'info');
    }

    setConnectedUiState(connected) {
        this.dom.operatorIdInput.disabled = connected;
        this.dom.btnConnect.disabled = connected;
        this.dom.btnDisconnect.disabled = !connected;
        this.dom.btnClaimNext.disabled = !connected;
        this.dom.presenceButtons.forEach((btn) => {
            btn.disabled = !connected;
        });
    }

    openSignalingWebSocket(operatorId) {
        this.closeSignalingWebSocket();
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws/operators/${encodeURIComponent(operatorId)}`;
        this.setWsBadge('CONNECTING');
        this.log(`Connecting signaling WebSocket to ${wsUrl}...`, 'info');

        this.signalingWs = new WebSocket(wsUrl);
        this.signalingWs.onopen = () => {
            this.setWsBadge('CONNECTED');
            this.startHeartbeatLoop();
            this.log(`Signaling WebSocket connected for ${operatorId}.`, 'success');
        };
        this.signalingWs.onmessage = (event) => {
            this.handleSignalingMessage(event.data);
        };
        this.signalingWs.onerror = () => {
            this.log('Signaling WebSocket error.', 'error');
        };
        this.signalingWs.onclose = (event) => {
            this.stopHeartbeatLoop();
            this.setWsBadge('DISCONNECTED');
            this.log(`Signaling WebSocket closed (code=${event.code}).`, 'warn');
        };
    }

    closeSignalingWebSocket() {
        this.stopHeartbeatLoop();
        if (this.signalingWs) {
            this.signalingWs.close(1000, 'Operator disconnected');
            this.signalingWs = null;
        }
        this.setWsBadge('DISCONNECTED');
    }

    startHeartbeatLoop() {
        this.stopHeartbeatLoop();
        const intervalMs = Math.max(2000, Number(this.config.heartbeatIntervalMs) || 10000);
        this.sendWsCommand('HEARTBEAT');
        this.heartbeatTimerId = setInterval(() => {
            this.sendWsCommand('HEARTBEAT');
        }, intervalMs);
    }

    stopHeartbeatLoop() {
        if (this.heartbeatTimerId) {
            clearInterval(this.heartbeatTimerId);
            this.heartbeatTimerId = null;
        }
        this.dom.heartbeatIndicator.className = 'heartbeat-dot heartbeat-idle';
        this.dom.heartbeatIndicator.textContent = 'IDLE';
    }

    sendWsCommand(type) {
        if (this.signalingWs && this.signalingWs.readyState === WebSocket.OPEN) {
            this.signalingWs.send(JSON.stringify({ type, clientTime: new Date().toISOString() }));
        }
    }

    handleSignalingMessage(rawJson) {
        try {
            const msg = JSON.parse(rawJson);
            switch (msg.type) {
                case 'CONSOLE_SNAPSHOT':
                    if (msg.presence) {
                        this.renderPresence(msg.presence);
                    }
                    if (msg.queueOverview) {
                        this.renderQueueOverview(msg.queueOverview);
                    }
                    if (msg.activeAssignment) {
                        this.renderAssignment(msg.activeAssignment);
                    } else {
                        this.clearAssignment();
                    }
                    this.log('Console snapshot synchronized.', 'info');
                    break;
                case 'ASSIGNMENT':
                    if (msg.assignment) {
                        this.renderAssignment(msg.assignment);
                        this.renderPresenceStatusOnly('BUSY');
                        this.log(
                            `Assigned call ${msg.assignment.callId} (PNR ${msg.assignment.pnr}).`,
                            'success'
                        );
                        this.refreshQueue();
                    }
                    break;
                case 'QUEUE_METRICS':
                    this.renderDepthMap(msg.depthsBySkillGroup || {});
                    this.refreshQueue();
                    break;
                case 'HANDOFF_STATE':
                    if (msg.state) {
                        this.log(
                            `Handoff state -> ${msg.state.status} (callId=${msg.state.callId}).`,
                            'info'
                        );
                        if (msg.state.status === 'COMPLETED' || msg.state.status === 'ABANDONED') {
                            this.clearAssignment();
                            this.disconnectAudioBridge();
                            this.refreshPresence();
                            this.refreshQueue();
                        }
                    }
                    break;
                case 'PONG':
                    this.dom.heartbeatIndicator.className = 'heartbeat-dot heartbeat-active';
                    this.dom.heartbeatIndicator.textContent = `OK ${new Date().toISOString().substring(11, 19)}`;
                    break;
                case 'ERROR':
                    this.log(`Console WS error [${msg.code}]: ${msg.message}`, 'error');
                    break;
                default:
                    this.log(`Unhandled WS message type: ${msg.type}`, 'warn');
            }
        } catch (err) {
            this.log(`Failed to parse WS message: ${err.message}`, 'error');
        }
    }

    async updatePresence(status) {
        if (!this.operatorId) return;
        const displayName = this.dom.displayNameInput.value.trim();
        const skillGroups = this.selectedSkillGroups();
        try {
            const res = await fetch(`/api/operators/${encodeURIComponent(this.operatorId)}/presence`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ displayName, status, skillGroups })
            });
            const body = await res.json();
            if (!res.ok) {
                this.log(`Presence update failed: ${body.message || res.status}`, 'error');
                return;
            }
            this.renderPresence(body);
            this.log(`Presence updated -> ${body.status}.`, 'success');
            if (body.status === 'BUSY' && body.activeCallId && !this.activeAssignment) {
                this.sendWsCommand('REQUEST_SNAPSHOT');
            }
        } catch (err) {
            this.log(`Presence update error: ${err.message}`, 'error');
        }
    }

    async refreshPresence() {
        if (!this.operatorId) return;
        try {
            const res = await fetch(`/api/operators/${encodeURIComponent(this.operatorId)}/presence`);
            if (res.ok) {
                this.renderPresence(await res.json());
            }
        } catch (ignored) {
            // Ignore transient refresh errors
        }
    }

    async refreshQueue() {
        const selectedSkill = this.dom.queueSkillFilter.value;
        const query = selectedSkill ? `?skillGroup=${encodeURIComponent(selectedSkill)}` : '';
        try {
            const res = await fetch(`/api/operators/queue${query}`);
            if (res.ok) {
                const overview = await res.json();
                this.renderQueueOverview(overview);
            }
        } catch (err) {
            this.log(`Queue refresh error: ${err.message}`, 'warn');
        }
    }

    async claimCall(callId) {
        if (!this.operatorId) return;
        try {
            const options = { method: 'POST', headers: { 'Content-Type': 'application/json' } };
            if (callId) {
                options.body = JSON.stringify({ callId });
            }
            const res = await fetch(`/api/operators/${encodeURIComponent(this.operatorId)}/claim`, options);
            if (res.status === 204) {
                this.log('No matching queued calls available to claim.', 'info');
                return;
            }
            const body = await res.json();
            if (!res.ok) {
                this.log(`Claim failed: ${body.message || res.status}`, 'error');
                return;
            }
            this.renderAssignment(body);
            this.renderPresenceStatusOnly('BUSY');
            await this.refreshQueue();
            this.log(`Claimed call ${body.callId} (PNR ${body.pnr}).`, 'success');
        } catch (err) {
            this.log(`Claim error: ${err.message}`, 'error');
        }
    }

    async completeActiveHandoff() {
        if (!this.operatorId || !this.activeAssignment) return;
        const callId = this.activeAssignment.callId;
        const resolutionNotes = this.dom.resolutionNotesInput.value.trim();
        try {
            const res = await fetch(
                `/api/operators/${encodeURIComponent(this.operatorId)}/calls/${encodeURIComponent(callId)}/complete`,
                {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ resolutionNotes })
                }
            );
            const body = await res.json();
            if (!res.ok) {
                this.log(`Complete handoff failed: ${body.message || res.status}`, 'error');
                return;
            }
            this.log(`Handoff completed for call ${callId}.`, 'success');
            this.clearAssignment();
            await this.disconnectAudioBridge();
            await this.refreshPresence();
            await this.refreshQueue();
        } catch (err) {
            this.log(`Complete handoff error: ${err.message}`, 'error');
        }
    }

    renderPresence(presence) {
        this.renderPresenceStatusOnly(presence.status);
    }

    renderPresenceStatusOnly(status) {
        const badge = this.dom.presenceBadge;
        badge.className = 'badge';
        badge.textContent = status || 'OFFLINE';
        switch (status) {
            case 'AVAILABLE':
                badge.classList.add('badge-available');
                break;
            case 'BUSY':
                badge.classList.add('badge-busy');
                break;
            case 'PAUSED':
                badge.classList.add('badge-paused');
                break;
            default:
                badge.classList.add('badge-offline');
        }
        this.dom.presenceButtons.forEach((btn) => {
            btn.classList.toggle('active', btn.dataset.status === status);
        });
    }

    renderQueueOverview(overview) {
        this.renderDepthMap(overview.depthsBySkillGroup || {});
        const tbody = this.dom.queueTableBody;
        tbody.textContent = '';

        const calls = Array.isArray(overview.queuedCalls) ? overview.queuedCalls : [];
        if (calls.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 8;
            td.className = 'empty-cell';
            td.textContent = 'No queued disruption calls.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        calls.forEach((item) => {
            const tr = document.createElement('tr');
            const flags = [
                item.hotelRequired ? 'HOTEL' : null,
                item.specialAssistance ? 'ACCESS' : null
            ].filter(Boolean).join(', ') || 'NONE';

            const values = [
                String(item.priorityScore),
                item.skillGroup,
                item.pnr,
                item.passengerName,
                item.originalFlightNumber,
                item.disruptionReason,
                flags
            ];
            values.forEach((val, index) => {
                const td = document.createElement('td');
                if (index === 0 || index === 2 || index === 4) {
                    td.className = 'mono';
                }
                td.textContent = val || '-';
                tr.appendChild(td);
            });

            const actionTd = document.createElement('td');
            const claimBtn = document.createElement('button');
            claimBtn.type = 'button';
            claimBtn.className = 'btn btn-sm btn-primary';
            claimBtn.textContent = 'Claim';
            claimBtn.disabled = !this.operatorId;
            claimBtn.addEventListener('click', () => this.claimCall(item.callId));
            actionTd.appendChild(claimBtn);
            tr.appendChild(actionTd);

            tbody.appendChild(tr);
        });
    }

    renderDepthMap(depths) {
        const container = this.dom.queueDepthPills;
        container.textContent = '';
        const skills = this.config.skillGroups || Object.keys(depths);
        skills.forEach((skill) => {
            const pill = document.createElement('span');
            pill.className = 'depth-pill';
            const count = depths[skill] !== undefined ? depths[skill] : 0;
            pill.textContent = `${skill}: ${count}`;
            container.appendChild(pill);
        });
    }

    renderAssignment(assignment) {
        this.activeAssignment = assignment;
        this.dom.ticketEmptyState.classList.add('hidden');
        this.dom.ticketDetails.classList.remove('hidden');

        this.dom.ticketPriorityBadge.className = 'badge badge-high';
        this.dom.ticketPriorityBadge.textContent = `${assignment.priority || 'HIGH'} PRIORITY`;

        this.dom.ticketPassenger.textContent = assignment.passengerName || '-';
        this.dom.ticketPnr.textContent = assignment.pnr || '-';
        this.dom.ticketFlight.textContent = assignment.originalFlightNumber || '-';
        this.dom.ticketReason.textContent = assignment.disruptionReason || '-';
        this.dom.ticketPreference.textContent = assignment.rebookingPreference || '-';
        this.dom.ticketSkill.textContent = assignment.skillGroup || '-';
        this.dom.ticketHotel.textContent = assignment.hotelRequired ? 'YES' : 'NO';
        this.dom.ticketAssistance.textContent = assignment.specialAssistance ? 'YES' : 'NO';
        this.dom.ticketCallerNumber.textContent = assignment.callerNumber || 'N/A';
        this.dom.ticketCategory.textContent = assignment.category || '-';
        this.dom.ticketIds.textContent = `call=${assignment.callId} | ticket=${assignment.ticketId}`;
        this.dom.ticketSummary.textContent = assignment.summary || '-';
        this.dom.resolutionNotesInput.value = '';

        this.renderTranscript(assignment.transcript || []);

        const bridgeUrl = assignment.bridgeUrl || '';
        this.dom.audioBridgeUrl.textContent = bridgeUrl || 'No bridge assigned';
        this.dom.btnAudioConnect.disabled = !bridgeUrl;
    }

    clearAssignment() {
        this.activeAssignment = null;
        this.dom.ticketEmptyState.classList.remove('hidden');
        this.dom.ticketDetails.classList.add('hidden');
        this.dom.ticketPriorityBadge.className = 'badge badge-offline';
        this.dom.ticketPriorityBadge.textContent = 'NO ACTIVE CALL';
        this.dom.audioBridgeUrl.textContent = 'No bridge assigned';
        this.dom.btnAudioConnect.disabled = true;
        this.renderTranscript([]);
    }

    renderTranscript(turns) {
        const panel = this.dom.transcriptPanel;
        panel.textContent = '';
        this.dom.transcriptTurnCount.textContent = `${turns.length} turns`;

        if (!turns || turns.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'empty-state';
            empty.textContent = 'Transcript turns will appear here when a call is assigned.';
            panel.appendChild(empty);
            return;
        }

        turns.forEach((turn) => {
            const row = document.createElement('div');
            row.className = 'transcript-turn';

            const speaker = document.createElement('span');
            speaker.className = 'transcript-speaker';
            speaker.textContent = `[${turn.speaker || 'CALLER'}]`;

            const text = document.createElement('span');
            text.textContent = turn.text || '';

            row.appendChild(speaker);
            row.appendChild(text);
            panel.appendChild(row);
        });
        panel.scrollTop = panel.scrollHeight;
    }

    async toggleAudioBridge() {
        if (this.isAudioConnected) {
            await this.disconnectAudioBridge();
        } else if (this.activeAssignment && this.activeAssignment.bridgeUrl) {
            await this.connectAudioBridge(this.activeAssignment.bridgeUrl);
        }
    }

    async connectAudioBridge(bridgeUrl) {
        this.setAudioBadge('CONNECTING');
        this.log(`Connecting operator audio bridge to ${bridgeUrl}...`, 'info');
        try {
            this.mediaStream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true
                }
            });

            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            this.audioContext = new AudioContextClass({ latencyHint: 'interactive' });
            if (this.audioContext.state === 'suspended') {
                await this.audioContext.resume();
            }

            await this.audioContext.audioWorklet.addModule('audio-processor.worklet.js');

            const micSource = this.audioContext.createMediaStreamSource(this.mediaStream);
            this.workletNode = new AudioWorkletNode(this.audioContext, 'linear-pcm16-downsampler-processor');
            this.silentGainNode = this.audioContext.createGain();
            this.silentGainNode.gain.value = 0.0;
            this.workletNode.connect(this.silentGainNode);
            this.silentGainNode.connect(this.audioContext.destination);

            this.analyserNode = this.audioContext.createAnalyser();
            this.analyserNode.fftSize = 256;
            micSource.connect(this.workletNode);
            micSource.connect(this.analyserNode);

            this.audioWs = new WebSocket(bridgeUrl);
            this.audioWs.binaryType = 'arraybuffer';

            this.audioWs.onopen = () => {
                this.isAudioConnected = true;
                this.setAudioBadge('BRIDGED');
                this.dom.btnAudioConnect.textContent = 'Disconnect Audio Bridge';
                this.dom.btnAudioConnect.className = 'btn btn-danger';
                this.dom.btnAudioMute.disabled = false;
                this.startVuMeter();
                this.log('Operator PCM audio bridge active.', 'success');
            };

            this.audioWs.onmessage = (event) => {
                if (event.data instanceof ArrayBuffer) {
                    this.handleInboundPcm(event.data);
                }
            };

            this.audioWs.onerror = () => {
                this.log('Audio bridge WebSocket error.', 'error');
            };

            this.audioWs.onclose = () => {
                this.cleanupAudio();
            };

            this.workletNode.port.onmessage = (event) => {
                if (event.data && event.data.type === 'pcm' && this.isAudioConnected && this.audioWs && this.audioWs.readyState === WebSocket.OPEN) {
                    this.audioWs.send(event.data.buffer);
                    this.audioPacketsSent++;
                    this.dom.metricAudioSent.textContent = `${this.audioPacketsSent} pkts`;
                }
            };
        } catch (err) {
            this.log(`Failed to start audio bridge: ${err.message}`, 'error');
            this.cleanupAudio();
        }
    }

    handleInboundPcm(arrayBuffer) {
        this.audioPacketsRecv++;
        this.dom.metricAudioRecv.textContent = `${this.audioPacketsRecv} pkts`;

        if (!this.audioContext || this.audioContext.state === 'closed') {
            return;
        }
        const int16Array = new Int16Array(arrayBuffer);
        const float32Array = new Float32Array(int16Array.length);
        for (let i = 0; i < int16Array.length; i++) {
            float32Array[i] = int16Array[i] / 32768.0;
        }
        const audioBuffer = this.audioContext.createBuffer(1, float32Array.length, 16000);
        audioBuffer.copyToChannel(float32Array, 0);

        const source = this.audioContext.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(this.audioContext.destination);

        const now = this.audioContext.currentTime;
        if (this.nextPlaybackTime < now || this.nextPlaybackTime > now + 0.1) {
            this.nextPlaybackTime = now + 0.025;
        }
        source.start(this.nextPlaybackTime);
        this.nextPlaybackTime += audioBuffer.duration;
    }

    toggleAudioMute() {
        if (!this.isAudioConnected || !this.mediaStream) return;
        this.isMuted = !this.isMuted;
        this.mediaStream.getAudioTracks().forEach((track) => {
            track.enabled = !this.isMuted;
        });
        if (this.workletNode) {
            this.workletNode.port.postMessage({ type: 'mute', value: this.isMuted });
        }
        this.dom.btnAudioMute.setAttribute('aria-pressed', String(this.isMuted));
        this.dom.btnAudioMute.textContent = this.isMuted ? 'Unmute Mic' : 'Mute Mic';
    }

    startVuMeter() {
        if (!this.analyserNode) return;
        const dataArray = new Uint8Array(this.analyserNode.frequencyBinCount);
        const render = () => {
            if (!this.isAudioConnected) {
                this.dom.audioVuBar.style.width = '0%';
                this.dom.audioVuValue.textContent = '0%';
                return;
            }
            this.analyserNode.getByteTimeDomainData(dataArray);
            let sum = 0;
            for (let i = 0; i < dataArray.length; i++) {
                const norm = (dataArray[i] - 128) / 128;
                sum += norm * norm;
            }
            const percent = Math.min(100, Math.round(Math.sqrt(sum / dataArray.length) * 250));
            this.dom.audioVuBar.style.width = `${percent}%`;
            this.dom.audioVuValue.textContent = `${percent}%`;
            this.vuAnimId = requestAnimationFrame(render);
        };
        this.vuAnimId = requestAnimationFrame(render);
    }

    async disconnectAudioBridge() {
        if (this.audioWs) {
            this.audioWs.close(1000, 'Operator ended bridge');
            this.audioWs = null;
        }
        this.cleanupAudio();
    }

    cleanupAudio() {
        this.isAudioConnected = false;
        this.isMuted = false;
        this.setAudioBadge('IDLE');
        this.dom.btnAudioConnect.textContent = 'Connect Audio Bridge';
        this.dom.btnAudioConnect.className = 'btn btn-primary';
        this.dom.btnAudioConnect.disabled = !(this.activeAssignment && this.activeAssignment.bridgeUrl);
        this.dom.btnAudioMute.disabled = true;
        this.dom.btnAudioMute.textContent = 'Mute Mic';
        this.dom.btnAudioMute.setAttribute('aria-pressed', 'false');

        if (this.vuAnimId) {
            cancelAnimationFrame(this.vuAnimId);
            this.vuAnimId = null;
        }
        if (this.silentGainNode) {
            try {
                this.silentGainNode.disconnect();
            } catch (ignored) {}
            this.silentGainNode = null;
        }
        if (this.mediaStream) {
            this.mediaStream.getTracks().forEach((track) => track.stop());
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
        this.nextPlaybackTime = 0;
        this.dom.audioVuBar.style.width = '0%';
        this.dom.audioVuValue.textContent = '0%';
    }

    setWsBadge(state) {
        const badge = this.dom.wsStatusBadge;
        badge.className = 'badge';
        badge.textContent = state;
        if (state === 'CONNECTED') {
            badge.classList.add('badge-connected');
        } else if (state === 'CONNECTING') {
            badge.classList.add('badge-connecting');
        } else {
            badge.classList.add('badge-offline');
        }
    }

    setAudioBadge(state) {
        const badge = this.dom.audioStatusBadge;
        badge.className = 'badge';
        badge.textContent = state;
        if (state === 'BRIDGED') {
            badge.classList.add('badge-bridged');
        } else if (state === 'CONNECTING') {
            badge.classList.add('badge-connecting');
        } else {
            badge.classList.add('badge-offline');
        }
    }

    log(message, level = 'info') {
        const entry = document.createElement('div');
        entry.className = `log-entry log-${level}`;
        const time = new Date().toISOString().substring(11, 19);
        entry.textContent = `[${time}] ${message}`;
        this.dom.consoleLog.appendChild(entry);
        this.dom.consoleLog.scrollTop = this.dom.consoleLog.scrollHeight;
    }
}

window.addEventListener('DOMContentLoaded', () => {
    window.operatorWebConsole = new OperatorWebConsole();
});
