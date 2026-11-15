package com.voicepulse.routing.infrastructure.redis;

/**
 * Runtime exception raised when reactive Redis 7 presence or priority queue operations fail, time
 * out, exceed configured queue capacity, or fail payload serialization.
 */
public class RedisRoutingException extends RuntimeException {

    public RedisRoutingException(String message) {
        super(message);
    }

    public RedisRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
