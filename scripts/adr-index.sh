#!/usr/bin/env bash
#
# Writes docs/adr/README.md: every decision, its story, and whether anything later changed it.
#
# The index is generated rather than kept by hand, because an index kept by hand is an index that is
# missing the last three entries. Everything it says is read from the ADRs themselves — the title from the
# heading, the story from `- Jira:`, and a later reversal from a `- Superseded:`, `- Narrowed:` or
# `- Revisited:` line in the same header. AdrIndexTest fails the build if the two disagree.
#
# Usage:  ./scripts/adr-index.sh          writes docs/adr/README.md
#         ./scripts/adr-index.sh --check  fails if it is not what this would write

DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/mizan.sh
. "$DIRECTORY/mizan.sh"

ROOT="$(cd "$DIRECTORY/.." && pwd)"
MODE="${1:-write}"

"$PYTHON" - "$ROOT" "$MODE" <<'PY'
import os
import re
import sys

root, mode = sys.argv[1], sys.argv[2]
folder = f"{root}/docs/adr"
index_path = f"{folder}/README.md"

# A header line that says something later changed this decision. The key is shown as it is written.
CHANGED = ("Superseded", "Superseded in part", "Narrowed", "Revisited", "Reversed")


def header_of(text):
    """The `- Key: value` lines above the first section."""
    header = {}
    for line in text.split("\n## ", 1)[0].splitlines():
        field = re.match(r"^- ([A-Za-z ]+):\s*(.*)$", line)
        if field:
            header[field.group(1).strip()] = field.group(2).strip()
    return header


rows = []
for name in sorted(os.listdir(folder)):
    if not re.match(r"^\d{4}-.*\.md$", name) or name.startswith("0000-"):
        continue
    with open(f"{folder}/{name}", encoding="utf-8") as handle:
        text = handle.read()

    title = re.match(r"^# ADR (\d{4}): (.*)$", text.splitlines()[0])
    if not title:
        print(f"{name} does not start with '# ADR NNNN: a decision'", file=sys.stderr)
        sys.exit(1)

    header = header_of(text)
    changed = [f"{key}: {header[key]}" for key in CHANGED if key in header]
    rows.append(
        {
            "number": title.group(1),
            "title": title.group(2),
            "file": name,
            "status": header.get("Status", ""),
            "jira": header.get("Jira", ""),
            "changed": " ".join(changed),
        }
    )

lines = [
    "# Decisions",
    "",
    "Every architecture decision in this platform, newest last. Generated from the ADRs themselves by",
    "`./scripts/adr-index.sh`, and checked by `AdrIndexTest`, so a decision recorded without an entry here",
    "fails the build.",
    "",
    "A decision that was later narrowed, superseded or revisited says so in its own header and in the",
    "**Changed since** column, naming what changed it and why. None of them are deleted: what was decided,",
    "and later decided differently, is the part worth reading.",
    "",
    "| ADR | Decision | Story | Status | Changed since |",
    "|---|---|---|---|---|",
]
for row in rows:
    lines.append(
        f"| {row['number']} | [{row['title']}]({row['file']}) | {row['jira']} | {row['status']} "
        f"| {row['changed'] or '—'} |"
    )
lines += [
    "",
    f"{len(rows)} decisions. The template for a new one is [0000-template.md](0000-template.md).",
    "",
]
wanted = "\n".join(lines)

if mode == "--check":
    try:
        with open(index_path, encoding="utf-8") as handle:
            current = handle.read()
    except FileNotFoundError:
        current = ""
    if current != wanted:
        print(
            "docs/adr/README.md is not what the ADRs say.\n"
            "Run ./scripts/adr-index.sh and commit the result.",
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"the index lists all {len(rows)} decisions, as they are written")
else:
    with open(index_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(wanted)
    print(f"docs/adr/README.md written: {len(rows)} decisions")
PY
