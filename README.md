# crm-engine

Distributed, sms parser backend built for 100M TPD. Java 25, Spring Boot 3.4, Kafka, Cassandra, Redis.

## Architecture

```
Write path: Client → REST (8080) → CommandHandler → Kafka → DynamoDB (event store)
Read path:  Client → REST (8081) → QueryController → Redis (materialized views)
Projection: Kafka → ProjectionEngine → Redis
```

- **Event sourcing:** Cassandra stores append-only domain events; current state is always rebuildable
- **CQRS:** Write and read services are independently deployable and scalable
- **Idempotency:** All commands carry an idempotency key; duplicates are silent no-ops

## Running Locally

```bash
docker-compose up -d          # starts Kafka, Cassandra, Redis
mvn spring-boot:run -pl write-service   # port 8080
```

## Tech Stack

Java 25 · Spring Boot 3.4 · Apache Kafka · Apache Cassandra 5 · Redis 7 · Testcontainers · Gatling
