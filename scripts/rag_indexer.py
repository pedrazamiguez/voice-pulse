#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "fastembed>=0.5.0",
#     "numpy>=1.26.0",
# ]
# ///
"""
rag_indexer.py — Semantic markdown chunker and vector indexer for VoicePulse docs.
Parses /docs/**/*.md and AGENTS.md, generating local embeddings into SQLite.
"""

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import sqlite3
import sys
from typing import List, Optional, Tuple
import numpy as np
from fastembed import TextEmbedding

DEFAULT_DOCS_DIR = Path("docs")
DEFAULT_AGENTS_MD = Path("AGENTS.md")
DEFAULT_DB_PATH = Path(".cache/voicepulse_rag.db")
MODEL_NAME = "BAAI/bge-small-en-v1.5"
MAX_CHUNK_CHAR_LEN = 1500


@dataclass
class MarkdownChunk:
    chunk_id: str
    file_path: str
    section_title: str
    content: str


def compute_file_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def split_section_content(
    content: str, max_chars: int = MAX_CHUNK_CHAR_LEN
) -> List[str]:
    """Splits long text into paragraphs while preserving code blocks."""
    if len(content) <= max_chars:
        return [content]

    paragraphs: List[str] = []
    current_para: List[str] = []
    in_code_fence = False

    for line in content.splitlines(keepends=True):
        stripped = line.strip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_code_fence = not in_code_fence

        current_para.append(line)
        if not in_code_fence and line == "\n":
            paragraphs.append("".join(current_para))
            current_para = []

    if current_para:
        paragraphs.append("".join(current_para))

    # Group paragraphs into chunks <= max_chars
    chunks: List[str] = []
    current_chunk: List[str] = []
    current_len = 0

    for para in paragraphs:
        if current_len + len(para) > max_chars and current_chunk:
            combined = "".join(current_chunk).strip()
            if combined:
                chunks.append(combined)
            current_chunk = [para]
            current_len = len(para)
        else:
            current_chunk.append(para)
            current_len += len(para)

    if current_chunk:
        combined = "".join(current_chunk).strip()
        if combined:
            chunks.append(combined)

    return chunks if chunks else [content.strip()]


def chunk_markdown(
    file_path: Path, max_chars: int = MAX_CHUNK_CHAR_LEN
) -> List[MarkdownChunk]:
    """Hierarchically chunks markdown preserving breadcrumbs and code fences."""
    chunks: List[MarkdownChunk] = []
    header_stack: List[Tuple[int, str]] = []  # (level, title)
    current_lines: List[str] = []
    in_code_fence = False

    def get_breadcrumb() -> str:
        if not header_stack:
            return file_path.name
        return f"{file_path.name} > " + " > ".join(t for _, t in header_stack)

    def flush_section():
        nonlocal current_lines
        content = "".join(current_lines).strip()
        if not content:
            current_lines = []
            return

        breadcrumb = get_breadcrumb()
        sub_contents = split_section_content(content, max_chars=max_chars)
        for idx, sub_content in enumerate(sub_contents):
            title = (
                breadcrumb
                if len(sub_contents) == 1
                else f"{breadcrumb} (Part {idx + 1})"
            )
            raw_id = f"{file_path}:{title}:{idx}:{sub_content[:32]}"
            cid = hashlib.sha256(raw_id.encode("utf-8")).hexdigest()[:16]
            chunks.append(
                MarkdownChunk(
                    chunk_id=cid,
                    file_path=str(file_path),
                    section_title=title,
                    content=sub_content,
                )
            )
        current_lines = []

    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            # Detect code fence boundaries
            if stripped.startswith("```") or stripped.startswith("~~~"):
                in_code_fence = not in_code_fence
                current_lines.append(line)
                continue

            # Detect markdown headers outside of code fences
            if not in_code_fence and stripped.startswith("#"):
                match_header = True
                level = 0
                for ch in stripped:
                    if ch == "#":
                        level += 1
                    else:
                        break
                if level > 0 and len(stripped) > level and stripped[level] == " ":
                    title = stripped[level:].strip()
                    flush_section()
                    while header_stack and header_stack[-1][0] >= level:
                        header_stack.pop()
                    header_stack.append((level, title))
                    continue

            current_lines.append(line)

        flush_section()

    return chunks


