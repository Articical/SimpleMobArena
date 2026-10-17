package cloud.mineplay.mobarena.integration;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.config.SpawnSafety;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Optional Citizens menu links. Persistent NPC UUIDs, never recycled numeric IDs, authorize clicks. */
public final class CitizensArenaNpc implements AutoCloseable {
    interface Npc {
        int id(); UUID uuid(); boolean spawned(); Location location();
        boolean spawn(Location location); void protect(); void destroy();
    }
    interface Access {
        Class<? extends Event> eventType(); Player clicker(Event event); Npc clicked(Event event);
        Npc lookup(int id); Npc lookup(UUID uuid); Npc create(String name); boolean isNpc(Player player); void save();
    }
    private final SimpleMobArena plugin;
    private final Consumer<Player> openMenu;
    private final Path file;
    private final Supplier<Access> factory;
    private final LongSupplier clock;
    private final Listener listener = new Listener() {};
    private final Map<UUID, Integer> links = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private final Map<UUID, Long> clicks = new HashMap<>();
    private Access access;
    private boolean started, closed;
    private String unavailable = "Citizens is not available. Install a compatible Citizens build and restart the server.";

    public CitizensArenaNpc(SimpleMobArena plugin, Consumer<Player> openMenu) {
        this(plugin, openMenu, plugin.getDataFolder().toPath().resolve("npc-menus.yml"),
            () -> new ReflectiveCitizensAccess(plugin), System::nanoTime);
    }

    CitizensArenaNpc(SimpleMobArena plugin, Consumer<Player> openMenu, Path file, Supplier<Access> factory, LongSupplier clock) {
        this.plugin = plugin; this.openMenu = openMenu; this.file = file; this.factory = factory; this.clock = clock;
    }

