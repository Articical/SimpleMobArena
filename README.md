# Mineplay SimpleMobArena

Cooperative wave arenas inside an existing survival world, with personal equipment or configured classes, kill-share Vault rewards, durable recovery records, and SQLite or MySQL storage. The plugin never creates or resets worlds and does not build the arena for you.

This implementation targets **Paper 26.3**, which is the official version name for the requested “1.26.3”, with **Java 25**. The API is pinned to `26.3.build.200-stable`; the Gradle wrapper is pinned to 9.8.1 with distribution checksum verification. See [Paper's project setup](https://docs.papermc.io/paper/dev/project-setup/).

**Release status:** implementation and automated verification are separate from a live server acceptance test. Do not treat this as production approved until the inventory, restart, event ordering and Vault scenarios in [MANUAL_TESTS.md](MANUAL_TESTS.md) pass on your staging server with your actual plugins. No live Mineplay installation or economy is modified by the build.

## Build

Install a Java 25 JDK, set `JAVA_HOME`, then run:

```powershell
.\gradlew.bat clean build
```

On Linux/macOS use `sh ./gradlew clean build`. Build output is `build/libs/SimpleMobArena-1.0.10.jar`. This single JAR contains SQLite JDBC, its native libraries, MySQL Connector/J and Gson; do not deploy compiled files from the old Eclipse `bin` directory. Tests are in `src/test/java` and reports in `build/reports/tests/test/index.html`.

The existing `src/cloud/mineplay/mobarena` source layout and the `Arena` / `ArenaManager` concepts are retained. Resources live in `src/main/resources`. SQLite and all runtime dependencies are packaged by the `jar` task; the Paper API and Vault economy are supplied by the server.

For Eclipse, import or refresh this as a Gradle project using Buildship and a Java 25-capable Eclipse release. The old machine-specific Spigot JAR reference has been replaced with the Gradle dependency container.

For this checkout, a checksum-verified portable JDK and Gradle may exist under ignored `.tools`. They are build aids, not shipped dependencies or changes to the system Java installation.

## Install and configure an arena

1. Back up the server and player data. Test on a staging copy first.
2. Stop the server, copy the JAR into `plugins`, then start Paper 26.3 using Java 25.
3. Install Vault and an EssentialsX economy provider if rewards are wanted. Without a provider, gameplay remains available and payable settlements remain pending.
4. Review `plugins/SimpleMobArena/config.yml`, `classes.yml` and `arenas/*.yml`. Bundled example arenas are disabled and have no preset spawn coordinates. Configure them at your actual arenas. The separate [Spawn Arena configuration](fixed-config/spawn.yml) preserves the supplied server coordinates and is already enabled; install it only for that existing arena.
5. Configure the arena geometry and spawns. All arena locations refer to an existing, loaded world; positions use `world,x,y,z,yaw,pitch`.
6. Check `/ma admin setup <arena>` for the stored coordinates, height bounds, spawn counts and repair instructions. Resolve every validation error, then `/ma admin enable <arena> true`.
7. Review `/ma admin economy` before running rewarded tests. Adjust limits to your SMP economy.

Example in-game setup, while standing at each intended location:

```text
/ma admin create spawn
/ma admin setpos1 spawn
/ma admin setpos2 spawn
/ma admin setplayer spawn
/ma admin setmob spawn
/ma admin addmob spawn
/ma admin setexit spawn
/ma admin setoutsider spawn exit
/ma admin setup spawn
/ma admin enable spawn true
```

Skip `create` if `spawn` already exists. Stand at opposite corners for `setpos1` and `setpos2`, including **different Y heights**. Corners enclose a nonzero three-dimensional volume, at most 256 blocks across in X/Z. Include the floor, combat area and sufficient air above it. Stand safely inside for player/mob spawns, and safely outside every arena for `setexit`. Spawns and exits need clear standing room above a dry, harmless supporting surface. Slabs and stairs are checked using their actual collision shapes; stand on the intended surface before saving the location. Overlapping arenas are rejected.

Large mobs need suitable spawn pads: ravagers and iron golems need width, and endermen need roughly three blocks of height. At spawn time the plugin checks the mob's dimensions against block collision shapes and tries another configured point if needed. If none fits, the match is cancelled rather than leaving a mob stuck in blocks. Flying mobs need enough space inside the arena boundary to move and attack; configuring their type does not enlarge the arena.

`setplayer` and `setmob` **replace all existing points of that type** with your current location. Use `addplayer` or `addmob` for extra points. `clearspawns <arena> <player|mob|red|blue|all>` removes the selected list; `all` removes player, mob and both team spawn lists. Configuration edits require all arenas to be unoccupied. `setoutsider <arena> exit` clears any separate outsider destination so it follows `setexit`; omit `exit` to save a separate destination at your feet.

### Repairing an arena configured with version 1.0.0

Version 1.0.0's `setplayer` and `setmob` appended to the sample coordinates. Changing the boundaries could therefore leave old points such as `world,0.5,181,0.5` outside your new arena, even after setting a new spawn. A separate sample `outsider-exit` could also remain invalid. Updating the JAR preserves existing arena files; the following commands replace those stale points without recreating the arena or changing match history.

After stopping any occupied session, stand at the intended player spawn **inside** your configured boundary and run `/ma admin setplayer spawn`. Stand at your first mob spawn and run `/ma admin setmob spawn`; use `/ma admin addmob spawn` at each additional spawn. Then stand on a safe floor **outside every arena** and run:

```text
/ma admin setexit spawn
/ma admin setoutsider spawn exit
/ma admin setup spawn
/ma admin enable spawn true
```

Replace `spawn` with your arena ID. If setup still reports a boundary issue, check its X/Y/Z ranges and reset both corners so they enclose every spawn and have different Y heights. The setup checklist identifies each remaining problem and its repair command.

Use `/ma admin reload` after YAML changes. Reload/edit operations are rejected while an arena is occupied or players are joining; finish or stop those sessions first. Stopping a session cancels its rewards. Changes to invalid economy values disable payouts safely and are reported in the log.

Old plugin arena files containing only `spawns`, `mobTypes`, `difficulty` and `maxPlayers` are not sufficient for safe operation. Back them up, then recreate or explicitly migrate them to the new boundary, separate player/mob spawns, exit and validation fields. The plugin never guesses a cuboid or silently enables an old arena.

If `worldguard-region` is configured, WorldGuard must be available and that region must contain the arena cuboid; otherwise validation fails. The plugin does not create regions or change flags. WorldGuard protection still applies when this optional setting is absent. SimpleMobArena never overrides a cancelled spawn or combat event.

### WorldGuard: a wave starts and immediately cancels

WorldGuard's `mob-spawning` and `deny-spawn` flags also govern manually spawned mobs. A valid `/ma admin setup` checks arena geometry; it does not prove another plugin will permit a wave. Configure a dedicated WorldGuard region covering only the arena, including its full height and every mob spawn. For the examples below, that region is named `mobarena_spawn`; this is a WorldGuard region ID, which can differ from the MobArena ID. [WorldGuard flag definitions](https://worldguard.enginehub.org/en/latest/regions/flags/).

Stand at a mob spawn and run `/rg info` to identify overlapping regions. If the arena region does not exist, select the arena cuboid with WorldEdit and run `/rg define mobarena_spawn`. Then allow spawning there:

```text
/rg flag mobarena_spawn mob-spawning allow
```

If an overlapping spawn region denies spawning, give the arena region a **higher** priority. At equal priority, a state flag's `deny` wins. For example, use `/rg setpriority mobarena_spawn 100` only if 100 exceeds the denying regions' priorities. Raising priority affects region protection and membership as well, so check that ordinary players still cannot edit arena blocks. [WorldGuard priorities and inheritance](https://worldguard.enginehub.org/en/latest/regions/priorities/).

Inspect `/rg flags mobarena_spawn` and the overlapping regions for `deny-spawn`. `mob-spawning allow` does not cancel this separate list. If the arena should allow all its configured mob types, including `CHICKEN` for jockey mounts, set an explicit empty deny list on its region:

```text
/rg flag mobarena_spawn deny-spawn -e
```

Omitting the value **without** `-e` unsets the local flag, which can expose an inherited or lower-priority deny list again. An explicit empty value supplies the arena's override; keep its priority above conflicting regions. Alternatively, set a deliberate list that excludes every arena mob type. [WorldGuard flag commands](https://worldguard.enginehub.org/en/latest/regions/commands/#flag).

If WorldGuard also prevents mobs hurting participants, allow mob damage and turn off region invincibility only in the arena:

```text
/rg flag mobarena_spawn mob-damage allow
/rg flag mobarena_spawn invincible deny
```

The arena's priority must also override any overlapping `mob-damage deny` or `invincible allow` flags. If a participant remains immune, check their separate god-mode setting and test in survival mode. [WorldGuard combat and invincibility flags](https://worldguard.enginehub.org/en/latest/regions/flags/).

Leave WorldGuard's `block-plugin-spawning` enabled; changing region flags keeps the exception local. WorldGuard uses that setting to apply protection to mobs created by plugins. [WorldGuard configuration](https://worldguard.enginehub.org/en/latest/config/).

Start a new match after correcting the flags and verify both spawning and combat. Keep surrounding spawn protection in place. SimpleMobArena cancels a rejected wave without payout and logs the failure; it does not bypass the protection plugin.

Arena creepers clear their explosion's affected block list and cannot start fires. They can still damage participants when WorldGuard permits that damage. An accepted explosion that WorldGuard later cancels still retires its tracked creeper safely, so the wave does not fail with a missing-mob error. A cancelled explosion prime leaves the creeper alive. Neither path uncancels WorldGuard events or awards a player kill for a self-explosion.

When PlaceholderAPI is installed, the bundled expansion registers `%simplemobarena_arena%`, `state`, `wave`, `completed_waves`, `kills`, `pot`, `alive`, `estimated_reward`, `sessions`, `total_kills`, `highest_wave` and `total_earned` using that same prefix/suffix. Live values are sampled on the server thread every second; lifetime statistics refresh asynchronously at most once per minute. `total_earned` means confirmed earnings. Initial/unavailable cached values return `loading`; the cache covers online players. Estimated reward remains before eligibility and caps.

## Playing and administration

Version 1.0.10 restores creepers with block protection and expands the supported roster to 28 mob types, including llamas. It adds occasional meme rounds, rare rapid-fire skeletons, themed arena examples, class loadout previews and a separate staff entry/build permission. Existing configuration files are preserved: add the new entries to your chosen pool or use one of the complete local-pool examples below. Existing creeper entries are now active again and are validated; malformed creeper settings must be repaired. Drowned remain excluded.

Version 1.0.8 adds per-arena mob sets, potion kits, 1v1 duels and team duels. Existing arena files default to `mode: MOB_ARENA` and retain the global mob pool. Existing class/config files are preserved; merge the new fields below where needed. SQLite/MySQL ledger schema 5 upgrades automatically; retain backups for downgrades.

Version 1.0.9 isolates class configuration failures from inventory recovery. An invalid class is unavailable until repaired; valid classes continue loading. A malformed classes file disables class choices, while personal gear remains available only when the configured equipment mode permits it. An invalid equipment mode disables new joins. Actual snapshot/database recovery failures still trigger recovery protection. Correct the reported YAML path and run `/ma admin reload` while arenas are idle; reload reports remaining equipment errors and cancels stale class lookups.

`/mobarena` is the primary command; `/ma` is its alias. Run either with no arguments to open the arena inventory menu. Left-click an arena to choose equipment, or right-click for its details. The stats, matches, leaderboard and classes buttons open their own menus. Player information commands open the same screens; console commands retain text reports. Menus have back, refresh and page controls. Statistics load in the background; closing or replacing a menu discards its pending display update.

| Command | Permission | Behavior |
| --- | --- | --- |
| `/ma`, `/ma list`, `/ma info <arena>` | `mobarena.use` | Menu and arena information |
| `/ma join <arena> [personal\|class]` | `mobarena.join` | Open equipment selection, or join with an explicit unlocked choice |
| `/ma classes` | `mobarena.use` | Class unlocks and progress toward the next kill requirement |
| `/ma leave` | None | Leave safely, retaining earned kill contribution |
| `/ma stats` | `mobarena.stats` | Personal statistics |
| `/ma stats <cached name\|UUID>` | `mobarena.stats.others` | Another player's statistics |
| `/ma history [page]` | `mobarena.history` | Your paginated match and payout history |
| `/ma duels [page]` | `mobarena.history` | Your separate duel history, teams and results |
| `/ma history <cached name\|UUID> [page]` | `mobarena.history`, plus `mobarena.stats.others` for someone else | A player's paginated history |
| `/ma match <session UUID>` | `mobarena.match` | Full match roster, recorded names, contributions, pot, outcome and settlement states |
| `/ma top` | `mobarena.top` | Top players by kills, then highest wave; shows confirmed earnings |
| `/ma admin create/delete <arena>` | `mobarena.admin.create` / `.delete` | Create disabled arena / remove idle configuration |
| `/ma admin setplayer/setmob <arena>` | Matching `mobarena.admin.<action>` | Replace all player/mob spawns with your current location |
| `/ma admin addplayer/addmob <arena>` | Matching `mobarena.admin.<action>` | Append an extra player/mob spawn |
| `/ma admin clearspawns <arena> <player\|mob\|red\|blue\|all>` | `mobarena.admin.clearspawns` | Clear the selected list; all includes both team spawn lists |
| `/ma admin setexit/setpos1/setpos2 <arena>` | Matching `mobarena.admin.<action>` | Save the executing player's location |
| `/ma admin setoutsider <arena> [exit]` | `mobarena.admin.setoutsider` | Save a separate safe outsider exit; `exit` uses the arena exit instead |
| `/ma admin setup <arena>` | `mobarena.admin.setup` | Stored geometry, spawn counts, validation checklist and repair commands |
| `/ma admin enable <arena> <true\|false>` | `mobarena.admin.enable` | Enable or disable an idle arena |
| `/ma admin mode <arena> <MOB_ARENA\|DUEL\|TEAM_DUEL>` | `mobarena.admin.mode` | Change mode and disable the arena until setup is checked |
| `/ma admin mobset <arena> <name\|default>` | `mobarena.admin.mobset` | Save a named pool or global selection; a local waves.mobs override still takes precedence |
| `/ma admin teamselection <arena> <AUTO\|CHOOSE>` | `mobarena.admin.teamselection` | Set how teams are selected in a team duel |
| `/ma admin setteam <arena> <red\|blue>` | `mobarena.admin.setteam` | Replace the team's spawn list with your location |
| `/ma admin addteam <arena> <red\|blue>` | `mobarena.admin.addteam` | Add another spawn for the team |
| `/ma admin start <arena>` | `mobarena.admin.start` | Start once minimum participation and valid spawns are satisfied |
| `/ma admin stop <arena>` | `mobarena.admin.stop` | Cancel without payout and recover equipment |
| `/ma admin forcewave <arena>` | `mobarena.admin.forcewave` | Skip an intermission only; never rewards an unfinished wave |
| `/ma admin reload` | `mobarena.admin.reload` | Validate and reload idle configuration |
| `/ma admin status <arena>` | `mobarena.admin.status` | State, session UUID and setup checklist |
| `/ma admin economy` | `mobarena.admin.economy` | Provider and payout status |
| `/ma admin npc create [name]` | `mobarena.admin.npc` | Create a Citizens player NPC at your location and link the arena menu |
| `/ma admin npc bind <id>` | `mobarena.admin.npc` | Link an existing Citizens NPC to the arena menu |
| `/ma admin npc unbind <id>` | `mobarena.admin.npc` | Remove the menu link while retaining the Citizens NPC |
| `/ma admin npc list` | `mobarena.admin.npc` | List saved arena menu NPC links |
| `/ma admin payouts <session UUID>` | `mobarena.admin.payouts` | Settlement IDs, amounts, states, caps and provider errors |
| `/ma admin reconcile <settlement UUID> [CONFIRM\|VOID]` | `mobarena.admin.reconcile` | Inspect instructions or record the verified outcome of an uncertain deposit |

Player use/join/personal-stats/history/match/top permissions default to true. Other-player stats and administrative command permissions default to operators. `mobarena.admin` grants the administrative command children; it does not grant `mobarena.admin.bypass`. Console can run administrative and financial commands except commands needing an in-game location. Original `create`, `setspawn`, `start`, `stop` and `forcewave` spellings are accepted as aliases; `setspawn` now replaces mob spawns like `setmob`. Use YAML for maximum player counts.

### Staff access during matches

`mobarena.admin.bypass` allows a staff member who is not playing to enter and build inside an occupied arena. It defaults to **false**, including for operators, and is separate from `mobarena.admin`. Grant it explicitly to the account or staff group that needs it. For example, with LuckPerms:

```text
/lp user <name> permission set mobarena.admin.bypass true
```

Remove that grant with `/lp user <name> permission unset mobarena.admin.bypass`. WorldGuard and other plugins still control their own building restrictions. This permission does not allow outsiders to damage arena mobs or players, assist combat with potions, earn contributions, or take arena drops. A staff member who joins a match follows the normal participant rules. Pending equipment recovery also keeps its protections; the permission cannot bypass recovery.

## Session and equipment rules

### Mob sets for each arena

Define reusable pools under `mob-sets` in `config.yml`, then select one with `/ma admin mobset <arena> undead` or `mob-set: undead` in the arena file:

```yaml
mob-sets:
  undead:
    mobs:
      ZOMBIE: {unlock-wave: 1, weight: 30}
      HUSK: {unlock-wave: 2, weight: 10}
      SKELETON: {unlock-wave: 3, weight: 12}
```

Only the listed types belong to that pool. Each row inherits that type's global `waves.mobs` settings, including gear and variant chances. An arena's own `waves.mobs` replaces the selected pool entirely; its rows inherit the corresponding global mob settings. Changing `mobset`, including choosing `default`, preserves that local override and reports when it remains active. To use the saved named/global selection, remove the arena's `waves.mobs` section and reload while idle. Setup shows the effective pool and saved selection. Named sets are case-sensitive and accept letters, numbers, underscores and hyphens. Equipment lists replace the inherited list for that slot; use `[]` to clear one. Unknown sets or malformed pools prevent that arena starting. Creepers are supported; drowned remain excluded. Ranged mobs still start at wave three or later.

Other `waves` settings can also be overridden per arena, including counts, spawn batches, intermission, time limits, healing and player health scaling:

```yaml
# In arenas/spawn.yml
mob-set: undead
waves:
  base-mobs: 8
  mobs-per-wave-per-player: 2
  player-health-scaling:
    additional-player-multiplier: 0.2
  mobs: # Optional: replaces undead with this arena's own pool.
    ZOMBIE:
      unlock-wave: 1
      weight: 20
      baby-chance: 0.1
      equipment:
        hand:
          - {material: STONE_SWORD, chance: 0.5}
        off_hand:
          - {material: SHIELD, chance: 0.1}
    SPIDER: {unlock-wave: 1, weight: 5}
```

Existing top-level arena `max-waves` and `max-active-mobs` take precedence over the corresponding `waves` values. Reload while arenas are idle after editing YAML.

Supported mob names are:

| Group | YAML entity names |
| --- | --- |
| Undead | `ZOMBIE`, `HUSK`, `SKELETON`, `STRAY`, `WITHER_SKELETON`, `ZOMBIFIED_PIGLIN` |
| Ground creatures | `SPIDER`, `CAVE_SPIDER`, `SILVERFISH`, `ENDERMITE`, `CREEPER` |
| Raiders | `PILLAGER`, `VINDICATOR`, `WITCH`, `RAVAGER`, `EVOKER` |
| Nether mobs | `PIGLIN`, `PIGLIN_BRUTE`, `BLAZE` |
| End and aerial mobs | `ENDERMAN`, `SHULKER`, `VEX`, `PHANTOM`, `BREEZE` |
| Normally neutral mobs | `IRON_GOLEM`, `GOAT`, `LLAMA`, `BEE` |

The earliest effective unlock is wave three for `SKELETON`, `STRAY`, `BLAZE`, `PILLAGER`, `WITCH`, `WITHER_SKELETON`, `SHULKER`, `BREEZE`, `EVOKER`, `PIGLIN`, `PHANTOM` and `LLAMA`. This protects the opening waves even when an older file sets an earlier unlock; later unlocks remain unchanged. It also applies to piglins configured with melee weapons. At least one other configured type must be available on wave one.

Weights are relative chances among currently unlocked types, not percentages or fixed spawn counts. Earlier types remain in the pool as new types unlock. A weight-one ravager is a rare possibility, not a guaranteed boss. `difficulty-multiplier` scales health; mob count follows `base-mobs + wave × mobs-per-wave-per-player × starting players`, capped by `max-mobs-per-wave`.

### Complete arena examples

These files use their own `waves.mobs` pools and do not require named sets in `config.yml`. Copy the desired file into `plugins/SimpleMobArena/arenas/`, configure its actual geometry and spawns, then reload while all arenas are idle. The four bundled examples remain disabled until configured and enabled. Fresh installations receive them automatically; updating the JAR preserves the existing arena directory.

| File | Waves / difficulty | Pool and limits |
| --- | --- | --- |
| [training-example.yml](src/main/resources/arenas/training-example.yml) | 12 / 0.75 | Zombies, spiders, husks, goats, llamas and bees; 24 concurrent, 36 per wave |
| [raiders-example.yml](src/main/resources/arenas/raiders-example.yml) | 25 / 1.15 | Vindicators, pillagers, witches, creepers, evokers and rare golems/ravagers; 36 concurrent, 80 per wave |
| [nether-example.yml](src/main/resources/arenas/nether-example.yml) | 25 / 1.10 | Zombified piglins, piglins, wither skeletons, blazes and piglin brutes; 32 concurrent, 72 per wave |
| [sky-example.yml](src/main/resources/arenas/sky-example.yml) | 20 / 1.20 | Endermites, endermen, bees, breezes, phantoms, vexes and shulkers; 24 concurrent, 48 per wave; needs flight space and ranged equipment |
| [fixed-config/spawn.yml](fixed-config/spawn.yml) | 30 / 1.00 | The supplied Spawn Arena, with every coordinate and exit preserved, all 28 types unlocked by wave 20, 40 concurrent and 100 per wave |

The separate Spawn Arena file is already enabled and keeps one to four players and the 15-second countdown. Its milder health growth is 10% every five waves plus 10% per additional starting player, capped at 1.3 for player scaling. With four starters, wave 30 has 100 planned mobs; an ordinary zombie has 39 health points and a rare golem/ravager has 117. Its existing ten-block-high boundary is unchanged, so check flying encounters and large spawn pads in game. Rewards, payout eligibility and class kits still come from the global economy and equipment configuration.

### Meme rounds and rare skeletons

The new default configuration and complete Spawn Arena have a 12% chance of a meme round from wave four: Goat Stampede, Llama Drama or Bee Swarm. The theme is chosen once when the wave begins and applies to every spawn batch in that wave. Three ordinary waves must pass before another theme can occur; a theme on wave four makes wave eight the next eligible wave. Normal mob count, health scaling and reward rules still apply.

```yaml
# In config.yml or an individual arena file.
waves:
  meme-rounds:
    enabled: true
    chance: 0.12
    minimum-wave: 4
    cooldown-waves: 3
    types:
      goats: {name: 'Goat Stampede', weight: 1, mobs: [GOAT]}
      llamas: {name: 'Llama Drama', weight: 1, mobs: [LLAMA]}
      bees: {name: 'Bee Swarm', weight: 1, mobs: [BEE]}
```

Every mob in a theme must already belong to that arena's resolved pool and be unlocked for the current wave. A theme cannot introduce goats into a nether-only arena or bypass the early-wave ranged restriction. If no theme qualifies, the wave is ordinary. Theme `weight` controls the relative choice after the probability roll; a multi-type theme uses the existing mob weights within its chosen subset. Missing meme settings keep older installations disabled. Set `waves.meme-rounds.enabled: false` to turn the feature off. Training enables the three default themes, Sky Trials allows Bee Swarm, and the raider/nether examples disable them.

Bow-equipped `SKELETON` mobs can also become a rare rapid-fire variant. The new default and Spawn Arena configure `rapid-fire: {chance: 0.02, minimum-wave: 8, interval-ticks: 8}` inside the skeleton's mob row: a 2% chance from wave eight, with eight ticks between shots. It still uses ordinary arrows, line of sight and a valid active target inside its own arena. Sword-equipped skeletons and other skeleton types do not receive this variant. Set its `chance` to `0` to disable it; older rows without the setting keep normal skeleton behavior.

### Duels and team duels

`DUEL` is one player per team with automatic assignment. `TEAM_DUEL` has two teams of `duel.team-size` players (default 2, maximum 16), using `duel.team-selection: AUTO` or `CHOOSE`. AUTO assigns the next player to the smaller team. CHOOSE opens red/blue selection after choosing equipment; `/ma join <arena> <class|personal> <red|blue>` also works. A full team rejects extra joins, including equipment preparations still in progress. Both teams must be full to start; ordinary `min-players`/`max-players` are ignored for duel modes.

```yaml
# Arena-specific fields; keep the existing world, cuboid and safe exit.
mode: TEAM_DUEL
duel:
  team-size: 2
  team-selection: CHOOSE
  maximum-seconds: 300
  red-spawns: []  # Set these in game with setteam/addteam.
  blue-spawns: []
```

For a new arena, use `/ma admin create courtyard`, `/ma admin mode courtyard TEAM_DUEL`, the usual two corner commands, `/ma admin setteam courtyard red`, `/ma admin setteam courtyard blue`, and `/ma admin setexit courtyard`. Check `/ma admin setup courtyard`, then enable it. Mob spawns are not required. Team spawn points must be safe and inside the cuboid; multiple points are supported. Players wait at the configured player spawns (or combined team spawns when none are set), then move to their team's spawns when the match starts. Disabled sample files are packaged in the JAR and saved on fresh installations.

Duels are single-round elimination matches. Friendly fire and outsider damage are blocked. Death, leaving, disconnecting or crossing the boundary removes a player; the last team with players alive wins. The time limit ends in a draw, and admin stop records a cancellation. There is no rejoin after a match starts. Duels use the same equipment recovery and command restrictions as mob arenas, but have no waves or cash payouts. PvP kills and damage are stored separately from mob statistics and class progression. `/ma duels` and the main menu's Recent duels button show the roster, teams, winner and each player's performance; `/ma match <UUID>` opens either match type.

Multiple mob arenas and duels can run concurrently. They have separate state, teams, countdowns, timers and tagged projectiles. Enabled cuboids cannot overlap and exits must remain outside them. Neighbouring arenas that share a Minecraft chunk retain its plugin ticket until the last arena finishes.

WorldGuard still controls whether damage is allowed. In the dedicated duel region, allow `pvp` and deny `invincible`, with priority above conflicting regions. Do not disable surrounding spawn protection. Cancelled protection events stay cancelled.

### Drinkable and splash potion kits

Add potions to empty slots in existing `classes.yml` kit definitions. `POTION` is drinkable; `SPLASH_POTION` is throwable. Use a Paper potion type such as `HEALING`, `STRONG_HEALING`, `SWIFTNESS` or `LONG_SWIFTNESS`:

An old item field such as `potion-type: SWIFTNESS` must become `potion: {base-type: SWIFTNESS}`. The supplied corrected twelve-class server configuration is [fixed-config/classes.yml](fixed-config/classes.yml); it preserves the supplied kit balance and unlock thresholds and works with 1.0.8 or later.

```yaml
items:
  '5':
    material: POTION
    potion:
      base-type: HEALING
  '6':
    material: SPLASH_POTION
    potion:
      base-type: WATER
      color: '#FF4477'
      effects:
        regeneration:
          amplifier: 0
          duration-ticks: 100
```

Custom effect amplifiers are zero-based, from 0 to 10; duration must be a positive whole number of ticks (20 ticks per second, use 1 for an instant effect). Optional `ambient`, `particles` and `icon` flags are booleans. A custom-only potion may omit `base-type` and uses WATER. Invalid types or effects fail validation instead of silently giving water. Potions occupy separate slots; lingering potions are rejected because arena clouds are disabled. Class equipment recovery preserves potion metadata.

In duels, helpful splash potions affect the thrower and teammates, while harmful splashes affect opponents. A mixed potion counts as harmful. Splash effects cannot cross arenas or reach outsiders, and throws from departed players are blocked. Helpful potions also work between mob waves. Fresh class files include healing potion examples; existing class files are not overwritten.

The central session state follows `WAITING → COUNTDOWN → ACTIVE → INTERMISSION → … → ENDING → FINISHED → WAITING`. A countdown falls back to waiting when too few players remain. Late joins and rejoining are rejected during ACTIVE and INTERMISSION. Disconnecting removes participation while retaining earned contribution. Participants are identified by UUID, not retained player objects. Each session has a unique ID.

Mob count and health use the participant count captured at match start. Eliminations, voluntary departures and disconnects do not lower either value for the remaining waves or later spawn batches. A new match takes a fresh count. Outsiders, spectators and players in other arenas are excluded.

```text
mobs = min(maxMobsPerWave, baseMobs + wave × mobsPerWavePerPlayer × startingPlayers)
playerHealthMultiplier = min(2.5, 1 + (startingPlayers - 1) × 0.15)
health = min(1024, baseHealth × mobHealthMultiplier × difficultyMultiplier
         × (1 + floor((wave - 1) / healthIncreaseEveryWaves) × healthIncreasePercent / 100)
         × playerHealthMultiplier)
```

The existing mob-count formula is retained. Health now gains 15% per player beyond the first, up to 2.5 times the solo health; this stacks with wave growth, the mob's own multiplier and arena difficulty. With default settings, wave one has:

| Starting players | Mobs | Zombie health points |
| --- | --- | --- |
| 1 | 6 | 20 |
| 2 | 7 | 23 |
| 4 | 9 | 29 |

Tune `waves.player-health-scaling.additional-player-multiplier` and `maximum-multiplier` in `config.yml`, or set `waves.player-health-scaling.enabled: false` to keep only the existing wave/mob/difficulty health scaling. Missing keys in older configs use the new defaults automatically. Invalid boolean, nonnumeric, nonfinite or out-of-range settings prevent arena entry and appear in the validation errors. Reload while arenas are idle to apply changes. Reward scaling and recorded starting-player counts retain their existing behavior.

Spawns are spread across ticks and the active-mob cap includes tracked chicken mounts and summoned vexes. Supported mob types, unlock waves, weights and health multipliers are YAML settings. Zombies, husks and skeletons can use configured equipment variants; shield equipment does not imply custom shield-blocking AI. Rare zombie chicken jockeys are tracked and removed with their riders; `chicken-jockey-chance` applies to `ZOMBIE` only.

Creepers explode without breaking arena blocks. Their self-explosions retire the tracked mob after damage resolves, without crediting a player kill. Endermen cannot pick up or place blocks, and arena mob block changes are blocked. Drowned remain excluded: legacy `waves.mobs.DROWNED` entries are ignored without rewriting the file. Arena transformations are blocked, so zombies cannot turn into drowned; piglins are kept immune to zombification. WorldGuard's spawning and damage decisions remain in force.

Arena zombies, husks, skeleton-family mobs and phantoms have daylight burning disabled during spawn setup. A second ignition guard protects owned mobs and clears pending fire ticks if sunlight tries to ignite them. This protection leaves fire/lava contact and attack-driven ignition unchanged; unrelated survival mobs keep their normal behavior.

Normally neutral mobs are directed at their arena's active players: golems attack, goats ram, llamas spit, piglins fight even when a participant wears gold, and bees remain angry and able to sting. Bees do not pollinate crops or enter hives. Endermen and flying mobs remain tied to the arena; arena raiders do not join ordinary raids. These changes apply only to owned arena mobs.

Evokers may have up to **three living summoned vexes each**, also limited by the arena's combined active-mob cap. Summons belong to their caster and are removed when it dies, when the wave finishes or when the match ends. They do not add planned wave enemies, player kills, damage contribution, class progress or reward share. A `VEX` selected directly from `waves.mobs` is an ordinary wave enemy and counts normally. Fangs and projectiles carry their session ownership; stale or cross-arena effects cannot assist another match.

Enabled arena boundaries block unrelated creature spawns even while no match is running, including natural spawning, spawners, eggs and other plugins. Only mobs tagged for that exact arena's current session can spawn inside. Disabled arenas and locations outside enabled boundaries keep normal spawning; this rule does not remove creatures already present.

Only registered current-session mobs count. Arena mobs drop no items or XP, so class and mob gear cannot be farmed into the survival economy. Deaths without an eligible active player killer receive no kill credit. Disappearing/invalid tracked mobs, failed spawns or a wave timeout cancel the session without payment. This conservative policy avoids turning noncombat mob removal into a reward exploit. Administrators should correct a stuck encounter instead of using commands to manufacture wave completion.

PERSONAL retains owned gear with normal combat consumption and wear by default. `equipment.personal.preserve-durability` prevents wear; `restore-consumables` refunds supported arrows, food and potions on exit while removing newly created empty containers. CLASS replaces equipment only after a durable original snapshot exists. BOTH offers either choice. `classes.yml` supplies Knight, Archer, Tank and Berserker and supports materials, stack amounts, enchantments, armor, offhand and potion effects.

Classes can require a saved all-time arena kill count. Fresh installations use Knight **0**, Archer **100**, Tank **500** and Berserker **1,000**. Each class accepts `display-name`, an item `icon`, and a nonnegative whole-number `required-kills`. These are lifetime kills recorded from finished arena matches, including recorded contributions to cancelled matches; ordinary survival kills do not count. Current-match kills become available after the result is saved. There is no separate unlock table: changing a threshold changes who can use that class.

**Upgrading an existing server:** existing `classes.yml` files are preserved. A class without `required-kills` stays available at zero kills. To enable the default progression, add the following field inside each existing class definition, alongside `display-name`, and run `/ma admin reload` while arenas are idle:

```yaml
# Merge these fields into the existing definitions; keep all kit items and effects.
classes:
  knight:
    required-kills: 0
  archer:
    required-kills: 100
  tank:
    required-kills: 500
  berserker:
    required-kills: 1000
```

Locked classes show their requirement and the player's progress. Direct commands and menu clicks use the same unlock check before touching inventory. Positive requirements are checked asynchronously against storage; failed or timed-out checks cannot equip the class. Players can cancel a pending check with `/ma leave`. Personal equipment and zero-kill classes do not require a successful statistics lookup.

Class menus include a loadout preview for equipment, items, potions and effects before confirming the choice. Previewing a class does not equip it or unlock it. Confirmation still checks the current class, permission, saved kill requirement, arena state and equipment recovery conditions.

Normal lethal damage is cancelled and becomes elimination. Remaining participants continue. Voluntary leavers and eliminated/disconnected players keep their recorded contribution. Item dropping, collection, containers, transfers, block edits, escape, cross-arena combat and outsider attacks are constrained to keep the physical arena isolated from SMP. Non-arena commands are blocked during participation, including alternate inventory/teleport commands. Configure `outsider-exit` using `/ma admin setoutsider` to move nonparticipants safely outside every arena. Class state is restored on exit/reconnect; a recovery failure pauses interaction and retains the saved inventory for administrator attention.

Eliminated players receive an **ELIMINATED** title and two chat lines showing kills, damage, time, wave reached and completed waves. This also works for the last/only player. Players leaving an ongoing match are told their reward will be worked out at the end. Surviving players get their stats when the match finishes. Once the result is saved, online participants see their queued reward or the reason no reward was earned. A separate paid message follows only after the Vault deposit and its confirmation are saved. Routine notices omit session IDs and repeated history prompts. Offline players can inspect `/ma history` and `/ma match` on return.

Waves one and two exclude ranged-capable mobs. Skeletons, strays, drowned, blazes, pillagers, witches and wither skeletons have an effective minimum unlock wave of three; configured later unlocks remain in effect. This applies to older configuration files as well, without rewriting them. Keep at least one melee mob, such as a zombie or spider, configured for wave one.

Tridents are melee-only in the MVP; retrieve any already-thrown trident or fishing hook before joining. Empty the crafting grid and cursor before joining so closing a crafting view cannot spill items from a full inventory. Fishing, bundles and wind charges are blocked during participation. These restrictions prevent equipment transfer, hidden-ammunition refunds and escapes. Paid ammunition loaded into a crossbow is included when computing optional consumable refunds; multishot counts as one paid projectile.

Snapshots include inventory, armor, offhand, XP, level, effects, health, food, game mode and original location, plus supporting movement/status values. Restoration replaces slots rather than adding items. The exact restore target is persisted before application, player data is saved while interaction is locked, then the snapshot is confirmed. Confirmed snapshots are not replayed. There is no atomic transaction spanning Minecraft player files and SQLite; process-crash and external-plugin interaction tests remain required.

## Citizens arena menu NPCs

With Citizens enabled, stand at a safe lobby location outside every enabled arena and run:

```text
/ma admin npc create MobArena
```

This creates and saves a Citizens player NPC at your location and links it to the existing arena selection GUI. Players right-click it, select an arena, then choose personal equipment or a class. The usual join permissions, match state, capacity and equipment recovery checks still apply. NPC interaction requires both `mobarena.use` and `mobarena.join`, which are available to players by default. Participants and players with equipment recovery pending cannot use an NPC to bypass the normal match flow.

To use an NPC you already have, run `/ma admin npc bind <Citizens NPC id>`. Place it outside the arena boundary so the arena's outsider protection does not affect it. `/ma admin npc list` shows links; `/ma admin npc unbind <id>` removes a link without deleting that NPC. The admin commands require `mobarena.admin.npc`, included in `mobarena.admin` and available to operators by default. Creation requires an in-game player; binding, unbinding and listing also work from the console.

Bindings are saved in `plugins/SimpleMobArena/npc-menus.yml`, using the persistent Citizens NPC UUID as identity and retaining its numeric ID for administration. Citizens owns the NPC's appearance, position and persistence. If creation or saving fails, the integration attempts to remove the new NPC and reports any cleanup failure in the console. Linked right-clicks open the menu once even if duplicate click events arrive; the interaction is consumed so Citizens command traits do not also run. Keep both plugin data directories when backing up or moving the server.

Citizens is optional: its absence or an unavailable API does not disable ordinary arena commands. Install a Citizens build compatible with your Paper server, then restart. This integration uses the documented [Citizens NPC API](https://wiki.citizensnpcs.co/API); it does not bundle Citizens or register NPCs as arena combat mobs.

## Announcements and sounds

Joins, match starts and match ends appear in public chat by default, with the arena name so concurrent arenas are distinguishable. Join messages offer `/ma join <arena>` as a clickable suggestion to eligible outsiders while spaces remain; clicking fills the chat input for the player to submit. Full arenas show that they are full. Wave updates, personal stats and reward receipts remain with their existing recipients. Leaderboard and personal-stat earnings display a `$` prefix; numeric storage and PlaceholderAPI values remain numeric.

The countdown appears as a center-screen **STARTING IN Ns** title only to that arena's participants, with the arena name and player count underneath. It never broadcasts countdown chat, including when an older config still contains `announcements.public.countdown: true`; that legacy setting is ignored. The initial countdown is shown once when enough players have joined. Further titles use `announcements.countdown-seconds`, defaulting to `[30, 15, 10, 5, 3, 2, 1]`. With the usual 15-second lobby, players see 15, 10, 5, 3, 2 and 1. The countdown follows the actual arena deadline, pauses with a private title/explanation when membership falls below the minimum, and restarts with the full duration once enough players join again. Delayed ticks produce one current-time title rather than a burst of missed countdowns. A zero-second lobby skips the countdown title. Participants see **FIGHT!** only when the match actually starts, after outstanding equipment preparation finishes. Leaving or cancelling a lobby clears its countdown title.

Set individual `announcements.public.join`, `start` or `end` values to `false` to keep that event's chat inside the arena. An empty countdown milestone list disables later countdown titles/cues but retains the initial title. Idle arenas do not announce cancellations during reload/shutdown.

Sound cues are sent only to participants: joining, countdown milestones, match start, later wave starts, credited mob kills, wave completion, elimination, match completion and cancellation. The first wave shares the match-start cue, and the final wave shares the match-end cue. The kill cue is a soft, higher-pitched experience pickup sound sent only to the credited killer. Duplicate death events, unrelated mobs, environmental deaths and ineligible killers do not trigger it. Eliminated players hear their own elimination cue even though they have already left the active set. Each event occurs once; sound or chat delivery failures cannot interrupt match cleanup or payout handling.

Use `sounds.enabled: false` to disable all cues, or set `sounds.<event>.enabled: false` for one cue. Event keys are `join`, `countdown`, `match-start`, `wave-start`, `wave-complete`, `mob-kill`, `eliminated`, `match-end` and `cancelled`. Each accepts a namespaced `sound` key, `volume` from 0 to 2 and `pitch` from 0.5 to 2. The packaged config lists all defaults. Sound keys must exist on the player's client or resource pack; an unknown client sound is silent, as described by [Paper's player sound API](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Player.html). Invalid configuration values log a warning and use safe defaults.

Existing configurations gain these defaults without being overwritten. To customize them, add the relevant keys to `config.yml`, then run `/ma admin reload` while all arenas are idle. Changing announcement settings does not alter an arena's `countdown-seconds` duration.

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

The default $100 pot after wave one is **not yet payable**: the team must complete three waves, and each rewarded player needs at least 60 seconds of active participation and credited kills. Joining messages explain these requirements; wave-completion messages show progress toward the wave minimum. Earlier eliminated players can still qualify if the team later completes enough waves, subject to their own participation, cooldown and caps. A solo player's normal elimination ends the match and settles its earned pot under these same rules. Update `economy.reward.minimum-reward-wave` and `economy.limits.minimum-participation-seconds` to rebalance these requirements; player messages use the validated configured values.

Daily limits reset at **00:00 UTC**. Pending, processing and uncertain payments continue reserving daily capacity across days until resolved. Cooldown eligibility is measured from a previous rewarded/reserved session's finish to the next session's start. Disabled payouts, cancellations, failed starts and failed waves never create a new payable reward. The optional short-session multiplier can reduce very short runs.

`economy.enabled: false` disables new reward allocations and pauses pending deposits while allowing gameplay. Missing Vault/providers pause pending payments. The payment provider must support the configured precision. Vault's deposit method requires `double`; conversion occurs only at that boundary, never in balance calculations.

`economy.payment-delay-seconds` defaults to **3** (including when absent from an older config). Set a whole number from 0 to 30; 0 removes the pause. The ledger worker waits for the deadline before claiming a queued payment, without sleeping or blocking the server thread. The once-per-second payment poll and other queued payouts can add time. Pending rewards stay durable through restart; the brief pause starts afresh after restart. Reward amounts, eligibility and duplicate-payment protections are unchanged. The server thread performs only the Vault call; ledger work stays on its background worker.

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

Create the database and dedicated plugin account beforehand; the plugin creates/version-controls its tables and reporting views. Set the password environment variable in the server process environment. When `password-env` is nonempty it must exist; otherwise the `password` field is used. Credentials are never included in loggable settings. `use-ssl: true` requires a trusted certificate matching the host (`VERIFY_IDENTITY`), not merely encryption. The website receives authenticated HTTPS snapshots and does not need the plugin's database credentials.

Storage is selected at startup. Stop the server before changing it. Changing the YAML alone does not copy existing records; migrate first to retain statistics, reward caps, cooldowns, payout history and outstanding equipment recovery. One plugin server instance must own each backend; this is not a coordinated cross-server match system.

### Offline migration

The `migrateStorage` Gradle task copies between selected backends, including SQLite to MySQL, MySQL to SQLite and file-to-file copies. It requires a completely empty destination, preserves the source, and includes equipment snapshots, audit records, mob and duel results, website source identity, revisions, exact queued payload bytes and delivery/backfill metadata. First upgrade the source to ledger schema 5 by running this plugin with website export disabled, then stop it. Keep both the server and any other database writer/publisher stopped for the entire migration.

1. Stop Paper cleanly and back up the complete plugin directory and matching player data.
2. Create source and target copies of the full `config.yml` with their respective `storage` sections. Do not switch the server's active configuration yet.
3. Create an empty target database/directory, supply both connection credentials through environment variables, then run:

```powershell
.\gradlew.bat migrateStorage --args='--source-config C:/migration/source.yml --target-config C:/migration/target.yml --source-dir C:/server/plugins/SimpleMobArena --target-dir C:/migration/target-data --server-stopped'
```

4. Check the tool's validation result before switching the active storage configuration. For a SQLite destination, place the migrated files in the plugin data directory while Paper is still stopped. Preserve the source backup.
5. Start the server, verify `/ma admin economy`, sample `/ma stats` and `/ma history` results, and test an outstanding equipment recovery before reopening gameplay.

Do not use migration to merge nonempty databases. If it fails, durable migration guards block use of the incomplete destination; retain both source and destination evidence and investigate before retrying. Migration refuses undrained settlement or duel outboxes. Restore database connectivity, start the plugin long enough to drain them, then stop it before migrating. Do not run the source and target as two active copies of the same plugin state: that would duplicate pending payment opportunities.

### Reporting and match history

Plugin 1.0.6 includes a durable HTTPS publisher for `https://www.mineplay.co.uk/api/integrations/simple-mob-arena/v1/match-snapshots`. See [WEBSITE_EXPORT.md](WEBSITE_EXPORT.md) for source/token setup, admin commands, retry behavior, backfill, thread boundaries and restore procedures. Export defaults to disabled until the website's source ID and credential are configured. The website retains its own PostgreSQL copy; the plugin remains on SQLite or MySQL.

Give the website implementation agent both [WEBSITE_DATA_HANDOFF.md](WEBSITE_DATA_HANDOFF.md), which describes source data and reporting semantics, and [WEBSITE_EXPORT.md](WEBSITE_EXPORT.md), which describes the implemented publisher. `/ma admin website status`, `backfill` and `retry <event UUID>` require `mobarena.admin.website`. Export SQL runs on the ledger worker and HTTP delivery on a separate bounded worker; website outages do not wait inside payment transactions.

Version 1.0.8 also publishes separate `duel.snapshot` events to `https://www.mineplay.co.uk/api/integrations/simple-mob-arena/v1/duel-snapshots`. Give the website developer [DUELS_API_HANDOFF.md](DUELS_API_HANDOFF.md) to implement that new receiver and PostgreSQL reporting. It includes teams, winner, full roster, PvP kills, damage and participation time. The mob-match payload remains unchanged. Duel results use a local `duel-outbox/` before the schema 5 tables; preserve and drain it alongside `settlement-outbox/` during backups and migration.

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
