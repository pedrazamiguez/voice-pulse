package com.voicepulse.routing.infrastructure.persistence;

/** Runtime exception thrown when PostgreSQL persistence or JSONB serialization fails. */
public class TicketPersistenceException extends RuntimeException {

    public TicketPersistenceException(String message) {
        super(message);
    }

    public TicketPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
