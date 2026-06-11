# SwiftPay — Real-Time Payment Ledger

A resilient, event-driven P2P payment platform built with Java 21, Spring Boot, PostgreSQL, Apache Kafka, and Redis.

---

## Architecture

```
Client
  │
  ▼
┌─────────────────────────────┐
│  Service A                  │  :8080
│  Transaction Gateway        │  REST API · Idempotency · Balance check
└────────┬──────────┬─────────┘
         │          │
    Postgres    Kafka: payment.initiated
  (transactions_db)  │
                     ▼
         ┌───────────────────────────┐
         │  Service B                │  :8081
         │  Ledger Service           │  Consumer · Atomic debit/credit
         └──────────┬───────────┬────┘
                    │           │
               Postgres     Kafka: payment.completed / payment.failed
             (ledger_db)        │
                                ▼
                   ┌────────────────────────┐
                   │  Service C             │  :8082
                   │  Analytics Worker      │  OLAP consumer → analytics_db
                   └────────────────────────┘
```

---

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21 (for local dev)
- K6 (for load testing)

### Spin up the full ecosystem

```bash
git clone https://github.com/your-org/swiftpay.git
cd swiftpay

docker compose up --build
```

Services will be available at:

| Service              | URL                                    |
|----------------------|----------------------------------------|
| Transaction Gateway  | http://localhost:8080                  |
| Swagger UI (Gateway) | http://localhost:8080/swagger-ui.html  |
| Ledger Service       | http://localhost:8081                  |
| Swagger UI (Ledger)  | http://localhost:8081/swagger-ui.html  |
| Analytics Worker     | http://localhost:8082                  |

---

## API Usage

### Initiate a Payment

```bash
curl -X POST http://localhost:8080/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "X-Request-ID: req-001" \
  -d '{
    "sender_id":   "a0000000-0000-0000-0000-000000000001",
    "receiver_id": "a0000000-0000-0000-0000-000000000002",
    "amount":      "100.00",
    "currency":    "USD"
  }'
```

**Response (202 Accepted):**
```json
{
  "payment_id":       "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "sender_id":        "a0000000-0000-0000-0000-000000000001",
  "receiver_id":      "a0000000-0000-0000-0000-000000000002",
  "amount":           100.00,
  "currency":         "USD",
  "status":           "PENDING",
  "idempotency_key":  "your-idempotency-key",
  "created_at":       "2024-06-11T10:30:00Z"
}
```

### Get Payment Status

```bash
curl http://localhost:8080/v1/payments/{payment_id}
```

### Get Payment History (Gateway)

```bash
curl http://localhost:8080/v1/payments/history/{user_id}
```

### Get Ledger History (Service B)

```bash
curl "http://localhost:8081/v1/ledger/{user_id}/history?page=0&size=20"
```

### Get Ledger Entries for a Payment

```bash
curl http://localhost:8081/v1/ledger/payment/{payment_id}
```

---

## Seeded Test Accounts

| ID                                     | Owner | Balance   |
|----------------------------------------|-------|-----------|
| `a0000000-0000-0000-0000-000000000001` | Alice | $10,000   |
| `a0000000-0000-0000-0000-000000000002` | Bob   | $5,000    |
| `a0000000-0000-0000-0000-000000000003` | Carol | $2,500    |

---

## Error Responses

All errors follow a standard envelope:

```json
{
  "code":       "INSUFFICIENT_FUNDS",
  "message":    "Sender a0000000-... has insufficient funds (available: 50.00, requested: 100.00)",
  "request_id": "req-001",
  "timestamp":  "2024-06-11T10:30:00Z"
}
```

| HTTP Code | Error Code           | Cause                              |
|-----------|----------------------|------------------------------------|
| 400       | `VALIDATION_ERROR`   | Missing/invalid request fields     |
| 404       | `ACCOUNT_NOT_FOUND`  | Sender or receiver doesn't exist   |
| 422       | `INSUFFICIENT_FUNDS` | Sender balance below transfer amount |
| 200       | `DUPLICATE_REQUEST`  | Idempotency key already seen       |
| 500       | `INTERNAL_ERROR`     | Unexpected server error            |

---

## Running Tests

```bash
# Unit tests (no infrastructure needed)
cd transaction-gateway && mvn test
cd ledger-service && mvn test

# Integration tests (spins up Testcontainers)
cd transaction-gateway && mvn verify
cd ledger-service && mvn verify
```

