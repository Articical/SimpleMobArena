# SimpleMobArena duel API handoff

Plugin 1.0.8, ledger schema 5, 21 October 2026.

The plugin now records and publishes 1v1 and team duel results. The website must implement the new endpoint below and store the received data in its own PostgreSQL database. This change does not implement or deploy the website receiver. The existing `match.snapshot` payload and mob-arena endpoint are unchanged.

## Endpoint and authentication

```text
POST https://www.mineplay.co.uk/api/integrations/simple-mob-arena/v1/duel-snapshots
Content-Type: application/json
Authorization: Bearer <existing ingestion credential>
Idempotency-Key: <event_id>
```

Configure `website-export.duel-endpoint` alongside the existing `endpoint`. Both destinations must use HTTPS and the same host and effective port, with different paths. They share the registered source UUID, bearer credential, publisher, retry policy and admin controls. Existing configuration without `duel-endpoint` derives a sibling `duel-snapshots` path from the mob endpoint. No redirects are followed.

Deploy the receiver before enabling website export on a server hosting duels. Both modes share `website-export.enabled`; there is no separate duel toggle. An absent route returns 404 and quarantines the event. After the route is available, use `/ma admin website retry <event UUID>` for retained events or `backfill` to generate fresh snapshots. Status and backfill include both modes. Authentication failures suspend the shared publisher.

## Exact payload

```json
{
  "schema_version": 1,
  "event_type": "duel.snapshot",
  "event_id": "10000000-0000-4000-8000-000000000001",
  "source_id": "20000000-0000-4000-8000-000000000002",
  "match_revision": "1",
  "observed_at": "2026-10-21T19:02:00.000000000Z",
  "duel": {
    "session_id": "30000000-0000-4000-8000-000000000003",
    "arena_id": "courtyard",
    "mode": "DUEL",
    "started_at": "2026-10-21T19:00:00.000000000Z",
    "finished_at": "2026-10-21T19:02:00.000000000Z",
    "winner": "RED",
    "cancelled": false,
    "end_reason": "TEAM_ELIMINATED",
    "participants": [
      {
        "player_id": "40000000-0000-4000-8000-000000000004",
        "player_name": "ExampleRed",
        "team": "RED",
        "kills": 1,
        "damage": 20.0,
        "participation_seconds": "120",
        "eliminated": false
      },
      {
        "player_id": "50000000-0000-4000-8000-000000000005",
        "player_name": "ExampleBlue",
        "team": "BLUE",
        "kills": 0,
        "damage": 8.5,
        "participation_seconds": "120",
        "eliminated": true
      }
    ]
  }
}
```

The example is illustrative. Tests generate actual encoder output in `build/duel-contract-fixtures/team-duel.json` and `cancelled-duel.json` for receiver contract tests.

| Field | Contract |
| --- | --- |
| `schema_version`, `event_type` | Exactly integer `1` and string `duel.snapshot`. |
| UUID fields | Canonical lowercase, nonzero UUID strings. Player names are historical labels, not identity. Preserve Bedrock/Floodgate names. |
| `match_revision` | Positive signed-64-bit decimal string. Monotonically allocated per duel; never a JSON number. |
| Timestamps | UTC ISO 8601 with nine fractional digits, years 0001–9999. Finish is at or after start. |
| `arena_id` | Nonblank string, at most 128 characters. Configuration ID, not display name. |
| `mode` | `DUEL` or `TEAM_DUEL`. |
| `winner` | `RED`, `BLUE` or `DRAW`. Cancelled matches use `DRAW`. |
| `cancelled` | JSON boolean. Cancelled results must not count as wins or losses. |
| `end_reason` | Nonblank text, at most 64 characters. Current normal reasons are `TEAM_ELIMINATED` and `TIME_LIMIT`; preserve other cancellation reasons without interpreting them as outcomes. |
| `participants` | Full roster, including eliminated players, leavers and disconnects. Stable order by team then player UUID. At most 1000 in the wire contract; gameplay currently supports at most 32. An empty roster is permitted only for a cancelled result. No duplicate player UUIDs. |
| `player_name`, `team` | Nonblank name at most 64 characters; team `RED` or `BLUE`. |
| `kills` | Nonnegative signed-32-bit JSON integer, credited opposing-player eliminations. |
| `damage` | Finite, nonnegative JSON number, recorded damage against opposing players. |
| `participation_seconds` | Nonnegative signed-64-bit decimal string, bounded by the whole match duration. Lobby/countdown time excluded. |
| `eliminated` | JSON boolean. True when the player was removed during play, including leaving or disconnecting. It is not a death counter. |

Strings cannot contain control characters or malformed Unicode. Requests are at most 2 MiB. No payout, prize pot, class, equipment, potion loadout, arena coordinates, mob set, password or token appears in a duel payload. PvE mob-set and potion changes do not add fields to the existing mob-match API.

