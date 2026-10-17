package cloud.mineplay.mobarena.website;

import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

/** Durable delivery only. This class has no Bukkit, Vault, or direct SQL access. */
public final class WebsitePublisher implements AutoCloseable {
    /** Implementations enqueue these short database operations on the existing ledger worker. */
    public interface Ledger {
        CompletableFuture<List<WebsiteExportStore.Event>> events(Instant now, int limit);
        CompletableFuture<Void> delivered(UUID eventId, long storedRevision, Instant now);
        CompletableFuture<Void> failed(UUID eventId, String safeCode, Instant nextAttempt, boolean quarantine);
    }

    private final WebsiteExportSettings settings;
    private final Ledger ledger;
    private final WebsiteTransport transport;
    private final Clock clock;
    private final DoubleSupplier random;
    private final Duration databaseTimeout;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean suspended = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<?>> databaseOperation = new AtomicReference<>();
    private volatile String lastFailureCode;

    public WebsitePublisher(WebsiteExportSettings settings, Ledger ledger) {
        this(settings, ledger, settings.enabled() ? new HttpWebsiteTransport(settings) : null,
                Clock.systemUTC(), () -> ThreadLocalRandom.current().nextDouble());
    }

    WebsitePublisher(WebsiteExportSettings settings, Ledger ledger, WebsiteTransport transport,
            Clock clock, DoubleSupplier random) {
        this(settings, ledger, transport, clock, random, Duration.ofSeconds(30));
    }