    public void start() {
        if (started || closed) return;
        started = true;
        try { load(); }
        catch (Exception failure) {
            unavailable = "Cannot read npc-menus.yml. Repair that file and restart; existing NPCs were not changed.";
            warn(unavailable, failure); return;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        try {
            access = factory.get();
            plugin.getServer().getPluginManager().registerEvent(access.eventType(), listener, EventPriority.HIGHEST,
                (ignored, event) -> clicked(event), plugin, true);
        } catch (RuntimeException | LinkageError failure) {
            access = null;
            unavailable = "Citizens menu integration is unavailable. Check Citizens compatibility and the server log, then restart.";
            warn(unavailable, failure);
        }
    }

    public String create(Player at, String name) {
        requireReady();
        if (name == null || name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Choose an NPC name of 1–64 characters without control characters.");
        Location location = at.getLocation().clone();
        String unsafe = SpawnSafety.problem(location);
        if (unsafe != null) throw new IllegalArgumentException("Stand at a safe NPC location: " + unsafe + ".");
        outsideArenas(location);
        Npc npc = access.create(name);
        try {
            npc.protect();
            if (!npc.spawn(location) || !npc.spawned()) throw new IllegalStateException("Citizens could not spawn the NPC at that location.");
            outsideArenas(npc.location());
            access.save(); // Citizens' supported registry persistence hook, before committing our link.
            saveLink(npc);
            return "Created and linked Citizens NPC " + npc.id() + " (" + name + "). Right-click it to open MobArena.";
        } catch (RuntimeException failure) {
            try { npc.destroy(); access.save(); }
            catch (RuntimeException rollback) { failure.addSuppressed(rollback); warn("Could not remove newly created NPC " + npc.id() + " after setup failed", rollback); }
            throw new IllegalStateException("NPC creation failed; the new NPC was rolled back where possible. " + failure.getMessage(), failure);
        }
    }

    public String bind(int npcId) {
        requireReady();
        Npc npc = required(npcId);
        if (npc.spawned()) outsideArenas(npc.location());
        saveLink(npc);
        return "Linked Citizens NPC " + npcId + " to the MobArena menu.";
    }

    public String unbind(int npcId) {
        requireMainThread();
        if (!started || closed) throw new IllegalStateException("NPC menu integration is not running.");
        UUID key = links.entrySet().stream().filter(entry -> entry.getValue() == npcId).map(Map.Entry::getKey).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("NPC " + npcId + " has no saved MobArena link."));
        Map<UUID, Integer> updated = new LinkedHashMap<>(links); updated.remove(key);
        persist(updated); links.clear(); links.putAll(updated);
        return "Removed the MobArena link for NPC " + npcId + ". The Citizens NPC was kept.";
    }

    public List<String> bindings() {
        List<String> result = new ArrayList<>();
        links.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getValue))
            .forEach(entry -> result.add("NPC " + entry.getValue() + " | " + entry.getKey()));
        if (result.isEmpty()) result.add("No Citizens NPCs are linked to MobArena.");
        if (access == null) result.add(unavailable);
        return List.copyOf(result);
    }

    private void saveLink(Npc npc) {
        for (var entry : links.entrySet()) if (entry.getValue() == npc.id() && !entry.getKey().equals(npc.uuid()))
            throw new IllegalStateException("NPC ID " + npc.id() + " was reused by a different NPC. Unbind the old link first, then bind this NPC explicitly.");
        Map<UUID, Integer> updated = new LinkedHashMap<>(links); updated.put(npc.uuid(), npc.id());
        persist(updated); links.clear(); links.putAll(updated);
    }

    private void clicked(Event event) {
        if (closed || access == null || event.isAsynchronous() || event instanceof Cancellable cancelled && cancelled.isCancelled()) return;
        try {
            Npc npc = access.clicked(event);
            Integer id = links.get(npc.uuid());
            if (id == null) return;
            // Consume linked NPC interactions before any rejection/debounce so Citizens cannot also dispatch command traits.
            if (event instanceof Cancellable cancelled) cancelled.setCancelled(true);
            Player player = access.clicker(event);
            String rejection = rejection(player);
            if (rejection != null) { if (player != null && player.isOnline()) player.sendMessage(ArenaMessages.warning(rejection)); return; }
            UUID playerId = player.getUniqueId(), npcUuid = npc.uuid();
            long now = clock.getAsLong();
            clicks.entrySet().removeIf(entry -> now - entry.getValue() > 60_000_000_000L);
            if (pending.containsKey(playerId) || now - clicks.getOrDefault(playerId, now - 500_000_000L) < 500_000_000L) return;
            clicks.put(playerId, now);
            BukkitTask task = plugin.getServer().getScheduler().runTask(plugin, () -> {
                pending.remove(playerId);
                if (closed || access == null || !plugin.isEnabled()) return;
                try {
                    if (!Integer.valueOf(id).equals(links.get(npcUuid)) || rejection(player) != null) return;
                    Npc current = access.lookup(npcUuid);
                    if (current == null || !npcUuid.equals(current.uuid()) || !current.spawned()) return;
                    outsideArenas(current.location());
                    openMenu.accept(player);
                } catch (RuntimeException | LinkageError failure) { warn("Could not open the arena NPC menu", failure); }
            });
            pending.put(playerId, task);
        } catch (RuntimeException | LinkageError failure) { warn("Could not handle a Citizens NPC click", failure); }
    }

    private String rejection(Player player) {
        if (player == null || !player.isOnline() || player.hasMetadata("NPC") || access.isNpc(player)
                || plugin.getServer().getPlayer(player.getUniqueId()) != player) return "Only online players can use this arena NPC.";
        if (!player.hasPermission("mobarena.use") || !player.hasPermission("mobarena.join")) return "You do not have permission to use the arena menu.";
        UUID id = player.getUniqueId();
        if (plugin.getArenaManager().getPlayerArena(id) != null) return "Use /ma leave before opening the arena NPC menu.";
        if (plugin.getEquipmentService().isLocked(id) || plugin.getEquipmentService().hasSnapshot(id)) return "Wait for your arena equipment to be restored before opening the menu.";
        return null;
    }

    private Npc required(int id) {
        if (id < 0) throw new IllegalArgumentException("Enter a non-negative Citizens NPC ID.");
        Npc npc = access.lookup(id);
        if (npc == null) throw new IllegalArgumentException("Citizens NPC " + id + " was not found in the default registry.");
        return npc;
    }
    private void outsideArenas(Location location) {
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException("The NPC needs a loaded world and a valid location.");
        if (plugin.getArenaManager().at(location) != null) throw new IllegalArgumentException("Place the menu NPC outside every enabled arena, including idle arenas.");
    }
    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Run NPC setup from the server's main thread.");
    }
    private void requireReady() {
        requireMainThread();
        if (closed || access == null || !plugin.getServer().getPluginManager().isPluginEnabled("Citizens")) throw new IllegalStateException(unavailable);
    }

    private void load() throws Exception {
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        if (!yaml.isInt("schema-version") || yaml.getInt("schema-version") != 1) throw new IllegalArgumentException("Unsupported NPC menu schema");
        var rows = yaml.getConfigurationSection("npcs");
        if (rows == null && yaml.contains("npcs")) throw new IllegalArgumentException("Invalid NPC binding map");
        Map<UUID, Integer> loaded = new LinkedHashMap<>(); var ids = new HashSet<Integer>();
        if (rows != null) for (String key : rows.getKeys(false)) {
            UUID uuid = UUID.fromString(key);
            if (!rows.isInt(key + ".id") || rows.getInt(key + ".id") < 0 || !ids.add(rows.getInt(key + ".id"))) throw new IllegalArgumentException("Invalid or duplicate NPC ID");
            loaded.put(uuid, rows.getInt(key + ".id"));
        }
        links.clear(); links.putAll(loaded);
    }

    private void persist(Map<UUID, Integer> updated) {
        Path temporary = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            YamlConfiguration yaml = new YamlConfiguration(); yaml.set("schema-version", 1); yaml.createSection("npcs");
            updated.forEach((uuid, id) -> yaml.set("npcs." + uuid + ".id", id));
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "npc-menus-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(yaml.saveToString());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not atomically save npc-menus.yml. Check the data directory permissions and available disk space.", failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }

    @Override public void close() {
        closed = true;
        for (BukkitTask task : pending.values()) try { task.cancel(); } catch (RuntimeException | LinkageError ignored) {}
        pending.clear(); clicks.clear(); access = null;
        try { HandlerList.unregisterAll(listener); } catch (RuntimeException | LinkageError failure) { warn("Could not unregister optional Citizens listener", failure); }
    }
    private void warn(String message, Throwable failure) {
        try { plugin.getLogger().log(Level.WARNING, message, failure); } catch (RuntimeException | LinkageError ignored) {}
    }
}
