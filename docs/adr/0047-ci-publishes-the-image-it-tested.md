# ADR 0047: CI publishes the image it tested, and a known fixable hole stops it

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-82

## Context

Until now nothing published an image. The only images were the ones Compose built on whichever
machine ran it, and nothing looked at what was inside them.

The first scan of the new distroless images found five serious findings that already had fixes:

- **Three CRITICAL in Tomcat 11.0.24**, the embedded server in every servlet service: a security
  constraint bypass and two authentication bypasses, all fixed in 11.0.25. Spring Boot 4.0.8
  manages 11.0.24, and no Boot release yet manages 11.0.25.
- **Two HIGH in the distroless base**, libexpat1 and liblcms2-2. Debian has published fixed
  packages; the distroless image has not been rebuilt on them, and a distroless image cannot have
  a package upgraded into it by hand.

Two ways to get this wrong are common. Scan, and let the results be advisory, which in practice
means unread. Or fail on everything, including findings with no fix, which teaches everybody
that the scan is noise and gets it switched off.

## Decision

**The images the smoke check tested are the ones scanned and the ones published. A HIGH or
CRITICAL finding with a fix available fails the build, unless it is accepted in writing with an
end date.**

- **Scanned after testing, in the same job.** The smoke check, the browser journey and the demo
  seed run against the images Compose built; `scripts/scan-images.sh` then scans those same
  images, and only then are they tagged and pushed. There is no second build whose output could
  differ from what was tested.
- **Published from main only, tagged by commit.** A pull request builds, tests and scans and
  publishes nothing. An image is tagged `ghcr.io/<owner>/mizan-<service>:<sha>` and never only
  with a moving tag, because `latest` says which image was pushed last, not which source built it.
- **Fixable and serious fails; unfixable does not.** A finding nobody can act on is noise, and
  noise is what gets a scan ignored.
- **The fix comes first.** Tomcat is pinned to 11.0.25 in `build.gradle.kts`, ahead of the Boot
  BOM, until a Boot release manages it — with a comment saying the pin goes in the same commit
  that moves Boot forward.
- **An exception is a decision with a deadline.** `.trivyignore.yaml` names the finding, why it
  is accepted, who accepted it, and when that stops being true. Trivy itself stops honouring an
  entry past its `expired_at`, which was checked by scanning with one expired entry and one in
  date. `ExceptionsTest` refuses an entry with no reason or owner and one that runs more than
  ninety days.

## Consequences

- A new CVE published against a dependency fails main even though no code changed. That is
  correct and occasionally inconvenient, and the answer is the same as for any other failure: fix
  it or accept it in writing.
- The two distroless exceptions expire on 2026-10-15. If distroless has not rebuilt by then the
  build fails, and somebody has to look again — which is the whole design.
- The pushed images are the Compose images, built on a CI runner with `docker compose build`.
  The Helm chart (MIZ-83) consumes these tags; nothing builds an image a second time for a
  cluster.
- The exceptions are scoped by CVE id, not by path. The first version of the file scoped them by
  library path and suppressed nothing, because an OS package reports no path. That was caught by
  scanning with the real file before trusting it.

## Alternatives

- **A separate image job that builds and pushes in parallel with the smoke job.** Faster, and it
  publishes an image that was never run.
- **Advisory scanning, results uploaded and not enforced.** Read for a week.
- **Fail on every finding, fixed or not.** Fails permanently on findings nobody can do anything
  about, and trains people to override it.
- **Accept the Tomcat findings until Boot catches up.** A fix exists and costs one constraint.
  Accepting a CRITICAL authentication bypass for convenience is not an exception, it is a choice
  nobody would sign.
