package cloud.mineplay.mobarena.website;

import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Only the publisher's dedicated worker invokes send; this client never follows a redirect. */
final class HttpWebsiteTransport implements WebsiteTransport {
    private final URI endpoint;
    private final String token;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final ThreadPoolExecutor executor;
    private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    HttpWebsiteTransport(WebsiteExportSettings settings) {
        this(settings.endpoint(), settings.token(), settings.connectTimeout(), settings.requestTimeout());
    }

    // Package-private endpoint injection permits a local HTTP mock; production only takes validated settings.
    HttpWebsiteTransport(URI endpoint, String token, Duration connectTimeout, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.token = token;
        this.requestTimeout = requestTimeout;
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "SimpleMobArena-website-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor).build();
    }

    @Override public Response send(WebsiteExportStore.Event event) throws IOException, InterruptedException {
        if (closed.get()) throw new IOException("TRANSPORT_CLOSED");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", event.eventId().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(event.payload())).build();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request,
                info -> info.statusCode() == 200 ? new LimitedBodySubscriber() : new IgnoreBodySubscriber());
        inFlight.set(future);
        if (closed.get()) future.cancel(true);
        try {
            // This also bounds a server which sends headers promptly and then stalls its response body.
            HttpResponse<byte[]> response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null),
                    response.headers().firstValue("Content-Type").orElse(null), response.body());
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new HttpTimeoutException("REQUEST_TIMEOUT");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("TRANSPORT_FAILURE");
        } catch (InterruptedException exception) {
            future.cancel(true);
            throw exception;
        } finally {
            inFlight.compareAndSet(future, null);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        CompletableFuture<?> request = inFlight.getAndSet(null);
        if (request != null) request.cancel(true);
        client.shutdownNow();
        executor.shutdownNow();
    }

    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() { super("RESPONSE_TOO_LARGE"); }
    }

    /** Error pages cannot turn a permanent HTTP rejection into endless body-size retries. */
    private static final class IgnoreBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        @Override public CompletionStage<byte[]> getBody() { return CompletableFuture.completedFuture(new byte[0]); }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> buffers) { }
        @Override public void onError(Throwable throwable) { }
        @Override public void onComplete() { }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > WebsiteAcknowledgement.MAXIMUM_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
