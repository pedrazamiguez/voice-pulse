/**
 * Secondary persistence adapters for PostgreSQL disruption ticket storage via Spring Data JDBC
 * repositories and PostgreSQL {@code JSONB} custom converters.
 *
 * <p>Offload all blocking JDBC repository operations to Virtual Thread executors ({@code
 * jdbcVirtualThreadExecutor}) to prevent blocking reactive Netty threads.
 */
package com.voicepulse.routing.infrastructure.persistence;
