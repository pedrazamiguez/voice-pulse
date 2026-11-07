/**
 * Secondary persistence adapters for PostgreSQL disruption ticket storage.
 *
 * <p>Offload any blocking JDBC queries to Virtual Thread executors to prevent blocking reactive
 * threads.
 */
package com.voicepulse.routing.infrastructure.persistence;
