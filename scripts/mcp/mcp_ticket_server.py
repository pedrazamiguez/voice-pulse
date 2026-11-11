#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "mcp>=1.3.0,<2",
# ]
# ///
"""
mcp_ticket_server.py — Standalone Model Context Protocol (MCP) & HTTP tool server
for SkyWay Airlines disruption ticket triage and compilation (RFC-0001 §4.4 & RFC-0003 §4.2).

Exposes `create_draft_ticket` and `finalize_ticket` over both:
  - HTTP JSON endpoints:
      POST /tools/create_draft_ticket
      POST /tools/finalize_ticket
      GET  /health
    (default port 8092)
  - FastMCP stdio transport: --transport stdio
"""

import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import sys
import uuid
from typing import Any, Dict, Optional

PNR_REGEX = re.compile(r"^[A-Za-z0-9]{6}$")
TICKET_STORE: Dict[str, Dict[str, Any]] = {}


def utc_now_iso() -> str:
    """Returns current UTC timestamp in ISO-8601 Z notation."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_draft_ticket(customer_id: str, category: str, summary: str) -> Dict[str, Any]:
    """Creates an in-memory draft disruption ticket."""
    if not customer_id or not str(customer_id).strip():
        raise ValueError("customer_id must not be blank")
    if not category or not str(category).strip():
        raise ValueError("category must not be blank")
    if not summary or not str(summary).strip():
        raise ValueError("summary must not be blank")

    ticket_id = f"TKT-{uuid.uuid4().hex[:8].upper()}"
    now = utc_now_iso()
    record = {
        "ticket_id": ticket_id,
        "customer_id": str(customer_id).strip(),
        "category": str(category).strip().upper(),
        "summary": str(summary).strip(),
        "status": "DRAFT",
        "created_at": now,
    }
    TICKET_STORE[ticket_id] = record
    return record


def build_finalized_ticket(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Validates and finalizes an airline disruption ticket for operator handoff."""
    ticket_id = str(payload.get("ticket_id", "")).strip()
    if not ticket_id:
        raise ValueError("ticket_id must not be blank")

    pnr = str(payload.get("pnr", "")).strip().upper()
    if not pnr:
        existing = TICKET_STORE.get(ticket_id, {})
        pnr = str(existing.get("pnr") or existing.get("customer_id") or "").strip().upper()
    if not PNR_REGEX.match(pnr):
        raise ValueError(f"pnr must be exactly 6 alphanumeric characters: {pnr}")

    category = str(payload.get("category") or "FLIGHT_REBOOKING").strip().upper()
    priority = str(payload.get("priority") or "HIGH").strip().upper()
    summary = str(
        payload.get("summary") or payload.get("resolution_notes") or "Disruption ticket finalized"
    ).strip()
    extracted_entities = payload.get("extracted_entities") or {}

    submitted_at = utc_now_iso()
    receipt = {
        "ticket_id": ticket_id,
        "pnr": pnr,
        "category": category,
        "priority": priority,
        "status": "PENDING_HANDOFF",
        "submitted_at": submitted_at,
    }
    TICKET_STORE[ticket_id] = {
        **receipt,
        "summary": summary,
        "extracted_entities": extracted_entities,
    }
    return receipt


class McpTicketHttpRequestHandler(BaseHTTPRequestHandler):
    """HTTP request handler serving /tools/create_draft_ticket, /tools/finalize_ticket, and /health."""

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(200, {"status": "UP", "server": "mcp-ticket-server"})
            return
        self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        if self.path not in ("/tools/create_draft_ticket", "/tools/finalize_ticket"):
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
            if self.path == "/tools/create_draft_ticket":
                result = build_draft_ticket(
                    customer_id=payload.get("customer_id", ""),
                    category=payload.get("category", ""),
                    summary=payload.get("summary", ""),
                )
            else:
                result = build_finalized_ticket(payload)
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
        sys.stderr.write(f"[mcp-ticket-server] {format_str % args}\n")


def run_stdio_server() -> None:
    """Runs the FastMCP stdio server for MCP tool hosts."""
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("mcp-ticket-server")

    @mcp.tool()
    def create_draft_ticket(customer_id: str, category: str, summary: str) -> Dict[str, Any]:
        """Initialize a draft airline disruption support ticket in the operational store."""
        return build_draft_ticket(customer_id=customer_id, category=category, summary=summary)

    @mcp.tool()
    def finalize_ticket(
        ticket_id: str,
        pnr: str,
        category: str = "FLIGHT_REBOOKING",
        priority: str = "HIGH",
        summary: str = "",
        resolution_notes: str = "",
        extracted_entities: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Finalize and commit a compiled airline disruption ticket for operator handoff."""
        return build_finalized_ticket(
            {
                "ticket_id": ticket_id,
                "pnr": pnr,
                "category": category,
                "priority": priority,
                "summary": summary or resolution_notes,
                "extracted_entities": extracted_entities or {},
            }
        )

    mcp.run()


def run_http_server(host: str, port: int) -> None:
    """Runs the HTTP JSON tool server."""
    server = ThreadingHTTPServer((host, port), McpTicketHttpRequestHandler)
    sys.stderr.write(f"[mcp-ticket-server] Listening on http://{host}:{port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description="SkyWay Airlines MCP Ticket Tool Server")
    parser.add_argument(
        "--transport",
        choices=["http", "stdio"],
        default=os.environ.get("MCP_TICKET_TRANSPORT", "http"),
        help="Transport protocol (default: http)",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("MCP_TICKET_HOST", "127.0.0.1"),
        help="Bind host for HTTP transport (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("MCP_TICKET_PORT", "8092")),
        help="Bind port for HTTP transport (default: 8092)",
    )
    args = parser.parse_args()

    if args.transport == "stdio":
        run_stdio_server()
    else:
        run_http_server(args.host, args.port)


if __name__ == "__main__":
    main()
