package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementOutbox;
import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import cloud.mineplay.mobarena.website.WebsiteExportSettings;
import cloud.mineplay.mobarena.website.WebsitePublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread Vault calls, single background ledger writer, and conservative crash recovery. */
public final class EconomyService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final BukkitScheduler scheduler;
    private final VaultGateway gateway;
    private final StoreFactory storeFactory;
    private final SettlementNotifier notifications;
    private final Clock paymentClock;
    private final PaymentDelay paymentDelay = new PaymentDelay();
    private volatile int paymentDelaySeconds = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMobArena-ledger");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService outboxWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMobArena-outbox");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<SettlementStore> ready;
    private final DatabaseSettings database;
    private final SettlementOutbox outbox;
    private final AtomicBoolean pumping = new AtomicBoolean();
    private final BukkitTask pumpTask;
    private final BukkitTask reconnectTask;
    private final BukkitTask websiteBackfillTask;
    private final WebsiteExportSettings websiteSettings;
    private final PublisherFactory publisherFactory;
    private final AtomicBoolean backfilling = new AtomicBoolean();
    private volatile WebsitePublisher websitePublisher;
    private volatile boolean websiteBackfillActive;
    private volatile String websiteError;
    private volatile EconomySettings settings = EconomySettings.defaults();
    private volatile String configurationError;
    private volatile String ledgerError;
    private volatile boolean closing;

    public EconomyService(JavaPlugin plugin) {
        this(plugin, new VaultGateway(plugin), SettlementStore::new);
    }

    EconomyService(JavaPlugin plugin, VaultGateway gateway, StoreFactory storeFactory) {
        this(plugin, gateway, storeFactory, null, WebsitePublisher::new);
    }

    EconomyService(JavaPlugin plugin, VaultGateway gateway, StoreFactory storeFactory,
                   WebsiteExportSettings suppliedWebsiteSettings, PublisherFactory publisherFactory) {
        this(plugin, gateway, storeFactory, suppliedWebsiteSettings, publisherFactory, Clock.systemUTC());
    }

    EconomyService(JavaPlugin plugin, VaultGateway gateway, StoreFactory storeFactory,
                   WebsiteExportSettings suppliedWebsiteSettings, PublisherFactory publisherFactory, Clock paymentClock) {
        this.plugin = plugin;
        logger = plugin.getLogger();
        scheduler = plugin.getServer().getScheduler();
        this.gateway = gateway;
        this.storeFactory = storeFactory;
        this.publisherFactory = publisherFactory;
        this.paymentClock = paymentClock;
        notifications = new SettlementNotifier(plugin, () -> closing);
        database = DatabaseSettings.load(plugin.getConfig().getConfigurationSection("storage"), plugin.getDataFolder().toPath());
        outbox = new SettlementOutbox(plugin.getDataFolder().toPath().resolve("settlement-outbox"));
        WebsiteExportSettings exportSettings;
        try {
            exportSettings = suppliedWebsiteSettings != null ? suppliedWebsiteSettings
                    : WebsiteExportSettings.load(plugin.getConfig().getConfigurationSection("website-export"));
        } catch (IllegalArgumentException error) {
            exportSettings = WebsiteExportSettings.disabled();
            websiteError = safeWebsiteCode(error, "CONFIG_INVALID");
            logger.warning("Website export paused: " + websiteError + ". Correct website-export configuration and restart.");
        }
        websiteSettings = exportSettings;
        initialize(null);
        reload(plugin.getConfig().getConfigurationSection("economy"));
        pumpTask = scheduler.runTaskTimer(plugin, this::pump, 20L, 20L);
        reconnectTask = scheduler.runTaskTimer(plugin, () -> {
            if (!closing && ledgerError != null && ready.isDone() && !pumping.get()) initialize(ready);
        }, 600L, 600L);
        websiteBackfillTask = scheduler.runTaskTimer(plugin, this::pumpWebsiteBackfill, 40L, 40L);
    }

    public boolean reload(ConfigurationSection section) {
        try {
            EconomySettings loaded = EconomySettings.load(section);
            int delay = PaymentDelay.seconds(section);
            settings = loaded;
            paymentDelaySeconds = delay;
            configurationError = null;
            return true;
        } catch (IllegalArgumentException exception) {
            configurationError = exception.getMessage();
            logger.severe("Payouts disabled by invalid economy configuration: " + configurationError);
            return false;
        }
    }

    public BigDecimal waveReward(int wave, int startingPlayers) { return RewardCalculator.waveReward(settings, wave, startingPlayers); }
    public BigDecimal capPot(BigDecimal pot) { return RewardCalculator.capPot(settings, pot); }
    public EconomySettings rewardSettings() { return effectiveSettings(); }

    public CompletableFuture<Void> finish(SessionResult result) {
        EconomySettings snapshot = effectiveSettings();
        if (closing) return CompletableFuture.failedFuture(new IllegalStateException("Ledger is shutting down"));
        CompletableFuture<SettlementStore> current = ready;
        return CompletableFuture.runAsync(() -> {
            try { outbox.save(result, snapshot); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, outboxWriter).thenRunAsync(() -> {
            try {
                // File durability is independent of a blocked remote SQL worker.
                SettlementStore store = current.join();
                boolean created = store.prepare(result, snapshot, Instant.now());
                notifications.prepared(store, result.arenaId(), result.sessionId(), snapshot);
                outbox.remove(result.sessionId());
                if (snapshot.auditLogging()) logger.info("Arena settlement " + result.sessionId() + " arena=" + result.arenaId()
                        + " pot=" + result.prizePot() + " cancelled=" + result.cancelled() + " created=" + created);
            } catch (Exception exception) { throw new CompletionException(exception); }
        }, worker).whenComplete((ignored, error) -> { if (error != null) failLedger(error); });
    }

    public CompletableFuture<PlayerStatistics> stats(UUID player) {
        return submit(store -> { try { return store.stats(player); } catch (Exception error) { throw new CompletionException(error); } });
    }

    public CompletableFuture<List<PlayerStatistics>> top(int limit) {
        return submit(store -> { try { return store.top(Math.min(100, Math.max(1, limit))); } catch (Exception error) { throw new CompletionException(error); } });
    }

    public CompletableFuture<List<Settlement>> payouts(UUID session) {
        return submit(store -> { try { return store.payouts(session); } catch (Exception error) { throw new CompletionException(error); } });
    }

    public CompletableFuture<List<MatchPerformance>> history(UUID player, int page) {
        return submit(store -> { try { return store.history(player, page); } catch (Exception error) { throw new CompletionException(error); } });
    }

    public CompletableFuture<List<MatchPerformance>> recentMatches(UUID player, int page) { return history(player, page); }

    public CompletableFuture<MatchDetails> match(UUID session) {
        return submit(store -> { try { return store.match(session); } catch (Exception error) { throw new CompletionException(error); } });
    }

    public CompletableFuture<String> reconcile(UUID settlement, String decision, String actor) {
        return submit(store -> {
            try {
                String result = store.reconcile(settlement, decision, actor, Instant.now());
                logger.warning("Settlement reconciliation actor=" + actor + " decision=" + decision + " " + result);
                return result;
            } catch (Exception error) { throw new CompletionException(error); }
        });
    }

    /** Administrative reads and mutations enqueue SQL work; callers never wait on the server thread. */
    public CompletableFuture<WebsiteStatus> websiteStatus() {
        return submit(store -> {
            try {
                WebsitePublisher publisher = websitePublisher;
                String code = websiteError != null ? websiteError : publisher == null ? null : publisher.lastFailureCode();
                return new WebsiteStatus(store.websiteStatus(), websiteSettings.enabled(),
                        websiteError != null || publisher == null || publisher.paused(), code);
            } catch (Exception error) { throw websiteFailure(); }
        });
    }

    public CompletableFuture<Void> websiteBackfill() {
        if (!websiteSettings.enabled() || websiteError != null)
            return CompletableFuture.failedFuture(new IllegalStateException("Website export must be configured and enabled before backfill."));
        return submit(store -> {
            try {
                store.websiteStartBackfill(Instant.now());
                websiteBackfillActive = true;
                return null;
            } catch (Exception error) { throw websiteFailure(); }
        });
    }

    public CompletableFuture<Boolean> websiteRetry(UUID eventId) {
        if (!websiteSettings.enabled() || websiteError != null)
            return CompletableFuture.failedFuture(new IllegalStateException("Website export must be configured and enabled before retry."));
        return submit(store -> {
            try {
                boolean changed = store.websiteRetry(eventId, Instant.now());
                WebsitePublisher publisher = websitePublisher;
                if (changed && publisher != null) publisher.resume();
                return changed;
            } catch (Exception error) { throw websiteFailure(); }
        });
    }

    private void pumpWebsiteBackfill() {
        if (closing || !websiteBackfillActive || websiteError != null || ledgerError != null
                || !ready.isDone() || !backfilling.compareAndSet(false, true)) return;
        submit(store -> {
            try { return store.websiteBackfillBatch(25, Instant.now()); }
            catch (Exception error) { throw websiteFailure(); }
        }).whenComplete((processed, error) -> {
            backfilling.set(false);
            if (error != null) {
                websiteBackfillActive = false;
                logger.warning("Website backfill paused: BACKFILL_STORAGE_FAILURE. Its durable cursor is retained; restart or request backfill to resume.");
            } else if (processed == 0) websiteBackfillActive = false;
        });
    }

    private synchronized void startWebsitePublisher() {
        if (closing || !websiteSettings.enabled() || websiteError != null || websitePublisher != null) return;
        try {
            websitePublisher = publisherFactory.create(websiteSettings, new WebsitePublisher.Ledger() {
                @Override public CompletableFuture<List<WebsiteExportStore.Event>> events(Instant now, int limit) {
                    return submit(store -> {
                        try { return store.websiteEvents(now, limit); } catch (Exception error) { throw websiteFailure(); }
                    });
                }
                @Override public CompletableFuture<Void> delivered(UUID eventId, long revision, Instant now) {
                    return submit(store -> {
                        try { store.websiteDelivered(eventId, revision, now); return null; }
                        catch (Exception error) { throw websiteFailure(); }
                    });
                }
                @Override public CompletableFuture<Void> failed(UUID eventId, String code, Instant nextAttempt, boolean quarantine) {
                    return submit(store -> {
                        try { store.websiteFailed(eventId, code, nextAttempt, quarantine); return null; }
                        catch (Exception error) { throw websiteFailure(); }
                    });
                }
            });
            websitePublisher.start();
        } catch (RuntimeException error) {
            websiteError = "PUBLISHER_START_FAILED";
            logger.warning("Website export paused: " + websiteError);
        }
    }

    private static CompletionException websiteFailure() {
        // JDBC diagnostics may contain connection details: export status exposes a bounded code only.
        return new CompletionException(new IllegalStateException("WEBSITE_STORAGE_FAILURE"));
    }

    private static String safeWebsiteCode(Exception error, String fallback) {
        String code = error.getMessage();
        return code != null && code.matches("[A-Z0-9_]{1,80}") ? code : fallback;
    }

    public record WebsiteStatus(WebsiteExportStore.Status queue, boolean enabled, boolean paused, String failureCode) { }

    public String status() {
        if (ledgerError != null) return "Payouts stopped: ledger error: " + ledgerError;
        if (configurationError != null) return "Payouts disabled: invalid configuration: " + configurationError;
        if (!settings.enabled()) return "Payouts administratively disabled; existing pending payments are paused.";
        if (!ready.isDone()) return "Ledger starting; payments paused.";
        gateway.available(settings.scale());
        return gateway.description() + "; daily limits reset at 00:00 UTC; unresolved payments continue to reserve limits.";
    }

    private void pump() {
        if (closing || configurationError != null || ledgerError != null || !settings.enabled() || !ready.isDone()
                || !gateway.available(settings.scale()) || !pumping.compareAndSet(false, true)) return;
        EconomySettings paymentSettings = settings;
        int delaySeconds = paymentDelaySeconds;
        submit(store -> {
            Instant now = paymentClock.instant();
            try { return SettlementPayments.claimNext(store, paymentSettings, true, now,
                    id -> { paymentDelay.forget(id); notifications.payment(store, id, true); },
                    payment -> paymentDelay.ready(payment.settlementId(), now, delaySeconds)); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }).whenComplete((payment, error) -> {
            if (error != null) { pumping.set(false); failLedger(error); return; }
            if (payment == null) { pumping.set(false); return; }
            if (closing) { pumping.set(false); return; } // Durable PROCESSING becomes UNCERTAIN on startup.
            try {
                scheduler.runTask(plugin, () -> payOnMainThread(payment, paymentSettings));
            } catch (RuntimeException exception) {
                pumping.set(false);
                logger.log(Level.WARNING, "Payment left PROCESSING for conservative restart recovery", exception);
            }
        });
    }

    private void payOnMainThread(Settlement payment, EconomySettings paymentSettings) {
        if (closing) { pumping.set(false); return; }
        PaymentOutcome outcome = configurationError != null || ledgerError != null || !settings.enabled()
                ? new PaymentOutcome(Settlement.Status.PENDING, "Payouts disabled before external payment; no deposit attempted")
                : gateway.deposit(payment.playerId(), payment.intendedAmount(), paymentSettings.scale());
        submit(store -> {
            try {
                store.complete(payment.settlementId(), outcome.status(), outcome.detail(), Instant.now());
                notifications.committed(payment.settlementId(), outcome.status());
                notifications.payment(store, payment.settlementId(), false);
                if (paymentSettings.auditLogging() || outcome.status() != Settlement.Status.CONFIRMED) {
                    logger.info("Payment " + payment.settlementId() + " player=" + payment.playerId() + " amount="
                            + payment.intendedAmount().toPlainString() + " status=" + outcome.status() + " detail=" + outcome.detail());
                }
                return null;
            } catch (Exception exception) { throw new CompletionException(exception); }
        }).whenComplete((ignored, error) -> {
            pumping.set(false);
            if (error != null) failLedger(error);
        });
    }

    private EconomySettings effectiveSettings() {
        EconomySettings current = settings;
        if (configurationError == null) return current;
        return new EconomySettings(false, current.baseWaveReward(), current.multiplyByWave(), current.playerScaling(),
                current.additionalPlayerMultiplier(), current.maximumMultiplier(), current.maximumPot(), current.maximumPlayerPayout(),
                current.maximumDailyPayout(), current.rewardCooldown(), current.minimumRewardWave(), current.minimumParticipationSeconds(),
                current.scale(), current.shortSessionSeconds(), current.shortSessionMultiplier(), current.auditLogging());
    }

    private <T> CompletableFuture<T> submit(Function<SettlementStore, T> action) {
        if (closing) return CompletableFuture.failedFuture(new IllegalStateException("Ledger is shutting down"));
        // Queue immediately behind startup, so shutdown drains writes submitted before startup completed.
        CompletableFuture<SettlementStore> current = ready;
        return CompletableFuture.supplyAsync(() -> action.apply(current.join()), worker);
    }

    private void initialize(CompletableFuture<SettlementStore> previous) {
        ready = CompletableFuture.supplyAsync(() -> {
            SettlementStore opened = null;
            try {
                if (previous != null && !previous.isCompletedExceptionally()) previous.join().close();
                opened = storeFactory.open(database);
                try {
                    opened.configureWebsiteExport(websiteSettings.enabled(), websiteSettings.sourceId(), Instant.now());
                    if (websiteSettings.enabled()) websiteBackfillActive = opened.websiteStatus().backfillActive();
                } catch (IllegalArgumentException error) {
                    websiteError = safeWebsiteCode(error, "SOURCE_CONFIGURATION_FAILED");
                    opened.configureWebsiteExport(false, null, Instant.now());
                    logger.warning("Website export paused: " + websiteError + ". Existing source identity and queued events are retained.");
                }
                int recovered = opened.recover(Instant.now());
                if (recovered > 0) logger.warning("Recovered " + recovered + " UNCERTAIN payments. Verify provider records before reconciliation.");
                for (SettlementOutbox.Entry entry : outbox.entries()) {
                    opened.prepare(entry.result(), entry.settings(), Instant.now());
                    notifications.prepared(opened, entry.result().arenaId(), entry.result().sessionId(), entry.settings());
                    outbox.remove(entry.result().sessionId());
                    logger.info("Recovered durable session result " + entry.result().sessionId());
                }
                ledgerError = null;
                return opened;
            } catch (Exception exception) {
                if (opened != null) try { opened.close(); } catch (Exception close) { exception.addSuppressed(close); }
                throw new CompletionException(exception);
            }
        }, worker);
        ready.whenComplete((store, error) -> {
            if (error != null) failLedger(error);
            else startWebsitePublisher();
        });
    }

    private void failLedger(Throwable error) {
        Throwable actual = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        ledgerError = actual.getClass().getSimpleName() + ": " + actual.getMessage();
        logger.log(Level.SEVERE, "Arena payments stopped to preserve the ledger. Any PROCESSING payments require restart recovery and reconciliation.", actual);
    }

    @FunctionalInterface
    interface StoreFactory { SettlementStore open(DatabaseSettings settings) throws Exception; }
    @FunctionalInterface
    interface PublisherFactory { WebsitePublisher create(WebsiteExportSettings settings, WebsitePublisher.Ledger ledger); }

    @Override public void close() {
        closing = true;
        synchronized (this) {
            if (websitePublisher != null) websitePublisher.close();
        }
        pumpTask.cancel();
        reconnectTask.cancel();
        websiteBackfillTask.cancel();
        // Drain local handoffs before shutting down SQL, even when its connection is stalled.
        outboxWriter.shutdown();
        try {
            if (!outboxWriter.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.severe("Local settlement outbox is still writing. Do not force-stop the process; preserve settlement-outbox for recovery.");
            }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        // Queued writes finish first. No Vault call is made during shutdown.
        CompletableFuture<SettlementStore> current = ready;
        worker.execute(() -> {
            if (current.isCompletedExceptionally()) return;
            try { current.join().close(); } catch (Exception exception) { logger.log(Level.SEVERE, "Could not close settlement ledger", exception); }
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) logger.warning("Ledger worker still finishing; preserve mobarena.db and WAL for recovery.");
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
