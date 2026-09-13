# ADR 0042: The dashboard is the file, and the browser is a view of it

- Status: accepted
- Date: 2026-09-14
- Jira: MIZ-77

## Context

MIZ-75 and MIZ-76 produced the numbers. Reading them still meant typing PromQL into Prometheus,
which is fine for somebody who already knows which metric to ask about and useless for anybody
else — including the same person at three in the morning.

Grafana is the obvious answer, and the obvious way to use it is also the way that rots. Somebody
opens it, builds a dashboard, gets it right, and that dashboard now exists in one place: the
container's SQLite file. It cannot be reviewed, because there is no diff. It cannot be recreated,
because nobody wrote down how it was built. It does not survive `docker compose down -v`. And
when it changes, nobody finds out — which for a monitoring surface is the same failure mode the
scrape configuration has (ADR 0040's neighbour, MIZ-75): a check that quietly stopped covering
something looks exactly like a thing with nothing wrong.

## Decision

**The dashboards, the datasource and the dashboard provider are files under
`deploy/local/grafana`, and Grafana is provisioned from them on every start.**

- **The file wins.** `allowUiUpdates: false`, so a change made in the browser is not saved and
  does not survive the next reload. Editing in the browser is still allowed, because reading a
  dashboard usually means poking at it — but the way to keep a change is to put it in the file
  and open a pull request, which is the point.
- **The datasource is named by uid**, `mizan-prometheus`, in every panel of every dashboard.
  Not "whichever datasource is default": a second datasource added later must not be able to
  silently repoint every panel on the platform.
- **Two dashboards, not one.** *Is the platform up* is asked by whoever is on call, about
  processes. *Is it just this payment* is asked with a merchant on the phone, about money. One
  dashboard serving both is the forty-panel dashboard nobody reads, and the panels that matter
  during one of those conversations are noise during the other.
- **No dual axis anywhere, and it is asserted.** Two measures of different scale sharing a pair
  of axes is a way to make any two lines look related when the relationship is an artefact of
  the scaling. Where two units both matter — how many things are queued, and how old the oldest
  is — they get two panels.
- **A panel's query is checked rather than trusted.** `DashboardsTest` fails a panel naming a
  `mizan_` metric no service registers, and smoke step 20 asks the running Prometheus the same
  question of every name on both dashboards, which covers the metrics this repository does not
  own. A panel querying a metric that does not exist renders as an empty chart, and an empty
  chart is indistinguishable from a quiet platform — discovered, if nothing checks, by the
  person who opened the dashboard during an incident.

## Consequences

- Changing a dashboard is a commit and a review, which is slower than dragging a panel. That is
  the trade being made deliberately: what the platform watches is part of the platform.
- Anything a person builds in the browser is lost on the next provisioning reload. It is worth
  saying out loud, because somebody will lose work to it once. The recovery is Grafana's JSON
  model view: copy it into the file.
- Two dashboards will become three, and then the question of when to split will need answering
  again. The rule that decides it is not the panel count — it is whether the same person, in the
  same conversation, needs both halves.
- The local Grafana runs with anonymous access and no sign-in. It holds no data of its own, it
  reads a Prometheus that holds no merchant's business (ADR 0041), and it is reachable only by
  somebody who already has the machine. Anywhere that matters, this is not how it is run, and
  the Kubernetes work in MIZ-12 is where that decision gets made properly rather than inherited.

## Alternatives

- **Build the dashboards in the browser and export the JSON afterwards.** The export drifts from
  what is running the first time somebody is in a hurry, and nothing notices.
- **Allow UI updates and let the database win.** Then the file is a starting point rather than
  the truth, and the two disagree silently — the worst of both.
- **One dashboard with rows for each audience.** Cheaper to provision, and it puts the panels
  nobody in this conversation needs directly in the way of the ones they do.
- **No Grafana, and let people query Prometheus.** Honest, and it means the only people who can
  read the platform's state are the people who already know what to ask.
