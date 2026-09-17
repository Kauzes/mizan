# The demo recording: a shot list

Somebody has to sit at a screen and record this. Nothing here can be generated, which is why it is a
written plan with a placeholder in the README rather than a file that quietly never appeared.

**Target: six minutes.** Longer than that and the only people who finish it are the ones who were going
to read the code anyway.

## Before recording

```sh
docker compose down -v && docker compose up -d --wait   # a platform with no history behind it
./scripts/seed.sh                                       # two merchants, a week of takings, a refund,
                                                        # a held payment, a settled day, a statement
```

Note the credentials it prints — the first merchant, Karaköy Kahve, is the one with the refund and the
held payment on it. Have the Android emulator running and the app installed
(`cd android && ./gradlew installDebug`) before starting, because watching Gradle is not a demo.

Record at 1920×1080. Zoom the terminal to about 16pt: the smallest text anybody will read on a phone is
the size the recording makes it.

**Do not show**: a real card number anywhere (the simulator's cards are fine and are the point), the
`.env` file, or any token in full.

## The shots

| # | Time | What is on screen | What is said |
|---|---|---|---|
| 1 | 0:00–0:25 | `docker compose ps`, every row healthy | Sixteen containers: eight services, Postgres, Kafka, Redis, and the four that watch them. One command started all of it. |
| 2 | 0:25–1:10 | `./scripts/smoke.sh` running, ending green | This is not a unit test. It registers a merchant, takes a payment, refunds it, and checks twenty-four things against real services in the images they deploy as. If this passes, the platform works. |
| 3 | 1:10–1:50 | Console overview for Karaköy Kahve | A week of takings, what went through, what did not and why. Every figure here came from a different service. |
| 4 | 1:50–2:30 | Payments list → one captured payment → its timeline → **In the books** | A payment is a state machine with its history written down, and every capture lands as a balanced entry. This is the double entry ledger the platform is named after: the sum of every posting is zero. |
| 5 | 2:30–3:20 | The refund on that payment, then the books again | A refund is a new movement that names the capture it reverses, never an edit. Both are readable, and the books still balance. |
| 6 | 3:20–4:20 | **Review queue**: the held payment, its three reasons, typing a reason, approving it | Risk held this one: it is eight times this merchant's usual, on a card declined here a minute ago, for an exactly round amount. Nobody was charged. A person rules on it, and has to say why. |
| 7 | 4:20–5:10 | The phone: take a payment, then switch the emulator's network off and take another | The same platform from a merchant's phone. With no signal the payment is queued with its idempotency keys, and the card is kept encrypted only until the authorization is answered. |
| 8 | 5:10–5:40 | Network back on; the queued payment sends itself; the console's list updates | It is sent once, and appears on the console without anybody refreshing anything. |
| 9 | 5:40–6:00 | `curl -s localhost:8082/actuator/ledgerintegrity` | Everything just moved, and the ledger still says every entry sums to zero, every balance matches its postings, and every currency balances platform wide. |

## After recording

- Save as `docs/demo/mizan.mp4`, under 50 MB (trim rather than re-encode past recognition), and add a
  one-line caption in the README replacing the placeholder there.
- If a shot does not work on the day, cut it rather than fake it. A demo of eight real things is worth
  more than a demo of nine where one was staged, and the README says which shots are in the recording.
