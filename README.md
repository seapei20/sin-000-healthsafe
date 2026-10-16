# HealthSafe

## Overview

Hospital ward status and emergency staffing schedules.

Domain entities: wards, wings, specialist departments.

Every class in this repo lives in a single flat package, `co.wethinkcode.healthsafe`. HealthSafe is built
as a small set of independent services, following a growth path from simple data
cleanup through synchronous REST calls to asynchronous MQ decoupling and alerting:

1. clean a messy legacy CSV export (`wards-outdated.csv`) — handled by **IngestionServiceApp**
2. serve it up and act on it, via three REST services calling each other directly
   over HTTP
3. decouple the relevant services with an ActiveMQ topic (`staffing-events-topic`) instead of
   direct calls — shared broker setup lives in [`common/`](common)
4. raise the alarm on failure — handled by **EquipmentAlertServiceApp**

| Service | Folder | Port | Role |
|---|---|---|---|
| IngestionServiceApp | [`ingestion-service/`](ingestion-service) | 7030 | Parses and cleans `wards-outdated.csv` |
| WardServiceApp | [`ward-service/`](ward-service) | 7031 | Provides lists of wards and departments. |
| AlertLevelServiceApp | [`alert-level-service/`](alert-level-service) | 7032 | Tracks the hospital Emergency Status (0-8, 8 = full Code Blue). |
| StaffingServiceApp | [`staffing-service/`](staffing-service) | 7033 | Provides on-call schedules for doctors based on ward and status. |
| EquipmentAlertServiceApp | [`equipment-alert-service/`](equipment-alert-service) | 7034 | uses a Queue to guarantee delivery of critical medical equipment failure alerts. |

Plus [`common/`](common) (no port) — the shared ActiveMQ broker and MQ config notes
for `staffing-events-topic`: Staffing updates are broadcast as Events via the broker to decouple the frontend from the Staffing Service.

**Status:** all four stages implemented — stages 1–2 (the required core) plus both
stretch stages 3–4. Each module builds and runs independently; 32 unit tests cover
the cleaning rules, the status scale, the rota maths and id canonicalisation.

## What's implemented

### Stage 1 — Ingestion (`ingestion-service`, port 7030)

`WardCleaner` reads `wards-outdated.csv` once at startup and normalises it:

- ids/`Wing`/`department` casing and padding (`w-05,east wing ,PAEDIATRICS` → `W-05`, `East Wing`, `Paediatrics`, including `Pediatrics` → `Paediatrics`)
- dates in `YYYY-MM-DD`, `MM/DD/YYYY` and `DD-MM-YYYY` → ISO-8601; impossible dates rejected (see the date-reading assumptions in [ingestion-service/README.md](ingestion-service/README.md))
- placeholders (`N/A`, `TBD`, `-`, `unknown`, blank) → `null`
- non-numeric / negative / unrealistic bed counts → `null` + a note, never a crash
- `Y/N`, `yes/no`, `1/0`, `true/FALSE` → real booleans
- duplicate ids written two ways (`W-05` / `w-05`) merged first-wins, with the conflicts recorded in `notes`
- rows that cannot be identified are rejected with a reason and reported, not served and not fatal

Endpoints: `GET /wards`, `GET /wards/{id}` (404), `GET /departments`, `GET /report`.

### Stage 2 — REST services

- **ward-service** (7031) copies the cleaned wards from ingestion-service in the background (so it still boots, and answers 503 with a reason, while ingestion is down).
- **alert-level-service** (7032) tracks Emergency Status 0–8; `PUT /alert-level` rejects anything outside the range or non-integer with 400 rather than clamping.
- **staffing-service** (7033) computes an on-call rota from ward + current status, calling ward-service and alert-level-service synchronously and mapping every downstream failure to an honest 404/502/503.

### Stage 3 — MQ decoupling (stretch)

staffing-service broadcasts every computed schedule to `staffing-events-topic`;
ward-service subscribes and serves `GET /wards/{id}/staffing` from that cache
(`"source": "mq"`). A synchronous call to staffing-service remains only as the
cold-cache fallback (`"source": "rest"`), so the response labels which path was
taken. `GET /staffing-events` shows everything received.

### Stage 4 — Alerting (stretch)

`POST /wards/{id}/equipment-failures` publishes a persistent message to
`equipment-failure-queue` (202 when queued, **503 when the broker is down** — an
alert is never silently dropped); equipment-alert-service consumes it with client
acknowledgement and redelivery, and exposes the result at `GET /alerts`.

| Endpoint | Service | Notes |
|---|---|---|
| `GET /wards` · `/wards/{id}` · `/departments` · `/report` | ingestion 7030 | cleaned data + what was fixed/rejected |
| `GET /wards` · `/wards/{id}` · `/departments` · `POST /wards/refresh` | ward 7031 | 503 until ingestion data arrives |
| `GET /wards/{id}/staffing` · `POST .../staffing/refresh` · `GET /staffing-events` | ward 7031 | topic cache first, REST fallback |
| `POST /wards/{id}/equipment-failures` | ward 7031 | → `equipment-failure-queue` |
| `GET`/`PUT`/`POST /alert-level` · `GET /alert-level/history` | alert-level 7032 | validated 0–8 |
| `GET /staffing?wardId=W-01` · `GET /staffing/{wardId}` | staffing 7033 | calls ward + alert-level over HTTP |
| `GET /alerts` · `GET /alerts/count` | equipment-alert 7034 | consumed with guaranteed delivery |

