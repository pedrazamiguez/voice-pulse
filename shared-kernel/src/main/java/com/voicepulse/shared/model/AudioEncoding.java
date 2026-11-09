package com.voicepulse.shared.model;

/** Supported uncompressed and compressed audio encodings for the VoicePulse telephony pipeline. */
public enum AudioEncoding {
    /** Uncompressed signed 16-bit little-endian Linear PCM. */
    LINEAR_PCM((byte) 0);

    private final byte wireId;

    AudioEncoding(byte wireId) {
        this.wireId = wireId;
    }

    /** Returns the unique stable wire identifier byte for binary serialization. */
    public byte wireId() {
        return wireId;
    }

    /**
     * Resolves the {@link AudioEncoding} corresponding to the specified wire ID byte.
     *
     * @param wireId wire identifier byte
     * @return matching audio encoding
     * @throws IllegalArgumentException if the wire ID is unknown
     */
    public static AudioEncoding fromWireId(byte wireId) {
        for (AudioEncoding encoding : values()) {
            if (encoding.wireId == wireId) {
                return encoding;
            }
        }
        throw new IllegalArgumentException("Unknown AudioEncoding wireId: " + wireId);
    }
}
