#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "mcp>=1.3.0,<2",
#     "fastembed>=0.5.0",
#     "numpy>=1.26.0",
# ]
# ///
"""
rag_mcp_server.py — FastMCP stdio server exposing voicepulse-rag tools to AI agents.
Provides hybrid semantic and BM25 search over VoicePulse domain and architecture docs.
"""

from collections import defaultdict
from pathlib import Path
import re
import sqlite3
from typing import Dict, List, Optional, Tuple
from fastembed import TextEmbedding
from mcp.server.fastmcp import FastMCP
import numpy as np

# Import indexer function
try:
    from scripts.rag_indexer import (
        DEFAULT_AGENTS_MD,
        DEFAULT_DB_PATH,
        DEFAULT_DOCS_DIR,
        MODEL_NAME,
        index_docs,
    )
except ImportError:
    from rag_indexer import (  # type: ignore
        DEFAULT_AGENTS_MD,
        DEFAULT_DB_PATH,
        DEFAULT_DOCS_DIR,
        MODEL_NAME,
        index_docs,
    )

mcp = FastMCP("voicepulse-rag")

_embedder: Optional[TextEmbedding] = None


def get_embedder() -> TextEmbedding:
    global _embedder
    if _embedder is None:
        _embedder = TextEmbedding(model_name=MODEL_NAME)
    return _embedder


def sanitize_fts5_query(query: str) -> str:
    """Sanitizes user input into safe FTS5 query tokens."""
    tokens = re.findall(r"\w+", query)
    if not tokens:
        return ""
    # Search for matching tokens with OR for maximum recall
    return " OR ".join(f'"{t}"' for t in tokens)


def compute_hybrid_search(
    query: str,
    top_k: int = 5,
    db_path: Path = DEFAULT_DB_PATH,
    rrf_k: float = 60.0,
) -> List[Tuple[float, float, str, str, str, str]]:
    """
    Executes hybrid dense vector and BM25 sparse search.
    Combines ranks using Reciprocal Rank Fusion (RRF):
        RRF_score(d) = sum(1.0 / (rrf_k + rank))
    Returns list of tuples: (rrf_score, dense_score, cid, title, path, content).
    """
    if not db_path.exists():
        return []

    conn = sqlite3.connect(db_path)
    embedder = get_embedder()

    # 1. Sparse BM25 Search using SQLite FTS5
    fts_rank_map: Dict[str, int] = {}
    fts_query = sanitize_fts5_query(query)
    if fts_query:
        try:
            fts_rows = conn.execute(
                """
                SELECT id, rank FROM chunks_fts
                WHERE chunks_fts MATCH ?
                ORDER BY rank
                LIMIT 50
                """,
                (fts_query,),
            ).fetchall()
            for rank_idx, (cid, _) in enumerate(fts_rows, start=1):
                fts_rank_map[cid] = rank_idx
        except sqlite3.OperationalError:
            # Fallback if query syntax fails
            pass

    # 2. Dense Vector Search (Cosine Similarity)
    q_emb = np.array(list(embedder.embed([query]))[0], dtype=np.float32)
    q_norm = np.linalg.norm(q_emb)

    all_chunks = conn.execute(
        "SELECT id, path, title, content, embedding FROM chunks"
    ).fetchall()

    dense_scores: List[Tuple[float, str, str, str, str]] = []
    chunk_meta: Dict[str, Tuple[str, str, str]] = {}

    for cid, path, title, content, blob in all_chunks:
        chunk_meta[cid] = (path, title, content)
        c_emb = np.frombuffer(blob, dtype=np.float32)
        c_norm = np.linalg.norm(c_emb)
        if q_norm > 0 and c_norm > 0:
            sim = float(np.dot(q_emb, c_emb) / (q_norm * c_norm))
        else:
            sim = 0.0
        dense_scores.append((sim, cid, path, title, content))

    dense_scores.sort(key=lambda x: x[0], reverse=True)
    dense_rank_map: Dict[str, int] = {}
    dense_score_map: Dict[str, float] = {}
    for rank_idx, (score, cid, _, _, _) in enumerate(dense_scores, start=1):
        dense_rank_map[cid] = rank_idx
        dense_score_map[cid] = score

    # 3. Reciprocal Rank Fusion
    all_candidate_ids = set(fts_rank_map.keys()).union(
        cid for _, cid, _, _, _ in dense_scores[:50]
    )

    rrf_scores: List[Tuple[float, float, str, str, str, str]] = []
    for cid in all_candidate_ids:
        score = 0.0
        if cid in dense_rank_map:
            score += 1.0 / (rrf_k + dense_rank_map[cid])
        if cid in fts_rank_map:
            score += 1.0 / (rrf_k + fts_rank_map[cid])

        dense_score = dense_score_map.get(cid, 0.0)
        path, title, content = chunk_meta[cid]
        rrf_scores.append((score, dense_score, cid, title, path, content))

    rrf_scores.sort(key=lambda x: x[0], reverse=True)
    conn.close()
    return rrf_scores[:top_k]


