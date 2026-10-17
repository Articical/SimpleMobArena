package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.cevents.*;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.config.SpawnSafety;
import cloud.mineplay.mobarena.economy.SessionResult;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.mobs.MobSpawn;
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
    private UUID sessionId = UUID.randomUUID();
    private Instant startedAt;
    private int wave, completedWaves, startingPlayers, preparing;
    private long deadline, nextSpawn, waveStarted;
    private BigDecimal prizePot = BigDecimal.ZERO;
    private boolean lobbyAdvertised;

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
    public int getCompletedWaves() { return completedWaves; }
    public int getStartingPlayerCount() { return startingPlayers; }
    public UUID getSessionId() { return sessionId; }
    public BigDecimal getPrizePot() { return prizePot; }
    public int getKills(UUID player) { Participation p = participants.get(player); return p == null ? 0 : p.kills; }
    public int getTotalKills() { return participants.values().stream().mapToInt(p -> p.kills).sum(); }
    public List<String> getValidationErrors() { return List.copyOf(definition.errors); }
    public Set<UUID> getActiveMobs() { return state.mobs(); }
    public Set<UUID> activeIds() { return state.active(); }
    public int reservedCount() { return preparing; }
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
    public void joined(Player player) {
        releaseReservation();
        if (!isAccepting() || !state.join(player.getUniqueId())) throw new IllegalStateException("Session no longer accepts players.");
        participants.put(player.getUniqueId(), new Participation(player.getUniqueId(),player.getName()));
        try {
            player.closeInventory();
            if (!player.teleport(definition.playerSpawns.get((state.active().size()-1)%definition.playerSpawns.size()))) {
                throw new IllegalStateException("Arena teleport was refused.");
            }
            heal(player);
        } catch (RuntimeException exception) {
            state.eliminate(player.getUniqueId()); participants.remove(player.getUniqueId());
            throw exception;
        }
        lobbyAdvertised = true;
        announcements.joined(player,state.active().size(),definition.maxPlayers,getPlayers());
        sounds.play(Cue.JOIN,getPlayers());
        sendPersonalFeedback(player,() -> player.sendMessage(MatchMessages.rewardRequirements(plugin.getEconomyService().rewardSettings())));
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
        if (definition.mobSpawns.isEmpty() || definition.mobSpawns.stream().anyMatch(l -> !ArenaDefinition.safe(l)))
            throw new IllegalStateException("No valid mob spawns; check the arena floor and clearance.");
        if (state.state() == GameState.WAITING) state.transition(GameState.COUNTDOWN);
        try { holdChunks(); } catch (RuntimeException e) { finish(true,"Could not load arena chunks."); return; }
        startedAt = Instant.now(); startingPlayers = state.active().size();
        participants.values().forEach(p -> p.start(startedAt));
        state.transition(GameState.ACTIVE);
        countdown.reset(0);
        announcements.started(startingPlayers,getPlayers());
        sounds.play(Cue.MATCH_START,getPlayers());
        Bukkit.getPluginManager().callEvent(new ArenaStartEvent(this));
        beginWave();
    }
    private void holdChunks() {
        for (int x = Math.min(definition.pos1.getBlockX(),definition.pos2.getBlockX()) >> 4;
             x <= (Math.max(definition.pos1.getBlockX(),definition.pos2.getBlockX()) >> 4); x++)
            for (int z = Math.min(definition.pos1.getBlockZ(),definition.pos2.getBlockZ()) >> 4;
                 z <= (Math.max(definition.pos1.getBlockZ(),definition.pos2.getBlockZ()) >> 4); z++) {
                Chunk chunk = definition.world.getChunkAt(x,z); chunk.addPluginChunkTicket(plugin); tickets.add(chunk);
            }
    }
    private void beginWave() {
        wave++;
        long count = definition.baseMobs+(long)wave*definition.perWavePerPlayer*startingPlayers;
        state.beginWave((int)Math.min(definition.maxMobsPerWave,count));
        nextSpawn = plugin.ticks(); waveStarted = nextSpawn;
        announce(ArenaMessages.info(Component.text("Wave "+wave,NamedTextColor.GOLD,TextDecoration.BOLD)
            .append(Component.text(" • "+state.pendingSpawns()+" mobs incoming!",NamedTextColor.GRAY).decoration(TextDecoration.BOLD,false))));
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
        if (state.state() == GameState.ACTIVE) {
            if (tick-waveStarted >= definition.maxWaveSeconds*20L) { finish(true,"Wave timed out; no payout."); return; }
            if (tick >= nextSpawn) {
                nextSpawn = tick+definition.spawnIntervalTicks;
                for (int i=0; i<definition.spawnBatchSize && state.pendingSpawns()>0 && state.mobs().size()+mounts.size()<definition.maxActiveMobs; i++) {
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
            if (tick%20 == 0) for (UUID id : state.mobs()) {
                Entity entity = Bukkit.getEntity(id);
                if (!(entity instanceof Mob mob) || !entity.isValid() || !owns(entity)) {
                    finish(true,"A tracked mob disappeared. Session cancelled without payout."); return;
                }
                if (!contains(mob.getLocation())) {
                    Entity vehicle = mob.getVehicle();
                    if (vehicle != null) { mob.leaveVehicle(); vehicle.remove(); mounts.remove(id); }
                    if (!mob.teleport(definition.mobSpawns.getFirst())) { finish(true,"Could not contain an arena mob."); return; }
                }
                Player target = getPlayers().stream().filter(p -> contains(p.getLocation()))
                    .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(mob.getLocation()))).orElse(null);
                mob.setTarget(target);
            }
        }
        if (tick%20 == 0) for (Player p : getPlayers()) {
            if (!contains(p.getLocation())) { eliminate(p,"left the arena boundary"); continue; }
            p.sendActionBar(actionBar(tick,p.getUniqueId()));
        }
    }
    private void spawnMob() {
        List<ArenaDefinition.MobRule> choices = definition.mobs.stream().filter(m -> wave >= m.unlockWave()).toList();
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
            MobSpawn.equip(entity,plugin.getConfig().getConfigurationSection("waves.mobs."+rule.type().name()));
        });
        if (!mob.isValid() || mob.isDead()) {
            mob.remove(); temporary.remove(mob.getUniqueId());
            throw new SpawnRejectedException(rule.type(),location);
        }
        // Reapply after spawn listeners have finished initializing the entity.
        MobSpawn.protectFromSunlight(mob);
        mob.setFireTicks(0);
        state.spawned(mob.getUniqueId());
        if (mob.getType() == EntityType.ZOMBIE && mob instanceof Zombie zombie
            && state.mobs().size()+mounts.size()<definition.maxActiveMobs
            && MobSpawn.roll(plugin.getConfig().getDouble("waves.mobs.ZOMBIE.chicken-jockey-chance",.015))) {
            Chicken mount = location.getWorld().spawn(location,Chicken.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,false,chicken -> {
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
    public void tag(Entity entity) {
        entity.getPersistentDataContainer().set(plugin.sessionKey(),PersistentDataType.STRING,sessionId.toString());
        temporary.add(entity.getUniqueId());
    }
    public void mobDeath(LivingEntity entity, Player killer) {
        temporary.remove(entity.getUniqueId());
        mounts.values().remove(entity.getUniqueId());
        if (state.state() != GameState.ACTIVE || !owns(entity) || !state.died(entity.getUniqueId())) return;
        UUID mount = mounts.remove(entity.getUniqueId());
        if (mount != null) { Entity vehicle = Bukkit.getEntity(mount); if (vehicle != null) vehicle.remove(); temporary.remove(mount); }
        if (killer != null && isParticipant(killer.getUniqueId()) && contains(killer.getLocation())) {
            participants.get(killer.getUniqueId()).kills++;
            sounds.play(Cue.MOB_KILL,killer);
        }
        if (state.waveComplete()) {
            completedWaves = wave;
            prizePot = plugin.getEconomyService().capPot(prizePot.add(plugin.getEconomyService().waveReward(wave,startingPlayers)));
            state.transition(GameState.INTERMISSION);
            Bukkit.getPluginManager().callEvent(new ArenaWaveCompleteEvent(this,wave));
            announce(ArenaMessages.success("Wave "+wave+" complete! Prize pot: $"+displayPot()+" (before eligibility and caps)."));
            announce(MatchMessages.rewardProgress(plugin.getEconomyService().rewardSettings(),completedWaves));
            if (wave < definition.maxWaves) sounds.play(Cue.WAVE_COMPLETE,getPlayers());
            if (plugin.getConfig().getBoolean("waves.heal-between-waves",true)) getPlayers().forEach(Arena::heal);
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
        if (startedAt == null) announcements.clearCountdown(player);
        if (startedAt != null && "was eliminated".equals(reason)) sounds.play(Cue.ELIMINATED,player);
        // The departing player is no longer in getPlayers(), so their result must be delivered directly.
        if (player.isOnline()) sendPersonalFeedback(player,() -> {
            if (startedAt != null && p != null) {
                int totalKills = getTotalKills();
                BigDecimal estimated = totalKills == 0 ? BigDecimal.ZERO.setScale(2)
                    : prizePot.multiply(BigDecimal.valueOf(p.kills)).divide(BigDecimal.valueOf(totalKills),2,java.math.RoundingMode.DOWN);
                var summary = new MatchMessages.ExitSummary(getName(),sessionId,reason,"was eliminated".equals(reason),
                    p.kills,p.damage,wave,completedWaves,p.seconds(leftAt),estimated,!state.active().isEmpty());
                sendPersonalFeedback(player,() -> player.showTitle(MatchMessages.eliminationTitle(summary)));
                MatchMessages.elimination(summary).forEach(player::sendMessage);
            } else player.sendMessage(ArenaMessages.info("You left the arena lobby. No match had started."));
        });
        if (startedAt == null) participants.remove(id);
        plugin.getArenaManager().release(id);
        plugin.getEquipmentService().restore(player,definition.exit,() -> { if (player.isOnline()) heal(player); });
        announce(ArenaMessages.warning(player.getName()+" "+reason+"."));
        if (state.active().isEmpty()) finish(startedAt == null,"No active participants remain.");
        else if (state.state() == GameState.COUNTDOWN && state.active().size()<definition.minPlayers) {
            state.transition(GameState.WAITING);
            countdown.reset(0);
            announcements.countdownPaused(state.active().size(),definition.minPlayers,getPlayers());
        }
    }
    public void endArena() { finish(true,"Arena administratively cancelled; no payout."); }
    public void forceWave() {
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
            announcements.finished(cancelled,completedWaves,remainingPlayers);
            sounds.play(cancelled ? Cue.CANCELLED : Cue.MATCH_END,remainingPlayers);
        }
        String announcement = reason == null || reason.isBlank() ? (cancelled ? "Match cancelled. No payout." : "Match complete!") : reason;
        announce(cancelled ? ArenaMessages.error(announcement) : ArenaMessages.success(announcement));
        Instant clockTime = Instant.now();
        Instant finish = startedAt != null && clockTime.isBefore(startedAt) ? startedAt : clockTime;
        for (Player player : getPlayers()) {
            UUID id = player.getUniqueId(); state.eliminate(id);
            participants.get(id).leave(finish); plugin.getArenaManager().release(id);
            plugin.getEquipmentService().restore(player,definition.exit,() -> {});
        }
        removeAllMobs();
        if (startedAt != null) {
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
        lobbyAdvertised = false;
    }
    public void removeAllMobs() {
        for (UUID id : new HashSet<>(temporary)) { Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove(); }
        for (Chunk chunk : tickets) for (Entity entity : chunk.getEntities()) if (owns(entity)) entity.remove();
        temporary.clear(); mounts.clear(); state.clearMobs();
        tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin)); tickets.clear();
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
    private Component actionBar(long tick,UUID player) {
        if (startedAt == null) {
            String status = "Waiting for players";
            if (state.state() == GameState.COUNTDOWN) status = tick >= deadline && preparing > 0
                ? "Preparing players" : "Starting in "+Math.max(0,(deadline-tick+19)/20)+"s";
            return Component.text(status,NamedTextColor.GOLD)
                .append(Component.text("  •  ",NamedTextColor.DARK_GRAY))
                .append(Component.text(state.active().size()+"/"+definition.maxPlayers+" players",NamedTextColor.GRAY));
        }
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
