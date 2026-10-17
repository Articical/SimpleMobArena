# Mineplay website export

SimpleMobArena **1.0.6**, ledger schema **4**, publishes match snapshots to the website ingestion API. The website owns its PostgreSQL reporting copy. Paper continues to use SQLite or MySQL for the authoritative payout ledger.

The default destination is:

```text
POST https://www.mineplay.co.uk/api/integrations/simple-mob-arena/v1/match-snapshots
```

## Enable it

1. Back up the plugin data and database. Install `SimpleMobArena-1.0.6.jar` and restart with export disabled to upgrade the ledger schema.
2. Choose a new source UUID for this ledger, for example using `[guid]::NewGuid().ToString('D')` in PowerShell. Register it on the website as `Arena__SourceId`. If this ledger already has a source ID, use the existing one from `/ma admin website status` instead.
3. Provision a random bearer credential of 32–1024 characters on the website as `Arena__IngestionToken`. Put the same value in the **Paper process environment** as `MINEPLAY_ARENA_INGESTION_TOKEN`. A hosting panel/service must pass the variable to Java; setting it in an unrelated shell is insufficient. Supported token characters are letters, digits, `.`, `_`, `~`, `+`, `/`, `=`, and `-`.
4. Add this section to an existing `plugins/SimpleMobArena/config.yml`, using the registered UUID, then restart Paper:

```yaml
website-export:
  enabled: true
  endpoint: 'https://www.mineplay.co.uk/api/integrations/simple-mob-arena/v1/match-snapshots'
  source-id: '<registered-source-uuid>'
  token-env: MINEPLAY_ARENA_INGESTION_TOKEN
  connect-timeout-seconds: 5
  request-timeout-seconds: 15
  retry-base-seconds: 5
  retry-max-seconds: 3600
  poll-interval-seconds: 5
  batch-size: 10
  concurrency: 1
```

5. Run `/ma admin website status`. Play an eligible test match and verify the website's stored roster and confirmed earnings. Run `/ma admin website backfill` to publish historical matches, including changes made while export was disabled.

The packaged configuration defaults to disabled and contains no source ID or credential. These settings are read at startup; `/ma admin reload` does not replace the export worker. URL changes and token rotation require a restart. HTTP, URL credentials, query strings, fragments and redirects are rejected. Use the final HTTPS ingestion URL directly; a redirect from another hostname will be quarantined.

A missing/invalid credential or mismatched source ID pauses export and reports a fixed diagnostic code. Ordinary payouts remain available. Correct the configuration, restart, then backfill changes recorded during the pause. Source identity is inserted only when valid export configuration explicitly enables this ledger. A persisted source ID can never be replaced by changing YAML.

## Commands and status

All commands below require `mobarena.admin.website`, included in `mobarena.admin` and available to operators by default. Console use is supported.

| Command | Effect |
| --- | --- |
| `/ma admin website status` | Shows source ID, running/paused state, pending/quarantined counts, oldest pending age, last committed acknowledgement, backfill state and last safe diagnostic/event ID. |
| `/ma admin website backfill` | Starts a historical scan or resumes its saved cursor. It processes up to 25 matches per batch on the ledger worker, yielding between batches. |
| `/ma admin website retry <event UUID>` | Makes a retained sendable event eligible again with the same bytes and event ID, and resumes a worker suspended by 401/403. It never deposits money. |

A successful backfill command means the scan is scheduled. A retry command means the HTTP event is queued. Only a valid website acknowledgement committed to the plugin database marks an event delivered. Queue age and last acknowledgement expose reporting lag; they are not live match statistics.

After a completed backfill, explicitly starting another scan creates new snapshots at higher revisions. That is useful after an export pause. Repeating the command during an active scan resumes the existing cursor. Disabling capture resets an active scan's cursor to the beginning while retaining its active intent, so re-enabling rescans matches changed during the gap. Normal payout mutations and backfill use the same revision allocator and ledger worker, so interleaved updates cannot reuse a revision. While capture is enabled, new matches behind the scan's UUID cursor already have their own transactional export event.

## Threading and durability

The server thread reads configuration and schedules work. Export SQL, schema migration, source registration, snapshot encoding, queue reads, retry metadata, acknowledgements and backfill run on `SimpleMobArena-ledger`. HTTP runs on a dedicated `SimpleMobArena-website-publisher` worker with a bounded HTTP executor. There are no HTTP calls, network sleeps or waits for website responses inside a ledger transaction. Bukkit and Vault calls retain their existing server-thread boundaries.

With capture enabled, the following changes enqueue a full snapshot in the same SQL transaction as the ledger mutation:

