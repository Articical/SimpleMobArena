package cloud.mineplay.mobarena.achievements;

import cloud.mineplay.mobarena.economy.AchievementProgress;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.integration.AchievementIntegration;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import uk.co.mineplay.achievements.api.Achievements;

/** Optional provider bridge. SQL stays in EconomyService; API futures are never waited on. */
public final class MineplayAchievementsBridge implements AchievementIntegration, Listener {
    private static final int PAGE_SIZE = 64;
    private static final int MAX_PENDING = 256;
    private static final int PLAYERS_PER_SECOND = 4;
    private static final int MAX_LIVE_CHAINS = 16;
    private static final long RESCAN_TICKS = 5 * 60 * 20;
    private final JavaPlugin plugin;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final ProgressReader reader;
    private final AchievementCatalog catalog;
    private final LinkedHashSet<UUID> pending = new LinkedHashSet<>();
    private final LinkedHashMap<UUID, LiveState> live = new LinkedHashMap<>(16, .75f, true);
    private volatile boolean closed;
    private volatile long generation;
    private volatile Achievements candidate;
    private volatile Achievements published;
    private boolean started, registering, syncBusy, pageBusy, replaying, replayAgain;
    private UUID inFlight, cursor;
    private long tick, registrationRetryAt, syncRetryAt, pageRetryAt, nextRescan;
    private int registrationBackoff = 1, syncBackoff = 1, pageBackoff = 1, budget;
    private int liveInFlight;
    private String lastFailure;
    private BukkitTask timer;

    public MineplayAchievementsBridge(JavaPlugin plugin, EconomyService economy) throws Exception {
        this(plugin, new ProgressReader() {
            @Override public CompletableFuture<AchievementProgress> progress(UUID player) { return economy.achievementProgress(player); }
            @Override public CompletableFuture<List<UUID>> players(UUID after, int limit) { return economy.achievementPlayers(after, limit); }
        }, AchievementCatalog.load(new File(plugin.getDataFolder(), "achievements.yml")));
    }

    MineplayAchievementsBridge(JavaPlugin plugin, ProgressReader reader, AchievementCatalog catalog) {
        this.plugin = Objects.requireNonNull(plugin);
        this.server = plugin.getServer();
        this.scheduler = server.getScheduler();
        this.reader = Objects.requireNonNull(reader);
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override public void start() {
        requireMainThread();
        if (started || closed) return;
        started = true;
        if (!catalog.enabled()) return;
        server.getPluginManager().registerEvents(this, plugin);
        timer = scheduler.runTaskTimer(plugin, this::tick, 20, 20);
        discover();
    }

    @EventHandler public void registered(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == Achievements.class) discover();
    }

