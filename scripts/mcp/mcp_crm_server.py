#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "mcp>=1.3.0,<2",
# ]
# ///
"""
mcp_crm_server.py — Standalone Model Context Protocol (MCP) & HTTP tool server
for SkyWay Airlines CRM customer and itinerary lookup (RFC-0001 §4.4 & RFC-0003 §4.1).

Exposes `lookup_customer(pnr)` over both:
  - HTTP JSON endpoint: POST /tools/lookup_customer (default port 8091)
  - FastMCP stdio transport: --transport stdio
"""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import sys
from typing import Any, Dict, Optional

PASSENGER_FIXTURES: Dict[str, Dict[str, Any]] = {
    "A8F9X2": {
        "pnr": "A8F9X2",
        "customer_name": "Andres",
        "frequent_flyer_tier": "Gold",
        "active_itinerary": {
            "flight_number": "SW-104",
            "origin": "CTU",
            "destination": "MAD",
            "status": "CANCELED_WEATHER",
            "original_departure": "2026-11-01T01:45:00Z",
        },
    },
    "B7G8Y3": {
        "pnr": "B7G8Y3",
        "customer_name": "Elena",
        "frequent_flyer_tier": "Platinum",
        "active_itinerary": {
            "flight_number": "SW-208",
            "origin": "MAD",
            "destination": "JFK",
            "status": "DELAYED_MAINTENANCE",
            "original_departure": "2026-11-02T09:15:00Z",
        },
    },
    "C3K9M1": {
        "pnr": "C3K9M1",
        "customer_name": "Carlos",
        "frequent_flyer_tier": "Silver",
        "active_itinerary": {
            "flight_number": "SW-412",
            "origin": "LHR",
            "destination": "MAD",
            "status": "CANCELED_CREW_REST",
            "original_departure": "2026-11-02T14:30:00Z",
        },
    },
    "D4P2R8": {
        "pnr": "D4P2R8",
        "customer_name": "Sofia",
        "frequent_flyer_tier": "Standard",
        "active_itinerary": {
            "flight_number": "SW-519",
            "origin": "CDG",
            "destination": "BCN",
            "status": "DIVERTED_TECHNICAL",
            "original_departure": "2026-11-02T18:00:00Z",
        },
    },
}


def find_customer_by_pnr(pnr: str) -> Optional[Dict[str, Any]]:
    """Looks up a passenger fixture by normalized 6-character PNR."""
    if not pnr or not isinstance(pnr, str):
        return None
    normalized = pnr.strip().upper()
    return PASSENGER_FIXTURES.get(normalized)


class McpCrmHttpRequestHandler(BaseHTTPRequestHandler):
    """HTTP request handler serving /tools/lookup_customer and /health."""

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(200, {"status": "UP", "server": "mcp-crm-server"})
            return
        self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        if self.path != "/tools/lookup_customer":
            self._send_json(404, {"error": f"Unknown tool endpoint: {self.path}"})
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
        try:
            payload = json.loads(raw_body)
        except json.JSONDecodeError:
            self._send_json(400, {"error": "Invalid JSON payload"})
            return

        pnr = payload.get("pnr", "")
        profile = find_customer_by_pnr(pnr)
        if profile is None:
            self._send_json(404, {"error": f"Customer profile not found for PNR: {pnr}"})
            return

        self._send_json(200, profile)

    def _send_json(self, status_code: int, data: Dict[str, Any]) -> None:
        encoded = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format_str: str, *args: Any) -> None:
        sys.stderr.write(f"[mcp-crm-server] {format_str % args}\n")


def run_stdio_server() -> None:
    """Runs the FastMCP stdio server for MCP tool hosts."""
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("mcp-crm-server")

    @mcp.tool()
    def lookup_customer(pnr: str) -> Dict[str, Any]:
        """
        Look up a SkyWay Airlines passenger profile and active disrupted itinerary by 6-character PNR.
        """
        profile = find_customer_by_pnr(pnr)
        if profile is None:
            raise ValueError(f"Customer profile not found for PNR: {pnr}")
        return profile

    mcp.run()


def run_http_server(host: str, port: int) -> None:
    """Runs the HTTP JSON tool server."""
    server = ThreadingHTTPServer((host, port), McpCrmHttpRequestHandler)
    sys.stderr.write(f"[mcp-crm-server] Listening on http://{host}:{port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description="SkyWay Airlines MCP CRM Tool Server")
    parser.add_argument(
        "--transport",
        choices=["http", "stdio"],
        default=os.environ.get("MCP_CRM_TRANSPORT", "http"),
        help="Transport protocol (default: http)",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("MCP_CRM_HOST", "127.0.0.1"),
        help="Bind host for HTTP transport (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("MCP_CRM_PORT", "8091")),
        help="Bind port for HTTP transport (default: 8091)",
    )
    args = parser.parse_args()

    if args.transport == "stdio":
        run_stdio_server()
    else:
        run_http_server(args.host, args.port)


if __name__ == "__main__":
    main()
