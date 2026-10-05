#!/usr/bin/env python3
"""Set a published GitHub release title using its original UTC publication date."""
import argparse
from datetime import datetime, timezone
import json
import subprocess
from urllib.parse import quote

MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def release_title(release):
    if release.get("draft") or not release.get("published_at"):
        raise ValueError("A published release with published_at is required")
    published = datetime.fromisoformat(release["published_at"].replace("Z", "+00:00"))
    if published.tzinfo is None:
        raise ValueError("published_at must include a timezone")
    published = published.astimezone(timezone.utc)
    return f"{release['tag_name']} – {MONTHS[published.month - 1]} {published.day}, {published.year}"


def api(endpoint, payload=None):
    command = ["gh", "api", endpoint]
    if payload is not None:
        command += ["--method", "PATCH", "--input", "-"]
    return json.loads(subprocess.check_output(
        command, input=json.dumps(payload) if payload is not None else None, text=True))


def update_title(repository, tag):
    release = api(f"repos/{repository}/releases/tags/{quote(tag, safe='')}")
    title = release_title(release)
    if release.get("name") != title:
        updated = api(f"repos/{repository}/releases/{release['id']}", {"name": title})
        if updated.get("name") != title or any(updated.get(key) != release.get(key) for key in
                ("id", "tag_name", "published_at", "body", "draft", "prerelease", "target_commitish")):
            raise ValueError("Release-title update did not preserve the publication metadata and body")
    print(title)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("tag")
    args = parser.parse_args()
    update_title(args.repository, args.tag)
