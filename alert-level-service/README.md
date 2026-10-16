# AlertLevelServiceApp

## Overview

Tracks the hospital Emergency Status (0-8, 8 = full Code Blue).

Part of the [HealthSafe](../README.md) project. Independent Maven module, no
parent pom.

REST: called by `staffing-service` (`../staffing-service`) to read the current
status when computing on-call schedules — see [Integration contracts](../README.md#integration-contracts)
in the root README for the endpoint shapes.

## Project structure

```
alert-level-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/healthsafe/AlertLevelServiceApp.java
    └── test/java/co/wethinkcode/healthsafe/AlertLevelServiceTest.java
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness (`OK`) |
| GET | `/alert-level` | `{ "level": 0-8, "name": ..., "updatedAt": ... }` |
| PUT/POST | `/alert-level` | body is `{"level": n}` (or a bare number); **400** for a non-integer or anything outside 0-8 — the level is never silently clamped |
| GET | `/alert-level/history` | last 20 changes, `{from, to, at}` |

## Build

```
mvn package
```

## Run

```
java -jar target/alert-level-service.jar
```

Listens on port `7032`.

## Test

3 JUnit tests in `src/test/java/co/wethinkcode/healthsafe/AlertLevelServiceTest.java`
cover which payloads parse as a level and what every band of the scale is called:

```
mvn test
```

Manually verify:

```
curl http://localhost:7032/health                     # -> OK
curl http://localhost:7032/alert-level                # -> {"level":0,...}
curl -X PUT -d '{"level":5}' localhost:7032/alert-level
curl -X PUT -d '{"level":9}' localhost:7032/alert-level   # -> 400, level stays 5
curl -X PUT -d 'banana' localhost:7032/alert-level        # -> 400
```
