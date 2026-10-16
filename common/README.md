# common — Asynchronous Decoupling (MQ)

## Overview

Topic: `staffing-events-topic`

Staffing updates are broadcast as Events via the broker to decouple the frontend from the Staffing Service.

Part of the [HealthSafe](../README.md) project. Holds the ActiveMQ broker shared
by the services below — not a service itself, so it has no port of its own.

- Producer: `staffing-service` (`../staffing-service`)
- Consumer(s): ward-service

Broker URL and topic name are shared via a common `co.wethinkcode.healthsafe.mq.MqConfig` class
(`BROKER_URL`, `TOPIC`). It's identical in every participating service's own source
tree — each service here is an independent Maven project with no shared parent pom,
so the common package is duplicated rather than imported from one place.

## Project structure

```
common/
├── docker-compose.yml
└── README.md
```

This folder holds the broker config and notes only — the actual publish/subscribe
code belongs in the producer/consumer services listed above (their poms already
depend on `activemq-client`, and each already has
`src/main/java/co/wethinkcode/healthsafe/mq/MqConfig.java`).

## Build

Nothing to build here directly — this folder just brings up the broker used by the
services listed above.

## Run

```
docker compose up -d
```

- Broker URL for clients: `tcp://localhost:61616`
- Web console: http://localhost:8161 (default admin/admin)

Then start the producer/consumer services as usual (`mvn package && java -jar ...`
from their own directories at the project root).

## Test

```
docker compose ps          # confirm the broker container is healthy
```

Verify end-to-end by computing a schedule and confirming the subscriber picks it up:

```
curl 'http://localhost:7033/staffing?wardId=W-01'   # publishes to the topic
curl http://localhost:7031/staffing-events          # ward-service received it
curl http://localhost:7031/wards/W-01/staffing      # -> "source": "mq"
```

The web console at http://localhost:8161 shows `staffing-events-topic` and
`equipment-failure-queue` while they are in use.

## Why a topic here, and a queue over there

| | `staffing-events-topic` | `equipment-failure-queue` |
|---|---|---|
| Producer | staffing-service | ward-service |
| Consumers | ward-service (and anything else that wants to watch) | equipment-alert-service, exactly one |
| Semantics | broadcast, fire-and-forget — a schedule is recomputable by asking staffing-service again | guaranteed, at-least-once — a lost failure alert is unacceptable |
| Subscriber | plain (non-durable) subscription | client acknowledgement + redelivery |

Both are wired: `StaffingEventPublisher`/`StaffingEventsSubscriber` on the topic,
`EquipmentFailurePublisher`/`EquipmentFailureConsumer` on the queue.
