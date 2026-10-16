package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.cevents.*;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.economy.SessionResult;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.mobs.MobSpawn;
import cloud.mineplay.mobarena.session.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;

/** Main-thread session coordinator. Events announce transitions; they never spawn waves. */
public final class Arena {
    private final SimpleMobArena plugin;
    private final ArenaDefinition definition;
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

    public Arena(SimpleMobArena plugin, ArenaDefinition definition) { this.plugin = plugin; this.definition = definition; }
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
        announce(player.getName()+" joined ("+state.active().size()+"/"+definition.maxPlayers+").");
        if (state.state() == GameState.WAITING && state.active().size() >= definition.minPlayers) {
            state.transition(GameState.COUNTDOWN);
            deadline = plugin.ticks()+definition.countdownSeconds*20L;
            announce("Starting in "+definition.countdownSeconds+" seconds.");
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
        announce("Wave "+wave+": "+state.pendingSpawns()+" mobs.");
    }
    public void tick(long tick) {
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
                        plugin.getLogger().warning("Arena "+getId()+" wave spawn failed: "+e.getMessage());
                        finish(true,"Wave failed to spawn; session cancelled without payout."); return;
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
            p.sendActionBar(Component.text("Wave "+wave+" | Alive "+state.active().size()+"/"+startingPlayers+" | Pot $"+prizePot+" | Kills "+getKills(p.getUniqueId())));
        }
    }
    private void spawnMob() {
        List<ArenaDefinition.MobRule> choices = definition.mobs.stream().filter(m -> wave >= m.unlockWave()).toList();
        int sum = choices.stream().mapToInt(ArenaDefinition.MobRule::weight).sum();
        int pick = java.util.concurrent.ThreadLocalRandom.current().nextInt(sum);
        ArenaDefinition.MobRule chosen = choices.getFirst();
        for (var rule : choices) { pick -= rule.weight(); if (pick < 0) { chosen = rule; break; } }
        Location location = definition.mobSpawns.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(definition.mobSpawns.size()));
        if (!ArenaDefinition.safe(location)) throw new IllegalStateException("Blocked mob spawn.");
        @SuppressWarnings("unchecked") Class<? extends Mob> type = (Class<? extends Mob>)chosen.type().getEntityClass();
        ArenaDefinition.MobRule rule = chosen;
        Mob mob = definition.world.spawn(location, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, false, entity -> {
            tag(entity); entity.setPersistent(false); entity.setRemoveWhenFarAway(false); entity.setCanPickupItems(false);
            var attribute = entity.getAttribute(Attribute.MAX_HEALTH);
            if (attribute == null) throw new IllegalStateException("Mob lacks health attribute.");
            double health = Math.min(1024,attribute.getBaseValue()*rule.healthMultiplier()*definition.difficulty
                *(1+((wave-1)/definition.healthEvery)*definition.healthIncrease));
            attribute.setBaseValue(health); entity.setHealth(health);
            MobSpawn.equip(entity,plugin.getConfig().getConfigurationSection("waves.mobs."+rule.type().name()));
        });
        if (!mob.isValid() || mob.isDead()) { mob.remove(); throw new IllegalStateException("Mob spawn cancelled by server protection."); }
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
        if (killer != null && isParticipant(killer.getUniqueId()) && contains(killer.getLocation())) participants.get(killer.getUniqueId()).kills++;
        if (state.waveComplete()) {
            completedWaves = wave;
            prizePot = plugin.getEconomyService().capPot(prizePot.add(plugin.getEconomyService().waveReward(wave,startingPlayers)));
            state.transition(GameState.INTERMISSION);
            Bukkit.getPluginManager().callEvent(new ArenaWaveCompleteEvent(this,wave));
            announce("Wave "+wave+" completed. Prize pot: $"+prizePot+" (subject to eligibility and caps).");
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
        Participation p = participants.get(id); if (p != null) p.leave(Instant.now());
        if (startedAt == null) participants.remove(id);
        plugin.getArenaManager().release(id);
        plugin.getEquipmentService().restore(player,definition.exit,() -> { if (player.isOnline()) heal(player); });
        announce(player.getName()+" "+reason+".");
        if (state.active().isEmpty()) finish(startedAt == null,"No active participants remain.");
        else if (state.state() == GameState.COUNTDOWN && state.active().size()<definition.minPlayers) {
            state.transition(GameState.WAITING); announce("Countdown cancelled: waiting for players.");
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
        plugin.getArenaManager().cancelReservations(this);
        announce(reason);
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
    }
    public void removeAllMobs() {
        for (UUID id : new HashSet<>(temporary)) { Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove(); }
        for (Chunk chunk : tickets) for (Entity entity : chunk.getEntities()) if (owns(entity)) entity.remove();
        temporary.clear(); mounts.clear(); state.clearMobs();
        tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin)); tickets.clear();
    }
    public void announce(String text) { getPlayers().forEach(p -> p.sendMessage(Component.text("[MobArena] "+text))); }
    public static void heal(Player p) {
        p.setFireTicks(0); p.setFallDistance(0); p.setHealth(Math.min(20,p.getAttribute(Attribute.MAX_HEALTH).getValue()));
        p.setFoodLevel(20); p.setSaturation(10);
    }
}
