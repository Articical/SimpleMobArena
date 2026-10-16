package cloud.mineplay.mobarena.integration;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.commands.CommandManager;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional PAPI integration. World/player state is sampled only by the main-thread task. */
public final class MobArenaExpansion extends PlaceholderExpansion implements AutoCloseable {
    private final SimpleMobArena plugin;
    private final Map<UUID, Map<String,String>> live = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStatistics> lifetime = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> pending = new ConcurrentHashMap<>();
    private BukkitTask refresh;
    private int samples;
    private volatile boolean closed;

    public MobArenaExpansion(SimpleMobArena plugin) { this.plugin = plugin; }
    public void start() {
        if (!register()) throw new IllegalStateException("PlaceholderAPI rejected SimpleMobArena expansion registration");
        refresh = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample, 1L, 20L);
    }
    @Override public String getIdentifier() { return "simplemobarena"; }
    @Override public String getAuthor() { return "Mineplay"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    private void sample() {
        samples++;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Arena arena = plugin.getArenaManager().getPlayerArena(id);
            live.put(id, arena == null ? Map.of("arena", "", "state", "NONE", "wave", "0", "completed_waves", "0", "kills", "0", "pot", "0.00", "alive", "0", "estimated_reward", "0.00")
                    : Map.of("arena", arena.getName(), "state", arena.getArenaState().name(), "wave", Integer.toString(arena.getCurrentWave()),
                    "completed_waves", Integer.toString(arena.getCompletedWaves()), "kills", Integer.toString(arena.getKills(id)),
                    "pot", arena.getPrizePot().toPlainString(), "alive", Integer.toString(arena.getPlayers().size()),
                    "estimated_reward", CommandManager.estimatedShare(arena, id).toPlainString()));
            if ((!lifetime.containsKey(id) || samples % 60 == 0) && pending.computeIfAbsent(id, ignored -> new AtomicBoolean()).compareAndSet(false, true)) {
                plugin.getEconomyService().stats(id).whenComplete((stats, failure) -> {
                    if (!closed && failure == null) lifetime.put(id, stats);
                    AtomicBoolean loading = pending.get(id);
                    if (loading != null) loading.set(false);
                });
            }
        }
        // Retain only connected players; disconnect/reconnect refreshes both caches.
        var online = plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        live.keySet().retainAll(online); lifetime.keySet().retainAll(online); pending.keySet().retainAll(online);
    }

    @Override public String onRequest(OfflinePlayer player, String parameter) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        String key = parameter.toLowerCase(Locale.ROOT);
        Map<String,String> current = live.get(id);
        if (current != null && current.containsKey(key)) return current.get(key);
        PlayerStatistics stats = lifetime.get(id);
        return switch (key) {
            case "sessions" -> stats == null ? "loading" : Long.toString(stats.sessions());
            case "total_kills" -> stats == null ? "loading" : Long.toString(stats.kills());
            case "highest_wave" -> stats == null ? "loading" : Integer.toString(stats.highestWave());
            case "total_earned" -> stats == null ? "loading" : stats.earned().toPlainString();
            case "arena", "state", "wave", "completed_waves", "kills", "pot", "alive", "estimated_reward" -> "loading";
            default -> null;
        };
    }

    @Override public void close() {
        closed = true;
        if (refresh != null) refresh.cancel();
        unregister(); live.clear(); lifetime.clear(); pending.clear();
    }
}
