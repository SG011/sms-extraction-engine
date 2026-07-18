# sms-extraction-engine

Distributed, sms parser backend built for 100M TPD. Java 25, Spring Boot 4, Kafka, DynamoDB, Redis.

## Architecture

```
Parsing and getting the data of sms to run marketing campaign!

## Running Locally

```bash
docker-compose up -d          # starts Kafka, DynamoDB, Redis
mvn spring-boot:run -pl write-service   # port 8080
```

## Tech Stack

Java 25 · Spring Boot 4 · Apache Kafka · DynamoDB · Redis 7 · Testcontainers · Gatling
