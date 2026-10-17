# Mineplay SimpleMobArena — website data and API handoff

Verified against plugin **1.0.5**, ledger schema **3**, on **17 October 2026**.

## Brief for the website Codex instance

Build the website around this flow:

```text
Paper / SimpleMobArena
  └─ authoritative plugin ledger: SQLite OR MySQL
       └─ durable export queue + authenticated HTTPS publisher [to be implemented]
            └─ website ingestion API [to be implemented]
                 └─ website PostgreSQL database
                      └─ player profiles, leaderboards, recent matches and match detail pages
```

The website retains its own PostgreSQL copy. It does not query the Minecraft database on every page request, and PostgreSQL does not replace the plugin's SQLite/MySQL backend. The plugin remains responsible for match results, reward eligibility, payments and reconciliation. The website stores and presents those results.

**Implemented today:** the source tables, reporting views, match/per-player statistics and payment state machine described below.

**Proposed contract, not implemented today:** the HTTP endpoint, JSON envelope, export revisions, website delivery queue and PostgreSQL tables in this document. Plugin 1.0.5 does not publish HTTP events or have a PostgreSQL driver. A separate plugin change is needed to connect the live server to the website. The website can implement this contract and develop against the example payload meanwhile.

Required website features:

- Leaderboard: total credited kills, total recorded matches, highest team wave completed and confirmed earnings.
- Player page: UUID identity, recorded name, aggregate statistics and recent matches.
- Match page: arena ID, times, completed waves, credited kill total, pot, cancellation/end reason, full roster, each player's kills/damage/time and payout.
- Payout history: one current settlement per player per match, with its amount and status.
- Import status: last successfully received data and reporting delay, so stale data is not presented as live.

## 1. What is actually stored

### Storage locations and lifecycle

With SQLITE, the plugin data folder contains:

