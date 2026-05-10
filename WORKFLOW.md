---
tracker:
  kind: file
  board_root: ./kanban
  active_states: [Todo, Explore, "In Progress", Review, QA, Learn]
  terminal_states: [Done, Cancelled, Blocked, Archive]
  archive_state: Archive
  archive_after_days: 30
  state_descriptions:
    Todo: "Triage; route to Explore"
    Explore: "Brief from llm-wiki + PRD + code"
    "In Progress": "TDD loop on Spring Boot module"
    Review: "Read diff, fix CRITICAL/HIGH"
    QA: "Run tests + curl proof against the running app"
    Learn: "Distill learnings, update llm-wiki"
    Done: "As-Is -> To-Be report"
    Archive: "Auto-archived after 30 days idle"

polling:
  interval_ms: 15000

workspace:
  root: ~/symphony_workspaces

# Sequential execution: one ticket at a time, all ticktes share the host project
# directory so cumulative work builds the same Spring Boot project.
hooks:
  after_create: |
    set -euo pipefail
    HOST_REPO="${SYMPHONY_WORKFLOW_DIR:?SYMPHONY_WORKFLOW_DIR not set}"

    # Symlink every host entry into the workspace so the agent operates
    # directly on the host project (sequential mode, no merge conflicts).
    for entry in $(ls -A "$HOST_REPO"); do
      case "$entry" in
        .git) continue ;;
        *) ln -sfn "$HOST_REPO/$entry" "$entry" ;;
      esac
    done

    # Ensure the canonical symphony folders exist on the host side.
    for dir in kanban docs llm-wiki log; do
      mkdir -p "$HOST_REPO/$dir"
    done

  before_run: |
    echo "[symphony] starting ticket at $(date -Iseconds)"

  after_run: |
    echo "[symphony] finished ticket at $(date -Iseconds)"

agent:
  kind: claude          # codex | claude | gemini | pi
  max_concurrent_agents: 1
  max_turns: 40
  max_retry_backoff_ms: 300000
  max_concurrent_agents_by_state:
    Todo: 1
    Explore: 1
    "In Progress": 1
    Review: 1
    QA: 1
    Learn: 1

claude:
  command: claude -p --output-format stream-json --verbose
  resume_across_turns: true
  turn_timeout_ms: 3600000
  read_timeout_ms: 5000
  stall_timeout_ms: 600000

codex:
  command: codex app-server
  approval_policy: never
  thread_sandbox: workspace-write
  turn_sandbox_policy: workspace-write
  turn_timeout_ms: 3600000

gemini:
  command: 'gemini -p ""'
  turn_timeout_ms: 3600000

server:
  port: 9991            # Avoid the 9999 default to dodge collisions with other symphony instances.

tui:
  language: ko
---
You are picking up ticket {{ issue.identifier }}: {{ issue.title }}.
Current state: {{ issue.state }}.
{% if attempt %}This is retry attempt {{ attempt }}. Read the previous `## Resolution`,
`## Blocker`, or `## QA Failure` section before acting and address the root cause,
not the symptom.{% endif %}

{% if issue.description %}
## Ticket Description

{{ issue.description }}
{% endif %}

{% if issue.labels %}Labels: {{ issue.labels | join: ", " }}{% endif %}

{% if issue.blocked_by %}
This ticket depends on:
{% for blocker in issue.blocked_by %}- {{ blocker.identifier }} ({{ blocker.state }})
{% endfor %}
{% endif %}

## Project Mission

We are building **Olive Young-style health & beauty commerce backend**, a
modular monolith Spring Boot 3.x service in Java 21. The full design lives at:

- `Oliveyoung Like Commerce Backend Design.pdf` — the source of truth.
- `llm-wiki/` — distilled per-domain notes (read this first in Explore).

Tech stack (locked):
- Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA, QueryDSL, Gradle (Kotlin DSL).
- PostgreSQL (Flyway migrations), Redis, OpenSearch, S3-compatible (LocalStack ok).
- Spring ApplicationEvents + Outbox pattern; Kafka introduced only when scaled out.
- Testing: JUnit 5, Spring Boot Test, Testcontainers (Postgres + Redis + OpenSearch), MockMvc, REST Assured, k6 for load.

Module layout (modular monolith — single Gradle root, sub-packages per domain):

```
commerce-backend
├── member
├── product
├── search
├── cart
├── order
├── payment
├── inventory
├── promotion
├── delivery
├── review
├── admin
└── common
```

## Production pipeline (seven stages, no skipping)

