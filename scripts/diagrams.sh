#!/usr/bin/env bash
#
# Puts the architecture diagrams into the README, and keeps them honest.
#
# The diagrams live in docs/architecture as Mermaid source. This copies them into the README between
# markers, because GitHub renders a Mermaid block where it finds one and will not follow a reference to a
# file — so the README has to carry the source, and something has to make sure the copy is the original.
#
# It also reads docker-compose.yml. A service added to the platform and not to the container diagram is a
# picture that quietly stops being true, which is the failure mode every architecture diagram eventually
# has. Here it fails a check instead.
#
# Usage:  ./scripts/diagrams.sh          writes the README
#         ./scripts/diagrams.sh --check  fails if the README is out of date, or a service is missing

DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/mizan.sh
. "$DIRECTORY/mizan.sh"

ROOT="$(cd "$DIRECTORY/.." && pwd)"
MODE="${1:-write}"

"$PYTHON" - "$ROOT" "$MODE" <<'PY'
import re
import sys

root, mode = sys.argv[1], sys.argv[2]
readme_path = f"{root}/README.md"
compose_path = f"{root}/docker-compose.yml"

diagrams = {
    "context": f"{root}/docs/architecture/context.mmd",
    "containers": f"{root}/docs/architecture/containers.mmd",
}


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def services_in_compose(text):
    """The services, and only the services: the volumes below them are indented the same way."""
    services, inside = [], False
    for line in text.splitlines():
        if re.match(r"^[a-zA-Z]", line):
            inside = line.startswith("services:")
            continue
        if inside:
            named = re.match(r"^  ([a-z0-9][a-z0-9-]*):\s*$", line)
            if named:
                services.append(named.group(1))
    return services


readme = read(readme_path)
compose = read(compose_path)

missing = [name for name in services_in_compose(compose) if name not in read(diagrams["containers"])]
if missing:
    print("These are in docker-compose.yml and not in the container diagram:", file=sys.stderr)
    for name in missing:
        print(f"  {name}", file=sys.stderr)
    print("Add them to docs/architecture/containers.mmd.", file=sys.stderr)
    sys.exit(1)

wanted = readme
for name, path in diagrams.items():
    block = f"<!-- diagram: {name} -->\n```mermaid\n{read(path).strip()}\n```\n<!-- end diagram -->"
    pattern = re.compile(
        rf"<!-- diagram: {name} -->.*?<!-- end diagram -->",
        re.S,
    )
    if not pattern.search(wanted):
        print(f"The README has no <!-- diagram: {name} --> block to fill.", file=sys.stderr)
        sys.exit(1)
    wanted = pattern.sub(lambda _: block, wanted, count=1)

if mode == "--check":
    if wanted != readme:
        print(
            "The README's diagrams are not what docs/architecture says.\n"
            "Run ./scripts/diagrams.sh and commit the result.",
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"the diagrams match docs/architecture, and cover every service in docker-compose.yml")
else:
    with open(readme_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(wanted)
    print("README diagrams written from docs/architecture")
PY
