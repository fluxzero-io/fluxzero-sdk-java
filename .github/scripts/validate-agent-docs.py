#!/usr/bin/env python3
"""Validate the SDK's transport-independent agent documentation graph (stdlib only)."""

import json
from pathlib import Path, PurePosixPath
import re
import sys


def validate(directory: Path) -> tuple[int, int]:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("unsupported or missing schemaVersion")
    namespace = manifest.get("namespace", "")
    if not isinstance(namespace, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", namespace):
        raise ValueError("namespace must be a lowercase identifier")
    articles = manifest.get("articles")
    if not isinstance(articles, list) or not articles:
        raise ValueError("articles must be a nonempty list")
    paths, sources = {}, set()
    for article in articles:
        path = article.get("path", "")
        if not isinstance(path, str) or not re.fullmatch(r"/docs(?:/[a-z0-9-]+)*", path):
            raise ValueError(f"invalid logical article path: {path!r}")
        if path in paths:
            raise ValueError(f"duplicate article: {namespace}:{path}")
        paths[path] = article
        for field in ("title", "summary", "source"):
            if not isinstance(article.get(field), str) or not article[field].strip():
                raise ValueError(f"{path}: missing {field}")
        source = PurePosixPath(article["source"])
        if (source.is_absolute() or ".." in source.parts or source.suffix != ".md"
                or source.parts[0] != "articles" or "\\" in article["source"]):
            raise ValueError(f"{path}: unsafe article source {source}")
        if source in sources:
            raise ValueError(f"duplicate source: {source}")
        sources.add(source)
        file = directory / source
        if not file.resolve().is_relative_to(directory.resolve()) or not file.is_file():
            raise ValueError(f"{path}: missing or escaped source {source}")
        if not file.read_text(encoding="utf-8").strip():
            raise ValueError(f"{path}: empty source {source}")
        symbols = article.get("symbols")
        if (not isinstance(symbols, list) or any(not isinstance(s, str) or not s.strip() for s in symbols)
                or len(symbols) != len(set(symbols))):
            raise ValueError(f"{path}: invalid or duplicate symbols")
        if not isinstance(article.get("links"), list):
            raise ValueError(f"{path}: links must be a list")

    present = {PurePosixPath(p.relative_to(directory).as_posix())
               for p in (directory / "articles").rglob("*.md")}
    if present != sources:
        raise ValueError(f"unregistered article sources: {sorted(map(str, present - sources))}")
    edges = 0
    for path, article in paths.items():
        targets = set()
        for link in article["links"]:
            target = link.get("path")
            if not isinstance(target, str) or target not in paths:
                raise ValueError(f"{path}: unresolved link {target!r}")
            if target in targets:
                raise ValueError(f"{path}: duplicate link {target}")
            if not isinstance(link.get("description"), str) or not link["description"].strip():
                raise ValueError(f"{path}: link to {target} needs a description")
            targets.add(target)
            edges += 1
    root = manifest.get("root")
    if root not in paths:
        raise ValueError(f"missing root article: {root!r}")
    visited, pending = set(), [root]
    while pending:
        path = pending.pop()
        if path not in visited:
            visited.add(path)
            pending.extend(link["path"] for link in paths[path]["links"])
    if visited != paths.keys():
        raise ValueError(f"unreachable articles: {sorted(paths.keys() - visited)}")
    return len(paths), edges


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2] / "docs" / "agents"
    if len(sys.argv) > 2:
        sys.exit("usage: validate-agent-docs.py [graph-directory]")
    try:
        articles, links = validate(Path(sys.argv[1]) if len(sys.argv) == 2 else root)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        sys.exit(f"Agent documentation graph is invalid: {error}")
    print(f"Agent documentation graph is valid: {articles} reachable articles, {links} links.")