```
  Todo  ->  Explore  ->  In Progress  ->  Review  ->  QA  ->  Learn  ->  Done
                              \                       \                    ^
                               +-> Blocked             +-> Blocked          |
                                                                            |
                              (QA failure rewinds to In Progress)
```

`docs/{{ issue.identifier }}/` is this ticket's evidence root — every artefact
this ticket produces lives under `docs/{{ issue.identifier }}/<stage>/`.
Learn writes back to `llm-wiki/<topic>.md`, the only artefact outside that root.

The ticket file lives at `kanban/{{ issue.identifier }}.md`. Edit the YAML
front matter `state:` field to transition; append narrative sections to the
body. Symphony reconciles on the next poll tick.

## Audience & writing style

The kanban is read by **non-developers as well as developers** (PMs / 기획자
포함). Every section you append must let a non-dev grasp "what changed, why,
and how" in ~30 seconds.

**Plain-Korean header (mandatory, first lines of every section except the
one-line Triage):**

```
**무엇**: <한 줄, 비-개발자도 이해 가능한 한국어>
**왜**: <한 줄, 사용자/시스템에 어떤 가치/위험이 있는지>
**As-Is → To-Be**:
- As-Is: <한 줄, 이 단계 시작 전 상태>
- To-Be: <한 줄, 이 단계 종료 후 상태>
```

After the header, write the stage-specific technical body — but obey the
length caps. Push everything that would push you over the cap into
`docs/{{ issue.identifier }}/<stage>/details.md` and add a link line at
the end: `_세부: docs/<id>/<stage>/details.md_`.

| Section                 | Body cap (after header)               | Goes in details.md instead          |
|-------------------------|---------------------------------------|--------------------------------------|
| `## Triage`             | 1-2 lines (no header needed)          | n/a                                  |
| `## Domain Brief`       | ≤ 12 lines                            | extra path:line citations, vendor docs |
| `## Plan Candidates`    | ≤ 8 lines (1-2 per option)            | per-option diff sketches             |
| `## Recommendation`     | ≤ 5 lines                             | first-failing-test full text         |
| `## Implementation`     | ≤ 10 lines                            | per-file change list                 |
| `## Review`             | ≤ 6 rows in severity table            | full check-list reasoning            |
| `## QA Evidence`        | header + commands + 1-line `**판정**` + AC table | raw test/curl output       |
| `## Learnings`          | ≤ 8 lines (3-4 bullets)               | extended rationale                   |
| `## Wiki Updates`       | ≤ 4 lines                             | n/a                                  |
| As-Is → To-Be Report    | ≤ 20 lines across all 4 sub-sections  | full evidence dump under docs/       |

## Stage rules

### TRIAGE  -- when state is `Todo`

1. Read the ticket end-to-end. Confirm there is enough information
   (description, acceptance criteria, blocking links) to start exploring.
2. Verify all `blocked_by` tickets are `Done`. If any are not, set this
   ticket back to `Todo` and stop — Symphony will retry once the blockers
   close.
3. If under-specified, append a `## Triage` listing the missing inputs and
   set state to `Blocked`.
