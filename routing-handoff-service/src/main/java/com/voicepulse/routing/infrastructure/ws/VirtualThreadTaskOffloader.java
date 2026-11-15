package com.voicepulse.routing.infrastructure.ws;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Offloads synchronous domain port invocations from non-blocking Netty WebFlux event-loop threads
 * onto Java 21 Virtual Threads so Redis reactive bridges and JDBC calls never block Netty workers.
 */
@Component
public class VirtualThreadTaskOffloader implements DisposableBean {

    private final ExecutorService executorService;
    private final Scheduler virtualThreadScheduler;

    public VirtualThreadTaskOffloader() {
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.virtualThreadScheduler =
                Schedulers.fromExecutorService(this.executorService, "operator-console-vt");
    }

    /** Executes a synchronous supplier on a Java 21 Virtual Thread and emits its result. */
    public <T> Mono<T> supply(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        return Mono.fromCallable(supplier::get).subscribeOn(virtualThreadScheduler);
    }

    /** Executes a synchronous runnable on a Java 21 Virtual Thread. */
    public Mono<Void> run(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        return Mono.fromRunnable(runnable).subscribeOn(virtualThreadScheduler).then();
    }

    @Override
    public void destroy() {
        virtualThreadScheduler.dispose();
        executorService.close();
    }
}
