# StaffingServiceApp

## Overview

Provides on-call schedules for doctors based on ward and status.

Part of the [HealthSafe](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ topic `staffing-events-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.healthsafe.mq.MqConfig` class alongside it in this module.

REST: calls `ward-service` (`../ward-service`) to validate the ward and
`alert-level-service` (`../alert-level-service`) to read the current Emergency
Status before computing a schedule — see [Integration contracts](../README.md#integration-contracts)
in the root README for the endpoint shapes.

## Project structure

```
staffing-service/
├── pom.xml
└── src/main/java/co/wethinkcode/healthsafe/
    ├── StaffingServiceApp.java     (routes + the two synchronous calls)
    ├── ScheduleCalculator.java     (pure ward + status -> rota logic)
    ├── StaffingEventPublisher.java (topic producer)
    ├── HttpJson.java               (service-to-service HTTP helper)
    └── mq/
        └── MqConfig.java
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness (`OK`) |
| GET | `/staffing?wardId=W-01` | on-call rota for that ward at the current Emergency Status |
| GET | `/staffing/{wardId}` | same, path form |

Each request validates the ward against ward-service, reads the level from
alert-level-service, computes the rota, then broadcasts it on
`staffing-events-topic`. Downstream failures are mapped rather than assumed:

| Situation | Status |
|---|---|
| unknown ward | 404 |
| ward-service / alert-level-service unreachable | 503 with the reason |
| a dependency is up but has no data yet / cannot reach *its* dependencies | 503 |
| a dependency returned something unusable | 502 |
| broker down | still **200** — the schedule is served, `eventPublished: false` explains the missing broadcast |

## Build

```
mvn package
```

## Run

```
java -jar target/staffing-service.jar
```

Listens on port `7033`.

## Test

8 JUnit tests in `src/test/java/co/wethinkcode/healthsafe/ScheduleCalculatorTest.java`
cover head count vs status, that every shift is covered at any level, that
shortfalls are flagged, determinism, and the escalation messages:

```
mvn test
```

Manually verify (alert-level and ward-service must be up):

```
curl http://localhost:7033/health                          # -> OK
curl 'http://localhost:7033/staffing?wardId=W-01'          # -> rota, eventPublished: true
curl 'http://localhost:7033/staffing?wardId=W-99'          # -> 404
curl 'http://localhost:7033/staffing'                      # -> 400 missing wardId
```