- Initial finished-match preparation, with the complete roster and initial settlements.
- Payment claims, including cap rechecks and deductions from the allocated pot.
- Completion as CONFIRMED, FAILED or UNCERTAIN, and PROCESSING returning to PENDING when no deposit was attempted.
- Startup recovery from PROCESSING to UNCERTAIN.
- Administrative CONFIRM/VOID reconciliation.

The transaction allocates a monotonically increasing per-match revision, generates a new event UUID, builds the explicit reporting allowlist, and persists the final UTF-8 bytes. A queue SQL failure rolls back the ledger mutation too. A response or delivery-mark failure leaves the original event available for retry. The server's local `settlement-outbox/` remains a separate handoff into the SQL ledger and is removed after SQL preparation; it never waits for the website.

Data that cannot meet the website contract produces a durable `QUARANTINED` record with a `LOCAL_*` diagnostic and no sendable payload. This preserves the affected match/revision for investigation while allowing payout state transitions to remain recorded. Such rows cannot be retried as HTTP requests. No invalid money is rounded, roster silently truncated, or bad player identity replaced to force import. Investigate the authoritative data before any correction; immutable facts already accepted by the website require a coordinated correction procedure, not an ordinary backfill.

The worker processes at most the configured batch size with one request in flight. Connection timeout is bounded to 1–60 seconds; whole-request timeout, including a stalled response body, is bounded to 1–120 seconds and must be at least the connection timeout. Responses are limited to 16 KiB. Shutdown cancels in-flight HTTP and waits at most two seconds for the publisher; durable rows remain for restart. A website outage does not hold the ledger worker while waiting for HTTP and does not retry Vault deposits.

Disabling export preserves source identity, revision and event rows; an active backfill restarts its scan after the capture gap. It stops capture and HTTP delivery. Re-enable with the same source ID and backfill to synchronize the intervening changes. Delivered rows are retained for evidence; this version performs no automatic export-history deletion. Monitor database size and use a separately reviewed retention process that only removes acknowledged events, never source identity or revision counters.

## Wire contract

Every request carries `Content-Type: application/json`, `Authorization: Bearer <credential>` and `Idempotency-Key: <event_id>`. Retries use byte-for-byte identical JSON and the same idempotency key. The envelope contains exactly `schema_version` (1), `event_type` (`match.snapshot`), `event_id`, `source_id`, `match_revision`, `observed_at`, and `match`.

The match contains its session/arena identity, start/finish timestamps, completed waves, credited kill total, prize/unallocated pot, cancellation flag, end reason and every participant. Each participant contains the server's UUID and recorded name, kills, damage, participation seconds, and their current settlement. The settlement contains its UUID, share, gross/intended amount, cap codes, status, reservation timestamp and nullable paid timestamp/day.

Money is an exact decimal string with two fractional places. Revisions and 64-bit counters are decimal strings; kills/waves are JSON integers and damage is a finite JSON number. UTC timestamps use a consistent nine-digit fractional representation. Player names are labels: Java and Bedrock/Floodgate names are preserved, and UUIDs remain identity. No external player lookup occurs. Unknown cap codes are preserved. Payloads are limited to 2 MiB and 1–1000 participants.

Immutable facts remain identical across revisions: match identity, arena, times, wave/kill totals, pot, cancellation/end reason, roster and contributions, settlement identity/share/gross/reserved time. Mutable fields are unallocated pot, intended payout, caps, status and paid timestamps/day. Revisions order updates; settlement status is not a monotonic ordering. The website must aggregate only CONFIRMED earnings and upsert one settlement per player per match rather than sum snapshot events.

No configuration, storage credentials, economy settings text, audit actor/detail, provider error, inventory/equipment, NPC references or HTTP credential is in the payload. The explicit source field descriptions remain in [WEBSITE_DATA_HANDOFF.md](WEBSITE_DATA_HANDOFF.md); the website's `Api/SnapshotContract.cs` and `Data/SnapshotValidator.cs` define its accepted ingestion contract.

Only HTTP 200 with this exact acknowledgement shape can mark delivery:

```json
{"event_id":"<sent-event-uuid>","result":"applied","stored_revision":"3"}
```

`result` can be `applied`, `duplicate` or `stale`; the matching event ID is mandatory and the positive stored revision must be at least the sent revision. Unknown/duplicate keys, numeric revisions instead of strings, invalid UTF-8, missing fields, malformed JSON, oversized responses or an invalid content type leave the event pending.

## Failures and recovery