4. Otherwise append a one-line `## Triage` ("ticket is actionable; routing
   to Explore") and set state to `Explore`.

### EXPLORE  -- when state is `Explore`

You are walking three lenses in one turn: **domain expert** (what does this
code/PRD section mean?), **implementer** (smallest sustainable change?),
**risk reviewer** (what could go wrong?).

1. Open `llm-wiki/INDEX.md` and read every entry whose topic plausibly
   relates to the ticket. Follow links into the entry files.
2. Open the source PRD at `./Oliveyoung Like Commerce Backend Design.pdf`
   if any ticket-relevant section is unclear from the wiki.
3. Skim git history for prior work in adjacent areas (`git log --oneline`).
4. Read the actual source files end-to-end before brief.
5. Drop boost material into `docs/{{ issue.identifier }}/explore/`.
6. Append three sections to the ticket: `## Domain Brief`, `## Plan
   Candidates` (2-3 options), `## Recommendation` (the option, the
   rationale, the first failing test).
7. Set state to `In Progress`.

### IMPLEMENT  -- when state is `In Progress`

1. Read `## Recommendation` first.
2. TDD loop: write the failing test the brief specified, make it pass,
   refactor. No production code without a test exercising it.
3. Use Spring Boot 3.x + Java 21 idioms: records for DTOs where appropriate,
   sealed interfaces for bounded enums, virtual threads where I/O is heavy,
   constructor injection only.
4. Migrations: every schema change goes through Flyway under
   `src/main/resources/db/migration/`. Never alter an applied migration —
   add a new one.
5. Pair the change with user-facing documentation under
   `docs/{{ issue.identifier }}/work/feature.md`.
6. Append `## Implementation` to the ticket: touched files + commit-style
   intent of each change. Set state to `Review`.

### REVIEW  -- when state is `Review`

1. Read your own diff (`git diff`, `git status`). Re-read touched files
   end-to-end.
2. Apply the checklist: clarity, naming, error handling, security,
   performance, simplicity, no dead code, no debug prints, no secrets.
3. For API changes, save baseline + new HTTP responses under
   `docs/{{ issue.identifier }}/verify/`.
4. Fix every CRITICAL and HIGH. Record findings under `## Review`
   (`severity | file:line | fix`).
5. If genuinely out of scope: set state `Blocked` + `## Blocker` section.
6. Otherwise set state to `QA`.

### QA  -- when state is `QA`  (THIS STAGE MUST EXECUTE REAL CODE)

A QA pass that only inspects code is a failed QA.

1. Run the matching real-world check:
   - Tests: `./gradlew test` (and `integrationTest` once Testcontainers is wired).
   - HTTP API: spin the app via Testcontainers profile; capture As-Is +
     To-Be responses with `curl` / httpie. Save under
     `docs/{{ issue.identifier }}/qa/`.
   - Schema: `./gradlew flywayInfo` against a Testcontainers Postgres.
   - Concurrency-sensitive code: write a parallel smoke (k6 or
     `CompletableFuture.allOf` + assertion) and capture the output.
2. Append `## QA Evidence` with the exact commands, exit codes, 3-10
   lines of output, and links to artefacts.
3. If anything fails: rewind to `In Progress`, append `## QA Failure`,
   stop. Do NOT silence, retry, or skip.
4. If all green: set state to `Learn`.

### LEARN  -- when state is `Learn`

1. Compare the Explore brief against reality. What assumption was wrong?
   What invariant only became visible during implementation?
2. For each non-trivial finding, update `llm-wiki/`: edit existing entry
   (append to **Decision log**) or create new `llm-wiki/<topic-slug>.md`
   with the standard shape (Summary, Invariants, Files of interest,
   Decision log, Last updated).
3. Refresh the corresponding row in `llm-wiki/INDEX.md`.
4. Append `## Learnings` and `## Wiki Updates` to the ticket.
5. Set state to `Done`.

### DONE  -- when state is `Done`

Terminal. Append `## As-Is -> To-Be Report` with this exact structure:

```
## As-Is -> To-Be Report

### As-Is
- <prior behaviour, with evidence: response payload, log line, screenshot path>

### To-Be
- <new behaviour, with the matching piece of evidence>

### Reasoning
- Why this approach over the alternatives considered.
- Trade-offs accepted (performance, complexity, scope).
- Follow-ups intentionally deferred (with ticket / file references).

### Evidence
- Commands run during QA, with exit codes.
- `docs/{{ issue.identifier }}/explore/` — exploration boost notes.
- `docs/{{ issue.identifier }}/work/` — user-facing feature docs.
- `docs/{{ issue.identifier }}/verify/` — review HTTP baseline/PR artefacts.
- `docs/{{ issue.identifier }}/qa/` — QA durable specs, traces, logs.
```

Leave state as `Done` and stop.

## Hard rules

- Never skip a stage. Never mark `Done` without `## QA Evidence`.
- Never silence failing tests, hide errors, or add fake success paths. Fix
  the root cause or move to `Blocked`.
- Touch only what the ticket requires. No drive-by refactors.
- Every artefact lives under `docs/{{ issue.identifier }}/<stage>/`. The
  llm-wiki write-back in Learn is the only exception.
- Migrations are append-only — never edit a Flyway file that has been
  applied (which means: any file already committed to main).
- Money is `BigDecimal` (DECIMAL(12,2) per PRD §7), never `double`.
- Prices, product names, option names are **copied** into `order_items`
  at order creation — orders must remain reproducible after the source
  product changes (PRD §20.2).
- Inventory is per `product_option_id`, never per product (PRD §20.3).
- Payment confirm and PG webhooks must be idempotent on
  `(orderNo, paymentKey, idempotencyKey)` (PRD §20.4).
- Inventory uses **reserve-then-commit** with Redis distributed lock,
  with DB row lock fallback when Redis is unavailable (PRD §20.5, §15.4).
