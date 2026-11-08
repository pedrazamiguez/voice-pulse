/**
 * VoicePulse AudioWorklet Processor: Linear PCM 16-bit Downsampler
 *
 * Runs on the Web Audio rendering thread to capture raw microphone input
 * at native browser sample rates (e.g. 44.1 kHz, 48 kHz), resamples client-side
 * to 16,000 Hz Mono Linear PCM via linear interpolation, and emits deterministic
 * 20 ms chunks (320 samples = 640 bytes) to the main thread.
 */
class LinearPcm16DownsamplerProcessor extends AudioWorkletProcessor {

    constructor() {
        super();
        this.targetSampleRate = 16000;
        this.chunkSize = 320; // 20ms at 16,000 Hz
        this.outBuffer = new Int16Array(this.chunkSize);
        this.outIndex = 0;
        this.phase = 0.0;
        this.inputBuffer = new Float32Array(2048);
        this.inputCount = 0;
        this.isMuted = false;

        this.port.onmessage = (event) => {
            if (event.data && event.data.type === 'mute') {
                this.isMuted = !!event.data.value;
            }
        };
    }

    process(inputs, outputs, parameters) {
        const input = inputs[0];
        if (!input || !input[0] || input[0].length === 0) {
            return true;
        }

        const channelData = input[0];
        const channelLength = channelData.length;

        if (this.inputCount + channelLength > this.inputBuffer.length) {
            const expanded = new Float32Array(this.inputBuffer.length * 2);
            expanded.set(this.inputBuffer.subarray(0, this.inputCount));
            this.inputBuffer = expanded;
        }
        this.inputBuffer.set(channelData, this.inputCount);
        this.inputCount += channelLength;

        const ratio = sampleRate / this.targetSampleRate;

        while (this.phase + 1 < this.inputCount) {
            const idx = Math.floor(this.phase);
            const frac = this.phase - idx;
            let sample = this.inputBuffer[idx] * (1.0 - frac) + this.inputBuffer[idx + 1] * frac;

            if (this.isMuted) {
                sample = 0.0;
            }

            // Clamp sample to [-1.0, 1.0]
            if (sample > 1.0) {
                sample = 1.0;
            } else if (sample < -1.0) {
                sample = -1.0;
            }

            // Quantize to 16-bit signed Linear PCM
            const pcm = sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767);
            this.outBuffer[this.outIndex++] = pcm;

            if (this.outIndex === this.chunkSize) {
                const bufferToSend = this.outBuffer.buffer.slice(0);
                this.port.postMessage({
                    type: 'pcm',
                    buffer: bufferToSend,
                    sampleRate: this.targetSampleRate,
                    samples: this.chunkSize
                }, [bufferToSend]);

                this.outIndex = 0;
            }

            this.phase += ratio;
        }

        const cutIdx = Math.floor(this.phase);
        if (cutIdx > 0) {
            const remaining = this.inputCount - cutIdx;
            this.inputBuffer.copyWithin(0, cutIdx, this.inputCount);
            this.inputCount = remaining;
            this.phase -= cutIdx;
        }

        return true;
    }
}

registerProcessor('linear-pcm16-downsampler-processor', LinearPcm16DownsamplerProcessor);