    @EventHandler public void unregistered(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == Achievements.class) {
            if (event.getProvider().getProvider() == candidate) replaceService(null);
            discover();
        }
    }

    @EventHandler public void joined(PlayerJoinEvent event) { requestSync(event.getPlayer().getUniqueId()); }

    @Override public boolean requestSync(UUID player) {
        Objects.requireNonNull(player);
        if (closed || !catalog.enabled()) return false;
        if (!server.isPrimaryThread()) return onMain(() -> requestSync(player));
        enqueue(player);
        pump();
        return true;
    }

    /** Only accepts snapshots whose kill totals have already committed to the activity ledger. */
    @Override public void reportLive(AchievementProgress progress) {
        Objects.requireNonNull(progress);
        if (closed || !catalog.enabled()) return;
        if (!server.isPrimaryThread()) { onMain(() -> reportLive(progress)); return; }
        if (published == null) { requestSync(progress.playerId()); return; }
        LiveState state = live.get(progress.playerId());
        if (state == null) {
            if (live.size() >= MAX_PENDING) {
                UUID idle = live.entrySet().stream().filter(entry -> !entry.getValue().busy)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
                if (idle == null) { requestSync(progress.playerId()); return; }
                LiveState removed = live.remove(idle);
                if (removed.latest != null) enqueue(idle);
            }
            state = new LiveState(progress.playerId());
            live.put(progress.playerId(), state);
        }
        state.latest = progress;
        pumpLive();
    }

    private void pumpLive() {
        if (closed || published == null || liveInFlight >= MAX_LIVE_CHAINS) return;
        for (LiveState state : live.values()) {
            if (!state.busy && state.latest != null && tick >= state.retryAt) sendLive(state);
            if (liveInFlight >= MAX_LIVE_CHAINS) break;
        }
    }

    private void sendLive(LiveState state) {
        AchievementProgress progress = state.latest;
        state.latest = null;
        Map<String, Long> changed = new LinkedHashMap<>();
        for (var source : catalog.sources()) {
            String local = source.id().substring(AchievementCatalog.NAMESPACE.length() + 1);
            if (!local.endsWith("_kills")) continue;
            long total = progress.totals().getOrDefault(local, 0L);
            if (total > state.reported.getOrDefault(source.id(), 0L)) changed.put(source.id(), total);
        }
        if (changed.isEmpty()) return;
        state.busy = true;
        liveInFlight++;
        long currentGeneration = generation;
        Achievements service = published;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var entry : changed.entrySet()) chain = chain.thenCompose(ignored ->
            call(currentGeneration, service, () -> service.reportLiveProgress(state.player, entry.getKey(), entry.getValue())));
        chain.whenComplete((ignored, failure) -> onMain(() -> {
            if (!current(currentGeneration, service) || published != service) return;
            state.busy = false;
            liveInFlight--;
            if (failure != null) {
                if (state.latest == null) state.latest = progress;
                state.retryAt = tick + state.backoff * 20L;
                failed("live progress", failure, state.backoff);
                state.backoff = backoff(state.backoff);
                requestSync(state.player);
            } else {
                state.reported.putAll(changed);
                state.backoff = 1;
            }
            pumpLive();
        }));
    }

    private void enqueue(UUID player) {
        if (pending.size() < MAX_PENDING || pending.contains(player)) pending.add(player);
        else replayAgain = true; // The authoritative records remain in the activity ledger.
    }

    void tick() {
        requireMainThread();
        if (closed || !catalog.enabled()) return;
        tick += 20;
        budget = PLAYERS_PER_SECOND;
        discover();
        pumpLive();
        pump();
    }

    private void discover() {
        requireMainThread();
        if (closed || !catalog.enabled()) return;
        RegisteredServiceProvider<Achievements> registration = server.getServicesManager().getRegistration(Achievements.class);
        Achievements found = registration == null || !registration.getPlugin().isEnabled() ? null : registration.getProvider();
        if (found != candidate) replaceService(found);
        if (candidate != null && published == null && !registering && tick >= registrationRetryAt) register();
    }

    private void replaceService(Achievements service) {
        generation++;
        if (inFlight != null) enqueue(inFlight);
        live.keySet().forEach(this::enqueue);
        live.clear();
        liveInFlight = 0;
        candidate = service;
        published = null;
        registering = syncBusy = pageBusy = replaying = false;
        inFlight = cursor = null;
        registrationRetryAt = syncRetryAt = pageRetryAt = 0;
        registrationBackoff = syncBackoff = pageBackoff = 1;
        lastFailure = null;
    }

    private void register() {
        registering = true;
        long currentGeneration = generation;
        Achievements service = candidate;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var source : catalog.sources()) chain = chain.thenCompose(ignored ->
            call(currentGeneration, service, () -> service.registerProgressSource(source)));
        for (var definition : catalog.definitions()) chain = chain.thenCompose(ignored ->
            call(currentGeneration, service, () -> service.registerAchievement(definition)));
        chain.whenComplete((ignored, failure) -> onMain(() -> {
            if (!current(currentGeneration, service)) return;
            registering = false;
            if (failure != null) {
                registrationRetryAt = tick + registrationBackoff * 20L;
                failed("catalog registration", failure, registrationBackoff);
                registrationBackoff = backoff(registrationBackoff);
                return;
            }
            published = service;
            registrationBackoff = 1;
            lastFailure = null;
            beginReplay();
            plugin.getLogger().info("Achievements: registered " + catalog.definitions().size() + " MobArena achievements.");
            pump();
        }));
    }

    private void beginReplay() {
        replaying = true;
        replayAgain = false;
        cursor = null;
        pageRetryAt = 0;
        nextRescan = tick + RESCAN_TICKS;
    }

    private void pump() {
        requireMainThread();
        if (closed || published == null) return;
        if (!replaying && !pageBusy && (replayAgain || tick >= nextRescan)) beginReplay();
        if (replaying && !pageBusy && pending.size() < PAGE_SIZE && tick >= pageRetryAt) fetchPage();
        if (!syncBusy && !pending.isEmpty() && budget > 0 && tick >= syncRetryAt) {
            UUID player = pending.removeFirst();
            budget--;
            sync(player);
        }
    }

    private void fetchPage() {
        pageBusy = true;
        long currentGeneration = generation;
        Achievements service = published;
        UUID after = cursor;
        invoke(() -> reader.players(after, PAGE_SIZE)).whenComplete((players, failure) -> onMain(() -> {
            if (!current(currentGeneration, service) || published != service) return;
            pageBusy = false;
            Throwable problem = failure;
            if (problem == null) {
                try { validatePage(players, after); }
                catch (RuntimeException invalid) { problem = invalid; }
            }
            if (problem != null) {
                pageRetryAt = tick + pageBackoff * 20L;
                failed("history replay", problem, pageBackoff);
                pageBackoff = backoff(pageBackoff);
                return;
            }
            pageBackoff = 1;
            for (UUID player : players) enqueue(player);
            if (!players.isEmpty()) cursor = players.getLast();
            if (players.size() < PAGE_SIZE) { replaying = false; nextRescan = tick + RESCAN_TICKS; }
            // A scheduled continuation avoids recursive chains when a store returns an immediate future.
            pump();
        }));
    }

    private void sync(UUID player) {
        syncBusy = true;
        inFlight = player;
        long currentGeneration = generation;
        Achievements service = published;
        invoke(() -> reader.progress(player)).thenCompose(progress -> {
            if (!player.equals(progress.playerId())) return CompletableFuture.failedFuture(new IllegalStateException("Wrong progress player"));
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (var source : catalog.sources()) {
                String local = source.id().substring(AchievementCatalog.NAMESPACE.length() + 1);
                long total = progress.totals().getOrDefault(local, 0L);
                chain = chain.thenCompose(ignored -> call(currentGeneration, service,
                    () -> service.setProgress(player, source.id(), total)).thenApply(snapshot -> null));
            }
            return chain;
        }).whenComplete((ignored, failure) -> onMain(() -> {
            if (!current(currentGeneration, service) || published != service) return;
            syncBusy = false;
            inFlight = null;
            if (failure != null) {
                enqueue(player);
                syncRetryAt = tick + syncBackoff * 20L;
                failed("progress sync", failure, syncBackoff);
                syncBackoff = backoff(syncBackoff);
            } else {
                syncBackoff = 1;
                lastFailure = null;
                pump();
            }
        }));
    }

    private static void validatePage(List<UUID> players, UUID after) {
        if (players == null || players.size() > PAGE_SIZE) throw new IllegalArgumentException("Invalid history page");
        String previous = after == null ? "" : after.toString();
        for (UUID player : players) {
            if (player == null || player.toString().compareTo(previous) <= 0) throw new IllegalArgumentException("History cursor did not advance");
            previous = player.toString();
        }
    }

    private <T> CompletableFuture<T> call(long expected, Achievements service, Supplier<CompletableFuture<T>> operation) {
        if (!current(expected, service)) return CompletableFuture.failedFuture(new CancellationException("Achievement service replaced"));
        return invoke(operation);
    }

    private static <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> operation) {
        try { return Objects.requireNonNull(operation.get(), "Provider returned no future"); }
        catch (RuntimeException | LinkageError failure) { return CompletableFuture.failedFuture(failure); }
    }

    private boolean current(long expected, Achievements service) {
        return !closed && generation == expected && candidate == service;
    }

    private boolean onMain(Runnable action) {
        if (closed) return false;
        try {
            scheduler.runTask(plugin, () -> { if (!closed) action.run(); });
            return true;
        }
        catch (RuntimeException schedulingFailure) {
            if (!closed) plugin.getLogger().warning("Achievements: callback could not be scheduled; durable totals will replay after restart.");
            return false;
        }
    }

    private void failed(String operation, Throwable failure, int seconds) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        lastFailure = operation + " (" + cause.getClass().getSimpleName() + ")";
        plugin.getLogger().warning("Achievements: " + lastFailure + "; retrying in " + seconds + "s. Arena records are retained.");
    }

    private static int backoff(int seconds) { return Math.min(60, seconds * 2); }

    @Override public String status() {
        requireMainThread();
        if (closed) return "Stopped";
        if (!catalog.enabled()) return "Disabled in achievements.yml";
        if (candidate == null) return "Waiting for MineplayAchievements; arena records are retained";
        if (published == null) return registering ? "Registering MobArena achievements" : "Registration pending: " + lastFailure;
        return "Ready; " + pending.size() + " players queued" + (replaying || pageBusy ? "; replaying stored totals" : "")
            + (lastFailure == null ? "" : "; retrying " + lastFailure);
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        generation++;
        if (timer != null) timer.cancel();
        HandlerList.unregisterAll(this);
        pending.clear();
        live.clear();
        candidate = published = null;
        inFlight = cursor = null;
    }

    private void requireMainThread() {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Achievements lifecycle must run on the server thread");
    }

    interface ProgressReader {
        CompletableFuture<AchievementProgress> progress(UUID player);
        CompletableFuture<List<UUID>> players(UUID after, int limit);
    }

    private static final class LiveState {
        final UUID player;
        final Map<String, Long> reported = new LinkedHashMap<>();
        AchievementProgress latest;
        boolean busy;
        long retryAt;
        int backoff = 1;

        LiveState(UUID player) { this.player = player; }
    }
}
