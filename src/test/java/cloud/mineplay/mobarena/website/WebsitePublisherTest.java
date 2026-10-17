package cloud.mineplay.mobarena.website;

import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebsitePublisherTest {
    private static final Instant NOW = Instant.parse("2026-10-17T18:03:04Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static WebsiteExportStore.Event event() {
        return new WebsiteExportStore.Event(UUID.randomUUID(), UUID.randomUUID(), 3,
                "{\"immutable_fixture\":\".Bedrock player \u00a3\"}\n".getBytes(StandardCharsets.UTF_8), 0);
    }

    private static WebsiteTransport.Response ack(WebsiteExportStore.Event event, String result, long revision) {
        return new WebsiteTransport.Response(200, null, "application/json; charset=utf-8",
                WebsiteAcknowledgementTest.acknowledgement(event.eventId(), result, Long.toString(revision)));
    }

    @FunctionalInterface interface Send {
        WebsiteTransport.Response send(WebsiteExportStore.Event event) throws IOException, InterruptedException;
    }

    private static WebsiteTransport transport(Send send) {
        return new WebsiteTransport() {
            @Override public Response send(WebsiteExportStore.Event event) throws IOException, InterruptedException {
                return send.send(event);
            }
            @Override public void close() { }
        };
    }

    private static WebsitePublisher publisher(FakeLedger ledger, Send send) {
        return new WebsitePublisher(WebsiteExportSettingsTest.settings(), ledger, transport(send), CLOCK, () -> 1);
    }

    @Test void lostResponseAndInvalidAcknowledgementRetryExactlyTheOriginalEventAndBytes() {
        WebsiteExportStore.Event original = event();
        FakeLedger ledger = new FakeLedger(original);
        List<WebsiteExportStore.Event> sent = new ArrayList<>();
        try (WebsitePublisher publisher = publisher(ledger, event -> {
            sent.add(event);
            if (sent.size() == 1) throw new IOException("lost response including private " + WebsiteExportSettingsTest.TOKEN);
            if (sent.size() == 2) return new WebsiteTransport.Response(200, null, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return ack(event, "duplicate", 4);
        })) {
            publisher.runBatch();
            assertEquals("NETWORK_ERROR", ledger.failures.getLast().code());
            assertEquals(NOW.plusSeconds(5), ledger.failures.getLast().nextAttempt());
            publisher.runBatch();
            assertEquals("INVALID_ACKNOWLEDGEMENT", ledger.failures.getLast().code());
            assertEquals(NOW.plusSeconds(10), ledger.failures.getLast().nextAttempt());
            publisher.runBatch();
            assertEquals(1, ledger.acknowledged.size());
            assertEquals(4, ledger.acknowledged.getFirst().storedRevision());
            assertNull(publisher.lastFailureCode());
        }
        assertEquals(3, sent.size());
        for (WebsiteExportStore.Event attempt : sent) {
            assertEquals(original.eventId(), attempt.eventId());
            assertEquals(original.revision(), attempt.revision());
            assertArrayEquals(original.payload(), attempt.payload());
        }
        assertFalse(ledger.failures.toString().contains(WebsiteExportSettingsTest.TOKEN));
    }

    @Test void failedDeliveryMarkRetainsTheEventForAnIdempotentRetry() {
        FakeLedger ledger = new FakeLedger(event());
        ledger.failNextDelivery = true;
        AtomicInteger sends = new AtomicInteger();
        try (WebsitePublisher publisher = publisher(ledger, event -> {
            sends.incrementAndGet();
            return ack(event, "duplicate", event.revision());
        })) {
            publisher.runBatch();
            assertEquals("DELIVERY_MARK_FAILED", ledger.failures.getFirst().code());
            assertNotNull(ledger.pending);
            publisher.runBatch();
            assertEquals(2, sends.get());
            assertNull(ledger.pending);
        }
    }

    @Test void quarantinesOperatorErrorsAndRedirectsButRetriesRateLimitsTimeoutsAndServerErrors() {
        for (int status : new int[]{302, 400, 401, 403, 404, 409, 413, 415, 422, 408, 429, 500, 502, 503}) {
            FakeLedger ledger = new FakeLedger(event());
            try (WebsitePublisher publisher = publisher(ledger,
                    event -> new WebsiteTransport.Response(status, "30", "text/plain", new byte[0]))) {
                publisher.runBatch();
                Failure failure = ledger.failures.getFirst();
                assertEquals("HTTP_" + status, failure.code());
                assertEquals(status < 400 || status >= 400 && status < 500 && status != 408 && status != 429,
                        failure.quarantine());
                assertEquals(NOW.plusSeconds(30), failure.nextAttempt());
                assertEquals(0, ledger.acknowledged.size());
            }
        }
    }

    @Test void authorizationFailuresSuspendTheSourceUntilExplicitOperatorRetry() {
        FakeLedger ledger = new FakeLedger(event());
        AtomicInteger sends = new AtomicInteger();
        try (WebsitePublisher publisher = publisher(ledger, event -> {
            if (sends.incrementAndGet() == 1) return new WebsiteTransport.Response(401, null, "text/plain", new byte[0]);
            return ack(event, "applied", event.revision());
        })) {
            publisher.runBatch();
            assertTrue(publisher.paused());
            ledger.pending = event();
            publisher.runBatch();
            assertEquals(1, sends.get());
            publisher.resume();
            publisher.runBatch();
            assertEquals(2, sends.get());
            assertEquals(1, ledger.acknowledged.size());
        }
    }

    @Test void operatorRetryAfterFailureCommitWinsBeforePublisherAwaitReturns() {
        for (int status : new int[]{401, 403}) {
            WebsiteExportStore.Event original = event();
            FakeLedger ledger = new FakeLedger(original);
            AtomicInteger sends = new AtomicInteger();
            try (WebsitePublisher publisher = publisher(ledger, event -> {
                if (sends.incrementAndGet() == 1)
                    return new WebsiteTransport.Response(status, null, "text/plain", new byte[0]);
                return ack(event, "applied", event.revision());
            })) {
                // Models the admin retry task running immediately after the failure write on the
                // ledger worker, before the publisher thread resumes from awaiting that write.
                ledger.afterFailure = () -> {
                    ledger.pending = original;
                    publisher.resume();
                };
                publisher.runBatch();
                publisher.runBatch();
                assertEquals(2, sends.get());
                assertEquals(1, ledger.acknowledged.size());
            }
        }
    }

    @Test void successRequiresJsonContentTypeAndAnAcknowledgementForThisEvent() {
        for (WebsiteTransport.Response response : List.of(
                new WebsiteTransport.Response(200, null, "text/html", "{}".getBytes(StandardCharsets.UTF_8)),
                new WebsiteTransport.Response(200, null, null, "{}".getBytes(StandardCharsets.UTF_8)),
                new WebsiteTransport.Response(200, null, "application/json",
                        WebsiteAcknowledgementTest.acknowledgement(UUID.randomUUID(), "applied", "3")))) {
            FakeLedger ledger = new FakeLedger(event());
            try (WebsitePublisher publisher = publisher(ledger, ignored -> response)) {
                publisher.runBatch();
                assertEquals("INVALID_ACKNOWLEDGEMENT", ledger.failures.getFirst().code());
                assertEquals(0, ledger.acknowledged.size());
            }
        }
    }

    @Test void boundsExponentialBackoffJitterAndBothRetryAfterFormats() {
        try (WebsitePublisher publisher = publisher(new FakeLedger(event()), event -> ack(event, "applied", 3))) {
            assertEquals(NOW.plusSeconds(5), publisher.retryAt(NOW, 0, null));
            assertEquals(NOW.plusSeconds(20), publisher.retryAt(NOW, 2, null));
            assertEquals(NOW.plusSeconds(3600), publisher.retryAt(NOW, Integer.MAX_VALUE, null));
            assertEquals(NOW.plusSeconds(3600), publisher.retryAt(NOW, 0, "9999999999999999999999999"));
            String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(120).atZone(ZoneOffset.UTC));
            assertEquals(NOW.plusSeconds(120), publisher.retryAt(NOW, 0, date));
            assertEquals(NOW.plusSeconds(5), publisher.retryAt(NOW, 0, "malformed private text"));
            assertEquals(NOW.plusSeconds(5), publisher.retryAt(NOW, 0, "-30"));
        }
        try (WebsitePublisher publisher = new WebsitePublisher(WebsiteExportSettingsTest.settings(),
                new FakeLedger(event()), transport(event -> ack(event, "applied", 3)), CLOCK, () -> 0)) {
            assertEquals(NOW.plusMillis(3750), publisher.retryAt(NOW, 0, null));
            assertEquals(NOW.plusSeconds(30), publisher.retryAt(NOW, 0, "30"));
        }
    }

    @Test void workerRunsHttpOutsideTheCallingThreadAndDisabledIntegrationNeverReadsTheLedger() throws Exception {
        FakeLedger ledger = new FakeLedger(event());
        AtomicReference<String> sendingThread = new AtomicReference<>();
        try (WebsitePublisher publisher = publisher(ledger, event -> {
            sendingThread.set(Thread.currentThread().getName());
            return ack(event, "applied", 3);
        })) {
            publisher.start();
            publisher.start();
            assertTrue(ledger.delivered.await(3, TimeUnit.SECONDS));
            assertEquals("SimpleMobArena-website-publisher", sendingThread.get());
            assertFalse(publisher.paused());
        }
        FakeLedger disabledLedger = new FakeLedger(event());
        try (WebsitePublisher publisher = new WebsitePublisher(WebsiteExportSettings.disabled(), disabledLedger,
                transport(event -> { fail("Disabled publisher sent HTTP"); return null; }), CLOCK, () -> 1)) {
            publisher.start();
            publisher.runBatch();
            assertEquals(0, disabledLedger.reads.get());
            assertTrue(publisher.paused());
        }
    }

    @Test void databaseErrorsAreContainedAndDoNotTerminateLaterDeliveryAttempts() {
        FakeLedger ledger = new FakeLedger(event());
        ledger.failNextRead = true;
        try (WebsitePublisher publisher = publisher(ledger, event -> ack(event, "applied", 3))) {
            publisher.runBatch();
            assertEquals("QUEUE_READ_FAILED", publisher.lastFailureCode());
            assertNotNull(ledger.pending);
            publisher.runBatch();
            assertEquals(1, ledger.acknowledged.size());
        }
        ledger = new FakeLedger(event());
        ledger.failFailureWrite = true;
        try (WebsitePublisher publisher = publisher(ledger, event -> { throw new IOException("private token"); })) {
            publisher.runBatch();
            assertEquals("QUEUE_FAILURE_WRITE_FAILED", publisher.lastFailureCode());
            assertNotNull(ledger.pending);
        }
    }

    @Test void shutdownInterruptsSlowHttpWithoutWaitingForTheConfiguredNetworkTimeout() throws Exception {
        FakeLedger ledger = new FakeLedger(event());
        CountDownLatch sending = new CountDownLatch(1);
        WebsitePublisher publisher = publisher(ledger, event -> {
            sending.countDown();
            new CountDownLatch(1).await(30, TimeUnit.SECONDS);
            return ack(event, "applied", 3);
        });
        publisher.start();
        assertTrue(sending.await(3, TimeUnit.SECONDS));
        long start = System.nanoTime();
        publisher.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
        assertNotNull(ledger.pending);
        assertEquals(0, ledger.acknowledged.size());
        assertTrue(publisher.paused());
    }

    @Test void databaseReadTimeoutDoesNotAccumulateQueuedLedgerReads() {
        CompletableFuture<List<WebsiteExportStore.Event>> slowRead = new CompletableFuture<>();
        AtomicInteger reads = new AtomicInteger();
        WebsitePublisher.Ledger ledger = new WebsitePublisher.Ledger() {
            @Override public CompletableFuture<List<WebsiteExportStore.Event>> events(Instant now, int limit) {
                reads.incrementAndGet();
                return slowRead;
            }
            @Override public CompletableFuture<Void> delivered(UUID eventId, long storedRevision, Instant now) {
                fail("Empty queue should not acknowledge an event");
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<Void> failed(UUID eventId, String code, Instant next, boolean quarantine) {
                fail("A read timeout must not submit another database operation");
                return CompletableFuture.completedFuture(null);
            }
        };
        try (WebsitePublisher publisher = new WebsitePublisher(WebsiteExportSettingsTest.settings(), ledger,
                transport(event -> { fail("Read is still pending"); return null; }), CLOCK, () -> 1, Duration.ofMillis(20))) {
            publisher.runBatch();
            assertEquals("QUEUE_READ_FAILED", publisher.lastFailureCode());
            for (int attempt = 0; attempt < 10; attempt++) publisher.runBatch();
            assertEquals(1, reads.get());
            slowRead.complete(List.of());
            publisher.runBatch();
            assertEquals(2, reads.get());
        }
    }

    @Test void deliveryMarkTimeoutDoesNotQueueRetriesOrFailureWritesBeforeTheMutationFinishes() {
        WebsiteExportStore.Event event = event();
        CompletableFuture<Void> slowMark = new CompletableFuture<>();
        AtomicInteger reads = new AtomicInteger(), sends = new AtomicInteger(), marks = new AtomicInteger();
        WebsitePublisher.Ledger ledger = new WebsitePublisher.Ledger() {
            @Override public CompletableFuture<List<WebsiteExportStore.Event>> events(Instant now, int limit) {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(slowMark.isDone() ? List.of() : List.of(event));
            }
            @Override public CompletableFuture<Void> delivered(UUID eventId, long revision, Instant now) {
                marks.incrementAndGet();
                return slowMark;
            }
            @Override public CompletableFuture<Void> failed(UUID eventId, String code, Instant next, boolean quarantine) {
                fail("An ambiguous delivery mark must finish before any other database mutation is queued");
                return CompletableFuture.completedFuture(null);
            }
        };
        try (WebsitePublisher publisher = new WebsitePublisher(WebsiteExportSettingsTest.settings(), ledger,
                transport(sent -> { sends.incrementAndGet(); return ack(sent, "applied", sent.revision()); }),
                CLOCK, () -> 1, Duration.ofMillis(20))) {
            publisher.runBatch();
            assertEquals("DELIVERY_MARK_TIMEOUT", publisher.lastFailureCode());
            for (int attempt = 0; attempt < 10; attempt++) publisher.runBatch();
            assertEquals(1, reads.get());
            assertEquals(1, sends.get());
            assertEquals(1, marks.get());
            slowMark.complete(null);
            publisher.runBatch();
            assertEquals(2, reads.get());
            assertEquals(1, sends.get());
        }
    }

    record Failure(UUID eventId, String code, Instant nextAttempt, boolean quarantine) { }
    record Acknowledged(UUID eventId, long storedRevision, Instant now) { }

    /** The SQL due-date selection is tested separately; this fake allows deterministic manual attempts. */
    private static final class FakeLedger implements WebsitePublisher.Ledger {
        volatile WebsiteExportStore.Event pending;
        final List<Failure> failures = new ArrayList<>();
        final List<Acknowledged> acknowledged = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch delivered = new CountDownLatch(1);
        boolean failNextDelivery, failNextRead, failFailureWrite;
        Runnable afterFailure;

        FakeLedger(WebsiteExportStore.Event event) { pending = event; }

        @Override public synchronized CompletableFuture<List<WebsiteExportStore.Event>> events(Instant now, int limit) {
            reads.incrementAndGet();
            if (failNextRead) {
                failNextRead = false;
                return CompletableFuture.failedFuture(new IOException("private connection credentials"));
            }
            return CompletableFuture.completedFuture(pending == null ? List.of() : List.of(pending));
        }

        @Override public synchronized CompletableFuture<Void> delivered(UUID eventId, long storedRevision, Instant now) {
            if (failNextDelivery) {
                failNextDelivery = false;
                return CompletableFuture.failedFuture(new IOException("temporary DB failure"));
            }
            acknowledged.add(new Acknowledged(eventId, storedRevision, now));
            pending = null;
            delivered.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override public synchronized CompletableFuture<Void> failed(UUID eventId, String safeCode,
                Instant nextAttempt, boolean quarantine) {
            if (failFailureWrite) return CompletableFuture.failedFuture(new IOException("DB unavailable"));
            failures.add(new Failure(eventId, safeCode, nextAttempt, quarantine));
            if (quarantine) pending = null;
            else pending = new WebsiteExportStore.Event(pending.eventId(), pending.sessionId(), pending.revision(),
                    pending.payload(), pending.attempts() + 1);
            if (afterFailure != null) afterFailure.run();
            return CompletableFuture.completedFuture(null);
        }
    }
}