def init_db(conn: sqlite3.Connection):
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS documents (
            file_path TEXT PRIMARY KEY,
            sha256 TEXT NOT NULL,
            updated_at TIMESTAMP NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS chunks (
            id TEXT PRIMARY KEY,
            path TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            embedding BLOB NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
            id,
            title,
            content,
            tokenize='porter'
        )
        """
    )
    conn.commit()


def index_docs(
    docs_dir: Path = DEFAULT_DOCS_DIR,
    agents_md: Path = DEFAULT_AGENTS_MD,
    db_path: Path = DEFAULT_DB_PATH,
    force: bool = False,
    embedder: Optional[TextEmbedding] = None,
) -> dict:
    """Indexes markdown documents into SQLite with FastEmbed embeddings."""
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    init_db(conn)

    # Gather target files
    target_files: List[Path] = []
    if docs_dir.exists():
        target_files.extend(sorted(docs_dir.rglob("*.md")))
    if agents_md.exists():
        target_files.append(agents_md)

    stats = {
        "total_files": len(target_files),
        "indexed_files": 0,
        "skipped_files": 0,
        "indexed_chunks": 0,
        "deleted_files": 0,
    }

    # Clean up deleted files from DB
    existing_in_db = [
        row[0] for row in conn.execute("SELECT file_path FROM documents").fetchall()
    ]
    current_paths_str = {str(p) for p in target_files}
    for old_path in existing_in_db:
        if old_path not in current_paths_str:
            conn.execute("DELETE FROM documents WHERE file_path = ?", (old_path,))
            conn.execute("DELETE FROM chunks WHERE path = ?", (old_path,))
            conn.execute(
                "DELETE FROM chunks_fts WHERE id IN (SELECT id FROM chunks WHERE path = ?)",
                (old_path,),
            )
            stats["deleted_files"] += 1

    files_to_process: List[Tuple[Path, str]] = []
    for fpath in target_files:
        current_sha = compute_file_sha256(fpath)
        row = conn.execute(
            "SELECT sha256 FROM documents WHERE file_path = ?", (str(fpath),)
        ).fetchone()
        if not force and row and row[0] == current_sha:
            stats["skipped_files"] += 1
            continue
        files_to_process.append((fpath, current_sha))

    if not files_to_process:
        conn.commit()
        conn.close()
        return stats

    if embedder is None:
        print(f"Loading embedding model '{MODEL_NAME}' on CPU ONNX...")
        embedder = TextEmbedding(model_name=MODEL_NAME)

    now = datetime.now(timezone.utc).isoformat()

    for fpath, sha in files_to_process:
        # Clear old chunks for this file
        conn.execute("DELETE FROM chunks WHERE path = ?", (str(fpath),))
        conn.execute("DELETE FROM chunks_fts WHERE id NOT IN (SELECT id FROM chunks)")

        chunks = chunk_markdown(fpath)
        if chunks:
            texts = [f"{c.section_title}\n{c.content}" for c in chunks]
            embeddings = list(embedder.embed(texts))

            for c, emb in zip(chunks, embeddings):
                blob = np.array(emb, dtype=np.float32).tobytes()
                conn.execute(
                    "INSERT OR REPLACE INTO chunks VALUES (?, ?, ?, ?, ?)",
                    (c.chunk_id, c.file_path, c.section_title, c.content, blob),
                )
                conn.execute(
                    "INSERT INTO chunks_fts (id, title, content) VALUES (?, ?, ?)",
                    (c.chunk_id, c.section_title, c.content),
                )
            stats["indexed_chunks"] += len(chunks)

        conn.execute(
            "INSERT OR REPLACE INTO documents (file_path, sha256, updated_at) VALUES (?, ?, ?)",
            (str(fpath), sha, now),
        )
        stats["indexed_files"] += 1

    conn.commit()
    conn.close()
    return stats


def main():
    force = "--force" in sys.argv
    print(
        f"Indexing documentation into {DEFAULT_DB_PATH} (force={force})..."
    )
    stats = index_docs(force=force)
    print(
        f"✅ RAG Index Complete: {stats['indexed_files']} files updated, "
        f"{stats['skipped_files']} skipped, {stats['indexed_chunks']} chunks indexed, "
        f"{stats['deleted_files']} purged."
    )


if __name__ == "__main__":
    main()
