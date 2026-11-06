# Ledger API

A double-entry ledger: money is held in accounts and moves only through balanced, immutable
transactions. Transactions are recorded through a REST API and from `payment-settled` messages,
and every recorded transaction is announced on Kafka. It runs as several instances and keeps every
balance exact under retries, redeliveries and concurrent requests.

Java 17 · Spring Boot 4.1 · MySQL 8.4 · Kafka 4.1 · Maven

## Running it

You need Docker, Git and a JDK 17. Nothing else: Maven comes through the wrapper.

```bash
./run.sh
```

The script:

1. builds the service and runs all the tests (`./mvnw verify`): unit tests, then integration tests against
   real MySQL and Kafka, started by Testcontainers;
2. starts MySQL, Kafka, **two instances** of the service and an nginx gateway with `docker compose up --wait`.

| What | Where |
|---|---|
| API, load-balanced over both instances | http://localhost:8080 (another port: `LEDGER_PORT=8090 ./run.sh`) |
| Instance 1 / instance 2 directly | http://localhost:8081, http://localhost:8082 |
| Kafka UI: topics, messages, the dead-letter topic, consumer lag | http://localhost:8085 |
| API contract | [`api/openapi.yaml`](api/openapi.yaml) |

Stop everything with `docker compose down -v`. Only the tests: `./mvnw verify` (unit tests only: `./mvnw test`).

MySQL and Kafka aren't published on the host, so they can't clash with local installs. Use
`docker compose exec mysql mysql -uledger -pledger ledger` to look at the database.

## Sample requests

The ledger's own settlement accounts always exist:

| Account | Id |
|---|---|
| Settlement EUR | `00000000-0000-0000-0000-000000000978` |
| Settlement USD | `00000000-0000-0000-0000-000000000840` |
| Settlement JPY | `00000000-0000-0000-0000-000000000392` |

Every `POST` needs an `Idempotency-Key`; a new UUID per request is a good choice.

```bash
API=http://localhost:8080
key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

# Open accounts (overdraft limit defaults to 0)
curl -s -X POST $API/v1/accounts -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' \
  -d '{"currency":"EUR","label":"Merchant 42"}'
# => 201 {"id":"0196...","currency":"EUR","overdraftLimit":"0.00","balance":"0.00","label":"Merchant 42",...}
MERCHANT=<id from the response>      # likewise open PAYOUTS and FEES in EUR

# Example 1: a payment of 125.50 EUR settles for Merchant 42 (here through the API; see below for the message)
curl -s -X POST $API/v1/transactions -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' -d '{
  "description": "Payment settled",
  "entries": [
    {"accountId": "00000000-0000-0000-0000-000000000978", "direction": "DEBIT",  "amount": "125.50"},
    {"accountId": "'$MERCHANT'", "direction": "CREDIT", "amount": "125.50"}]}'

# Example 2: payout of 98.50 with a fee of 1.50
curl -s -X POST $API/v1/transactions -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' -d '{
  "description": "Payout",
  "entries": [
    {"accountId": "'$MERCHANT'", "direction": "DEBIT",  "amount": "100.00"},
    {"accountId": "'$PAYOUTS'",  "direction": "CREDIT", "amount": "98.50"},
    {"accountId": "'$FEES'",     "direction": "CREDIT", "amount": "1.50"}]}'
PAYOUT=<id from the response>

# Example 3: reverse it (a second time: 409 ALREADY_REVERSED)
curl -s -X POST $API/v1/transactions/$PAYOUT/reversal -H "Idempotency-Key: $(key)"

# Read
curl -s $API/v1/accounts/$MERCHANT
curl -s $API/v1/transactions/$PAYOUT                              # shows reversedByTransactionId
curl -s "$API/v1/accounts/$MERCHANT/entries?limit=20"              # newest first, with balanceAfter
curl -s "$API/v1/accounts/$MERCHANT/balance?asOf=2026-11-04T10:00:00Z"

# A refusal: nothing is recorded, and the response says why and where
curl -s -X POST $API/v1/transactions -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' -d '{
  "description": "Too much",
  "entries": [
    {"accountId": "'$MERCHANT'", "direction": "DEBIT",  "amount": "500.00"},
    {"accountId": "'$PAYOUTS'",  "direction": "CREDIT", "amount": "500.00"}]}'
# => 422 application/problem+json
# {"type":"https://ledger.example/problems/overdraft-limit-exceeded","title":"Unprocessable Content","status":422,
#  "code":"OVERDRAFT_LIMIT_EXCEEDED","detail":"Merchant 42 (...) would go to -374.50, past its overdraft limit of 0.00",
#  "errors":[{"pointer":"/entries/0","code":"OVERDRAFT_LIMIT_EXCEEDED","detail":"..."}],"requestId":"..."}
```

