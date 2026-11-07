#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "fastembed>=0.5.0",
#     "mcp>=1.3.0,<2",
#     "numpy>=1.26.0",
#     "pytest>=8.0.0",
# ]
# ///
"""
test_rag.py — Comprehensive unit & integration verification for VoicePulse RAG system.
Tests semantic chunking, code-fence preservation, incremental SHA256 caching,
dense vector embeddings, SQLite FTS5 BM25 search, RRF scoring, and FastMCP tools.
"""

import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
import unittest
import numpy as np

# Ensure scripts directory is on sys.path
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from rag_indexer import (
    MarkdownChunk,
    chunk_markdown,
    compute_file_sha256,
    index_docs,
    init_db,
    split_section_content,
)
from rag_mcp_server import (
    compute_hybrid_search,
    get_doc_section,
    reindex_project_knowledge,
    sanitize_fts5_query,
    search_project_knowledge,
)


class TestVoicePulseRAG(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="voicepulse_rag_test_")
        self.docs_dir = Path(self.test_dir) / "docs"
        self.docs_dir.mkdir(parents=True, exist_ok=True)
        self.db_path = Path(self.test_dir) / "test_rag.db"
        self.agents_md = Path(self.test_dir) / "AGENTS.md"

        # Create dummy AGENTS.md
        self.agents_md.write_text(
            "# AGENTS.md Directives\n\n## 1. Hexagonal Purity\nDomain must be pure.\n",
            encoding="utf-8",
        )

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_chunking_hierarchy(self):
        """Verifies hierarchical headers and breadcrumbs."""
        test_file = self.docs_dir / "telephony.md"
        test_file.write_text(
            "# Telephony Guide\n\n"
            "Intro paragraph.\n\n"
            "## Invariants\n\n"
            "Core telephony invariants and constraints.\n\n"
            "### 25-Word Rule\n\n"
            "Max 25 words per turn.\n",
            encoding="utf-8",
        )

        chunks = chunk_markdown(test_file)
        self.assertEqual(len(chunks), 3)

        self.assertEqual(chunks[0].section_title, "telephony.md > Telephony Guide")
        self.assertIn("Intro paragraph.", chunks[0].content)

        self.assertEqual(
            chunks[1].section_title, "telephony.md > Telephony Guide > Invariants"
        )
        self.assertIn("Core telephony invariants", chunks[1].content)

        self.assertEqual(
            chunks[2].section_title,
            "telephony.md > Telephony Guide > Invariants > 25-Word Rule",
        )
        self.assertIn("Max 25 words per turn.", chunks[2].content)

    def test_code_fence_preservation(self):
        """Verifies markdown code fences are preserved and # inside code is not treated as header."""
        test_file = self.docs_dir / "code_sample.md"
        content = (
            "# Code Architecture\n\n"
            "Here is the code block:\n\n"
            "```python\n"
            "# This is a python comment, NOT a markdown header\n"
            "def handle_call(call_id):\n"
            "    # Another comment\n"
            "    return True\n"
            "```\n\n"
            "After code block.\n"
        )
        test_file.write_text(content, encoding="utf-8")

        chunks = chunk_markdown(test_file)
        self.assertEqual(len(chunks), 1)
        self.assertEqual(chunks[0].section_title, "code_sample.md > Code Architecture")
        self.assertIn("def handle_call(call_id):", chunks[0].content)
        self.assertIn("# This is a python comment", chunks[0].content)

    def test_paragraph_splitting_long_sections(self):
        """Verifies long sections are split on paragraph boundaries without breaking code fences."""
        long_para_1 = "Paragraph 1 with repetitive words. " * 30  # ~1000 chars
        code_fence = (
            "```java\n"
            "// Critical code block\n"
            "public record FlightTicket(String pnr) {}\n"
            "```"
        )
        long_para_2 = "Paragraph 2 with more words. " * 30  # ~870 chars
        full_content = f"{long_para_1}\n\n{code_fence}\n\n{long_para_2}"

        sub_chunks = split_section_content(full_content, max_chars=800)
        self.assertTrue(len(sub_chunks) >= 2)
        # Ensure code fence is intact in one of the chunks
        code_found = any(code_fence in sc for sc in sub_chunks)
        self.assertTrue(code_found, "Code fence must remain completely intact")

    def test_incremental_indexing(self):
        """Verifies incremental indexing: skips unchanged, updates modified, purges deleted."""
        doc1 = self.docs_dir / "doc1.md"
        doc1.write_text("# Doc 1\n\nInitial content for doc 1.\n", encoding="utf-8")

        # Initial index
        stats1 = index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=False,
        )
        self.assertEqual(stats1["indexed_files"], 2)  # doc1 + agents_md
        self.assertEqual(stats1["skipped_files"], 0)

        # Second index without modifications (should skip all)
        stats2 = index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=False,
        )
        self.assertEqual(stats2["indexed_files"], 0)
        self.assertEqual(stats2["skipped_files"], 2)

        # Modify doc1
        doc1.write_text("# Doc 1\n\nUpdated new content.\n", encoding="utf-8")
        stats3 = index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=False,
        )
        self.assertEqual(stats3["indexed_files"], 1)
        self.assertEqual(stats3["skipped_files"], 1)

        # Delete doc1
        doc1.unlink()
        stats4 = index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=False,
        )
        self.assertEqual(stats4["deleted_files"], 1)

    def test_dense_vector_search(self):
        """Verifies vector embedding calculation and cosine similarity search."""
        doc = self.docs_dir / "vad.md"
        doc.write_text(
            "# Voice Activity Detection\n\n"
            "VAD energy threshold detects silence and emits barge in event for playback buffer flush.\n",
            encoding="utf-8",
        )
        index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=True,
        )

        results = compute_hybrid_search(
            query="energy threshold barge in interruption",
            top_k=3,
            db_path=self.db_path,
        )
        self.assertTrue(len(results) > 0)
        top_result = results[0]
        # (rrf_score, dense_score, cid, title, path, content)
        dense_score = top_result[1]
        self.assertGreater(dense_score, 0.5)
        self.assertIn("vad.md", top_result[4])

    def test_sparse_fts5_search(self):
        """Verifies SQLite FTS5 BM25 match query and query sanitization."""
        sanitized = sanitize_fts5_query("What is the 25-word brevity rule?")
        self.assertEqual(
            sanitized,
            '"What" OR "is" OR "the" OR "25" OR "word" OR "brevity" OR "rule"',
        )

        doc = self.docs_dir / "nato.md"
        doc.write_text(
            "# Phonetics\n\nNATO phonetic spelling for booking reference verification.\n",
            encoding="utf-8",
        )
        index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=True,
        )

        conn = sqlite3.connect(self.db_path)
        fts_rows = conn.execute(
            "SELECT id, title, content FROM chunks_fts WHERE chunks_fts MATCH ?",
            ('"phonetic"',),
        ).fetchall()
        conn.close()
        self.assertEqual(len(fts_rows), 1)
        self.assertIn("NATO phonetic spelling", fts_rows[0][2])

    def test_hybrid_rrf_scoring(self):
        """Verifies that dense and sparse results are combined via RRF formula (k=60.0)."""
        doc = self.docs_dir / "kafka.md"
        doc.write_text(
            "# Kafka Partitioning\n\nPartition key mandate requires callId.toString() on raw topic.\n",
            encoding="utf-8",
        )
        index_docs(
            docs_dir=self.docs_dir,
            agents_md=self.agents_md,
            db_path=self.db_path,
            force=True,
        )

        results = compute_hybrid_search(
            query="Kafka partition key callId",
            top_k=1,
            db_path=self.db_path,
            rrf_k=60.0,
        )
        self.assertEqual(len(results), 1)
        rrf_score = results[0][0]
        # If item ranked 1 in both dense and sparse, RRF score = 1/(60+1) + 1/(60+1) = 2/61 ~= 0.03278
        self.assertGreater(rrf_score, 0.015)
        self.assertIn("Kafka Partitioning", results[0][3])

    def test_mcp_tool_execution(self):
        """Verifies MCP tools: search_project_knowledge, get_doc_section, reindex_project_knowledge."""
        # Test on main repo RAG db
        search_res = search_project_knowledge("25-word brevity rule", top_k=2)
        self.assertIn("25-Word Brevity Rule", search_res)

        section_res = get_doc_section("telephony-invariants", "25-Word Brevity Rule")
        self.assertIn("25-word maximum limit", section_res)

        reindex_res = reindex_project_knowledge(force=False)
        self.assertIn("RAG Index Updated", reindex_res)


if __name__ == "__main__":
    unittest.main()
