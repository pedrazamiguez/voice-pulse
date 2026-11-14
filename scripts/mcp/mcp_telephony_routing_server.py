#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "mcp>=1.3.0,<2",
# ]
# ///
"""
mcp_telephony_routing_server.py — Standalone Model Context Protocol (MCP) & HTTP tool server
for SkyWay Airlines operator skill queue checks and call handoff transfers (RFC-0001 §4.4 & RFC-0003 §4.3).

Exposes `check_queue_status` and `transfer_to_human_operator` over both:
  - HTTP JSON endpoints:
      POST /tools/check_queue_status
      POST /tools/transfer_to_human_operator
      GET  /health
    (default port 8093)
  - FastMCP stdio transport: --transport stdio
"""

import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import sys
from typing import Any, Dict

QUEUE_FIXTURES: Dict[str, Dict[str, int]] = {
    "DISRUPTION_DESK_GOLD": {
        "available_operators": 6,
        "estimated_wait_seconds": 30,
        "queue_position": 2,
    },
    "DISRUPTION_DESK_ACCESSIBILITY": {
        "available_operators": 4,
        "estimated_wait_seconds": 45,
        "queue_position": 3,
    },
    "DISRUPTION_DESK_PRIORITY": {
        "available_operators": 5,
        "estimated_wait_seconds": 90,
        "queue_position": 7,
    },
    "DISRUPTION_DESK_STANDARD": {
        "available_operators": 8,
        "estimated_wait_seconds": 120,
        "queue_position": 14,
    },
}

TRANSFERS: Dict[str, Dict[str, Any]] = {}


def utc_now_iso() -> str:
    """Returns current UTC timestamp in ISO-8601 Z notation."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def query_queue_status(department: str) -> Dict[str, Any]:
    """Looks up real-time operator availability and wait estimate for a skill group."""
    if not department or not str(department).strip():
        raise ValueError("department must not be blank")
    normalized = str(department).strip().upper()
    metrics = QUEUE_FIXTURES.get(normalized)
    if metrics is None:
        raise ValueError(f"Unknown operator skill group department: {normalized}")
    return {
        "department": normalized,
        **metrics,
    }


def dispatch_transfer(call_id: str, department: str, ticket_id: str) -> Dict[str, Any]:
    """Dispatches a call session and finalized ticket into the target operator queue."""
    if not call_id or not str(call_id).strip():
        raise ValueError("call_id must not be blank")
    if not ticket_id or not str(ticket_id).strip():
        raise ValueError("ticket_id must not be blank")

    queue_info = query_queue_status(department)
    normalized_call_id = str(call_id).strip()
    normalized_ticket_id = str(ticket_id).strip()
    next_position = queue_info["queue_position"] + 1
    queue_info_department = queue_info["department"]
    QUEUE_FIXTURES[queue_info_department]["queue_position"] = next_position

    receipt = {
        "call_id": normalized_call_id,
        "ticket_id": normalized_ticket_id,
        "department": queue_info_department,
        "status": "QUEUED",
        "estimated_wait_seconds": queue_info["estimated_wait_seconds"],
        "queue_position": next_position,
        "dispatched_at": utc_now_iso(),
    }
    TRANSFERS[normalized_call_id] = receipt
    return receipt


class McpRoutingHttpRequestHandler(BaseHTTPRequestHandler):
    """HTTP request handler serving /tools/check_queue_status, /tools/transfer_to_human_operator, and /health."""

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(200, {"status": "UP", "server": "mcp-telephony-routing"})
            return
        self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        if self.path not in ("/tools/check_queue_status", "/tools/transfer_to_human_operator"):
            self._send_json(404, {"error": f"Unknown tool endpoint: {self.path}"})
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
        try:
            payload = json.loads(raw_body)
        except json.JSONDecodeError:
            self._send_json(400, {"error": "Invalid JSON payload"})
            return

        try:
            if self.path == "/tools/check_queue_status":
                result = query_queue_status(department=payload.get("department", ""))
            else:
                result = dispatch_transfer(
                    call_id=payload.get("call_id", ""),
                    department=payload.get("department", ""),
                    ticket_id=payload.get("ticket_id", ""),
                )
            self._send_json(200, result)
        except ValueError as err:
            self._send_json(400, {"error": str(err)})

    def _send_json(self, status_code: int, data: Dict[str, Any]) -> None:
        encoded = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format_str: str, *args: Any) -> None:
        sys.stderr.write(f"[mcp-telephony-routing] {format_str % args}\n")


def run_stdio_server() -> None:
    """Runs the FastMCP stdio server for MCP tool hosts."""
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("mcp-telephony-routing")

    @mcp.tool()
    def check_queue_status(department: str) -> Dict[str, Any]:
        """Check real-time operator availability and wait time for an operator skill group."""
        return query_queue_status(department=department)

    @mcp.tool()
    def transfer_to_human_operator(
        call_id: str, department: str, ticket_id: str
    ) -> Dict[str, Any]:
        """Transfer an active call session and finalized disruption ticket to a human operator queue."""
        return dispatch_transfer(call_id=call_id, department=department, ticket_id=ticket_id)

    mcp.run()


def run_http_server(host: str, port: int) -> None:
    """Runs the HTTP JSON tool server."""
    server = ThreadingHTTPServer((host, port), McpRoutingHttpRequestHandler)
    sys.stderr.write(f"[mcp-telephony-routing] Listening on http://{host}:{port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description="SkyWay Airlines MCP Telephony Routing Tool Server")
    parser.add_argument(
        "--transport",
        choices=["http", "stdio"],
        default=os.environ.get("MCP_ROUTING_TRANSPORT", "http"),
        help="Transport protocol (default: http)",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("MCP_ROUTING_HOST", "127.0.0.1"),
        help="Bind host for HTTP transport (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("MCP_ROUTING_PORT", "8093")),
        help="Bind port for HTTP transport (default: 8093)",
    )
    args = parser.parse_args()

    if args.transport == "stdio":
        run_stdio_server()
    else:
        run_http_server(args.host, args.port)


if __name__ == "__main__":
    main()