## Gameplay meanings

Each duel is one round. `DUEL` starts with one player per team; `TEAM_DUEL` starts with equal configured teams, assigned automatically or selected by players. The match records the resulting assignments, not the selection policy. A team's last elimination/forfeit ends the match. A timeout is a draw, regardless of remaining health. An administrative stop or graceful shutdown records cancellation. Lobby cancellations do not create results.

Leaving or disconnecting forfeits the player's place. Rejoining that running match is denied. A credited elimination uses the most recent valid opposing attacker within ten server seconds, still active at elimination; leaving/disconnecting does not award a kill. Confirmed poison/wither splash effects credit their later damage ticks while the effect and opposing players remain valid. Removal, expiry, replacement or a session change ends that attribution. Environmental eliminations without a qualifying attacker can therefore leave total kills below the number eliminated. Friendly and cross-arena damage are blocked. WorldGuard protections still apply.

PvP kills must stay separate from mob-kill totals and class unlocks. Duels do not create settlements or cash rewards. Show a winner/draw/cancellation, not a zero-dollar payout. A player eliminated before their teammates win is still on the winning team. `eliminated` alone does not determine victory.

Different arenas run concurrently with independent session UUIDs, teams, timers and results. Session identity, rather than arena ID or finish order, identifies a match. An arena can host different modes over time, so use the recorded mode for historical pages.

## PostgreSQL import and acknowledgement

Keep a separate duel aggregate or an explicitly typed match model. A suitable mapping is:

| Entity | Key and contents |
| --- | --- |
| Duel | `(source_id, session_id)`; arena, mode, start/finish, winner, cancelled, end reason, last accepted revision. |
| Duel participant | `(source_id, session_id, player_id)`; recorded name, team, kills, damage, seconds, eliminated. |
| Ingestion receipt | `(source_id, event_id)`; body hash, event type, session, revision and committed acknowledgement. |

Apply the complete roster and receipt atomically. Validate the source/token binding and idempotency header before processing. Preserve all immutable duel facts across revisions; backfill produces higher revisions of the same result. A repeated event with different bytes, or conflicting immutable facts, should return 409. Duplicate/stale snapshots must not add kills, wins or matches again. Use a unique source/session identity even if you combine PvE and duel history in one website timeline.

Return HTTP 200 only after committing the transaction, with exactly:

```json
{"event_id":"10000000-0000-4000-8000-000000000001","result":"applied","stored_revision":"1"}
```

`result` is `applied`, `duplicate` or `stale`. The event UUID must match the request and `stored_revision` must be a positive decimal string at least as high as the sent revision. The existing strict acknowledgement parser is shared with mob matches. Receipt/idempotency rules must work across both event types.

For a player page, query participant membership, sort by finish time and session UUID, then open the selected duel's full roster. Keep cancelled matches visible with their reason. Compute wins/losses from team versus winner only for noncancelled, nondraw results. Display PvP kills and damage separately from mob performance and confirmed earnings.

## Source storage and delivery

Schema 5 adds `duels`, `duel_participants`, `website_duel_export_state`, `website_duel_revisions` and `website_duel_export_events` to the existing SQLite/MySQL ledger. These tables and exact queued payload bytes are included in offline storage migration and fingerprint verification. The local `duel-outbox/` must also be preserved until drained, including on MySQL installations.

Finished results first go through the dedicated file worker, then the ledger worker records the result and website event transactionally. HTTP uses the separate bounded publisher worker. Neither SQL nor HTTP runs on the main server thread. SQL/website outages can delay reporting; this is a completed-match feed, not live telemetry. Abruptly interrupted active matches are not resumed or synthesized into finished results.

Retries preserve event ID and exact body bytes. The shared publisher alternates due mob and duel events so one mode does not starve the other. See [WEBSITE_EXPORT.md](WEBSITE_EXPORT.md) for timeouts, quarantine, restore procedures and operator commands. Keep the source UUID and revision history through migrations/restores.

## Receiver acceptance checks

- Import both generated fixtures and verify team/roster/outcome fields in PostgreSQL and a recent-duels page.
- Replay identical bytes; verify one duel and one contribution per participant. Reject a changed body under the same event UUID.
- Apply a later backfill revision and then an older event; verify no duplicated aggregate contributions.
- Reject malformed types, invalid enum values, duplicate participants, invalid UUIDs, nonfinite/negative damage, negative counters and times outside the match.
- Keep mob-match contract tests passing and verify ordinary mob earnings/class progress do not include PvP kills.
- Test real staging delivery with authentication and durable acknowledgements, including a website outage/restart. Plugin tests alone do not establish deployed receiver acceptance.