Network failures, timeouts, 408, 429, server errors and invalid acknowledgements retry with bounded exponential backoff and jitter. `Retry-After` seconds or HTTP dates are honored up to `retry-max-seconds`. Retry count and next attempt are persisted. 400, 401, 403, 409, 413, 415, 422 and other permanent 4xx responses are quarantined; redirects are quarantined without following them. 401/403 also suspend the running worker to prevent repeatedly using a rejected credential.

| Diagnostic | Operator action |
| --- | --- |
| `EXPORT_SOURCE_ID_REQUIRED` / `EXPORT_SOURCE_ID_INVALID` | Register a canonical, nonzero UUID for this ledger and restart. |
| `WEBSITE_SOURCE_MISMATCH` | Restore the already persisted source ID; do not create a new identity to evade a conflict. |
| `WEBSITE_SOURCE_STATE_MISSING` | Restore source metadata coherently with the existing revisions/events. A partial restore must not create a new identity. |
| `EXPORT_TOKEN_INVALID` | Supply the configured environment variable with a valid registered credential, then restart. |
| `HTTP_401` / `HTTP_403` | Check website token/source authorization; repair configuration and restart, then explicitly retry retained events as needed. |
| `HTTP_409` | Compare website receipts and plugin backup/revisions. Preserve the conflicting event and investigate restore or immutable-data conflicts before retrying. |
| `HTTP_413` / `HTTP_422` / `LOCAL_*` | Inspect the retained match and receiver contract. Do not trim data or silently alter historical facts. |
| `QUEUE_*` / `WEBSITE_STORAGE_FAILURE` | Restore database access; retained rows remain authoritative. Check status after restart and resume backfill if needed. |

Status and exporter logs use fixed codes, not bearer headers, payload bodies, raw HTTP response content or JDBC errors. Full endpoint details and credentials are not shown in export status. The queue retains per-event diagnostics; status shows the most recent failed event. Inspect the database read-only for earlier quarantines, then retry by UUID after the underlying cause is fixed.

## Migration, backups and rollback

Before offline SQLite/MySQL migration, run this plugin once with export disabled to upgrade the **source to ledger schema 4**, then stop all writers. The migration tool refuses schema 3 sources rather than modifying the source during copying. Drain the existing local settlement outbox first, as described in the README.

Schema 4 adds:

| Table | Preserved state |
| --- | --- |
| `website_export_state` | Stable source ID, backfill cursor/state, last committed acknowledgement and safe failure/event ID. |
| `website_match_revisions` | Last allocated revision per session. |
| `website_export_events` | Event/session UUIDs, revision, exact payload BLOB, creation time, attempts, next attempt, delivery state, failure code, acknowledgement time and acknowledged website revision. |

`migrateStorage` includes all these rows, including delivered and quarantined events, and verifies row counts and SHA-256 fingerprints. Payload BLOBs are compared as exact bytes. Preserve the same `source-id` and re-provision the secret environment variable on the destination; tokens are not migrated in the ledger.

Never start both source and destination publishers, even temporarily. Keep the retired server stopped and revoke its access before switching ownership. This plugin does not provide distributed multi-server locking. Also preserve the local settlement outbox, equipment database/snapshots and matching player data; website export is not an equipment backup.

Restoring an older backup can lose revisions or payment evidence that the website already accepted. Keep export disabled after such a restore until the website receipts, durable plugin revisions and payment provider evidence have been reconciled. Do not reset revision counters, regenerate event IDs, change source UUIDs or delete conflicting receipts to make requests pass. If the full ledger and queue can be restored, replay the preserved events; otherwise agree an explicit website resynchronization/correction procedure before resuming. A stale acknowledgement means the website already has a newer revision, not that an old backup is safe to promote. Older plugin versions refuse the newer schema: rollback requires a coherent backup and the same recovery review.

## Verification

The Gradle suite covers SQL transaction rollback, revision allocation, exact-byte queue replay, recovery/reconciliation, backfill, source preservation/mismatch, secret exclusions, Bedrock identities, asynchronous service callbacks, HTTP retries/acknowledgements/timeouts/redirects and offline migration. The optional disposable-MySQL test uses the existing `MOB_ARENA_MYSQL_TEST_*` environment variables.

Plugin tests also generate `build/contract-fixtures/*.json`. With the website checkout and .NET 10 SDK available, run:

```powershell
.\tools\verify-website-contract.ps1 -WebsiteDirectory C:/Users/rybis/source/repos/MinePlayWebsite
```

This compiles the actual website DTO/validator read-only in a separate build directory, validates the emitted plugin bytes and compares immutable hashes across revisions. It does not deploy the website or prove authentication, PostgreSQL commits or live Paper behavior. Production acceptance still requires the deployed endpoint, matching source/token and verification of a real test match's confirmed results on the website.