| Item | Purpose | Send to website? |
| --- | --- | --- |
| mobarena.db | Match ledger, participants, settlements and settlement audit | Export the allowlisted fields below through the API |
| equipment.db | Inventory snapshots and recovery state | No |
| settlement-outbox/ | Durable binary handoff from finished match to the plugin's SQL ledger | No; this is not a website delivery queue |
| config.yml | Storage credentials, economy, waves, sounds and other settings | No |
| arenas/*.yml | Current arena IDs/names, boundaries, spawns and settings | Not in the initial match API |
| classes.yml | Current equipment/class definitions | No |
| npc-menus.yml | Citizens NPC UUID/ID links to the arena menu | No |

With MYSQL, ledger and equipment tables share the configured MySQL database. The local settlement-outbox directory is still used. Neither storage option currently writes to website PostgreSQL.

Only matches that actually started are persisted. Leaving/cancelling a lobby before the match starts produces no stored match. After a started match ends, the plugin saves its result to the durable local outbox, then inserts the match, all participants and all initial settlements in one SQL transaction. Replaying the same session UUID does not insert a duplicate.

Eliminated players, voluntary leavers and disconnected players remain in the finished roster with their contributions. SQL outages can delay appearance of a finished match. Abruptly interrupted active matches are not resumed and are not automatically converted into completed match records. A graceful stop cancels started matches and records them without a payout.

There is no separate source players table: lifetime statistics are aggregates over match participation and settlements.

### sessions — one row per recorded match

| Column | Meaning |
| --- | --- |
| session_id | Match UUID; primary key. Preserve it in the website. |
| arena_id | Arena configuration ID, up to 128 characters; not its display name. |
| started_at | Actual match start, excluding lobby countdown. UTC timestamp. |
| finished_at | Match finish/cancellation timestamp. |
| completed_waves | Fully cleared waves for the whole team. |
| total_kills | Sum of the roster's credited kills. Not the number of all mobs that died. |
| prize_pot | Capped match pot before any short-session reduction. Not money already paid. |
| cancelled | Boolean represented as integer 0/1 in the source. |
| end_reason | Explanatory text, at most 64 characters. Not an outcome enum. |
| unallocated_pot | Pot not allocated to current payable/confirmed settlements; can change after match finish. |
| settings | EconomySettings.toString() captured when finishing. Internal Java text, not JSON or a stable API format. Omit from the website payload. |

### participants — one row per player in a match

Primary key: (session_id, player_id). session_id references sessions.

| Column | Meaning |
| --- | --- |
| session_id | Match UUID. |
| player_id | Minecraft player UUID. Use this as identity, never the name. |
| player_name | Name captured at joining, at most 64 characters. Historical, not necessarily current. Older/imported records can contain the UUID text instead. |
| kills | Credited kills of registered arena mobs by this participant. |
| damage | Recorded damage dealt to registered active mobs; finite, nonnegative floating-point health points, not money. Each recorded hit is capped to the target's remaining health. |
| participation_seconds | Whole seconds from match start to first departure or match finish. Excludes lobby time, includes intermissions. |

### settlements — one current payout record per player per match

Primary key: settlement_id. Unique pair: (session_id, player_id). The application inserts one settlement per participant, including zero-payment cases.

| Column | Meaning |
| --- | --- |
| settlement_id | Stable UUID for this payout record. |
| session_id, player_id | Match and player identity. |
| share | Kill fraction between 0 and 1, with up to 16 decimal places. 0.25 means 25%. |
| gross_amount | Allocated kill-share amount before personal eligibility and caps. May already reflect a short-session reduction. |
| intended_amount | Amount currently reserved/attempted/confirmed. Can decrease at payment-time cap checks. Its status determines whether it was paid. |
| caps_applied | Comma-separated reason codes; empty string means no recorded reason. API converts this to an array of strings. |
| status | One of the seven states in section 2. |
| reserved_at | Match finished_at, even if SQL preparation happened much later. Not insertion time. |
| paid_at | Nullable time confirmation was recorded. For an operator reconciliation, this is reconciliation time, not necessarily the original deposit time. |
| paid_day | Nullable UTC calendar date of paid_at, used by the plugin's payout limits. |
| error | Nullable provider/recovery/operator detail. A nonempty value does not necessarily mean failure. Keep internal; do not expose on public pages. |

The source database does not enforce a composite settlement-to-participant foreign key, although the application creates them together. The proposed website schema below adds that constraint.

### Other source tables

- **settlement_audit:** append-only transition evidence: id (auto-increment), settlement_id (nullable reference), event_at, actor, event and detail. Actor can identify an administrator. This is operational evidence, not a public event API. Omit raw actor/detail from website ingestion.
- **equipment_snapshots:** player_uuid primary key, unique recovery token, choice, original inventory blob, nullable restore_target blob, status, created_at and updated_at. Status is PREPARED, RESTORING or CONFIRMED. This is recovery state, not historical per-match kit data; a later join can replace a confirmed row. Do not export inventory blobs or recovery tokens.
- **mobarena_schema:** MySQL schema-version metadata, keyed by component; ledger is version 3 and equipment is version 1. SQLite keeps the equivalent versions in each database's PRAGMA user_version.
- **mobarena_migration_guard:** operational guard used by the offline storage migration tool; not website data.

### Physical types and existing reporting views

SQLite stores UUIDs, timestamps, money and shares as TEXT; damage is REAL. MySQL uses UUID VARCHAR(36), timestamp VARCHAR(40), money DECIMAL(20,2), share DECIMAL(30,16), and damage DOUBLE. Source timestamps are Java Instant strings in UTC, with variable fractional precision up to nanoseconds.

Existing views:

| View | What it exposes |
| --- | --- |
| mobarena_match_performance | One player/match row joined to its settlement, including match fields and derived confirmed_amount |
| mobarena_player_payout_history | Player/match payout reporting fields |
| mobarena_leaderboard | Per-player totals and confirmed_earned_minor |

confirmed_earned_minor is always in hundredths: 12345 means $123.45, even when the configured payout rounding scale is 0 or 1. The views omit fields such as settlement_id, reserved_at, paid_day, raw settings and error. A complete exporter should read an explicit base-table projection, not SELECT * from a reporting view.

## 2. Meanings the website must preserve

### Money and payout states

Use decimal strings in JSON and PostgreSQL NUMERIC for money. Source calculations use BigDecimal with payout precision from 0 to 2 decimal places. Normalize transport money to two places, e.g. "500.00", without changing its value. Use $ only in presentation; this is the server's game currency, not an asserted real-world currency.

**Confirmed earnings = SUM(intended_amount WHERE status = 'CONFIRMED').**

| State | Public meaning |
| --- | --- |
| PENDING | Reward recorded, awaiting payment. |
| PROCESSING | Payment claim recorded; outcome not yet confirmed. |
| CONFIRMED | Provider confirmed the expected amount, or an operator verified a previously uncertain payment. Count as earned. |
| UNCERTAIN | Payment may have happened; awaiting operator verification. Do not count as confirmed and never ask the website to retry the deposit. |
| FAILED | Provider explicitly reported failure. No automatic retry. |
| WITHHELD | No payable reward after eligibility/caps/cancellation/disabled payouts. |
| VOID | Operator verified that an uncertain payment did not happen. |

Transitions are not a simple increasing status order. PROCESSING can return to PENDING when no deposit was attempted. Restart recovery changes PROCESSING to UNCERTAIN. An operator can later change UNCERTAIN to CONFIRMED or VOID. Reconciliation records an outcome; it does not send money.

unallocated_pot initially equals prize_pot minus intended allocations. It increases when a claim lowers an amount, a payment fails or an uncertain payment is voided. **It is not prize_pot minus confirmed earnings:** pending/uncertain amounts can still reserve part of the pot.

Current reason codes are CANCELLED, PAYOUTS_DISABLED, MINIMUM_WAVE, ZERO_KILLS, MINIMUM_PARTICIPATION, NO_KILLS, PLAYER_SESSION_CAP, SESSION_POT_CAP, SHORT_SESSION_REDUCTION, REWARD_COOLDOWN, DAILY_CAP and CAP_RECHECK_AT_PAYMENT. Preserve unknown future codes; use a readable generic label if no translation exists.

The default minimums are three completed team waves, 60 seconds of individual participation and credited kills, plus configured caps/cooldown. They are configurable. Display the source result; do not recalculate historical eligibility or payouts using today's configuration.

### Statistics and outcomes

- Total matches, kills and damage include cancelled and zero-reward matches in the existing plugin statistics. If offering a different filter, label it explicitly.
- The existing highest_wave statistic means the greatest **team completed_waves** among a player's matches. A player eliminated early can therefore have a high team-wave record. Label it "Highest team wave completed", not "Personal wave survived".
- The current in-game leaderboard sorts by kills descending, highest team wave descending, then UUID ascending. An earnings leaderboard can be a separate website sort.
- A noncancelled match is not necessarily a victory. A team wipe also ends normally and can pay out. end_reason is free text; do not parse it into a guaranteed win/loss enum.
- Show a stored name on historical match pages. For a profile label, use the most recently observed historical name or a separately verified current name. UUID identity must survive renames.
- Payout history is a list of settlements, not an additional sum of each processing/retry/audit event.

### Data not directly recorded in schema 3

There are no stored per-player elimination waves, exit reasons/timestamps, death counts, selected classes, mob-type kill breakdowns, per-wave results, survivor lists, explicit victory flag, arena display-name snapshot, configured maximum waves, historical mob-health settings, or per-match plugin/server version.

starting_player_count is not an explicit column. For normal current matches it can be derived from roster size: lobby departures are removed, late joins are disallowed, and post-start departures stay in the roster. Treat this as a derived roster count, with unknown/null provenance for anomalous/imported data rather than inventing an exact historical snapshot.

There is no live-match HTTP feed and no ledger updated_at, source-server ID, export revision or export cursor. Equipment updated_at is unrelated to match synchronization.

## 3. Proposed API contract

Everything in this section is a contract to implement, not an existing endpoint.

### Transport

- Website route: POST /api/integrations/simple-mob-arena/v1/match-snapshots
- Content-Type: application/json
- Authorization: Bearer <server integration credential>, over HTTPS.
- Idempotency-Key: the event_id UUID in the body.
- Provision a stable source_id UUID for this server/ledger lineage. Bind the credential to that source; never trust a client-supplied source ID without checking the credential.
- The publisher runs in the plugin/backend, never in browser JavaScript. The browser uses the website's normal read API.
- One request contains one complete match snapshot, including every participant and their current settlement. It is not a delta.

New envelope fields:

| Field | Contract |
| --- | --- |
| schema_version | Integer 1. Reject unsupported versions explicitly. |
| event_type | Literal "match.snapshot". |
| event_id | UUID generated once and persisted with the export event; unchanged across retries. |
| source_id | Stable integration/server UUID. New exporter metadata, not a source ledger field today. |
| match_revision | Positive 64-bit integer encoded as a decimal string, monotonically increasing per source/match. New exporter metadata. |
| observed_at | UTC time this snapshot was captured from committed source state. Not match finish or actual payment time. |
| match | Source-derived object shown below. |

Money and share values are decimal strings. participation_seconds and total_kills are decimal integer strings because their durable types can be 64-bit. Per-player kills and completed_waves are nonnegative JSON integers. damage is a finite, nonnegative JSON number. JSON must not contain NaN or Infinity. UUIDs are canonical UUID strings.

The example is fictional; all values are sample data:

```json
{
  "schema_version": 1,
  "event_type": "match.snapshot",
  "event_id": "6b8ca269-160e-439a-8e2c-b66900d552ee",
  "source_id": "75fc55d0-2d76-467e-9830-9a59772ab42b",
  "match_revision": "3",
  "observed_at": "2026-10-17T18:03:04Z",
  "match": {
    "session_id": "a9834ea3-f4e3-452f-b9cc-524c29e6ac2d",
    "arena_id": "spawn",
    "started_at": "2026-10-17T18:00:00Z",
    "finished_at": "2026-10-17T18:03:00Z",
    "completed_waves": 3,
    "total_kills": "27",
    "prize_pot": "750.00",
    "unallocated_pot": "0.00",
    "cancelled": false,
    "end_reason": "No active participants remain.",
    "participants": [
      {
        "player_id": "0b8f3c4b-1597-4c02-96ca-c08a631536e3",
        "player_name": "ExamplePlayer",
        "kills": 18,
        "damage": 340.5,
        "participation_seconds": "180",
        "settlement": {
          "settlement_id": "868fdbbb-3828-4953-a588-2bd3472abec4",
          "share": "0.6666666666666666",
          "gross_amount": "500.00",
          "intended_amount": "500.00",
          "caps_applied": [],
          "status": "CONFIRMED",
          "reserved_at": "2026-10-17T18:03:00Z",
          "paid_at": "2026-10-17T18:03:02Z",
          "paid_day": "2026-10-17"
        }
      },
      {
        "player_id": "e4a86a58-ae72-4ad1-84ec-4643ff298ba8",
        "player_name": "ExampleTeammate",
        "kills": 9,
        "damage": 250.25,
        "participation_seconds": "120",
        "settlement": {
          "settlement_id": "af12942c-f8ba-4b1f-96ca-bc5c9fe36466",
          "share": "0.3333333333333333",
          "gross_amount": "250.00",
          "intended_amount": "250.00",
          "caps_applied": [],
          "status": "PENDING",
          "reserved_at": "2026-10-17T18:03:00Z",
          "paid_at": null,
          "paid_day": null
        }
      }
    ]
  }
}
```

In this snapshot confirmed earnings are $500.00, while $250.00 is still pending. A later revision can confirm the second settlement without creating a second match or another payout record.

Do not send raw settings, error, settlement_audit actor/detail, credentials, original inventories, restore targets, recovery tokens, NPC links or outbox binary files.

### Ingestion, retries and ordering

1. Authenticate and authorize the source. Validate schema, identifiers, field lengths, money precision/ranges, finite damage, timestamps and the complete roster before applying anything.
2. Require unique players and settlement IDs in the roster, exactly one settlement per participant, and total_kills equal to the sum of participant kills. Check nonnegative amounts, share between 0 and 1, unallocated_pot no greater than prize_pot, and finished_at no earlier than started_at. Do not require shares to sum to exactly 1: source fractions are truncated and zero-kill matches have zero shares.
3. Serialize updates for a source/session within a PostgreSQL transaction. Record the receipt and apply parent, roster and settlements atomically. Concurrent initial inserts and revision races must receive the same protection as later updates.
4. An already received event_id with the same exact body hash succeeds as "duplicate". The same event ID with different bytes is a 409 conflict. Persist the exact request bytes at the publisher so retries do not change whitespace, observed_at or IDs.
5. Apply a snapshot only when its match_revision is greater than the stored revision. A lower revision succeeds as "stale" without altering match data. The same revision with a different event ID is a conflict; the publisher must preserve the original event.
6. Match identity, historical roster/contributions, gross shares and basic match facts are immutable after initial import under this v1 contract. New revisions update settlement state/intended amounts/cap reasons/payment timestamps and match unallocated_pot. Reject conflicting immutable facts rather than silently rewriting historical matches. Corrections require a separately designed administrative process.
7. Commit before acknowledging success. A lost response is safe to retry. Never increment lifetime totals just because another delivery arrived; query canonical rows or refresh derived aggregates after commit.

Successful response, HTTP 200:

```json
{
  "event_id": "6b8ca269-160e-439a-8e2c-b66900d552ee",
  "result": "applied",
  "stored_revision": "3"
}
```

result is applied, duplicate or stale. Use 400/422 for invalid data, 401/403 for authentication/authorization, and 409 for identity/revision conflicts. Retry network failures, 429 and 5xx with bounded exponential backoff/jitter; honor Retry-After. Keep rejected events and report the problem rather than silently dropping them or looping rapidly.

### Exporter work needed on the plugin side

Add a separate durable SQL website export queue and per-match revision state. Enqueue a versioned, allowlisted snapshot **in the same transaction** as each relevant ledger mutation: initial prepare, claim/cap recheck, payment completion, recovery to UNCERTAIN and administrative reconciliation. Capture consistent parent/participant/settlement state; do not construct a snapshot from unrelated reads that straddle a payment update.

The existing settlement-outbox is removed after SQL preparation and contains no later payment transitions. It must not be repurposed or deleted in response to website delivery. Likewise, the internal audit table is not a published replay contract.

Send HTTP in background work with timeouts, never on the Paper server thread. A website outage must delay reporting rather than block gameplay, equipment recovery or payments. Retain export events until committed acknowledgement and expose queue age/failures to administrators. Coalescing is optional only if the revision/idempotency guarantees remain intact.

Backfill old SQL matches through this same snapshot protocol, serialized with normal mutations so a confirmation cannot race ahead of its initial revision. Preserve source identity, revision state and pending website deliveries across SQLite/MySQL migration. The current offline StorageMigration copies a fixed set of tables and needs extending for these new tables.

Do not synchronize only WHERE finished_at > last_seen: payments and unallocated_pot can change long after that time. Do not order by payment status or equipment updated_at. A deliberate source database rollback/replacement needs an explicit resynchronization/fencing process; reusing lower revisions would leave the website ahead of the restored ledger.

## 4. Proposed PostgreSQL retention model

This is a website schema blueprint, not a plugin migration. Adapt its naming to the website project while preserving the keys, decimal types and atomic revision rules.

There is intentionally no mandatory players master table: profiles can be derived by UUID from participants. A website account/profile table may link a verified Minecraft UUID separately; a recorded player name does not authenticate an account.

```sql
CREATE TABLE ma_sources (
    source_id uuid PRIMARY KEY,
    display_name text NOT NULL,
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE ma_matches (
    source_id uuid NOT NULL REFERENCES ma_sources(source_id),
    session_id uuid NOT NULL,
    arena_id varchar(128) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    completed_waves integer NOT NULL CHECK (completed_waves >= 0),
    total_kills bigint NOT NULL CHECK (total_kills >= 0),
    prize_pot numeric(20,2) NOT NULL CHECK (prize_pot >= 0),
    unallocated_pot numeric(20,2) NOT NULL
        CHECK (unallocated_pot >= 0 AND unallocated_pot <= prize_pot),
    cancelled boolean NOT NULL,
    end_reason varchar(64) NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision > 0),
    source_observed_at timestamptz NOT NULL,
    last_ingested_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_id, session_id),
    CHECK (finished_at >= started_at)
);

CREATE TABLE ma_participants (
    source_id uuid NOT NULL,
    session_id uuid NOT NULL,
    player_id uuid NOT NULL,
    player_name varchar(64) NOT NULL,
    kills integer NOT NULL CHECK (kills >= 0),
    damage double precision NOT NULL CHECK (damage >= 0 AND damage < 'Infinity'::double precision),
    participation_seconds bigint NOT NULL CHECK (participation_seconds >= 0),
    PRIMARY KEY (source_id, session_id, player_id),
    FOREIGN KEY (source_id, session_id) REFERENCES ma_matches(source_id, session_id)
);

CREATE TABLE ma_settlements (
    source_id uuid NOT NULL,
    settlement_id uuid NOT NULL,
    session_id uuid NOT NULL,
    player_id uuid NOT NULL,
    share numeric(30,16) NOT NULL CHECK (share >= 0 AND share <= 1),
    gross_amount numeric(20,2) NOT NULL CHECK (gross_amount >= 0),
    intended_amount numeric(20,2) NOT NULL CHECK (intended_amount >= 0),
    caps_applied text[] NOT NULL DEFAULT '{}',
    status varchar(16) NOT NULL CHECK (status IN
        ('PENDING','PROCESSING','CONFIRMED','UNCERTAIN','FAILED','WITHHELD','VOID')),
    reserved_at timestamptz NOT NULL,
    paid_at timestamptz,
    paid_day date,
    PRIMARY KEY (source_id, settlement_id),
    UNIQUE (source_id, session_id, player_id),
    FOREIGN KEY (source_id, session_id, player_id)
        REFERENCES ma_participants(source_id, session_id, player_id)
);

CREATE TABLE ma_ingest_receipts (
    source_id uuid NOT NULL REFERENCES ma_sources(source_id),
    event_id uuid NOT NULL,
    session_id uuid NOT NULL,
    match_revision bigint NOT NULL CHECK (match_revision > 0),
    body_sha256 char(64) NOT NULL,
    result varchar(16) NOT NULL CHECK (result IN ('applied','stale')),
    received_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL,
    PRIMARY KEY (source_id, event_id),
    UNIQUE (source_id, session_id, match_revision),
    FOREIGN KEY (source_id, session_id) REFERENCES ma_matches(source_id, session_id)
);

CREATE INDEX ma_matches_recent ON ma_matches(source_id, finished_at DESC, session_id);
CREATE INDEX ma_participants_player ON ma_participants(player_id, source_id, session_id);
CREATE INDEX ma_settlements_player_status ON ma_settlements(player_id, source_id, status);
```

PostgreSQL timestamps have microsecond resolution; the payload JSONB retains the original timestamp strings for diagnostics. Use match_revision for update ordering rather than timestamp equality. See the [PostgreSQL date/time documentation](https://www.postgresql.org/docs/current/datatype-datetime.html).

Money remains NUMERIC; damage is approximate by design. Validate finite decimal inputs at the API boundary, including rejection of NaN/Infinity, before sending them to PostgreSQL. See the [PostgreSQL numeric-type documentation](https://www.postgresql.org/docs/current/datatype-numeric.html).

Store SHA-256 of the original request body for retry/conflict checks; JSONB is not a byte-for-byte representation. Keep integration credentials outside public payloads and pages. Do not expose ma_ingest_receipts directly through the public website.

Source namespaces prevent different servers' arena IDs or cloned UUIDs from overwriting each other. Preserve UUIDs; do not generate replacement match, player or settlement IDs during import. Do not blindly combine an original source and its migrated copy into an all-servers leaderboard.

### Example website queries

The following examples use PostgreSQL bind parameters. They are against the proposed website tables, not the plugin database.

Leaderboard for one source; parameter $1 is source_id:

```sql
SELECT p.player_id,
       COUNT(*) AS total_matches,
       SUM(p.kills) AS total_kills,
       SUM(p.damage) AS total_damage,
       MAX(m.completed_waves) AS highest_team_wave_completed,
       SUM(CASE WHEN s.status = 'CONFIRMED' THEN s.intended_amount ELSE 0 END)
           AS confirmed_earnings
FROM ma_participants p
JOIN ma_matches m USING (source_id, session_id)
JOIN ma_settlements s USING (source_id, session_id, player_id)
WHERE p.source_id = $1
GROUP BY p.player_id
ORDER BY total_kills DESC, highest_team_wave_completed DESC, p.player_id
LIMIT 50;
```

Recent matches for a player; $1 is source_id, $2 is player UUID:

```sql
SELECT m.session_id, m.arena_id, m.started_at, m.finished_at,
       m.completed_waves, m.cancelled, m.end_reason,
       p.player_name, p.kills, p.damage, p.participation_seconds,
       s.settlement_id, s.status, s.intended_amount,
       CASE WHEN s.status = 'CONFIRMED' THEN s.intended_amount ELSE 0 END
           AS confirmed_amount
FROM ma_participants p
JOIN ma_matches m USING (source_id, session_id)
JOIN ma_settlements s USING (source_id, session_id, player_id)
WHERE p.source_id = $1 AND p.player_id = $2
ORDER BY m.finished_at DESC, m.session_id
LIMIT 20;
```

For later pages, use a cursor containing finished_at and session_id, retaining the same descending-time/ascending-ID ordering. Do not use receipt arrival order: backfills and retries can arrive late.

Match detail roster; $1 is source_id, $2 is session UUID:

```sql
SELECT p.player_id, p.player_name, p.kills, p.damage, p.participation_seconds,
       s.settlement_id, s.share, s.gross_amount, s.intended_amount,
       s.caps_applied, s.status, s.paid_at,
       CASE WHEN s.status = 'CONFIRMED' THEN s.intended_amount ELSE 0 END
           AS confirmed_amount
FROM ma_participants p
JOIN ma_settlements s USING (source_id, session_id, player_id)
WHERE p.source_id = $1 AND p.session_id = $2
ORDER BY p.kills DESC, p.player_id;
```

Fetch the parent from ma_matches using the same source/session keys. Read parent and roster consistently, e.g. in one read-only repeatable-read transaction or one composed query, so a payment update cannot produce a mixed page.

Render unknown fields as unavailable, not zero or invented values. Expose large aggregate integers and money as strings from the website API if the frontend uses JavaScript. Resolve the latest recorded name using the newest match finished_at, with stable source/session tie-breakers; a late older import must not replace a newer name.

## 5. Acceptance checks for the website implementation

1. Import the sample: one match, two participants, two settlements; confirmed earnings $500.00, pending $250.00, unallocated $0.00.
2. Repeat the identical request: no extra rows and no increase in kills, matches or earnings.
3. Send a higher revision confirming the second settlement: confirmed earnings become $750.00; roster totals and match count stay unchanged.
4. Retry an older revision after that: confirmation must not revert to pending. Check concurrent deliveries too.
5. Reuse an event ID with changed data, or a revision with a different event ID: reject without partial writes.
6. Import a cancelled/withheld match: preserve its recorded contributions; earnings remain zero. Do not label every noncancelled match a victory.
7. Import a zero-kill match, changed cap amount, FAILED outcome and UNCERTAIN-to-VOID/CONFIRMED reconciliation. Display each accurately without initiating payments.
8. Stop PostgreSQL or lose the HTTP response after commit: publisher retries safely; the website acknowledges only committed work.
9. Backfill old matches after newer ones: recent-match ordering and latest recorded names remain correct.
10. Test decimal shares/rounding, large integer serialization, missing optional paid timestamps, UUID-name fallback and unknown cap reason codes.
11. Reject an authenticated source trying to submit another source's data. Keep ingestion credentials out of browser bundles and public responses.
12. Confirm that the website works using its PostgreSQL data while the Minecraft server is offline.

## 6. Source references and scope of this handoff

Repository-relative implementation references:

- src/main/resources/db/001-initial.sql and 001-mysql.sql — initial ledger tables/types.
- src/main/resources/db/002-match-details.sql — player_name and end_reason.
- src/main/resources/db/003-reporting-views.sql and 003-reporting-views-mysql.sql — existing reporting semantics.
- src/cloud/mineplay/mobarena/storage/SettlementStore.java — preparation, transitions, statistics/history, timestamps and audit.
- src/cloud/mineplay/mobarena/economy/SessionResult.java, MatchDetails.java, MatchPerformance.java and PlayerStatistics.java — current detached Java data objects.
- src/cloud/mineplay/mobarena/economy/RewardCalculator.java and EconomySettings.java — money allocation, reason codes and settings.
- src/cloud/mineplay/mobarena/economy/EconomyService.java — durable result handoff, SQL worker, payment/reconciliation lifecycle.
- src/cloud/mineplay/mobarena/storage/SettlementOutbox.java — existing local SQL-delivery outbox.
- src/cloud/mineplay/mobarena/arenas/Arena.java and session/Participation.java — roster lifetime, finish recording and participation timing.
- src/cloud/mineplay/mobarena/events/ArenaListener.java — credited damage and death handling.
- src/cloud/mineplay/mobarena/equipment/SnapshotStore.java — private recovery data.
- src/cloud/mineplay/mobarena/storage/StorageMigration.java — existing offline SQLite/MySQL migration; not HTTP synchronization.

This handoff documents existing data and proposes an integration contract. It does not deploy an API, change the plugin's storage backend, provision PostgreSQL, or prove end-to-end delivery. Implement and test the importer and publisher together before relying on the website as a current reporting mirror.
