package com.voicepulse.conversation.infrastructure.mcp;

/**
 * Unchecked infrastructure exception thrown when an outbound Model Context Protocol (MCP) tool
 * invocation fails due to transport timeouts, non-recoverable HTTP statuses, or malformed payloads.
 */
public class McpClientException extends RuntimeException {

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