## Your task

Each stage below builds on the last — do them in order. Every service already builds
and runs (`/health` returns `OK`). *This brief is kept as the record of the
exercise; all four stages below are implemented — see
[What's implemented](#whats-implemented) for what ended up in the code.*

1. **Ingestion** (required) — in `IngestionServiceApp`, read and clean
   `wards-outdated.csv` (see [ingestion-service/README.md](ingestion-service/README.md)
   for the known data issues and a worked example) and expose the cleaned records
   over REST for `ward-service` to consume.
2. **REST services** (required) — implement `ward-service`, `alert-level-service`,
   and `staffing-service` per the [Integration contracts](#integration-contracts)
   below: wards/departments lookup, Emergency Status tracking, and on-call
   scheduling that calls the other two services synchronously over HTTP.
3. **MQ decoupling** (stretch) — replace the synchronous call from `ward-service`
   to `staffing-service` with the `staffing-events-topic` broadcast described in
   [common/README.md](common/README.md), so staffing updates reach `ward-service`
   asynchronously instead.
4. **Alerting** (stretch) — have `ward-service` publish to the
   `equipment-failure-queue` when it detects an equipment failure, and implement
   `equipment-alert-service` as the guaranteed-delivery consumer (see
   [equipment-alert-service/README.md](equipment-alert-service/README.md)).

Stage 1-2 are the required core; stages 3-4 are where you can show judgment about
when to reach for a queue/topic instead of a direct call. There's no fixed time
limit, but budget your effort so you have a working stage 1-2 before spending time
on 3-4 — a complete core beats a half-done everything.

Automated tests aren't required, but are a good way to show your work — each
service's `## Test` section lists what is covered and how to run it.

## Integration contracts

Endpoint shapes below are illustrative, not a fixed spec to match byte-for-byte —
reasonable field names/status codes are fine as long as the calling service can
consume them.

| From | To | Call | Purpose |
|---|---|---|---|
| `ward-service` | `ingestion-service` | `GET /wards` → cleaned ward records | Populate its own ward/department list |
| `staffing-service` | `ward-service` | `GET /wards/{id}` → `404` if unknown | Validate the ward before scheduling |
| `staffing-service` | `alert-level-service` | `GET /alert-level` → `{ "level": 0-8 }` | Read current Emergency Status to size the on-call schedule |
| `staffing-service` | `ward-service` (topic, stage 3) | publish to `staffing-events-topic` | Broadcast a schedule/status change |
| `ward-service` (topic, stage 3) | — | subscribe to `staffing-events-topic` | React to staffing updates without polling |
| `ward-service` (queue, stage 4) | `equipment-alert-service` | publish to `equipment-failure-queue` | Guarantee delivery of an equipment failure alert |

## Project structure

```
healthsafe/
├── README.md
├── pom.xml                     (aggregator: builds all modules)
├── .gitignore
├── ingestion-service/          (port 7030)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/
│       ├── java/co/wethinkcode/healthsafe/IngestionServiceApp.java
│       └── resources/wards-outdated.csv
├── ward-service/          (port 7031)
├── alert-level-service/          (port 7032)
├── staffing-service/          (port 7033)
├── common/
│   ├── docker-compose.yml
│   └── README.md
└── equipment-alert-service/          (port 7034)
```

## Build

Requirements: Java 17+, Maven 3.8+, Docker (for the broker in `common/`).

Every folder here (`ingestion-service/`, each domain service, and `equipment-alert-service/`) is
an **independent** Maven project. A root aggregator `pom.xml` simply lists them as
modules so the whole repo can be built in one pass from the project root (the
services themselves have no parent and remain independently buildable). Build one
at a time, e.g.:

```
cd ward-service
mvn package
```

...or build every module in the repo in one pass from the project root:

```
mvn -q package
```

## Run

```
# ingestion
cd ingestion-service && mvn package && java -jar target/ingestion-service.jar

# domain services, each in its own terminal
# terminal 1
cd ward-service && mvn package && java -jar target/ward-service.jar
# terminal 2
cd alert-level-service && mvn package && java -jar target/alert-level-service.jar
# terminal 3
cd staffing-service && mvn package && java -jar target/staffing-service.jar

# MQ broker (needed once the MQ-aware services above are wired up)
cd common && docker compose up -d

# alerting
cd equipment-alert-service && mvn package && java -jar target/equipment-alert-service.jar
```

| Service | Port |
|---|---|
| IngestionServiceApp (`ingestion-service`) | 7030 |
| WardServiceApp (`ward-service`) | 7031 |
| AlertLevelServiceApp (`alert-level-service`) | 7032 |
| StaffingServiceApp (`staffing-service`) | 7033 |
| EquipmentAlertServiceApp (`equipment-alert-service`) | 7034 |

## Test

Each running service exposes `/health`, so sanity-check manually:

```
curl http://localhost:7030/health   # -> OK
```

Unit tests (JUnit 5 + Surefire) exist for the parts that are pure functions —
`WardCleanerTest` (18 tests, the whole cleaning ruleset plus malformed-row
robustness), `AlertLevelServiceTest`, `ScheduleCalculatorTest`,
`WardStoreTest` — 32 in total:

```
mvn -q test
```

The service-to-service and broker behaviour is deliberately left to a running
stack; check it with the calls in [What's implemented](#whats-implemented) after
bringing up the five services and `common/docker-compose.yml`.
