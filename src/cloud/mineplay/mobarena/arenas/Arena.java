package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.cevents.*;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.config.SpawnSafety;
import cloud.mineplay.mobarena.economy.SessionResult;
import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.duels.DuelTeams;
import cloud.mineplay.mobarena.duels.DuelTeams.Team;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.mobs.MobSpawn;
import cloud.mineplay.mobarena.mobs.MobBehavior;
import cloud.mineplay.mobarena.mobs.MobWaveSelector;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import cloud.mineplay.mobarena.messages.MatchMessages;
import cloud.mineplay.mobarena.messages.ArenaAnnouncements;
import cloud.mineplay.mobarena.messages.ArenaSounds;
import cloud.mineplay.mobarena.messages.CountdownSchedule;
import cloud.mineplay.mobarena.messages.ArenaSounds.Cue;
import cloud.mineplay.mobarena.session.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;

/** Main-thread session coordinator. Events announce transitions; they never spawn waves. */
public final class Arena {
    private final SimpleMobArena plugin;
    private final ArenaDefinition definition;
    private final ArenaAnnouncements announcements;
    private final ArenaSounds sounds;
    private final CountdownSchedule countdown;
    private SessionState state = new SessionState();
    private final Map<UUID,Participation> participants = new LinkedHashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private final Set<UUID> temporary = new HashSet<>();
    private final Map<UUID,UUID> mounts = new HashMap<>();
    private final Map<UUID,UUID> summonedMobs = new HashMap<>();
    private final Set<UUID> explodingMobs = new HashSet<>();
    private UUID sessionId = UUID.randomUUID();
    private Instant startedAt;
    private int wave, completedWaves, startingPlayers, preparing;
    private int lastMemeWave;
    private List<ArenaDefinition.MobRule> waveMobPool = List.of();
    private String waveTheme = "";
    private long deadline, nextSpawn, waveStarted;
    private BigDecimal prizePot = BigDecimal.ZERO;
    private boolean lobbyAdvertised;
    private final DuelTeams teams = new DuelTeams();
    private final Set<UUID> duelEliminated = new HashSet<>();
    private final Map<UUID, HitCredit> recentHits = new HashMap<>();
    private String duelWinner = "DRAW";
    private record HitCredit(UUID attacker, long tick) { }

