package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import java.io.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;

public final class ArenaManager {
    private final SimpleMobArena plugin;
    private final Map<String,Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID,Arena> membership = new HashMap<>();
    private final Set<UUID> preparing = new HashSet<>();
    private final cloud.mineplay.mobarena.storage.DatabaseSettings storage;
    public ArenaManager(SimpleMobArena plugin) {
        this.plugin = plugin;
        storage = cloud.mineplay.mobarena.storage.DatabaseSettings.load(plugin.getConfig().getConfigurationSection("storage"),plugin.getDataFolder().toPath());
    }
    public void loadArenas() {
        arenas.clear();
        File directory = new File(plugin.getDataFolder(),"arenas");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create arenas directory.");
        File[] files = directory.listFiles((dir,name) -> name.endsWith(".yml"));
        if (files == null) throw new IllegalStateException("Cannot read arenas directory.");
        Arrays.sort(files);
        for (File file : files) {
            String id = file.getName().substring(0,file.getName().length()-4);
            if (!validId(id)) { plugin.getLogger().warning("Invalid arena filename: "+file.getName()); continue; }
            YamlConfiguration config = new YamlConfiguration();
            try { config.load(file); } catch (Exception e) { plugin.getLogger().severe("Cannot load "+file+": "+e.getMessage()); continue; }
            ArenaDefinition definition = new ArenaDefinition(id,config,plugin.getConfig());
            arenas.put(id,new Arena(plugin,definition));
        }
        for (Arena a : arenas.values()) {
            for (Arena b : arenas.values()) if (a != b && b.definition().enabled && a.definition().overlaps(b.definition()))
                a.definition().errors.add("Boundary overlaps enabled arena "+b.getId());
            for (Arena b : arenas.values()) if (b.definition().enabled && b.contains(a.definition().exit))
                a.definition().errors.add("Exit is inside enabled arena "+b.getId());
            Location outsiderExit = ArenaDefinition.read(a.getConfiguration().getString("outsider-exit"));
            for (Arena b : arenas.values()) if (a != b && b.definition().enabled && b.contains(outsiderExit))
                a.definition().errors.add("Outsider exit is inside enabled arena "+b.getId()+". Use /ma admin setoutsider "+a.getId()+" exit to share the normal exit.");
            for (String error : a.getValidationErrors()) plugin.getLogger().warning("Arena "+a.getId()+": "+error);
        }
    }
    public Collection<Arena> getArenas() { return Collections.unmodifiableCollection(arenas.values()); }
    public Arena getArena(String id) { return arenas.get(id.toLowerCase(Locale.ROOT)); }
    public Arena getPlayerArena(UUID id) { return membership.get(id); }
    public Arena at(Location location) {
        return arenas.values().stream().filter(a -> a.definition().enabled && a.contains(location)).findFirst().orElse(null);
    }
    public Arena owner(Entity entity) { return arenas.values().stream().filter(a -> a.owns(entity)).findFirst().orElse(null); }
    public boolean occupied(Arena arena) { return arena != null && (!arena.activeIds().isEmpty() || arena.reservedCount()>0 || arena.isRunning()); }
    public void join(Player player, String id, String selection) {
        if (!player.hasPermission("mobarena.join")) throw new IllegalStateException("You do not have permission to join.");
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)
            throw new IllegalStateException("Switch to survival or adventure mode before joining.");
        if (player.isDead() || player.isInsideVehicle() || player.isGliding() || player.isSleeping()) throw new IllegalStateException("Stand safely before joining.");
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClasses(Trident.class,FishHook.class)) {
                if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player owner
                        && owner.getUniqueId().equals(player.getUniqueId())) {
                    throw new IllegalStateException("Retrieve your thrown trident or fishing hook before joining.");
                }
            }
        }
        if (membership.containsKey(player.getUniqueId()) || plugin.getEquipmentService().hasSnapshot(player.getUniqueId()))
            throw new IllegalStateException("You already joined or have equipment awaiting recovery.");
        Arena arena = required(id); arena.reserve();
        UUID playerId = player.getUniqueId(), session = arena.getSessionId();
        membership.put(playerId,arena); preparing.add(playerId);
        plugin.getEquipmentService().prepare(player,selection,() -> {
            boolean reserved = preparing.remove(playerId);
            if (!reserved || membership.get(playerId) != arena || !arena.getSessionId().equals(session)
                    || !player.isOnline() || Bukkit.getPlayer(playerId) != player) {
                if (reserved) arena.releaseReservation();
                release(playerId); plugin.getEquipmentService().restore(player,arena.definition().exit,() -> {}); return;
            }
            try { arena.joined(player); }
            catch (RuntimeException e) {
                release(playerId); plugin.getEquipmentService().restore(player,arena.definition().exit,() -> {});
                player.sendMessage(ArenaMessages.error(e.getMessage()));
            }
        },message -> {
            if (preparing.remove(playerId)) arena.releaseReservation();
            release(playerId); player.sendMessage(ArenaMessages.error(message));
        });
    }
    public void leave(Player player) {
        Arena arena = membership.get(player.getUniqueId());
        if (arena == null) throw new IllegalStateException("You are not in an arena.");
        if (preparing.remove(player.getUniqueId())) {
            arena.releaseReservation(); membership.remove(player.getUniqueId());
            // Request recovery now; EquipmentService waits for the durable original and
            // skips kit application when preparation completes after a leave/disconnect.
            plugin.getEquipmentService().restore(player,null,() -> {});
        } else arena.eliminate(player,"left the match");
    }
    public void disconnected(Player player) {
        if (membership.containsKey(player.getUniqueId())) leave(player);
    }
    public void release(UUID id) { membership.remove(id); }
    public void cancelReservations(Arena arena) {
        for (UUID id : new HashSet<>(preparing)) if (membership.get(id) == arena) {
            preparing.remove(id); membership.remove(id); arena.releaseReservation();
            Player player = Bukkit.getPlayer(id);
            if (player != null) plugin.getEquipmentService().restore(player,null,() -> {});
        }
    }
    public void tick(long ticks) {
        for (Arena arena : arenas.values()) {
            try { arena.tick(ticks); }
            catch (RuntimeException e) { plugin.getLogger().severe("Arena "+arena.getId()+" failed: "+e); arena.endArena(); }
            if (ticks%20 == 0 && occupied(arena)) for (Player p : arena.definition().world.getPlayers())
                if (arena.contains(p.getLocation()) && !arena.isParticipant(p.getUniqueId()) && membership.get(p.getUniqueId()) != arena)
                    eject(p,arena);
        }
    }
    public void eject(Player player, Arena arena) {
        if (plugin.getEquipmentService().isLocked(player.getUniqueId())) return;
        Location exit = ArenaDefinition.read(arena.getConfiguration().getString("outsider-exit"));
        if (exit == null || at(exit) != null || !ArenaDefinition.safe(exit)) exit = arena.definition().exit;
        if (exit == null) return;
        player.leaveVehicle();
        if (player.teleport(exit)) player.sendMessage(ArenaMessages.warning("This arena is reserved for match participants. You have been moved to its exit."));
        else player.kick(Component.text("Arena exit teleport was blocked. Rejoin outside the active arena."));
    }
    public void create(String id) {
        ensureIdle();
        id = normalize(id);
        if (arenas.containsKey(id)) throw new IllegalArgumentException("Arena already exists.");
        YamlConfiguration c = new YamlConfiguration();
        c.set("name",id); c.set("enabled",false); c.set("world","world"); c.set("min-players",1); c.set("max-players",4);
        c.set("countdown-seconds",15); c.set("difficulty-multiplier",1.0); c.set("player-spawns",List.of()); c.set("mob-spawns",List.of());
        save(id,c); loadArenas();
    }
    public void delete(String id) {
        ensureIdle(); Arena a = required(id);
        try { java.nio.file.Files.delete(file(a.getId()).toPath()); } catch (IOException e) { throw new IllegalStateException("Cannot delete arena file",e); }
        arenas.remove(a.getId());
    }
    public void setLocation(String id,String key,Location location) {
        ensureIdle(); Arena a = required(id); YamlConfiguration c = a.getConfiguration();
        if (!Set.of("player-spawns","mob-spawns","exit","pos1","pos2","outsider-exit").contains(key)) throw new IllegalArgumentException("Unknown location key.");
        checkLocation(location);
        if (key.endsWith("-spawns")) c.set(key,List.of(ArenaDefinition.write(location)));
        else c.set(key,ArenaDefinition.write(location));
        if (key.equals("pos1")) c.set("world",location.getWorld().getName());
        save(a.getId(),c); loadArenas();
    }
    public void addSpawn(String id,String key,Location location) {
        ensureIdle(); Arena a = required(id); YamlConfiguration c = a.getConfiguration();
        if (!Set.of("player-spawns","mob-spawns").contains(key)) throw new IllegalArgumentException("Choose player-spawns or mob-spawns.");
        checkLocation(location);
        List<String> list = new ArrayList<>(c.getStringList(key));
        String value = ArenaDefinition.write(location);
        if (!list.contains(value)) list.add(value);
        c.set(key,list); save(a.getId(),c); loadArenas();
    }
    public void clearSpawns(String id,String type) {
        ensureIdle(); Arena a = required(id); YamlConfiguration c = a.getConfiguration();
        if (!Set.of("player","mob","all").contains(type)) throw new IllegalArgumentException("Choose player, mob or all.");
        if (!type.equals("mob")) c.set("player-spawns",List.of());
        if (!type.equals("player")) c.set("mob-spawns",List.of());
        save(a.getId(),c); loadArenas();
    }
    public void useExitForOutsiders(String id) {
        ensureIdle(); Arena a = required(id);
        a.getConfiguration().set("outsider-exit",null);
        save(a.getId(),a.getConfiguration()); loadArenas();
    }
    private static void checkLocation(Location location) {
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException("Stand in a loaded world to set a location.");
        location.checkFinite();
    }
    public void setEnabled(String id,boolean enabled) {
        ensureIdle(); Arena a = required(id);
        if (enabled && !a.getValidationErrors().isEmpty()) throw new IllegalStateException("Arena setup has "+a.getValidationErrors().size()+" issue(s). Use /ma admin setup "+a.getId()+" for locations and fixes.");
        a.getConfiguration().set("enabled",enabled); save(a.getId(),a.getConfiguration()); loadArenas();
    }
    public void reloadArenas() {
        ensureIdle();
        YamlConfiguration replacement = new YamlConfiguration();
        try { replacement.load(new File(plugin.getDataFolder(),"config.yml")); }
        catch (Exception error) { throw new IllegalStateException("Invalid configuration: "+error.getMessage()); }
        if (!storage.equals(cloud.mineplay.mobarena.storage.DatabaseSettings.load(replacement.getConfigurationSection("storage"),plugin.getDataFolder().toPath())))
            throw new IllegalStateException("Storage changes require a stopped server, completed migration, and restart.");
        plugin.reloadConfig(); plugin.getEquipmentService().reload();
        plugin.getEconomyService().reload(plugin.getConfig().getConfigurationSection("economy")); loadArenas();
    }
    private void ensureIdle() {
        if (!membership.isEmpty() || arenas.values().stream().anyMatch(Arena::isRunning)) throw new IllegalStateException("Stop all occupied arenas before changing configuration.");
    }
    public void stopAllArenas() { for (Arena a : arenas.values()) { cancelReservations(a); a.endArena(); } }
    public Arena required(String id) { Arena a=getArena(id); if(a==null) throw new IllegalArgumentException("Unknown arena: "+id); return a; }
    private File file(String id) { return new File(plugin.getDataFolder(),"arenas/"+normalize(id)+".yml"); }
    private void save(String id,YamlConfiguration config) {
        try { config.save(file(id)); } catch (IOException e) { throw new IllegalStateException("Cannot save arena configuration",e); }
    }
    private static boolean validId(String id) { return id.matches("[a-z0-9][a-z0-9_-]{0,39}"); }
    private static String normalize(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        if (!validId(normalized)) throw new IllegalArgumentException("Use a short arena ID containing letters, numbers, underscores or hyphens.");
        return normalized;
    }
}