---

## Load Testing

```bash
# Install K6
brew install k6   # macOS
# or: https://k6.io/docs/getting-started/installation/

# With full stack running:
docker compose up -d

# Run 250 TPS for ~1 million transactions
cd load-tests
k6 run --out json=results.json load-test.js

# With PCAP capture (Linux/macOS, requires root):
sudo tcpdump -i lo -w swiftpay-load.pcap port 8080 &
k6 run --out json=results.json load-test.js
sudo pkill tcpdump
```

Expected thresholds:
- p95 latency < 500ms
- p99 latency < 1000ms  
- Success rate > 95%

---

## Design Decisions

### Idempotency
Redis `SET NX EX` provides an atomic, TTL-scoped idempotency check. The key is reserved after the payment row is persisted, ensuring the stored ID is always valid on a cache hit.

### Atomic transfers
Service B uses `SELECT ... FOR UPDATE` with `SERIALIZABLE` isolation. Rows are always locked in ascending UUID order to eliminate deadlocks under concurrent load.

### Consumer-side idempotency
Service B tracks every processed `payment_id` in a `processed_payments` table. Kafka at-least-once delivery means a message may be replayed — this table ensures the debit/credit is applied exactly once.

### Retry and DLT
`@RetryableTopic` gives 3 attempts with exponential backoff (1s → 2s → 4s). Messages that exhaust retries land in `payment.initiated.DLT` and trigger a `FAILED` event so Service A can update the payment status.

### Balance cache
Redis caches balances with a 60-second TTL. A stale read here is acceptable because the final balance enforcement happens inside the Ledger Service's DB transaction. The cache only prevents obviously bad requests from reaching the queue.

### Double-entry ledger
Every transfer produces two `ledger_entries` rows: one DEBIT for the sender and one CREDIT for the receiver. `balance_after` is snapshotted on every row to provide a point-in-time audit trail without recomputing running totals.

---

## Performance Tuning Notes

At 250 TPS the typical bottleneck sequence is:

1. **HikariCP pool exhaustion** — tuned to `maximumPoolSize=20` per service. Monitor with `pg_stat_activity`.
2. **Kafka producer batching** — `linger.ms` + `batch.size` can be tuned for throughput vs latency trade-off.
3. **Redis connection pool** — Lettuce pool set to `max-active=16`.
4. **PostgreSQL `max_connections`** — default 100 may need raising at high concurrency: `ALTER SYSTEM SET max_connections = 300`.

---

## Project Structure

```
swiftpay/
├── transaction-gateway/        # Service A
│   ├── src/main/java/com/swiftpay/
│   │   ├── controller/         # REST endpoints
│   │   ├── service/            # Business logic, idempotency, balance cache
│   │   ├── kafka/              # Producer & result consumer
│   │   ├── repository/         # Spring Data JPA
│   │   ├── model/              # JPA entities
│   │   ├── dto/                # Request/response/event objects
│   │   ├── config/             # Redis, Kafka, OpenAPI beans
│   │   └── exception/          # Custom exceptions & global handler
│   └── src/test/
├── ledger-service/             # Service B
│   ├── src/main/java/com/swiftpay/
│   │   ├── consumer/           # PaymentInitiated Kafka consumer + DLT handler
│   │   ├── service/            # Atomic transfer logic
│   │   ├── kafka/              # Result producer
│   │   ├── controller/         # Ledger history REST endpoint
│   │   └── repository/         # Accounts + ledger entries + processed payments
│   └── src/test/
├── analytics-worker/           # Service C
│   └── src/main/java/com/swiftpay/
│       └── consumer/           # PaymentCompleted OLAP writer
├── load-tests/
│   └── load-test.js            # K6 250 TPS / 1M transaction script
├── .github/workflows/
│   └── ci.yml                  # Build → Test → Docker push pipeline
└── docker-compose.yml          # Full ecosystem (Zookeeper, Kafka, 3× Postgres, Redis, 3 services)
```

---

## CI/CD

GitHub Actions pipeline (`.github/workflows/ci.yml`) runs on every push and PR:

1. **Build & Test** — compiles each service and runs unit + integration tests in parallel
2. **Docker Build & Push** — builds multi-stage images and pushes to GitHub Container Registry (main branch only)
3. **Integration Smoke Test** — spins up `docker compose`, fires a real payment request, asserts `PENDING` status (PRs only)
