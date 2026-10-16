# EquipmentAlertServiceApp

## Overview

Uses a Queue to guarantee delivery of critical medical equipment failure alerts.

Part of the [HealthSafe](../README.md) project — its alerting service.
Independent Maven module, no parent pom.

Mechanism: ActiveMQ Queue (guaranteed delivery)

Queue: `equipment-failure-queue`

- Producer: `ward-service` (`../ward-service`) — publishes when it detects an
  equipment failure on one of its wards.
- Consumer: this service.

Broker URL and queue name are shared via a common `co.wethinkcode.healthsafe.mq.MqConfig`
class (`BROKER_URL`, `QUEUE`). It's identical in every participating service's own
source tree — each service here is an independent Maven project with no shared
parent pom, so the common package is duplicated rather than imported from one place.

A queue (not a topic) is used deliberately: each failure alert must be delivered to
exactly one consumer and processed at least once, even if this service is briefly
down — unlike the broadcast `staffing-events-topic` in [`../common/`](../common),
where every subscriber gets every message.

## Project structure

```
equipment-alert-service/
├── pom.xml
└── src/main/java/co/wethinkcode/healthsafe/
    ├── EquipmentAlertServiceApp.java (Javalin routes)
    ├── EquipmentFailureConsumer.java (queue consumer with guaranteed delivery)
    ├── HttpJson.java                 (JSON helper)
    └── mq/
        └── MqConfig.java
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness (`OK`) — answers even with no broker running |
| GET | `/alerts` | every alert handled so far, each stamped with `receivedAt` |
| GET | `/alerts/count` | queue name, broker URL, and how many alerts were handled |

## Build

```
mvn package
```

## Run

```
java -jar target/equipment-alert-service.jar
```

Listens on port `7034`.

## Test

No unit tests here — the interesting behaviour is the delivery guarantee, which
needs a real broker. Verify it by taking the consumer *out* of the equation:

```
# 1. stop this service, then publish two alerts from ward-service
#    (both still return 202 — the queue holds them):
curl -X POST -H 'Content-Type: application/json' \
     -d '{"equipment":"Defibrillator","fault":"battery fault"}' \
     http://localhost:7031/wards/W-05/equipment-failures

# 2. restart this service and watch the held alerts arrive:
curl http://localhost:7034/alerts/count
curl http://localhost:7034/alerts
```

With the broker stopped instead, ward-service answers **503 "alert NOT queued"**
rather than pretending an alert was delivered. Also worth watching the log for a
failed handle being redelivered (`session.recover()`), and the ActiveMQ console at
http://localhost:8161 for the queue itself.
