# ADR 0063: The architecture diagram is generated from source and checked against the platform

- Status: accepted
- Date: 2026-09-17
- Jira: MIZ-92

## Context

Somebody arriving at this repository should see how it is put together before reading any of it. That
means a picture in the README: who uses the platform, and what the sixteen containers in
`docker-compose.yml` are.

Architecture diagrams have one well known failure mode, and it is not being ugly. It is being wrong: a
service is added, the picture is not, and the picture keeps being shown to people for a year. A diagram
nobody can trust is worse than no diagram, because it is believed.

A second, smaller problem: GitHub renders a Mermaid block where it finds one in a Markdown file, and will
not follow a reference to a `.mmd` file. So the README has to carry the diagram source itself, which is
exactly the arrangement where a copy drifts from its original.

## Decision

**The diagrams are Mermaid source in `docs/architecture`, copied into the README by
`./scripts/diagrams.sh`, and CI fails if the README's copy has drifted or if a service in
`docker-compose.yml` is missing from the container diagram.**

- **Mermaid, not an image.** It renders on GitHub, it is diffable, and changing it does not require a
  drawing tool or a binary in the repository. An exported PNG would be a second thing to keep in step.
- **The source is `docs/architecture`; the README holds a copy.** One command writes the copy, and
  `--check` proves it is current, so the thing a reader sees is the thing that is maintained.
- **The check reads `docker-compose.yml`.** Every service name there has to appear in the container
  diagram. That is the guarantee worth having: not that the picture is beautiful, but that it is complete.
- **It is a CI step**, beside the one that parses every script, and for the same reason — it costs a
  second, and the failure it catches otherwise waits months to be noticed by a reader who then stops
  trusting the rest.
- **Edges are drawn from the group where the fact is about the group.** Every service has its own database
  on that Postgres and every service emits spans; drawing thirteen edges to say so makes a picture nobody
  reads. Two labelled edges from the services box say the same thing.

## Evidence

- `./scripts/diagrams.sh --check` passes on this branch, and CI runs it.
- Renaming `settlement-service` in the container diagram makes it fail with
  `These are in docker-compose.yml and not in the container diagram: settlement-service`.
- Both diagrams were rendered with `@mermaid-js/mermaid-cli` before being committed, so a syntax error is
  not discovered by a reader looking at a broken block on GitHub. The rendering is not in CI: it downloads
  a browser, and the failure it catches is caught once, when the diagram is written.

## Consequences

- **A new service means an edit to `containers.mmd`,** or CI goes red. That is the decision working; the
  error message says which service and which file.
- **The check knows about existence, not about truth.** It cannot tell that an arrow points the wrong way
  or that a service stopped calling another. Those still need a person to read the picture, and the
  diagram source sits beside the ADRs where such a change is being described anyway.
- **The README carries a generated block.** Editing the diagram in the README directly is wasted work — the
  next run overwrites it, and `--check` fails in the meantime.
- **Mermaid's layout is Mermaid's.** Node placement is not controlled here, so a large change can move
  things around; the compensation is that nobody has to maintain coordinates.

## Alternatives

- **A drawn image (Excalidraw, draw.io, a PNG).** Prettier, and stale within a month with nothing able to
  tell.
- **Structurizr or another C4 tool.** A real answer at a larger scale, and a toolchain plus a DSL to learn
  for two diagrams.
- **Generate the container diagram entirely from `docker-compose.yml`.** Then it is always complete and
  says nothing: compose knows what exists and what depends on what for startup, not what calls what or why.
- **Keep the diagram in the README only.** One less file, and no way to tell whether what is rendered is
  what was meant.
