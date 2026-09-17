#!/usr/bin/env bash
#
# Builds the API documentation site from the committed OpenAPI specifications.
#
# The specifications are generated from the code and committed (ADR 0005), and a test in each service
# compares the committed file against what the running service serves — so they are already known to be
# true. This turns them into something a merchant can read: one page per service, and an index, with no
# server and no stack needed to look at it.
#
# CI builds this on every push and keeps it as an artifact, so the documentation for any commit is the
# documentation that commit's code produced.
#
# Usage:  ./scripts/api-docs.sh [output directory]   (default: build/api-docs)

DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/mizan.sh
. "$DIRECTORY/mizan.sh"

ROOT="$(cd "$DIRECTORY/.." && pwd)"
OUTPUT="${1:-$ROOT/build/api-docs}"
SPECS="$ROOT/docs/api"

if ! command -v npx > /dev/null; then
    echo "This needs Node, for @redocly/cli. Nothing else." >&2
    exit 1
fi

mkdir -p "$OUTPUT"
echo "building into $OUTPUT"

built=()
for spec in "$SPECS"/*.yaml; do
    name="$(basename "$spec" .yaml)"
    echo "  $name"
    npx -y @redocly/cli@2 build-docs "$spec" --output "$OUTPUT/$name.html" > /dev/null
    built+=("$name")
done

# The index. Written here rather than kept as a file, so a service that gains a specification appears
# without anybody remembering to add it.
{
    cat <<'HEAD'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mizan API</title>
<style>
  :root { color-scheme: light dark; --ink: #1f2328; --muted: #59636e; --line: #d1d9e0; --bg: #ffffff; }
  @media (prefers-color-scheme: dark) {
    :root { --ink: #e6edf3; --muted: #9198a1; --line: #3d444d; --bg: #0d1117; }
  }
  body { background: var(--bg); color: var(--ink); margin: 0 auto; max-width: 46rem; padding: 3rem 1.5rem;
         font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; }
  h1 { font-size: 1.75rem; margin-bottom: 0.25rem; }
  p { color: var(--muted); }
  ul { list-style: none; padding: 0; }
  li { border-top: 1px solid var(--line); padding: 0.85rem 0; }
  a { color: inherit; font-weight: 600; text-decoration: none; }
  a:hover { text-decoration: underline; }
  code { color: var(--muted); font-size: 0.9rem; }
</style>
</head>
<body>
<h1>Mizan API</h1>
<p>Generated from the OpenAPI specifications this platform's services produce, and committed alongside
them. Every merchant-facing endpoint is reached through the gateway at <code>/api/v1/...</code>.</p>
<ul>
HEAD
    for name in "${built[@]}"; do
        printf '  <li><a href="%s.html">%s</a><br><code>docs/api/%s.yaml</code></li>\n' \
            "$name" "$name" "$name"
    done
    cat <<'FOOT'
</ul>
<p>Start with <a href="https://github.com/Kauzes/mizan/blob/main/docs/api/worked-example.md">the worked
example</a>: register, sign in, take a payment and give part of it back, with the real requests and
responses a running platform answered.</p>
</body>
</html>
FOOT
} > "$OUTPUT/index.html"

echo "built ${#built[@]} specifications and an index"
