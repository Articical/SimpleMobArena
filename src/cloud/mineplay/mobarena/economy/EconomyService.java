package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import cloud.mineplay.mobarena.storage.DatabaseSettings;
import cloud.mineplay.mobarena.storage.SettlementOutbox;
import java.math.BigDecimal;
import java.nio.file.Path;
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
    private volatile EconomySettings settings = EconomySettings.defaults();
    private volatile String configurationError;
    private volatile String ledgerError;
    private volatile boolean closing;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
        logger = plugin.getLogger();
        scheduler = plugin.getServer().getScheduler();
        gateway = new VaultGateway(plugin);
        database = DatabaseSettings.load(plugin.getConfig().getConfigurationSection("storage"), plugin.getDataFolder().toPath());
        outbox = new SettlementOutbox(plugin.getDataFolder().toPath().resolve("settlement-outbox"));
        initialize(null);
        reload(plugin.getConfig().getConfigurationSection("economy"));
        pumpTask = scheduler.runTaskTimer(plugin, this::pump, 20L, 20L);
        reconnectTask = scheduler.runTaskTimer(plugin, () -> {
            if (!closing && ledgerError != null && ready.isDone() && !pumping.get()) initialize(ready);
        }, 600L, 600L);
    }

    public boolean reload(ConfigurationSection section) {
        try {
            settings = EconomySettings.load(section);
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
        submit(store -> {
            try { return SettlementPayments.claimNext(store, paymentSettings, true, Instant.now()); }
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
                opened = new SettlementStore(database);
                int recovered = opened.recover(Instant.now());
                if (recovered > 0) logger.warning("Recovered " + recovered + " UNCERTAIN payments. Verify provider records before reconciliation.");
                for (SettlementOutbox.Entry entry : outbox.entries()) {
                    opened.prepare(entry.result(), entry.settings(), Instant.now());
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
        ready.whenComplete((store, error) -> { if (error != null) failLedger(error); });
    }

    private void failLedger(Throwable error) {
        Throwable actual = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        ledgerError = actual.getClass().getSimpleName() + ": " + actual.getMessage();
        logger.log(Level.SEVERE, "Arena payments stopped to preserve the ledger. Any PROCESSING payments require restart recovery and reconciliation.", actual);
    }

    @Override public void close() {
        closing = true;
        pumpTask.cancel();
        reconnectTask.cancel();
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