    WebsitePublisher(WebsiteExportSettings settings, Ledger ledger, WebsiteTransport transport,
            Clock clock, DoubleSupplier random, Duration databaseTimeout) {
        this.settings = settings;
        this.ledger = ledger;
        this.transport = transport;
        this.clock = clock;
        this.random = random;
        this.databaseTimeout = databaseTimeout;
        if (databaseTimeout.toMillis() < 1) throw new IllegalArgumentException("EXPORT_DATABASE_TIMEOUT_INVALID");
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SimpleMobArena-website-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (!settings.enabled() || closed.get() || !started.compareAndSet(false, true)) return;
        worker.scheduleWithFixedDelay(this::runBatch, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Explicit operator retry after a credential/configuration repair. Never changes event identity or bytes. */
    public void resume() { if (!closed.get()) suspended.set(false); }

    public boolean paused() { return !settings.enabled() || !started.get() || closed.get() || suspended.get(); }
    public String lastFailureCode() { return lastFailureCode; }

    void runBatch() {
        if (!settings.enabled() || closed.get() || suspended.get() || databaseBusy()) return;
        List<WebsiteExportStore.Event> events;
        try {
            events = await(ledger.events(clock.instant(), settings.batchSize()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            lastFailureCode = "QUEUE_READ_FAILED";
            return;
        }
        if (events == null || events.size() > settings.batchSize()) {
            lastFailureCode = "QUEUE_BATCH_INVALID";
            return;
        }
        for (WebsiteExportStore.Event event : events) {
            if (closed.get() || suspended.get() || databaseBusy() || Thread.currentThread().isInterrupted()) return;
            try { deliver(event); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                // No exception text: transport/provider messages may contain URLs, headers or secrets.
                lastFailureCode = "PUBLISHER_FAILURE";
                return;
            }
        }
    }

    private void deliver(WebsiteExportStore.Event event) throws InterruptedException {
        WebsiteTransport.Response response;
        try { response = transport.send(event); }
        catch (HttpTimeoutException exception) { fail(event, "REQUEST_TIMEOUT", null, false); return; }
        catch (HttpWebsiteTransport.ResponseTooLargeException exception) { fail(event, "RESPONSE_TOO_LARGE", null, false); return; }
        catch (IOException exception) { fail(event, "NETWORK_ERROR", null, false); return; }
        catch (RuntimeException exception) {
            if (!closed.get()) fail(event, "TRANSPORT_FAILURE", null, false);
            return;
        }
        if (closed.get()) return;
        int status = response.status();
        if (status == 200) {
            long storedRevision;
            try {
                if (response.contentType() == null
                        || !"application/json".equalsIgnoreCase(response.contentType().split(";", 2)[0].trim()))
                    throw new IOException("INVALID_ACKNOWLEDGEMENT");
                storedRevision = WebsiteAcknowledgement.revision(response.body(), event.eventId(), event.revision());
            } catch (IOException exception) { fail(event, "INVALID_ACKNOWLEDGEMENT", null, false); return; }
            try {
                await(ledger.delivered(event.eventId(), storedRevision, clock.instant()));
                lastFailureCode = null;
            } catch (TimeoutException exception) {
                // This mutation may still commit. Wait for it before submitting another database operation.
                lastFailureCode = "DELIVERY_MARK_TIMEOUT";
            } catch (ExecutionException | RuntimeException exception) {
                fail(event, "DELIVERY_MARK_FAILED", null, false);
            }
            return;
        }
        String code = status >= 100 && status <= 599 ? "HTTP_" + status : "HTTP_UNEXPECTED_STATUS";
        boolean quarantine = status >= 300 && status < 400
                || status >= 400 && status < 500 && status != 408 && status != 429;
        // An operator retry can run on the ledger thread immediately after the failure commits.
        // Suspend first so that its explicit resume cannot be overwritten when our await returns.
        if (status == 401 || status == 403) suspended.set(true);
        fail(event, code, response.retryAfter(), quarantine);
    }

    private void fail(WebsiteExportStore.Event event, String code, String retryAfter, boolean quarantine)
            throws InterruptedException {
        lastFailureCode = code;
        if (closed.get()) return;
        Instant now = clock.instant();
        try { await(ledger.failed(event.eventId(), code, retryAt(now, event.attempts(), retryAfter), quarantine)); }
        catch (ExecutionException | TimeoutException | RuntimeException exception) {
            // The persisted event remains eligible if the database could not save retry metadata.
            lastFailureCode = "QUEUE_FAILURE_WRITE_FAILED";
        }
    }

    Instant retryAt(Instant now, int attempts, String retryAfter) {
        long maximum = settings.retryMaximum().toMillis();
        long backoff = settings.retryBase().toMillis();
        for (int step = 0; step < Math.min(Math.max(0, attempts), 63) && backoff < maximum; step++) {
            backoff = backoff > maximum / 2 ? maximum : Math.min(maximum, backoff * 2);
        }
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample)) sample = 0.5;
        double factor = 0.75 + Math.min(1, Math.max(0, sample)) * 0.25;
        long delay = Math.max(1, Math.min(maximum, (long) (backoff * factor)));
        delay = Math.max(delay, retryAfterMillis(now, retryAfter, maximum));
        return now.plusMillis(delay);
    }

    private static long retryAfterMillis(Instant now, String value, long maximum) {
        if (value == null || value.length() > 128) return 0;
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]{1,30}")) {
            return new BigInteger(trimmed).multiply(BigInteger.valueOf(1000))
                    .min(BigInteger.valueOf(maximum)).longValue();
        }
        try {
            Instant date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0, Math.min(maximum, Duration.between(now, date).toMillis()));
        } catch (DateTimeParseException | ArithmeticException exception) { return 0; }
    }

    private boolean databaseBusy() {
        CompletableFuture<?> pending = databaseOperation.get();
        return pending != null && !pending.isDone();
    }

    private <T> T await(CompletableFuture<T> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        databaseOperation.set(future);
        // Cancelling this future would not reliably cancel an already-running SQL transaction.
        // Keep at most one outstanding adapter operation, even across database wait timeouts.
        future.whenComplete((result, failure) -> databaseOperation.compareAndSet(future, null));
        return future.get(databaseTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow();
        if (transport != null) transport.close();
        try { worker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
