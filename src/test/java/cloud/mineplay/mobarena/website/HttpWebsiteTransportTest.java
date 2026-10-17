package cloud.mineplay.mobarena.website;

import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpWebsiteTransportTest {
    private static WebsiteExportStore.Event event() {
        return new WebsiteExportStore.Event(UUID.randomUUID(), UUID.randomUUID(), 3,
                "{\"fixture\":\"exact UTF-8 bytes \u00a3 .BedrockPlayer\"}\n".getBytes(StandardCharsets.UTF_8), 0);
    }

    private static URI endpoint(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test void sendsExactStoredBytesAndHeadersAndNeverFollowsRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        WebsiteExportStore.Event event = event();
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicInteger redirectRequests = new AtomicInteger();
        server.createContext("/sink", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getResponseHeaders().set("Location", endpoint(server, "/redirect").toString());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            redirectRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (HttpWebsiteTransport transport = new HttpWebsiteTransport(endpoint(server, "/sink"),
                WebsiteExportSettingsTest.TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            assertEquals(302, transport.send(event).status());
            assertArrayEquals(event.payload(), received.get());
            assertEquals("Bearer " + WebsiteExportSettingsTest.TOKEN, authorization.get());
            assertEquals(event.eventId().toString(), idempotency.get());
            assertEquals("application/json", contentType.get());
            assertEquals(0, redirectRequests.get());
        } finally { server.stop(0); }
    }

    @Test void boundsResponseBodyBeforeAccumulatingMoreThanSixteenKiB() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sink", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] oversized = new byte[WebsiteAcknowledgement.MAXIMUM_BYTES + 1];
            exchange.sendResponseHeaders(200, oversized.length);
            try { exchange.getResponseBody().write(oversized); }
            finally { exchange.close(); }
        });
        server.start();
        try (HttpWebsiteTransport transport = new HttpWebsiteTransport(endpoint(server, "/sink"),
                WebsiteExportSettingsTest.TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            assertThrows(HttpWebsiteTransport.ResponseTooLargeException.class, () -> transport.send(event()));
        } finally { server.stop(0); }
    }

    @Test void boundsStalledResponseBodyAfterHeadersAndCloseCancelsAnInFlightSend() throws Exception {
        for (boolean close : new boolean[]{false, true}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
            server.createContext("/sink", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 10);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                headers.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            HttpWebsiteTransport transport = new HttpWebsiteTransport(endpoint(server, "/sink"),
                    WebsiteExportSettingsTest.TOKEN, Duration.ofMillis(100), Duration.ofMillis(close ? 5000 : 500));
            try (var sender = Executors.newSingleThreadExecutor()) {
                var future = sender.submit(() -> transport.send(event()));
                assertTrue(headers.await(2, TimeUnit.SECONDS));
                if (close) transport.close();
                var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> future.get(2, TimeUnit.SECONDS));
                if (!close) assertInstanceOf(HttpTimeoutException.class, exception.getCause());
            } finally {
                release.countDown();
                transport.close();
                server.stop(0);
            }
        }
    }
}
