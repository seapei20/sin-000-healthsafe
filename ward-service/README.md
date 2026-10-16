# WardServiceApp

## Overview

Provides lists of wards and departments.

Part of the [HealthSafe](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `staffing-events-topic` — see [`../common/`](../common) — and publishes to the ActiveMQ queue `equipment-failure-queue` when it detects an equipment failure on one of its wards, consumed by [`../equipment-alert-service`](../equipment-alert-service). Broker URL, topic name, and queue name come from the common `co.wethinkcode.healthsafe.mq.MqConfig` class alongside it in this module.

REST: called by `staffing-service` (`../staffing-service`) and `alert-level-service` (`../alert-level-service`) — see [Integration contracts](../README.md#integration-contracts) in the root README for the endpoint shapes.

## Project structure

```
ward-service/
├── pom.xml
└── src/main/java/co/wethinkcode/healthsafe/
    ├── WardServiceApp.java            (routes + wiring)
    ├── WardStore.java                 (in-memory copy of the cleaned wards)
    ├── StaffingCache.java             (latest staffing event per ward)
    ├── StaffingEventsSubscriber.java  (topic consumer -> StaffingCache)
    ├── EquipmentFailurePublisher.java (queue producer)
    ├── HttpJson.java                  (service-to-service HTTP helper)
    └── mq/
        └── MqConfig.java
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness (`OK`) |
| GET | `/wards` | wards copied from ingestion-service (503 until loaded) |
| GET | `/wards/{id}` | one ward, case/format tolerant; 404 if unknown, 503 if not loaded |
| GET | `/departments` | distinct departments |
| POST | `/wards/refresh` | force a re-read from ingestion-service |
| GET | `/wards/{id}/staffing` | on-call rota: topic cache first (`source: mq`), synchronous staffing-service fallback (`source: rest`) |
| POST | `/wards/{id}/staffing/refresh` | force the synchronous path |
| GET | `/staffing-events` | everything received on `staffing-events-topic` |
| POST | `/wards/{id}/equipment-failures` | publish to `equipment-failure-queue` — 202 queued, 503 if the queue could not take it, 400/404 for bad input |

The ward copy is loaded in the background rather than at boot, so this service
stays independently runnable: with ingestion-service down, `/health` still answers
and the ward endpoints explain why they cannot (503) instead of refusing to start.
Likewise the topic subscription retries rather than failing the process, so a
missing broker costs you the broadcast but not the REST traffic.

## Build

```
mvn package
```

## Run

```
java -jar target/ward-service.jar
```

Listens on port `7031`.

## Test

3 JUnit tests in `src/test/java/co/wethinkcode/healthsafe/WardStoreTest.java`
cover ward-id canonicalisation and the not-loaded-yet state:

```
mvn test
```

Manually verify the wiring end to end:

```
curl http://localhost:7031/health                        # -> OK
curl http://localhost:7031/wards                         # -> wards from ingestion-service
curl http://localhost:7031/wards/W-01/staffing           # -> "source": "mq" (or "rest" on a cold cache)
curl -X POST -H 'Content-Type: application/json' \
     -d '{"equipment":"Ventilator","fault":"E-42"}' \
     http://localhost:7031/wards/W-01/equipment-failures # -> 202 queued
```