    public Arena(SimpleMobArena plugin, ArenaDefinition definition) {
        this.plugin = plugin; this.definition = definition;
        announcements = new ArenaAnnouncements(plugin,definition.id,definition.name);
        sounds = new ArenaSounds(plugin);
        CountdownSchedule configured;
        try { configured = new CountdownSchedule(plugin.getConfig()); }
        catch (IllegalArgumentException error) {
            plugin.getLogger().warning(error.getMessage()+"; using default countdown announcement times.");
            configured = new CountdownSchedule(new YamlConfiguration());
        }
        countdown = configured;
    }
    public String getId() { return definition.id; }
    public String getName() { return definition.name; }
    public YamlConfiguration getConfiguration() { return definition.config; }
    public ArenaDefinition definition() { return definition; }
    public GameState getArenaState() { return state.state(); }
    public int getMaxPlayers() { return definition.maxPlayers; }
    public int getCurrentWave() { return wave; }
    public String getCurrentWaveTheme() { return waveTheme; }
    public int getCompletedWaves() { return completedWaves; }
    public int getStartingPlayerCount() { return startingPlayers; }
    public UUID getSessionId() { return sessionId; }
    public BigDecimal getPrizePot() { return prizePot; }
    public int getKills(UUID player) { Participation p = participants.get(player); return p == null ? 0 : p.kills; }
    public int getTotalKills() { return participants.values().stream().mapToInt(p -> p.kills).sum(); }
    public List<String> getValidationErrors() { return List.copyOf(definition.errors); }
    public Set<UUID> getActiveMobs() { return state.mobs(); }
    public boolean isCombatMob(UUID id) { return state.mobs().contains(id) || summonedMobs.containsKey(id); }
    public Set<UUID> activeIds() { return state.active(); }
    public int reservedCount() { return preparing; }
    public boolean isDuel() { return definition.mode.isDuel(); }
    public Team team(UUID player) { return teams.team(player); }
    public int teamCount(Team team) { return teams.count(team); }
    public boolean opponents(UUID a, UUID b) {
        return isDuel() && state.state() == GameState.ACTIVE && isParticipant(a) && isParticipant(b) && teams.opponents(a,b);
    }
    public void duelHit(UUID attacker, UUID victim, double damage) {
        if (!opponents(attacker,victim) || !Double.isFinite(damage) || damage <= 0) return;
        damage(attacker,damage);
        recentHits.put(victim,new HitCredit(attacker,plugin.ticks()));
    }
    public boolean contains(Location location) { return definition.contains(location); }
    public boolean isParticipant(UUID id) { return state.active().contains(id); }
    public boolean owns(Entity entity) {
        return sessionId.toString().equals(entity.getPersistentDataContainer().get(plugin.sessionKey(), PersistentDataType.STRING));
    }
    public Set<Player> getPlayers() {
        Set<Player> players = new LinkedHashSet<>();
        for (UUID id : state.active()) { Player p = Bukkit.getPlayer(id); if (p != null) players.add(p); }
        return players;
    }
    public boolean isRunning() { return state.state() != GameState.WAITING && state.state() != GameState.FINISHED; }
    public boolean isAccepting() { return state.state() == GameState.WAITING || state.state() == GameState.COUNTDOWN; }
    public void reserve() {
        if (!definition.enabled) throw new IllegalStateException("Arena is disabled; an administrator must enable it.");
        if (!definition.errors.isEmpty()) throw new IllegalStateException("Arena configuration is invalid: " + String.join("; ", definition.errors));
        if (!isAccepting()) throw new IllegalStateException("This session has already started.");
        if (state.active().size()+preparing >= definition.maxPlayers) throw new IllegalStateException("Arena is full.");
        preparing++;
    }
    public void releaseReservation() { preparing = Math.max(0, preparing-1); }
    public void reserve(UUID player, String requestedTeam) {
        reserve();
        try {
            if (isDuel()) teams.reserve(player,requestedTeam,definition.chooseTeams,definition.teamSize);
            else if (requestedTeam != null) throw new IllegalArgumentException("Teams are only available in duel arenas.");
        } catch (RuntimeException error) { releaseReservation(); throw error; }
    }
    public void releaseReservation(UUID player) { releaseReservation(); teams.remove(player); }
    public void joined(Player player) {
        releaseReservation();
        if (!isAccepting() || !state.join(player.getUniqueId())) throw new IllegalStateException("Session no longer accepts players.");
        participants.put(player.getUniqueId(), new Participation(player.getUniqueId(),player.getName()));
        try {
            player.closeInventory();
            List<Location> spawns = definition.playerSpawns;
            if (!player.teleport(spawns.get((state.active().size()-1)%spawns.size()))) {
                throw new IllegalStateException("Arena teleport was refused.");
            }
            heal(player);
        } catch (RuntimeException exception) {
            state.eliminate(player.getUniqueId()); participants.remove(player.getUniqueId()); teams.remove(player.getUniqueId());
            throw exception;
        }
        lobbyAdvertised = true;
        announcements.joined(player,state.active().size(),definition.maxPlayers,getPlayers());
        sounds.play(Cue.JOIN,getPlayers());
        sendPersonalFeedback(player,() -> player.sendMessage(isDuel()
            ? ArenaMessages.info("You're on " + teams.team(player.getUniqueId()).label() + " team. Last team standing wins. No cash rewards in duels.")
            : MatchMessages.rewardRequirements(plugin.getEconomyService().rewardSettings())));
        if (state.state() == GameState.WAITING && state.active().size() >= definition.minPlayers) {
            state.transition(GameState.COUNTDOWN);
            deadline = plugin.ticks()+definition.countdownSeconds*20L;
            countdown.reset(definition.countdownSeconds);
            if (definition.countdownSeconds > 0) announceCountdown(definition.countdownSeconds);
        }
    }
    public void startArena() {
        if (!isAccepting()) throw new IllegalStateException("Arena is already running.");
        if (!definition.enabled || !definition.errors.isEmpty()) throw new IllegalStateException("Enable and validate the arena first.");
        if (preparing != 0) throw new IllegalStateException("Players are still saving equipment. Try again shortly.");
        if (state.active().size() < definition.minPlayers) throw new IllegalStateException("Not enough participants to start.");
        if (!isDuel() && (definition.mobSpawns.isEmpty() || definition.mobSpawns.stream().anyMatch(l -> !ArenaDefinition.safe(l))))
            throw new IllegalStateException("No valid mob spawns; check the arena floor and clearance.");
        if (isDuel() && (teams.alive(Team.RED,state.active()) != definition.teamSize || teams.alive(Team.BLUE,state.active()) != definition.teamSize))
            throw new IllegalStateException("Both teams must be full before this duel starts.");
        if (state.state() == GameState.WAITING) state.transition(GameState.COUNTDOWN);
        try { holdChunks(); } catch (RuntimeException e) { finish(true,"Could not load arena chunks."); return; }
        if (isDuel()) {
            try {
                for (Team team : Team.values()) {
                    List<Location> spawns = teamSpawns(team);
                    if (spawns.stream().anyMatch(location -> !ArenaDefinition.safe(location))) throw new IllegalStateException("A team spawn is no longer safe.");
                    int index = 0;
                    for (Player player : getPlayers()) if (teams.team(player.getUniqueId()) == team) {
                        if (!player.teleport(spawns.get(index++ % spawns.size()))) throw new IllegalStateException("A team teleport was blocked.");
                        heal(player);
                    }
                }
            } catch (RuntimeException error) { finish(true,error.getMessage()); return; }
        }
        startedAt = Instant.now(); startingPlayers = state.active().size();
        participants.values().forEach(p -> p.start(startedAt));
        state.transition(GameState.ACTIVE);
        countdown.reset(0);
        announcements.started(startingPlayers,getPlayers());
        sounds.play(Cue.MATCH_START,getPlayers());
        Bukkit.getPluginManager().callEvent(new ArenaStartEvent(this));
        if (isDuel()) {
            deadline = plugin.ticks() + definition.duelMaxSeconds * 20L;
            for (Team team : Team.values()) {
                String roster = participants.values().stream().filter(p -> teams.team(p.playerId) == team)
                    .map(p -> p.playerName).collect(java.util.stream.Collectors.joining(", "));
                announce(ArenaMessages.info(Component.text(team.label()+": ",team == Team.RED ? NamedTextColor.RED : NamedTextColor.BLUE)
                    .append(Component.text(roster,NamedTextColor.WHITE))));
            }
        } else beginWave();
    }
    private List<Location> teamSpawns(Team team) {
        if (team == null) throw new IllegalStateException("Choose a team before joining this arena.");
        return team == Team.RED ? definition.redSpawns : definition.blueSpawns;
    }
    private void holdChunks() {
        for (int x = Math.min(definition.pos1.getBlockX(),definition.pos2.getBlockX()) >> 4;
             x <= (Math.max(definition.pos1.getBlockX(),definition.pos2.getBlockX()) >> 4); x++)
            for (int z = Math.min(definition.pos1.getBlockZ(),definition.pos2.getBlockZ()) >> 4;
                 z <= (Math.max(definition.pos1.getBlockZ(),definition.pos2.getBlockZ()) >> 4); z++) {
                Chunk chunk = definition.world.getChunkAt(x,z);
                if (!tickets.contains(chunk)) { ArenaChunkTickets.acquire(plugin,chunk); tickets.add(chunk); }
            }
    }
    private void beginWave() {
        wave++;
        var eligible = definition.mobs.stream().filter(m -> wave >= m.unlockWave()).toList();
        var selection = MobWaveSelector.select(definition.memeRounds,eligible,wave,lastMemeWave,
            java.util.concurrent.ThreadLocalRandom.current());
        waveMobPool = selection.mobs(); waveTheme = selection.name();
        if (selection.meme()) lastMemeWave = wave;
        long count = definition.baseMobs+(long)wave*definition.perWavePerPlayer*startingPlayers;
        state.beginWave((int)Math.min(definition.maxMobsPerWave,count));
        nextSpawn = plugin.ticks(); waveStarted = nextSpawn;
        announce(ArenaMessages.info(Component.text("Wave "+wave,NamedTextColor.GOLD,TextDecoration.BOLD)
            .append(Component.text((waveTheme.isEmpty() ? "" : " • "+waveTheme)+" • "+state.pendingSpawns()+" mobs incoming!",NamedTextColor.GRAY).decoration(TextDecoration.BOLD,false))));
        if (wave > 1) sounds.play(Cue.WAVE_START,getPlayers());
    }
    private void announceCountdown(int seconds) {
        Set<Player> players = getPlayers();
        announcements.countdown(seconds,state.active().size(),definition.maxPlayers,players);
        sounds.play(Cue.COUNTDOWN,players);
    }
    public void tick(long tick) {
        if (state.state() == GameState.COUNTDOWN) {
            int remaining = (int)Math.max(0,(deadline-tick+19)/20);
            if (countdown.advance(remaining)) announceCountdown(remaining);
        }
        if (state.state() == GameState.COUNTDOWN && tick >= deadline && preparing == 0) {
            try { startArena(); } catch (RuntimeException e) { finish(true,e.getMessage()); }
        } else if (state.state() == GameState.INTERMISSION && tick >= deadline) {
            state.transition(GameState.ACTIVE); beginWave();
        }
        if (state.state() == GameState.ACTIVE && isDuel() && tick >= deadline) {
            duelWinner = "DRAW"; finish(false,"TIME_LIMIT"); return;
        }
        if (state.state() == GameState.ACTIVE && !isDuel()) {
            if (tick-waveStarted >= definition.maxWaveSeconds*20L) { finish(true,"Wave timed out; no payout."); return; }
            if (tick >= nextSpawn) {
                nextSpawn = tick+definition.spawnIntervalTicks;
                for (int i=0; i<definition.spawnBatchSize && state.pendingSpawns()>0 && liveMobCount()<definition.maxActiveMobs; i++) {
                    try { spawnMob(); } catch (RuntimeException e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Arena "+getId()+" wave "+wave+" spawn failed (session "+sessionId+"): "+e.getMessage(),e);
                        finish(true,e instanceof SpawnRejectedException
                            ? "Mob spawning was blocked. Check WorldGuard mob-spawning / deny-spawn flags and region priorities; session cancelled without payout."
                            : "Wave failed to spawn; session cancelled without payout. Check the server console for the cause.");
                        return;
                    }
                }
            }
            if (tick%20 == 0) for (UUID id : combatMobs()) {
                if (explodingMobs.contains(id)) continue;
                Entity entity = Bukkit.getEntity(id);
                if (summonedMobs.containsKey(id) && (!(entity instanceof Mob) || !entity.isValid()
                        || !owns(entity) || !state.mobs().contains(summonedMobs.get(id)))) {
                    removeSummonedMob(id); continue;
                }
                if (!(entity instanceof Mob mob) || !entity.isValid() || !owns(entity)) {
                    finish(true,"A tracked mob disappeared. Session cancelled without payout."); return;
                }
                if (!contains(mob.getLocation())) {
                    Entity vehicle = mob.getVehicle();
                    if (vehicle != null) { mob.leaveVehicle(); vehicle.remove(); mounts.remove(id); }
                    Location safe = fittingSpawn(mob,definition.mobSpawns.getFirst());
                    if (safe == null || !mob.teleport(safe)) { finish(true,"Could not contain an arena mob."); return; }
                }
                Player target = getPlayers().stream().filter(p -> contains(p.getLocation()))
                    .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(mob.getLocation()))).orElse(null);
                Location anchor = target == null ? definition.mobSpawns.getFirst().clone() : target.getLocation().clone();
                anchor.setY(Math.min(anchor.getY()+2,Math.max(definition.pos1.getY(),definition.pos2.getY())-1));
                MobBehavior.retarget(mob,target,anchor,tick);
            }
        }
        if (tick%20 == 0) for (UUID id : new HashSet<>(temporary)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) { temporary.remove(id); continue; }
            if ((entity instanceof Projectile || entity instanceof EvokerFangs || entity instanceof AreaEffectCloud)
                    && !contains(entity.getLocation())) {
                entity.remove(); temporary.remove(id);
            }
        }
        if (tick%20 == 0) for (Player p : getPlayers()) {
            if (!contains(p.getLocation())) { eliminate(p,"left the arena boundary"); continue; }
            p.sendActionBar(actionBar(tick,p.getUniqueId()));
        }
    }
    private void spawnMob() {
        List<ArenaDefinition.MobRule> choices = waveMobPool;
        int sum = choices.stream().mapToInt(ArenaDefinition.MobRule::weight).sum();
        int pick = java.util.concurrent.ThreadLocalRandom.current().nextInt(sum);
        ArenaDefinition.MobRule chosen = choices.getFirst();
        for (var rule : choices) { pick -= rule.weight(); if (pick < 0) { chosen = rule; break; } }
        Location location = definition.mobSpawns.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(definition.mobSpawns.size()));
        String safetyProblem = SpawnSafety.problem(location);
        if (safetyProblem != null) throw new IllegalStateException("Mob spawn at "+ArenaDefinition.write(location)+": "+safetyProblem);
        @SuppressWarnings("unchecked") Class<? extends Mob> type = (Class<? extends Mob>)chosen.type().getEntityClass();
        ArenaDefinition.MobRule rule = chosen;
        Mob mob = definition.world.spawn(location, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, false, entity -> {
            tag(entity); entity.setPersistent(false); entity.setRemoveWhenFarAway(false); entity.setCanPickupItems(false);
            var attribute = entity.getAttribute(Attribute.MAX_HEALTH);
            if (attribute == null) throw new IllegalStateException("Mob lacks health attribute.");
            // Starting membership stays fixed for every spawn, including after eliminations/disconnects.
            double playerHealth = definition.playerHealthScaling
                ? Math.min(definition.maxPlayerHealthMultiplier,1+Math.max(0,startingPlayers-1)*definition.healthPerAdditionalPlayer) : 1;
            double health = Math.min(1024,attribute.getBaseValue()*rule.healthMultiplier()*definition.difficulty
                *(1+((wave-1)/definition.healthEvery)*definition.healthIncrease)*playerHealth);
            attribute.setBaseValue(health); entity.setHealth(health);
            MobSpawn.equip(entity,definition.mobSettings(rule.type()));
            MobBehavior.configure(entity);
            MobBehavior.configureVariant(entity,definition.mobSettings(rule.type()),wave,target ->
                state.state()==GameState.ACTIVE && owns(entity) && isParticipant(target.getUniqueId())
                    && contains(target.getLocation()) && contains(entity.getLocation()));
        });
        if (!mob.isValid() || mob.isDead()) {
            mob.remove(); temporary.remove(mob.getUniqueId());
            throw new SpawnRejectedException(rule.type(),location);
        }
        Location fitting = fittingSpawn(mob,location);
        if (fitting == null || (!fitting.equals(location) && !mob.teleport(fitting))) {
            mob.remove(); temporary.remove(mob.getUniqueId());
            throw new IllegalStateException("No mob spawn has enough clear space for "+rule.type()+". Add a wider/taller spawn inside the arena.");
        }
        // Reapply after spawn listeners have finished initializing the entity.
        MobSpawn.protectFromSunlight(mob);
        mob.setFireTicks(0);
        state.spawned(mob.getUniqueId());
        if (mob.getType() == EntityType.ZOMBIE && mob instanceof Zombie zombie
            && liveMobCount()<definition.maxActiveMobs
            && MobSpawn.roll(definition.mobSettings(EntityType.ZOMBIE).getDouble("chicken-jockey-chance",.015))) {
            Chicken mount = fitting.getWorld().spawn(fitting,Chicken.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,false,chicken -> {
                tag(chicken); chicken.setPersistent(false); chicken.setAdult(); chicken.setIsChickenJockey(true);
                chicken.setEggLayTime(Integer.MAX_VALUE);
                chicken.setRemoveWhenFarAway(false);
            });
            if (!mount.isValid()) { mount.remove(); return; }
            zombie.setBaby(true);
            if (mount.addPassenger(zombie)) mounts.put(zombie.getUniqueId(),mount.getUniqueId()); else mount.remove();
        }
    }
    private static final class SpawnRejectedException extends IllegalStateException {
        SpawnRejectedException(EntityType type,Location location) {
            super("Server rejected "+type+" spawn at "+ArenaDefinition.write(location)
                +". Check WorldGuard mob-spawning, deny-spawn, overlapping region priorities, and other spawn protection plugins."
                +" SimpleMobArena respects cancelled spawn events.");
        }
    }
    private Location fittingSpawn(Mob mob, Location preferred) {
        double width = Math.max(.6,mob.getWidth()), height = Math.max(1.8,mob.getHeight());
        List<Location> candidates = new ArrayList<>();
        candidates.add(preferred); candidates.addAll(definition.mobSpawns);
        for (Location candidate : candidates) {
            if (contains(candidate.clone().add(-width/2,0,-width/2))
                    && contains(candidate.clone().add(width/2,height,width/2))
                    && SpawnSafety.problem(candidate,width,height)==null) return candidate;
        }
        return null;
    }
    public void tag(Entity entity) {
        entity.getPersistentDataContainer().set(plugin.sessionKey(),PersistentDataType.STRING,sessionId.toString());
        temporary.add(entity.getUniqueId());
    }
    private int liveMobCount() { return state.mobs().size()+mounts.size()+summonedMobs.size(); }
    private Set<UUID> combatMobs() {
        Set<UUID> result = new HashSet<>(state.mobs());
        result.addAll(summonedMobs.keySet());
        return result;
    }
    /** Evoker helpers belong to their caster, never expand the wave's reward budget. */
    public boolean adoptSummonedMob(Mob mob, Mob summoner) {
        if (!(mob instanceof Vex) || !(summoner instanceof Evoker) || isDuel()
                || state.state()!=GameState.ACTIVE || !owns(summoner)
                || !state.mobs().contains(summoner.getUniqueId()) || !contains(mob.getLocation())
                || liveMobCount()>=definition.maxActiveMobs || summonedMobs.containsKey(mob.getUniqueId())
                || summonedMobs.values().stream().filter(summoner.getUniqueId()::equals).count()>=3) return false;
        tag(mob);
        mob.setPersistent(false); mob.setRemoveWhenFarAway(false); mob.setCanPickupItems(false);
        MobSpawn.equip(mob,definition.mobSettings(EntityType.VEX));
        MobBehavior.configure(mob);
        summonedMobs.put(mob.getUniqueId(),summoner.getUniqueId());
        return true;
    }
    private void removeSummonedMob(UUID id) {
        Entity entity = Bukkit.getEntity(id);
        if (entity != null && owns(entity)) entity.remove();
        summonedMobs.remove(id); temporary.remove(id);
    }
    /** Finish after the explosion's damage events, including explosions cancelled by protection plugins. */
    public void mobExploded(Creeper creeper) {
        UUID id = creeper.getUniqueId(), session = sessionId;
        if (state.state()!=GameState.ACTIVE || !owns(creeper) || !state.mobs().contains(id) || !explodingMobs.add(id)) return;
        Bukkit.getScheduler().runTask(plugin,() -> {
            if (!sessionId.equals(session) || !explodingMobs.remove(id)) return;
            creeper.remove();
            mobDeath(creeper,null);
        });
    }
    public void mobDeath(LivingEntity entity, Player killer) {
        if (!owns(entity)) return;
        if (summonedMobs.remove(entity.getUniqueId()) != null) { temporary.remove(entity.getUniqueId()); return; }
        temporary.remove(entity.getUniqueId());
        mounts.values().remove(entity.getUniqueId());
        if (state.state() != GameState.ACTIVE || !owns(entity) || !state.died(entity.getUniqueId())) return;
        explodingMobs.remove(entity.getUniqueId());
        for (UUID child : new HashSet<>(summonedMobs.keySet()))
            if (entity.getUniqueId().equals(summonedMobs.get(child))) removeSummonedMob(child);
        UUID mount = mounts.remove(entity.getUniqueId());
        if (mount != null) { Entity vehicle = Bukkit.getEntity(mount); if (vehicle != null) vehicle.remove(); temporary.remove(mount); }
        if (killer != null && isParticipant(killer.getUniqueId()) && contains(killer.getLocation())) {
            participants.get(killer.getUniqueId()).kills++;
            sounds.play(Cue.MOB_KILL,killer);
        }
        if (state.waveComplete()) {
            for (UUID child : new HashSet<>(summonedMobs.keySet())) removeSummonedMob(child);
            completedWaves = wave;
            prizePot = plugin.getEconomyService().capPot(prizePot.add(plugin.getEconomyService().waveReward(wave,startingPlayers)));
            state.transition(GameState.INTERMISSION);
            Bukkit.getPluginManager().callEvent(new ArenaWaveCompleteEvent(this,wave));
            if (wave < definition.maxWaves) {
                announce(ArenaMessages.success("Wave "+wave+" cleared! Prize pot: $"+displayPot()+"."));
                announce(MatchMessages.rewardProgress(plugin.getEconomyService().rewardSettings(),completedWaves));
                sounds.play(Cue.WAVE_COMPLETE,getPlayers());
            }
            if (definition.healBetweenWaves) getPlayers().forEach(Arena::heal);
            if (wave >= definition.maxWaves) finish(false,"All waves completed!");
            else deadline = plugin.ticks()+definition.intermissionSeconds*20L;
        }
    }
    public void damage(UUID player, double damage) {
        if (state.state() == GameState.ACTIVE && isParticipant(player) && Double.isFinite(damage) && damage > 0)
            participants.get(player).damage += damage;
    }
    public void eliminate(Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!state.eliminate(id)) return;
        Instant leftAt = Instant.now();
        Participation p = participants.get(id); if (p != null) p.leave(leftAt);
        if (isDuel() && startedAt != null) {
            duelEliminated.add(id);
            HitCredit hit = recentHits.remove(id);
            if ("was eliminated".equals(reason) && hit != null && plugin.ticks()-hit.tick() <= 200
                    && isParticipant(hit.attacker()) && teams.opponents(hit.attacker(),id)) {
                participants.get(hit.attacker()).kills++;
                Player killer = Bukkit.getPlayer(hit.attacker());
                if (killer != null) sounds.play(Cue.MOB_KILL,killer);
            }
        }
        boolean duelOver = isDuel() && startedAt != null && (teams.alive(Team.RED,state.active()) == 0 || teams.alive(Team.BLUE,state.active()) == 0);
        if (startedAt == null) announcements.clearCountdown(player);
        if (startedAt != null && "was eliminated".equals(reason)) sounds.play(Cue.ELIMINATED,player);
        // The departing player is no longer in getPlayers(), so their result must be delivered directly.
        if (player.isOnline()) sendPersonalFeedback(player,() -> {
            if (isDuel() && startedAt != null && p != null) {
                if (!duelOver) {
                    player.showTitle(net.kyori.adventure.title.Title.title(Component.text("ELIMINATED",NamedTextColor.RED),Component.text(getName(),NamedTextColor.GRAY)));
                    player.sendMessage(duelStats(p,leftAt,"Eliminated"));
                    player.sendMessage(ArenaMessages.info("Your team is still fighting. The result will appear when the duel ends."));
                }
            } else if (startedAt != null && p != null) {
                var summary = new MatchMessages.ExitSummary(getName(),sessionId,reason,"was eliminated".equals(reason),
                    p.kills,p.damage,wave,completedWaves,p.seconds(leftAt),BigDecimal.ZERO,!state.active().isEmpty());
                sendPersonalFeedback(player,() -> player.showTitle(MatchMessages.eliminationTitle(summary)));
                MatchMessages.elimination(summary).forEach(player::sendMessage);
            } else player.sendMessage(ArenaMessages.info("You left the lobby."));
        });
        if (startedAt == null) { participants.remove(id); teams.remove(id); }
        plugin.getArenaManager().release(id);
        plugin.getEquipmentService().restore(player,definition.exit,() -> { if (player.isOnline()) heal(player); });
        if (!duelOver) announce(ArenaMessages.warning(player.getName()+" "+reason+"."));
        if (duelOver) {
            duelWinner = teams.alive(Team.RED,state.active()) > 0 ? "RED" : teams.alive(Team.BLUE,state.active()) > 0 ? "BLUE" : "DRAW";
            finish(false,"TEAM_ELIMINATED");
        } else if (state.active().isEmpty()) finish(startedAt == null,"No active participants remain.");
        else if (state.state() == GameState.COUNTDOWN && state.active().size()<definition.minPlayers) {
            state.transition(GameState.WAITING);
            countdown.reset(0);
            announcements.countdownPaused(state.active().size(),definition.minPlayers,getPlayers());
        }
    }
    public void endArena() { finish(true,"Arena administratively cancelled; no payout."); }
    public void forceWave() {
        if (isDuel()) throw new IllegalStateException("Duels do not have waves.");
        if (state.state() != GameState.INTERMISSION) throw new IllegalStateException("Forcewave only skips an intermission; it cannot complete or reward a partial wave.");
        deadline = plugin.ticks();
    }
    public void finish(boolean cancelled, String reason) {
        if (state.state() == GameState.ENDING || state.state() == GameState.FINISHED) return;
        String recordedReason = reason == null || reason.isBlank() ? (cancelled ? "CANCELLED" : "COMPLETED") : reason.substring(0,Math.min(64,reason.length()));
        state.transition(GameState.ENDING);
        countdown.reset(0);
        plugin.getArenaManager().cancelReservations(this);
        Set<Player> remainingPlayers = getPlayers();
        if (lobbyAdvertised || startedAt != null) {
            if (isDuel()) announcements.duelFinished(cancelled,duelWinner,remainingPlayers);
            else announcements.finished(cancelled,completedWaves,remainingPlayers);
            sounds.play(cancelled ? Cue.CANCELLED : Cue.MATCH_END,remainingPlayers);
        }
        if (cancelled && reason != null && !reason.isBlank()) announce(ArenaMessages.error(reason));
        Instant clockTime = Instant.now();
        Instant finish = startedAt != null && clockTime.isBefore(startedAt) ? startedAt : clockTime;
        if (isDuel() && startedAt != null) for (Participation p : participants.values()) {
            Player player = Bukkit.getPlayer(p.playerId);
            if (player == null || !player.isOnline()) continue;
            sendPersonalFeedback(player,() -> {
                boolean won = !cancelled && teams.team(p.playerId).name().equals(duelWinner);
                String outcome = cancelled ? "Cancelled" : "DRAW".equals(duelWinner) ? "Draw" : won ? "Victory" : "Defeat";
                Arena currentArena = plugin.getArenaManager().getPlayerArena(p.playerId);
                if (currentArena == null || currentArena == this)
                    player.showTitle(net.kyori.adventure.title.Title.title(Component.text(outcome.toUpperCase(Locale.ROOT),won ? NamedTextColor.GREEN : NamedTextColor.YELLOW),Component.text(getName(),NamedTextColor.GRAY)));
                player.sendMessage(duelStats(p,finish,outcome));
            });
        }
        for (Player player : getPlayers()) {
            UUID id = player.getUniqueId(); state.eliminate(id);
            Participation participation = participants.get(id);
            participation.leave(finish);
            if (startedAt != null && !cancelled && !isDuel()) sendPersonalFeedback(player, () -> {
                var summary = new MatchMessages.ExitSummary(getName(),sessionId,reason,false,
                    participation.kills,participation.damage,wave,completedWaves,participation.seconds(finish),BigDecimal.ZERO,false);
                MatchMessages.completed(summary).forEach(player::sendMessage);
            });
            plugin.getArenaManager().release(id);
            plugin.getEquipmentService().restore(player,definition.exit,() -> {});
        }
        removeAllMobs();
        if (startedAt != null && isDuel()) {
            DuelResult result = new DuelResult(sessionId,getId(),definition.mode.name(),startedAt,finish,cancelled ? "DRAW" : duelWinner,cancelled,recordedReason,
                participants.values().stream().map(p -> new DuelResult.Participant(p.playerId,p.playerName,teams.team(p.playerId).name(),p.kills,p.damage,p.seconds(finish),duelEliminated.contains(p.playerId))).toList());
            plugin.getEconomyService().finishDuel(result).whenComplete((unused,error) -> {
                if (error != null) plugin.getLogger().severe("Duel recording failed for "+result.sessionId()+": "+error.getMessage());
            });
        } else if (startedAt != null) {
            SessionResult result = new SessionResult(sessionId,getId(),startedAt,finish,completedWaves,prizePot,
                participants.values().stream().map(p -> new SessionResult.Participant(p.playerId,p.playerName,p.kills,p.damage,p.seconds(finish))).toList(),cancelled,recordedReason);
            plugin.getEconomyService().finish(result).whenComplete((unused,error) -> {
                if (error != null) plugin.getLogger().severe("Settlement failed for "+result.sessionId()+": "+error.getMessage());
            });
        }
        Bukkit.getPluginManager().callEvent(new ArenaEndEvent(this));
        state.transition(GameState.FINISHED); state.transition(GameState.WAITING);
        state = new SessionState(); participants.clear(); sessionId = UUID.randomUUID(); startedAt = null;
        wave = completedWaves = startingPlayers = 0; prizePot = BigDecimal.ZERO;
        lastMemeWave = 0; waveMobPool = List.of(); waveTheme = "";
        lobbyAdvertised = false;
        teams.clear(); duelEliminated.clear(); recentHits.clear(); duelWinner = "DRAW";
    }
    public void removeAllMobs() {
        for (UUID id : new HashSet<>(temporary)) { Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove(); }
        for (Chunk chunk : tickets) for (Entity entity : chunk.getEntities()) if (owns(entity)) entity.remove();
        temporary.clear(); mounts.clear(); summonedMobs.clear(); explodingMobs.clear(); state.clearMobs();
        tickets.forEach(chunk -> ArenaChunkTickets.release(plugin,chunk)); tickets.clear();
    }
    public void announce(String text) { announce(ArenaMessages.info(text)); }
    private void announce(Component message) { getPlayers().forEach(p -> sendPersonalFeedback(p,() -> p.sendMessage(message))); }
    private void sendPersonalFeedback(Player player,Runnable send) {
        try { send.run(); }
        catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Could not display arena feedback for "+player.getUniqueId()+"; match cleanup continues",error);
        }
    }
    private String displayPot() { return prizePot.setScale(2,java.math.RoundingMode.DOWN).toPlainString(); }
    private Component duelStats(Participation p, Instant finish, String outcome) {
        return ArenaMessages.info(outcome+" in "+getName()+" · "+p.kills+" kills · "+String.format(Locale.ROOT,"%.1f",p.damage)
            +" damage · "+p.seconds(finish)+"s played");
    }
    private Component actionBar(long tick,UUID player) {
        if (startedAt == null) {
            String status = "Waiting for players";
            if (state.state() == GameState.COUNTDOWN) status = tick >= deadline && preparing > 0
                ? "Preparing players" : "Starting in "+Math.max(0,(deadline-tick+19)/20)+"s";
            return Component.text(status,NamedTextColor.GOLD)
                .append(Component.text("  •  ",NamedTextColor.DARK_GRAY))
                .append(Component.text(state.active().size()+"/"+definition.maxPlayers+" players",NamedTextColor.GRAY));
        }
        if (isDuel()) return Component.text("Red "+teams.alive(Team.RED,state.active()),NamedTextColor.RED)
            .append(Component.text("  •  Blue "+teams.alive(Team.BLUE,state.active()),NamedTextColor.BLUE))
            .append(Component.text("  •  "+Math.max(0,(deadline-tick+19)/20)+"s left  •  "+getKills(player)+" kills",NamedTextColor.GRAY));
        return Component.text("Wave "+wave,NamedTextColor.GOLD)
            .append(Component.text("  •  ",NamedTextColor.DARK_GRAY))
            .append(Component.text("Alive "+state.active().size()+"/"+startingPlayers,NamedTextColor.GREEN))
            .append(Component.text("  •  ",NamedTextColor.DARK_GRAY))
            .append(Component.text("Pot $"+displayPot(),NamedTextColor.YELLOW))
            .append(Component.text("  •  ",NamedTextColor.DARK_GRAY))
            .append(Component.text("Kills "+getKills(player),NamedTextColor.AQUA));
    }
    public static void heal(Player p) {
        p.setFireTicks(0); p.setFallDistance(0); p.setHealth(Math.min(20,p.getAttribute(Attribute.MAX_HEALTH).getValue()));
        p.setFoodLevel(20); p.setSaturation(10);
    }
}
