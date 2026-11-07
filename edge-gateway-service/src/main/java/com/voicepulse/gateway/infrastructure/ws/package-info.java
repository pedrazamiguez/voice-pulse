/**
 * Netty WebFlux WebSocket infrastructure adapters for real-time binary audio streaming.
 *
 * <p>Direct memory safety invariant: all allocated ByteBuf instances MUST be explicitly released
 * via ReferenceCountUtil.release() in finally blocks.
 */
package com.voicepulse.gateway.infrastructure.ws;
