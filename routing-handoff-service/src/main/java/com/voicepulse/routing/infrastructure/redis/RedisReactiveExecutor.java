package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executes reactive Redis {@link Mono} pipelines on behalf of synchronous domain outbound port
 * implementations with bounded timeouts and non-blocking Netty event-loop protection.
 */
@Component
@RequiredArgsConstructor
public class RedisReactiveExecutor {

    private final @NonNull RedisRoutingProperties properties;

    /**
     * Awaits completion of a reactive pipeline within the configured {@code operationTimeout}.
     *
     * @param operationName descriptive operation name for diagnostics
     * @param pipeline reactive pipeline returning a value
     * @param <T> result type
     * @return resolved result
     */
    public <T> T await(String operationName, Mono<T> pipeline) {
        Objects.requireNonNull(operationName, "operationName must not be null");
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        assertNotOnNonBlockingThread(operationName);
        try {
            return pipeline.block(properties.operationTimeout());
        } catch (RedisRoutingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RedisRoutingException(
                    "Failed to execute Redis operation: " + operationName, ex);
        }
    }

    /**
     * Awaits completion of a void reactive pipeline within the configured {@code operationTimeout}.
     *
     * @param operationName descriptive operation name for diagnostics
     * @param pipeline reactive void pipeline
     */
    public void awaitVoid(String operationName, Mono<Void> pipeline) {
        Objects.requireNonNull(operationName, "operationName must not be null");
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        assertNotOnNonBlockingThread(operationName);
        try {
            pipeline.block(properties.operationTimeout());
        } catch (RedisRoutingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RedisRoutingException(
                    "Failed to execute Redis operation: " + operationName, ex);
        }
    }

    private static void assertNotOnNonBlockingThread(String operationName) {
        if (Schedulers.isInNonBlockingThread()) {
            throw new RedisRoutingException(
                    "Cannot invoke blocking port bridge on non-blocking Netty thread for operation:"
                            + " "
                            + operationName);
        }
    }
}
