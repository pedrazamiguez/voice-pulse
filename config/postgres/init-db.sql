-- Database: voicepulse
-- VoicePulse PostgreSQL Schema Initialization (RFC-0001 §3, RFC-0003 §4, AGENTS.md §4)

-- Extensions & Utilities
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Table 1: Call Sessions
CREATE TABLE IF NOT EXISTS call_sessions (
    call_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pnr VARCHAR(10),
    caller_number VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    start_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMPTZ,
    duration_seconds INTEGER,
    assigned_operator_id VARCHAR(64),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table 2: Airline Disruption Tickets (RFC-0003 & AGENTS.md §4)
CREATE TABLE IF NOT EXISTS disruption_tickets (
    ticket_id VARCHAR(64) PRIMARY KEY,
    call_id UUID REFERENCES call_sessions(call_id) ON DELETE SET NULL,
    pnr VARCHAR(10) NOT NULL,
    passenger_name VARCHAR(128) NOT NULL,
    original_flight_number VARCHAR(16) NOT NULL,
    disruption_reason VARCHAR(64) NOT NULL,
    rebooking_preference VARCHAR(64) NOT NULL,
    hotel_required BOOLEAN NOT NULL DEFAULT FALSE,
    special_assistance BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    summary TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table 3: Operator Handoff & Audit Log
CREATE TABLE IF NOT EXISTS operator_handoff_logs (
    id BIGSERIAL PRIMARY KEY,
    call_id UUID REFERENCES call_sessions(call_id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    source_state VARCHAR(32),
    target_state VARCHAR(32),
    operator_id VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for Call Sessions
CREATE INDEX IF NOT EXISTS idx_call_sessions_status ON call_sessions (status);
CREATE INDEX IF NOT EXISTS idx_call_sessions_pnr ON call_sessions (pnr);
CREATE INDEX IF NOT EXISTS idx_call_sessions_metadata ON call_sessions USING gin (metadata);

-- Indexes for Disruption Tickets
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_pnr ON disruption_tickets (pnr);
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_call_id ON disruption_tickets (call_id);
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_status ON disruption_tickets (status);
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_payload ON disruption_tickets USING gin (payload);

-- Indexes for Operator Handoff Logs
CREATE INDEX IF NOT EXISTS idx_operator_handoff_logs_call_id ON operator_handoff_logs (call_id);