Send the same request again with the same `Idempotency-Key` and you get the same response back, with
`Idempotent-Replayed: true`, and nothing happens twice.

### Sending a payment-settled message

```bash
./scripts/send-payment.sh <merchantAccountId> 125.50 EUR             # new paymentId and eventId
./scripts/send-payment.sh <merchantAccountId> 15000 JPY pay-123      # a given paymentId
./scripts/send-payment.sh <merchantAccountId> 15000 JPY pay-123      # re-sent: recorded once
./scripts/watch-transactions.sh                                      # the announcements (Ctrl-C to stop)
./scripts/watch-transactions.sh --dlt                                # messages that couldn't be recorded
```

The message, on topic `payments.settled`, keyed by payment id:

```json
{"eventId": "8c1e...", "paymentId": "pay-123", "merchantAccountId": "0196...", "amount": "125.50", "currency": "EUR"}
```

## Design

### Code structure

A layered layout: controllers and listeners on the outside, use cases in the middle, the ledger's rules
in domain services, with the model mapped straight onto the tables.

```
com.ledger
├── domain
│   ├── model               Account, Transaction, Entry: Spring Data JDBC entities, one per table,
│   │                       amounts in minor units. Money, LedgerCurrency, Direction, the drafts, Posting
│   ├── service             the business rules, as stateless services that write nothing:
│   │                       AccountService (opening, applying an entry, overdraft limit),
│   │                       PostingService (recording a transaction, drafting a reversal)
│   └── exception           LedgerRuleException, Violation, ErrorCode
├── application             the use cases, each one database transaction
│   ├── service             LedgerService (writes), LedgerQueryService (reads)
│   ├── port                interfaces for what the use cases need from outside: AccountRepository,
│   │                       TransactionRepository, IdempotencyKeyRepository, LedgerReadRepository,
│   │                       LedgerClock, TransactionEventPublisher
│   ├── exception           NotFoundException
│   └── support             UuidV7
└── adapter
    ├── mapper              CommonMapper: amounts and times, shared by the MapStruct mappers
    ├── web
    │   ├── controller      implement the interfaces generated from openapi.yaml
    │   ├── mapper          MapStruct: AccountMapper, TransactionMapper (model <-> DTOs)
    │   ├── idempotency     IdempotencyService: Idempotency-Key handling
    │   ├── exception       problem+json: GlobalExceptionHandler, ProblemFactory, the HTTP-layer exceptions
    │   └── filter          RequestIdFilter
    ├── persistence         MySQL
    │   ├── config          JdbcConfig: UUID <-> CHAR(36)
    │   ├── repository      the repository implementations (AccountRepositoryImpl, …): locking,
    │   │                   balance updates, read queries, idempotency keys
    │   ├── outbox          writes announcements into the outbox table (TransactionEventMapper)
    │   ├── clock           the database clock
    │   └── scheduler       IdempotencyKeyCleanupScheduler: deletes expired idempotency keys
    └── messaging           Kafka
        ├── config          topics, dead-letter error handler
        ├── consumer        PaymentSettledListener
        ├── outbox          OutboxRelay: outbox table -> ledger.transactions
        └── exception       InvalidPaymentMessageException
```

Why:

* **One model.** `Account`, `Transaction` and `Entry` are the entities themselves: they mirror their
  tables (amounts as `long` minor units, as stored) and carry no logic, so there is no second set of
  classes to keep in step and no conversion layer.
* **The rules are services**, and they write nothing: given the locked state, `PostingService.post` either
  returns the transaction and the new balances, or refuses. That makes them testable on their own, with
  every example from the brief (`PostingServiceTest`), no database needed.