@mcp.tool()
def search_project_knowledge(query: str, top_k: int = 5) -> str:
    """
    Search VoicePulse architecture, domain rules, state machines, and engineering standards.
    Uses Reciprocal Rank Fusion (RRF) combining dense ONNX vector similarity and BM25 full-text search.
    """
    if not DEFAULT_DB_PATH.exists():
        return (
            "RAG database not found. Please run 'make rag-index' or invoke "
            "'reindex_project_knowledge()' to initialize."
        )

    results = compute_hybrid_search(query=query, top_k=top_k)
    if not results:
        return f"No documentation chunks found matching query: '{query}'"

    formatted: List[str] = []
    for rrf_score, dense_score, cid, title, path, content in results:
        formatted.append(
            f"### {title}\n"
            f"**File:** `{path}` | **Score:** {rrf_score:.4f} (Dense Cosine: {dense_score:.3f})\n\n"
            f"{content}\n"
        )

    return "\n---\n\n".join(formatted)


@mcp.tool()
def get_doc_section(file_path: str, section_title: str) -> str:
    """
    Fetch exact markdown section content from indexed docs without loading the whole file.
    """
    if not DEFAULT_DB_PATH.exists():
        return "RAG database not found. Run 'make rag-index' first."

    conn = sqlite3.connect(DEFAULT_DB_PATH)
    # Try exact or partial path and title match
    row = conn.execute(
        """
        SELECT path, title, content FROM chunks
        WHERE path LIKE ? AND title LIKE ?
        LIMIT 1
        """,
        (f"%{file_path}%", f"%{section_title}%"),
    ).fetchone()

    if not row:
        # Fallback to searching title only
        row = conn.execute(
            """
            SELECT path, title, content FROM chunks
            WHERE title LIKE ?
            LIMIT 1
            """,
            (f"%{section_title}%",),
        ).fetchone()

    conn.close()

    if not row:
        return f"Section '{section_title}' in file '{file_path}' not found."

    path, title, content = row
    return f"### {title}\n**File:** `{path}`\n\n{content}"


@mcp.tool()
def reindex_project_knowledge(force: bool = False) -> str:
    """
    Rebuilds or updates the local documentation vector database.
    Incremental by default; set force=True to re-index all files.
    """
    stats = index_docs(
        docs_dir=DEFAULT_DOCS_DIR,
        agents_md=DEFAULT_AGENTS_MD,
        db_path=DEFAULT_DB_PATH,
        force=force,
        embedder=get_embedder(),
    )
    return (
        f"RAG Index Updated: {stats['indexed_files']} files updated, "
        f"{stats['skipped_files']} skipped, {stats['indexed_chunks']} chunks indexed, "
        f"{stats['deleted_files']} purged."
    )


if __name__ == "__main__":
    mcp.run()
