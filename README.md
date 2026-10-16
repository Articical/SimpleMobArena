# Mineplay SimpleMobArena

Cooperative wave arenas inside an existing survival world, with personal equipment or configured classes, kill-share Vault rewards, durable recovery records, and SQLite or MySQL storage. The plugin never creates or resets worlds and does not build the arena for you.

This implementation targets **Paper 26.3**, which is the official version name for the requested “1.26.3”, with **Java 25**. The API is pinned to `26.3.build.200-stable`; the Gradle wrapper is pinned to 9.8.1 with distribution checksum verification. See [Paper's project setup](https://docs.papermc.io/paper/dev/project-setup/).

**Release status:** implementation and automated verification are separate from a live server acceptance test. Do not treat this as production approved until the inventory, restart, event ordering and Vault scenarios in [MANUAL_TESTS.md](MANUAL_TESTS.md) pass on your staging server with your actual plugins. No live Mineplay installation or economy is modified by the build.

## Build

Install a Java 25 JDK, set `JAVA_HOME`, then run:

```powershell
.\gradlew.bat clean build
```

On Linux/macOS use `sh ./gradlew clean build`. Build output is `build/libs/SimpleMobArena-1.0.0.jar`. This single JAR contains SQLite JDBC, its native libraries and MySQL Connector/J; do not deploy compiled files from the old Eclipse `bin` directory. Tests are in `src/test/java` and reports in `build/reports/tests/test/index.html`.

The existing `src/cloud/mineplay/mobarena` source layout and the `Arena` / `ArenaManager` concepts are retained. Resources live in `src/main/resources`. SQLite and all runtime dependencies are packaged by the `jar` task; the Paper API and Vault economy are supplied by the server.

For Eclipse, import or refresh this as a Gradle project using Buildship and a Java 25-capable Eclipse release. The old machine-specific Spigot JAR reference has been replaced with the Gradle dependency container.

For this checkout, a checksum-verified portable JDK and Gradle may exist under ignored `.tools`. They are build aids, not shipped dependencies or changes to the system Java installation.

## Install and configure an arena

1. Back up the server and player data. Test on a staging copy first.
2. Stop the server, copy the JAR into `plugins`, then start Paper 26.3 using Java 25.
3. Install Vault and an EssentialsX economy provider if rewards are wanted. Without a provider, gameplay remains available and payable settlements remain pending.
4. Review `plugins/SimpleMobArena/config.yml`, `classes.yml` and `arenas/*.yml`. The supplied arena is disabled and its illustrative coordinates must be replaced with your actual arena above spawn.
5. Configure the arena geometry and spawns. All arena locations refer to an existing, loaded world; positions use `world,x,y,z,yaw,pitch`.
6. Check `/ma admin status <arena>`, resolve every validation error, then `/ma admin enable <arena> true`.
7. Review `/ma admin economy` before running rewarded tests. Adjust limits to your SMP economy.

Example in-game setup, while standing at each intended location:

```text
/ma admin create spawn
/ma admin setpos1 spawn
/ma admin setpos2 spawn
/ma admin setplayer spawn
/ma admin setmob spawn
/ma admin setexit spawn
/ma admin setoutsider spawn
/ma admin status spawn
/ma admin enable spawn true
```

`setplayer` and `setmob` append spawn points. To remove or reorder them, edit the arena YAML while no session is occupied, then reload. Corners must enclose a nonzero three-dimensional volume, at most 256 blocks across in X/Z. Include the floor, combat area and sufficient air above it. Player and mob spawn points must be inside; the exit must be outside. Spawns and exit need two clear blocks above a solid floor. Overlapping arenas are rejected.

Use `/ma admin reload` after YAML changes. Reload/edit operations are rejected while an arena is occupied or players are joining; finish or stop those sessions first. Stopping a session cancels its rewards. Changes to invalid economy values disable payouts safely and are reported in the log.

Old plugin arena files containing only `spawns`, `mobTypes`, `difficulty` and `maxPlayers` are not sufficient for safe operation. Back them up, then recreate or explicitly migrate them to the new boundary, separate player/mob spawns, exit and validation fields. The plugin never guesses a cuboid or silently enables an old arena.

The arena runs independently of spawn protection. If `worldguard-region` is configured, WorldGuard must be available and that region must contain the arena cuboid; otherwise validation fails. The plugin does not create regions or change flags. Configure flags to permit required mob spawning, combat and teleports; cancellation by other protection plugins is respected.

When PlaceholderAPI is installed, the bundled expansion registers `%simplemobarena_arena%`, `state`, `wave`, `completed_waves`, `kills`, `pot`, `alive`, `estimated_reward`, `sessions`, `total_kills`, `highest_wave` and `total_earned` using that same prefix/suffix. Live values are sampled on the server thread every second; lifetime statistics refresh asynchronously at most once per minute. `total_earned` means confirmed earnings. Initial/unavailable cached values return `loading`; the cache covers online players. Estimated reward remains before eligibility and caps.

## Playing and administration

`/mobarena` is the primary command; `/ma` is its alias. Run either with no arguments to open the arena inventory menu. Left-click an arena and select personal equipment or a class. Menus show live information at the time they are opened; Refresh updates it. Estimated rewards are before eligibility/caps and are never guarantees.

| Command | Permission | Behavior |
| --- | --- | --- |
| `/ma`, `/ma list`, `/ma info <arena>` | `mobarena.use` | Menu and arena information |
| `/ma join <arena> [personal\|class]` | `mobarena.join` | Join; defaults to personal when available, otherwise opens class selection |
| `/ma leave` | None | Leave safely, retaining earned kill contribution |
| `/ma stats` | `mobarena.stats` | Personal statistics |
| `/ma stats <cached name\|UUID>` | `mobarena.stats.others` | Another player's statistics |
| `/ma history [page]` | `mobarena.history` | Your paginated match and payout history |
| `/ma history <cached name\|UUID> [page]` | `mobarena.history`, plus `mobarena.stats.others` for someone else | A player's paginated history |
| `/ma match <session UUID>` | `mobarena.match` | Full match roster, recorded names, contributions, pot, outcome and settlement states |
| `/ma top` | `mobarena.top` | Top players by kills, then highest wave; shows confirmed earnings |
| `/ma admin create/delete <arena>` | `mobarena.admin.create` / `.delete` | Create disabled arena / remove idle configuration |
| `/ma admin setplayer/setmob/setexit/setpos1/setpos2 <arena>` | Matching `mobarena.admin.<action>` | Save the executing player's location |
| `/ma admin setoutsider <arena>` | `mobarena.admin.setoutsider` | Set a safe outsider ejection location outside every arena |
| `/ma admin enable <arena> <true\|false>` | `mobarena.admin.enable` | Enable or disable an idle arena |
| `/ma admin start <arena>` | `mobarena.admin.start` | Start once minimum participation and valid spawns are satisfied |
| `/ma admin stop <arena>` | `mobarena.admin.stop` | Cancel without payout and recover equipment |
| `/ma admin forcewave <arena>` | `mobarena.admin.forcewave` | Skip an intermission only; never rewards an unfinished wave |
| `/ma admin reload` | `mobarena.admin.reload` | Validate and reload idle configuration |
| `/ma admin status <arena>` | `mobarena.admin.status` | State, session UUID and validation problems |
| `/ma admin economy` | `mobarena.admin.economy` | Provider and payout status |
| `/ma admin payouts <session UUID>` | `mobarena.admin.payouts` | Settlement IDs, amounts, states, caps and provider errors |
| `/ma admin reconcile <settlement UUID> [CONFIRM\|VOID]` | `mobarena.admin.reconcile` | Inspect instructions or record the verified outcome of an uncertain deposit |

Player use/join/personal-stats/history/match/top permissions default to true. Other-player stats and all administrative permissions default to operators. `mobarena.admin` grants every administrative child permission. Console can run administrative and financial commands except commands needing an in-game location. Original `create`, `setspawn`, `start`, `stop` and `forcewave` spellings are accepted as aliases; `setspawn` means mob spawn. Use YAML for maximum player counts.

## Session and equipment rules

The central session state follows `WAITING → COUNTDOWN → ACTIVE → INTERMISSION → … → ENDING → FINISHED → WAITING`. A countdown falls back to waiting when too few players remain. Late joins and rejoining are rejected during ACTIVE and INTERMISSION. Disconnecting removes participation while retaining earned contribution. Participants are identified by UUID, not retained player objects. Each session has a unique ID.

Each wave uses the participant count captured at match start:

```text
mobs = min(maxMobsPerWave, baseMobs + wave × mobsPerWavePerPlayer × startingPlayers)
health = baseHealth × mobHealthMultiplier × difficultyMultiplier
         × (1 + floor((wave - 1) / healthIncreaseEveryWaves) × healthIncreasePercent / 100)
```

Spawns are spread across ticks and the active-mob cap includes tracked mounts. Supported mob types, unlock waves, weights and health multipliers are YAML settings. Zombies, skeletons and drowned can use configured equipment variants; shield equipment does not imply custom shield-blocking AI. Rare zombie chicken jockeys are tracked and removed with their riders. Arena zombies and skeletons do not burn in sunlight. Ordinary SMP mobs keep their normal behavior.

Only registered current-session mobs count. Arena mobs drop no items or XP, so class and mob gear cannot be farmed into the survival economy. Deaths without an eligible active player killer receive no kill credit. Disappearing/invalid tracked mobs, failed spawns or a wave timeout cancel the session without payment. This conservative policy avoids turning noncombat mob removal into a reward exploit. Administrators should correct a stuck encounter instead of using commands to manufacture wave completion.

PERSONAL retains owned gear with normal combat consumption and wear by default. `equipment.personal.preserve-durability` prevents wear; `restore-consumables` refunds supported arrows, food and potions on exit while removing newly created empty containers. CLASS replaces equipment only after a durable original snapshot exists. BOTH offers either choice. `classes.yml` supplies Knight, Archer, Tank and Berserker and supports materials, stack amounts, enchantments, armor, offhand and potion effects.

Normal lethal damage is cancelled and becomes elimination. Remaining participants continue. Voluntary leavers and eliminated/disconnected players keep their recorded contribution. Item dropping, collection, containers, transfers, block edits, escape, cross-arena combat and outsider attacks are constrained to keep the physical arena isolated from SMP. Non-arena commands are blocked during participation, including alternate inventory/teleport commands. Configure `outsider-exit` using `/ma admin setoutsider` to move nonparticipants safely outside every arena. Class state is restored on exit/reconnect; a recovery failure pauses interaction and retains the saved inventory for administrator attention.

Tridents are melee-only in the MVP; retrieve any already-thrown trident or fishing hook before joining. Empty the crafting grid and cursor before joining so closing a crafting view cannot spill items from a full inventory. Fishing, bundles and wind charges are blocked during participation. These restrictions prevent equipment transfer, hidden-ammunition refunds and escapes. Paid ammunition loaded into a crossbow is included when computing optional consumable refunds; multishot counts as one paid projectile.

Snapshots include inventory, armor, offhand, XP, level, effects, health, food, game mode and original location, plus supporting movement/status values. Restoration replaces slots rather than adding items. The exact restore target is persisted before application, player data is saved while interaction is locked, then the snapshot is confirmed. Confirmed snapshots are not replayed. There is no atomic transaction spanning Minecraft player files and SQLite; process-crash and external-plugin interaction tests remain required.

## Rewards and limits

Default completed-wave reward is `100 × wave × min(2, 1 + (startingPlayers - 1) × 0.25)`. Disable player scaling if desired. With scaling disabled, completing waves 1–10 accumulates 5,500; 1–30 accumulates 46,500 before caps. Partial waves add nothing.

Each participant's base share is their eligible kills divided by all eligible session kills, including contributions from earlier leavers/eliminated players. All calculations use `BigDecimal` or integer minor units. Largest remainders receive remaining minor units, with UUID ordering breaking ties. Uncapped allocations sum exactly to the distributable pot. Per-player caps and eligibility deductions are removed, never redistributed. Zero kills means no payout; the unallocated pot remains recorded.

| Default control | Value |
| --- | --- |
| Maximum session pot | 50,000 |
| Maximum player payment per session | 25,000 |
| Maximum player daily payments | 50,000 |
| Reward cooldown | 15 minutes |
| Minimum completed wave | 3 |
| Minimum active participation | 60 seconds |
| Currency precision | 2 decimal places |

Daily limits reset at **00:00 UTC**. Pending, processing and uncertain payments continue reserving daily capacity across days until resolved. Cooldown eligibility is measured from a previous rewarded/reserved session's finish to the next session's start. Disabled payouts, cancellations, failed starts and failed waves never create a new payable reward. The optional short-session multiplier can reduce very short runs.

`economy.enabled: false` disables new reward allocations and pauses pending deposits while allowing gameplay. Missing Vault/providers pause pending payments. The payment provider must support the configured precision. Vault's deposit method requires `double`; conversion occurs only at that boundary, never in balance calculations.

## Ledger, recovery and backups

### Selecting file or MySQL storage

SQLite is the default file-based backend and needs no database server. Choose MySQL for centrally accessible reporting. An example MySQL configuration is:

```yaml
storage:
  type: MYSQL
  mysql:
    host: db.example.internal
    port: 3306
    database: mobarena
    username: mobarena
    password: ''
    password-env: MOB_ARENA_DB_PASSWORD
    use-ssl: true
```

Create the database and dedicated plugin account beforehand; the plugin creates/version-controls its tables and reporting views. Set the password environment variable in the server process environment. When `password-env` is nonempty it must exist; otherwise the `password` field is used. Credentials are never included in loggable settings. `use-ssl: true` requires a trusted certificate matching the host (`VERIFY_IDENTITY`), not merely encryption. Use a read-only account for a future website, not the plugin's writer credentials.

Storage is selected at startup. Stop the server before changing it. Changing the YAML alone does not copy existing records; migrate first to retain statistics, reward caps, cooldowns, payout history and outstanding equipment recovery. One plugin server instance must own each backend; this is not a coordinated cross-server match system.

### Offline migration

The `migrateStorage` Gradle task copies between selected backends, including SQLite to MySQL, MySQL to SQLite and file-to-file copies. It requires a completely empty destination, preserves the source, and includes the equipment snapshots and audit records. Keep both the server and any other database writer stopped for the entire operation.

1. Stop Paper cleanly and back up the complete plugin directory and matching player data.
2. Create source and target copies of the full `config.yml` with their respective `storage` sections. Do not switch the server's active configuration yet.
3. Create an empty target database/directory, supply both connection credentials through environment variables, then run:

```powershell
.\gradlew.bat migrateStorage --args='--source-config C:/migration/source.yml --target-config C:/migration/target.yml --source-dir C:/server/plugins/SimpleMobArena --target-dir C:/migration/target-data --server-stopped'
```

4. Check the tool's validation result before switching the active storage configuration. For a SQLite destination, place the migrated files in the plugin data directory while Paper is still stopped. Preserve the source backup.
5. Start the server, verify `/ma admin economy`, sample `/ma stats` and `/ma history` results, and test an outstanding equipment recovery before reopening gameplay.

Do not use migration to merge nonempty databases. If it fails, durable migration guards block use of the incomplete destination; retain both source and destination evidence and investigate before retrying. Migration refuses an undrained settlement outbox. Restore database connectivity, start the plugin long enough to drain the outbox, then stop it before migrating. Do not run the source and target as two active copies of the same plugin state: that would duplicate pending payment opportunities.

### Reporting and match history

The SQL views `mobarena_leaderboard`, `mobarena_match_performance` and `mobarena_player_payout_history` expose reporting data for a future website. Use player UUIDs as identity; recorded names are labels. Intended payouts and confirmed earnings are separate. Only CONFIRMED payments count as earned money. Do not sum every intended settlement regardless of state, and do not use binary floating point for website money calculations. The leaderboard exposes confirmed earnings in integer minor units for aggregation.

The in-game `/ma history` command shows recent per-player match results and `/ma match` shows a full stored match, including the roster, recorded names, kill/damage contributions, participation time, pot and cancellation reason. Reporting views are read-only interfaces for consumers; write through the plugin's state machines only. The plugin does not create or publish a website.

With `storage.type: SQLITE`, `mobarena.db` stores sessions, participants, settlements and an append-only settlement audit trail. Money is stored as decimal text. Its versioned migrations are packaged under `db/`. `equipment.db` stores durable equipment snapshots and restoration targets, created by `SnapshotStore` with its versioned schema. Both use SQLite WAL and `synchronous=FULL`. With `MYSQL`, both stores use tables in the configured database; the same durable state machines apply.

Payment states:

| State | Meaning and recovery |
| --- | --- |
| PENDING | Intended amount is durable; safe to attempt when a provider is available |
| PROCESSING | Claim was committed before invoking Vault |
| CONFIRMED | Provider reported the expected successful amount, or an operator verified it |
| UNCERTAIN | Outcome may already have succeeded; **never automatically retried** |
| FAILED | Provider explicitly reported failure; no automatic retry |
| WITHHELD | Ineligible, capped to zero, disabled or cancelled |
| VOID | Operator verified an uncertain payment was not made |

On startup, leftover PROCESSING rows become UNCERTAIN. UUID constraints stop duplicate session and participant settlements. There is **no exactly-once guarantee** across a process crash because Vault and the plugin's SQL backend cannot share a transaction.

Completed match results first enter a local, atomically written and fsynced `settlement-outbox` before database delivery. A dedicated file worker keeps that local handoff independent of blocked SQL connections; shutdown drains local writes before closing SQL. SQL connection recovery and outbox replay are retried every 30 seconds; the session UUID prevents duplicate settlement insertion. Preserve this directory even with MySQL storage. If both the database and the local outbox are unavailable, the plugin cannot guarantee durable recording; resolve the storage error before continuing rewarded gameplay.

Active matches are not resumed after an abrupt crash. Only finished results that have reached their durable outbox/ledger handoff can be recovered for settlement; an interrupted active match does not create a restart payout. Equipment recovery is independent and still restores outstanding snapshots. A graceful server stop cancels active sessions without reward.

To reconcile, obtain the session/settlement UUIDs from the console audit or `/ma admin payouts`. Compare the intended amount and timestamps with the underlying economy provider's records. Use `CONFIRM` only when the deposit is verified, and `VOID` only when it is verified absent. Neither action deposits money. Do not retry or manually add an uncertain amount based only on the player's current balance. A provider-reported failure stays recorded for administrator review; any replacement payment is an explicit separate operational decision.

Back up the entire plugin data folder together with matching world player data. Stop the server cleanly for a simple consistent copy; otherwise use a SQLite-aware backup procedure and preserve `-wal`/`-shm` files. Do not delete databases to clear an error: that can discard recovery evidence, caps and original inventory snapshots. Missing recovery worlds or corrupted snapshots require restoring the correct world/data, not creating a new world with the same name.

Database work uses background workers where safe. Bukkit inventory/world access and Vault calls remain on the server thread. Startup/shutdown recovery performs bounded synchronization because releasing unconfirmed equipment is unsafe.

## Verification boundaries

The automated suite checks reward arithmetic, eligibility/caps, settlement persistence and crash states, snapshot fencing, and pure arena lifecycle behavior. It does not prove client GUI behavior, actual Minecraft item serialization, all third-party event interactions, real EssentialsX deposits, or recovery after a server process is terminated at every possible instant.

The optional live database test uses `MOB_ARENA_MYSQL_TEST_PORT`, `MOB_ARENA_MYSQL_TEST_USER` (default `root`) and `MOB_ARENA_MYSQL_TEST_PASSWORD` against **127.0.0.1 only**. Run it on a disposable MySQL instance; its account must create/drop the generated `mobarena_test_<UUID>` database. It validates real schema creation, payout states, reporting, snapshot bytes and a SQLite → MySQL → SQLite round trip. Without the port environment variable this one integration test is skipped. Other tests use temporary local files and mocked Paper objects.

Use [MANUAL_TESTS.md](MANUAL_TESTS.md) for acceptance tests and retain server logs, database copies, before/after inventory evidence and provider transaction records. Test using fictional in-game currency only. No real-world payment integration, wagering or separate economy is included.