* **Everything that makes the rules safe** under concurrency (locks, constraints, transactions) is in one
  place, `LedgerService` and the repository implementations, where it can be read top to bottom.
* **The use cases depend only on interfaces** (`application/port`), so a repository can be reimplemented
  (another database, JPA) without touching them.
* **Mappers are MapStruct**, one per resource, with every target property mapped explicitly (unmapped
  targets fail the build). Amounts are never converted implicitly: a `long` of minor units means nothing
  without its currency, so each one goes through `CommonMapper`'s `amount` conversion with the currency.
* It's one service, so it's one module with packages, not several Maven modules.

### Money is exact

* In the API, amounts are **decimal strings** (`"125.50"`), never JSON numbers, so no client's float
  parser can round them.
* In the model, amounts are a `long` number of **minor units** (cents, yen); arithmetic goes through
  `Money`, which pairs them with their currency. Requests
  are parsed as `BigDecimal` and refused if they have more decimal places than the currency allows
  (trailing zeros don't count: `"100.000"` JPY is 100). Overflow throws (`Math.addExact`) and is
  refused as `AMOUNT_OUT_OF_RANGE`; it never wraps.
* In MySQL, amounts are `BIGINT` minor units. No `float`, `double` or `DECIMAL` arithmetic anywhere.

### Recording a transaction

`LedgerService.recordTransaction`, in one database transaction:

1. **Lock** every account the transaction touches (`SELECT … FOR UPDATE`), one at a time **in ascending
   id order**.
2. Read the time from the database.
3. Apply the rules (`PostingService.post`) to the locked state: at least two entries, positive amounts that fit
   the currency, existing accounts in one currency, debits equal to credits, and no account past its
   overdraft limit. On a refusal, throw. **Nothing has been written yet.**
4. Insert the transaction and its entries (each with the account's balance after it), update the
   balances, and insert the announcement into the outbox.

Why this holds under pressure:

* **No lost updates, no broken overdraft.** Balances are read under the row lock and written before it's
  released, so two transactions on one account are serialised and each sees the other's result.
  Isolation is READ COMMITTED: the locking read always returns the latest committed balance, and InnoDB
  takes no gap locks on our inserts.
* **No deadlocks**, including A → B and B → A at the same moment: every transaction takes its locks in
  the same global order. A single `WHERE id IN (…) FOR UPDATE` would leave the order to the query plan,
  so the locks are taken one row at a time. A reversal also locks the original transaction first, and
  nothing else ever locks a transaction row, so the order is the same everywhere.
* **All or nothing**: one database transaction. Refusals happen before the first write.
* **Integrity**: every transaction is balanced before it's applied, and balances change only together
  with the entries that explain them, so each currency's balances add up to zero. The tests check this,
  and that each balance equals the sum of its entries, after every test.
* **Several instances**: all of it is enforced by MySQL, not by anything held in one instance's memory.

Safety nets in the schema, in case a bug gets past all that: `CHECK` constraints (a balance within its
overdraft limit, entry amounts > 0), `UNIQUE(reverses_id)`, `UNIQUE(payment_id)`, and **triggers that
reject any `UPDATE` or `DELETE` on transactions and entries**, even typed by hand.

Decisions the brief left open:

* The overdraft limit applies to where an account **ends up** after the transaction, not after each of
  its entries: a transaction is atomic.
* One account may appear in several entries of a transaction.
* Currency codes must be upper case, as ISO writes them. `EURO` and `ABC` are `INVALID_CURRENCY`;
  `GBP` is valid ISO 4217 but `UNSUPPORTED_CURRENCY`.
* The settlement accounts are the only accounts without an overdraft limit. They're created by a
  migration with fixed ids. An account opened through the API always has a limit (0 by default).
* `recordedAt` is the **database's** clock, read after the locks are taken, so an account's entries
  have non-decreasing timestamps whichever instance recorded them, despite clock drift between
  instances. "Balance as at an instant" depends on that.

### Persistence: Spring Data JDBC and `JdbcClient`

The entities are mapped by Spring Data JDBC (`JdbcConfig` converts UUID ids to `CHAR(36)`; enums are stored
by name). A transaction and its entries are one aggregate, written with a single
`JdbcAggregateTemplate.insert`. It is always an insert and never `save()`, which on an existing
aggregate deletes and re-inserts its children. Locking reads, balance updates and the read models (the
entry list, past balances) are plain SQL through `JdbcClient`, because there the SQL itself is the point.
JPA was left out: a ledger never updates its records, so dirty checking, lazy loading and the
persistence context would bring nothing but a risk of SQL nobody wrote.

Ids are UUIDv7, time-ordered so inserts land at the end of the primary-key index. Schema migrations are
Flyway (`src/main/resources/db/migration`).

### Reversals

`POST /v1/transactions/{id}/reversal` locks the original, checks it hasn't been reversed already,
and records a new transaction with every entry flipped, linked through `reversesTransactionId`.
The original's response then shows `reversedByTransactionId`; its stored row is never touched. A reversal
follows every rule of any other transaction, overdraft limits included. Two concurrent reversals of
the same transaction queue up on the original's lock, so exactly one wins and the other gets
`409 ALREADY_REVERSED`. `UNIQUE(reverses_id)` backs this up. A reversal can't be reversed
(`409 REVERSAL_NOT_REVERSIBLE`).

### Idempotency

Every `POST` (open an account, record a transaction, reverse one) requires an `Idempotency-Key`. In
**one database transaction**, the service:

1. inserts the key, with a SHA-256 of the method, path and body (the parsed body, re-serialised, so
   whitespace and field order don't matter);
2. does the work;
3. stores the response (status, body, `Location`) on the key's row.

| Situation | What happens |
|---|---|
| The retry comes after the first attempt finished | The INSERT of the key fails as a duplicate; the stored response is returned, with `Idempotent-Replayed: true`. On any instance: it's in MySQL. |
| The retry comes while the first attempt is still running | Its INSERT waits on the first one's row lock, then returns the first one's response. |
| The first attempt crashed halfway | Everything rolled back, the key included. The retry does the work afresh. |
| The first attempt was refused by a ledger rule (422, 404, 409) | Only the key and the refusal were written. The retry gets **the same refusal, even if it would now succeed**: one key, one outcome. To try again after fixing the cause, send a new key. |
| The request was malformed (400) | Not stored: it depends only on the request, so the retry gets the same 400 anyway. |
| The same key comes with a different request | `422 IDEMPOTENCY_KEY_REUSED`. The ledger never takes a different request for a retry. |

Keys are kept for 24 hours (`ledger.idempotency.retention`); `IdempotencyKeyCleanupScheduler` then deletes
them, so the table doesn't grow forever. The brief doesn't ask for this; it's housekeeping.

Payment-settled messages don't carry an idempotency key. They're deduplicated on the **payment id**
(below), which also catches a payment re-sent under a new event id.

### API

The contract, [`api/openapi.yaml`](api/openapi.yaml), was written first. The controllers implement
interfaces that `openapi-generator` creates from it at build time, so the code can't drift from the
contract without failing to compile.

| | |
|---|---|
| `POST /v1/accounts` | open an account |
| `GET /v1/accounts/{id}` | an account and its current balance |
| `GET /v1/accounts/{id}/entries?limit=&cursor=` | its entries, newest first, each with the balance after it; keyset-paged with an opaque cursor |
| `GET /v1/accounts/{id}/balance?asOf=` | its balance now, or as at a past instant |
| `POST /v1/transactions` | record a transaction |
| `GET /v1/transactions/{id}` | a transaction, with its entries and any reversal |
| `POST /v1/transactions/{id}/reversal` | reverse a transaction. It creates a new resource (the reversal), so it's a POST on a sub-resource, not a PATCH or DELETE of the original. |

* **Errors** are RFC 9457 `application/problem+json`, with a stable machine-readable `code` and, for
  every problem found, an `errors[]` entry whose `pointer` is a JSON Pointer to the part of the request
  at fault (`/entries/1/amount`). Several problems are reported together when they're independent of
  each other. Status codes: `400` malformed, `404` the resource in the path doesn't exist, `409` conflicts
  with the resource's state (already reversed), `422` well-formed but refused by a ledger rule. An
  unknown account *inside* the body is a `422 UNKNOWN_ACCOUNT`, not a 404. The full list of codes is in
  the contract.
* **Versioning**: the major version is in the path (`/v1`). Within it, changes are additive only, and
  clients are told to ignore unknown fields and treat unknown error codes by their HTTP status. Fields
  without a value are left out rather than sent as `null`.
* **Tracing**: send `X-Request-Id` (or one is generated); it's echoed back, included in problem bodies,
  in every log line, and in the header of every announcement the request causes.

### Broker: Kafka

Kafka, because what the ledger publishes is a log: an ordered, durable, replayable stream that any
number of services read at their own pace, and that a new consumer can read from the beginning.
Ordering per key and consumer groups come with it. Topics: `payments.settled` (in),
`payments.settled.DLT` (in, could not be recorded), `ledger.transactions` (out), three partitions each.
It runs as a single KRaft node in Docker: no ZooKeeper.

### Consuming payment-settled messages

Each message records a transaction that debits the settlement account in its currency and credits the
merchant. The consumer commits its offset only after the database transaction commits, so a crash means
redelivery, never loss. Redelivery is harmless:

* A payment is recorded **at most once per payment id**: checked before recording, and guaranteed by
  `UNIQUE(payment_id)` when two copies race. A copy with the same details is acknowledged and skipped,
  whatever its event id. A copy with *different* details (another amount or merchant) is refused as
  `PAYMENT_CONFLICT`: that one needs a human.
* A message that can't be recorded (not JSON, fields missing, unknown account, wrong or unsupported
  currency, too many decimal places, conflicting) goes **straight to `payments.settled.DLT`**, with the
  exception in its headers, and the messages behind it carry on. Unexpected errors, such as the database
  being briefly down, are retried three times, a second apart, before they're dead-lettered too.
  Nothing is dropped without a trace.
* While a message is being handled, its payment id and event id are in every log line, and the event id
  becomes the `requestId` of the announcement it causes.

### Announcing recorded transactions: a transactional outbox

Recording a transaction also inserts an event into the `outbox_events` table, **in the same database
transaction**. A relay publishes the table to Kafka topic `ledger.transactions`, in outbox order, and
marks each row published once Kafka has acknowledged it (`acks=all`, idempotent producer). Only one
instance relays at a time: it holds a lock row (`SELECT … FOR UPDATE NOWAIT`), and the others skip their
turn. Publishing to Kafka directly after the commit was rejected, because a crash between the two would
lose the event. Publishing before the commit was rejected too, because a rollback would announce a
transaction that doesn't exist.

The event (`eventType: ledger.transaction.recorded.v1`, Kafka key = currency, headers `eventId`,
`eventType`, `requestId`). `reversesTransactionId` and `paymentId` appear when they apply:

```json
{"eventId": "...", "eventType": "ledger.transaction.recorded.v1", "requestId": "...",
 "transaction": {"id": "...", "description": "Payout", "currency": "EUR", "recordedAt": "2026-11-04T10:00:00.123456Z",
   "entries": [{"accountId": "...", "direction": "DEBIT", "amount": "100.00", "balanceAfter": "25.50"}, "..."]}}
```

**What a consumer can rely on:**

* **It can't miss a transaction.** The event exists if and only if the transaction was recorded. Every
  recorded transaction is announced, reversals and settled payments included.
* **It can see one twice.** If the relay crashes after Kafka accepted a batch but before marking it
  published, the batch is sent again. Delivery is at-least-once: deduplicate on `transaction.id`
  (or `eventId`).
* **Order.** All transactions touching an account arrive **in the order they were applied to it**.
  Events are keyed by currency, and every transaction is in one currency, so they share a partition.
  The relay sends in outbox order, which for one account is application order, because the account's
  lock is held until commit. `balanceAfter` lets a consumer check its sequence. Transactions in
  different currencies are on different partitions, with no order between them.
* **If the service crashes right after recording a transaction**, the transaction and its outbox row are
  committed together. The event goes out as soon as any instance relays again (within ~200 ms of it
  being up), late but not lost. If it crashes before the commit, neither exists and nothing is announced.

### Tests

`./mvnw verify` runs:

| | |
|---|---|
| `PostingServiceTest` (unit) | every example in the brief, including each refusal in example 5; reporting several problems at once; exact money; overflow |
| `MoneyTest`, `AccountServiceTest` (unit) | minor units and decimal places per currency; exact arithmetic, overflow and mixed currencies refused; opening an account, its overdraft limit and each way it's refused; applying debits and credits; the overdraft check |
| `IdempotencyServiceTest` (unit) | against an in-memory key store: a retry gets the stored response without redoing the work; a different body or endpoint under the same key is refused; refusals are stored and replayed; an unexpected failure stores nothing, so the retry runs afresh |
| `ProblemFactoryTest` (unit) | problem details for refusals and missing resources; which error codes are 409 and which 422; request id |
| `AccountMapperTest`, `TransactionMapperTest`, `TransactionEventMapperTest` (unit) | the generated mappers (a Spring context with only them): amounts as strings in each currency's decimal places, UTC times, reversal links, paging cursors and bad cursors |
| `LedgerServiceIT` | a refused transaction leaves nothing behind (row counts and balances); immutability even for hand-written SQL; 200 concurrent A ↔ B transfers with no deadlock and exact balances; 50 concurrent debits against an overdraft limit (exactly 10 succeed); 20 concurrent reversals of one transaction (exactly one wins); paging and balance-as-at. After each test, every currency's balances sum to zero and each balance equals the sum of its entries. |
| `ApiIT` | a retry gets the same response and takes effect once; 12 identical requests at once record one transaction; a crash between the writes and the commit leaves nothing, and the retry then succeeds; a key reused for another request is refused; a retried refusal stays refused; problem details and pointers for every error; request ids |
| `MessagingIT` | settled payments; the same payment arriving repeatedly and under new event ids is recorded once; six kinds of bad message are dead-lettered without blocking the good one behind them; every transaction is announced in order, with details and request id |
| `MultiInstanceIT` | a **second instance** of the service on the same MySQL and Kafka: retries spread across both instances take effect once; 80 opposing transfers spread across both balance exactly, and their announcements arrive in the order the entries were applied |

Integration tests run against real MySQL 8.4 and Kafka 4.1 (Testcontainers), as the brief requires.

## Improvements

What I'd change before production:

* **Hot settlement accounts.** Every settled payment in a currency locks that currency's settlement
  account, so settlements in one currency are serialised. That's fine at this scale, but at high volume
  I'd shard each settlement account into N sub-accounts (picked by payment id) and report their sum.
* **Outbox housekeeping and throughput.** Published outbox rows are never deleted: add a purge job,
  like the idempotency keys have. One relay at a time caps throughput. If it becomes a bottleneck,
  relay per partition key, or switch to change data capture (Debezium on the binlog).
* **Idempotency keys are global.** In production they'd be scoped per client (from authentication), so
  two clients can't collide. Keys expire after 24 hours, after which a retry would be treated as new.
* **Security**: no authentication, authorisation or TLS here; the Kafka listeners are plaintext.
* **Dead-letter handling** stops at the DLT: there is no tool to inspect, fix and replay those messages,
  and no alert when one arrives.
* **Observability**: metrics (Micrometer and Prometheus) for transaction rates, refusals by code, lock
  waits, outbox lag and DLT size; OpenTelemetry tracing instead of the hand-rolled request id.
* **Operations**: MySQL and Kafka are single nodes here, with no backups or replication. In production
  they'd be a replicated MySQL (with semi-synchronous replication, so a failover can't lose a committed
  transaction) and a three-broker Kafka with `min.insync.replicas=2`.
* **Schema for the event**: a schema registry (Avro or JSON Schema) and compatibility checks, rather than
  a JSON payload documented here.
* **Balance as at an instant** includes transactions recorded *at or before* the instant. A transaction
  whose commit is still in flight at that moment can appear in a later query for the same instant. A
  "watermark" (only answer for instants older than the longest transaction) would make those answers
  stable.

What I skipped, and why:

* **Listing and searching accounts and transactions**, closing accounts, changing an overdraft limit: not
  in the brief, and each needs decisions (who may do it, what it means for history) I'd rather not guess.
* **Multi-currency transactions or FX**: a transaction is single-currency by the brief's rules.
* **Separate Maven modules for the domain**: package boundaries are enough for a service this size.
