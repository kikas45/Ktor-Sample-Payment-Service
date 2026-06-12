# Payment System

**Author:** Pius Powella David

A backend service for payment initiation and settlement, built with Kotlin and Ktor.

---

## What this does

It handles three things: onboarding merchants, processing payments on their behalf, and settling those payments into batches. Think of it as the core engine behind something like Paystack — minus the card processing and the frontend.

When a transaction comes in, the system records it, deducts a fee (1.5% of the amount, capped at ₦200), and marks it as successful. Settlement then groups all the successful unsettled transactions for a merchant into a batch and tells you how much they're owed after fees.

---

## Stack

- **Kotlin + Ktor** — lightweight, no magic, easy to reason about
- **PostgreSQL** — relational DB, fits the data model well
- **HikariCP** — connection pooling
- **Flyway** — handles DB migrations on startup automatically
- **Raw JDBC** — no ORM, just plain SQL with prepared statements as required

---

## Setup

### Prerequisites

- JDK 21
- PostgreSQL (any method — see below)
- An IDE that supports Kotlin: Android Studio, IntelliJ IDEA, or any other Kotlin-compatible platform. Just open the project and it will pick up the Gradle setup automatically. No need to install Gradle separately — the wrapper (`gradlew`) is included.

### 1. Database

You need a running PostgreSQL instance with a database called `payment_db`. Use whichever approach is most convenient — the project supports all of them:

**Option A — pgAdmin** (what I used during development): open pgAdmin, connect to your server, and create a new database named `payment_db`. That's it.

**Option B — Docker**: if you have Docker installed, just run:

```bash
docker-compose up -d
```

The `docker-compose.yml` in the root will spin up a Postgres 16 container on port 5432 with `payment_db` already configured — no manual setup needed.

**Option C — psql command line**:

```sql
CREATE DATABASE payment_db;
```

Whichever option you pick, once the database exists the server handles the rest. Flyway automatically creates all the tables on first startup — you don't need to run any SQL manually.

### 2. Environment variables

Create a `.env` file in the project root (there is a `.env.example` file already there — just copy it and fill in your values):

```
DB_URL=jdbc:postgresql://localhost:5432/payment_db
DB_USER=postgres
DB_PASSWORD=yourpassword
ADMIN_USER=admin
ADMIN_PASSWORD=changeme
MERCHANT_API_TOKEN=merchant-secret-token
```

The project reads this file automatically on startup via the Gradle build config — you don't need to set anything manually in your terminal.

### 3. Run

**Option A — simple one-liner (recommended):**

```bash
./gradlew :server:run
```

**On Windows:**
```powershell
.\gradlew.bat :server:run
```

That's it. As long as the `.env` file exists in the project root, the server picks up all credentials automatically.

**Option B — inline environment variables (if you prefer not to use a `.env` file):**

Windows PowerShell:
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/payment_db"; $env:DB_USER="postgres"; $env:DB_PASSWORD="yourpassword"; $env:ADMIN_USER="admin"; $env:ADMIN_PASSWORD="changeme"; $env:MERCHANT_API_TOKEN="merchant-secret-token"; .\gradlew.bat :server:run
```

Mac/Linux:
```bash
DB_URL=jdbc:postgresql://localhost:5432/payment_db \
DB_USER=postgres \
DB_PASSWORD=yourpassword \
ADMIN_USER=admin \
ADMIN_PASSWORD=changeme \
MERCHANT_API_TOKEN=merchant-secret-token \
./gradlew :server:run
```

The server starts on `http://localhost:8080`. Flyway runs the migration automatically on startup — no need to manually create any tables.

### 4. API Docs

Open `http://localhost:8080/openapi` in your browser for the full Swagger UI.

---

## API Overview

| Method | Endpoint | Auth | What it does |
|--------|----------|------|--------------|
| POST | `/merchants` | Basic (admin) | Create a merchant |
| GET | `/merchants` | Bearer token | List all merchants |
| GET | `/merchants/{id}` | Bearer token | Get a single merchant |
| POST | `/transactions` | Bearer token | Initiate a transaction |
| GET | `/merchants/{id}/transactions` | Bearer token | List transactions (filterable) |
| POST | `/merchants/{id}/settle` | Bearer token | Settle unsettled transactions |
| GET | `/health` | None | Health check |

### Filtering transactions

```
GET /merchants/1/transactions?status=SUCCESS&dateFrom=2026-01-01&dateTo=2026-12-31
```

All three query params are optional — use any combination.

### Auth

- **Creating a merchant** uses HTTP Basic Auth (`admin` / `changeme` by default). The idea is that only an internal admin should be able to onboard merchants.
- **Everything else** uses a Bearer token in the `Authorization` header (`merchant-secret-token` by default).

Both credentials are controlled via environment variables so they're easy to rotate without touching code.

---

## Architecture

The code is split into three layers and they only talk to each other in one direction:

```
HTTP Routes (plugins/)
      ↓
  PaymentService (service/)
      ↓
  Repositories (service/)
      ↓
  PostgreSQL
```

**Routes** just parse the request and hand off to the service. No business logic lives here.

**PaymentService** owns all the business rules — fee calculation, idempotency checks, merchant validation before a transaction is recorded. If something doesn't make sense (zero amount, merchant doesn't exist, blank ref), it throws here before anything touches the database.

**Repositories** do one thing: talk to the database. Each one owns a single table and uses raw JDBC prepared statements. No query builders, no ORM — just SQL.

This separation means the service can be unit tested without a real database (which the tests do — they mock the repositories with MockK).

---

## Fee calculation

```
fee = amount * 0.015
if fee > 200 then fee = 200
```

So a ₦5,000 transaction costs ₦75 in fees. A ₦50,000 transaction costs ₦200 (capped). The net amount the merchant receives is `totalAmount - feeDeducted`, which is returned in the settlement batch summary.

---

## Bonus features implemented

**Idempotency** — pass an `idempotencyKey` in the transaction request body. If you send the same key twice (e.g. network retry), the second request returns the original transaction without creating a duplicate. The key is stored with a UNIQUE constraint in the DB so even concurrent duplicates are handled safely.

**Auth** — two schemes as described above. Basic auth for admin operations, bearer token for merchant operations. Credentials come from environment variables.

**Unit tests** — `PaymentServiceTest` covers merchant creation, fee calculation (including the cap), idempotency replay, and settlement. Run with:

```powershell
.\gradlew.bat :server:test
```

---

## Assumptions

- There's no actual card network or bank involved. The "debit from customer" step is simulated — the transaction goes straight from INITIATED to SUCCESS. In a real system this would wait for a webhook from a payment processor.
- Currency defaults to NGN but can be passed in the request. The fee cap of ₦200 is applied regardless of currency — a real system would need per-currency fee tables.
- Settlement can only be triggered manually via the API. Automatic scheduled settlement (e.g. daily at midnight) is not implemented but would be a natural next step.
- There's no merchant authentication — any caller with the bearer token can act on any merchant. In production each merchant would have their own key.
- The admin credentials and merchant token are static environment variables. A real system would use a proper secrets manager and JWT with expiry.