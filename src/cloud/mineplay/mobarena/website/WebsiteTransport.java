package cloud.mineplay.mobarena.website;

import cloud.mineplay.mobarena.storage.WebsiteExportStore;
import java.io.IOException;

interface WebsiteTransport extends AutoCloseable {
    Response send(WebsiteExportStore.Event event) throws IOException, InterruptedException;
    @Override void close();

    record Response(int status, String retryAfter, String contentType, byte[] body) { }
}
