package cloud.mineplay.mobarena.website;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable settings captured before a background publisher starts. Never print the credential. */
public final class WebsiteExportSettings {
    public static final String DEFAULT_ENDPOINT =
            "https://www.mineplay.co.uk/api/integrations/simple-mob-arena/v1/match-snapshots";
    private final boolean enabled;
    private final URI endpoint;
    private final UUID sourceId;
    private final String token;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration retryBase;
    private final Duration retryMaximum;
    private final Duration pollInterval;
    private final int batchSize;

    private WebsiteExportSettings(boolean enabled, URI endpoint, UUID sourceId, String token,
            Duration connectTimeout, Duration requestTimeout, Duration retryBase, Duration retryMaximum,
            Duration pollInterval, int batchSize) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.sourceId = sourceId;
        this.token = token;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.retryBase = retryBase;
        this.retryMaximum = retryMaximum;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
    }

    public static WebsiteExportSettings disabled() { return load(null); }

    public static WebsiteExportSettings load(ConfigurationSection section) {
        return load(section, System::getenv);
    }

    static WebsiteExportSettings load(ConfigurationSection section, Function<String, String> environment) {
        boolean enabled = bool(section, "enabled", false);
        URI endpoint;
        try { endpoint = URI.create(string(section, "endpoint", DEFAULT_ENDPOINT)); }
        catch (RuntimeException exception) { throw invalid("EXPORT_ENDPOINT_INVALID"); }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getHost().isBlank() || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || endpoint.getRawPath() == null || endpoint.getRawPath().isBlank()) {
            throw invalid("EXPORT_ENDPOINT_INVALID");
        }
        String configuredId = string(section, "source-id", "");
        UUID sourceId = null;
        if (!configuredId.isEmpty()) {
            try { sourceId = UUID.fromString(configuredId); }
            catch (IllegalArgumentException exception) { throw invalid("EXPORT_SOURCE_ID_INVALID"); }
            if (!sourceId.toString().equals(configuredId) || sourceId.equals(new UUID(0, 0)))
                throw invalid("EXPORT_SOURCE_ID_INVALID");
        }
        if (enabled && sourceId == null) throw invalid("EXPORT_SOURCE_ID_REQUIRED");
        // Inline credentials are deliberately unsupported, including when currently disabled.
        if (section != null && (section.contains("token") || section.contains("authorization")))
            throw invalid("EXPORT_USE_TOKEN_ENV");
        String tokenEnvironment = string(section, "token-env", "MINEPLAY_ARENA_INGESTION_TOKEN");
        if (!tokenEnvironment.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw invalid("EXPORT_TOKEN_ENV_INVALID");
        String token = null;
        if (enabled) {
            try { token = environment.apply(tokenEnvironment); }
            catch (RuntimeException exception) { throw invalid("EXPORT_TOKEN_UNAVAILABLE"); }
            if (token == null || token.length() < 32 || token.length() > 1024
                    || !token.matches("[A-Za-z0-9._~+/=\\-]+"))
                throw invalid("EXPORT_TOKEN_INVALID");
        }
        Duration connect = seconds(section, "connect-timeout-seconds", 5, 1, 60);
        Duration request = seconds(section, "request-timeout-seconds", 15, 1, 120);
        if (connect.compareTo(request) > 0) throw invalid("EXPORT_TIMEOUT_INVALID");
        Duration retryBase = seconds(section, "retry-base-seconds", 5, 1, 3600);
        Duration retryMaximum = seconds(section, "retry-max-seconds", 3600, 1, 86400);
        if (retryMaximum.compareTo(retryBase) < 0) throw invalid("EXPORT_RETRY_BOUNDS_INVALID");
        Duration poll = seconds(section, "poll-interval-seconds", 5, 1, 300);
        int batchSize = integer(section, "batch-size", 10, 1, 100);
        if (integer(section, "concurrency", 1, 1, 1) != 1) throw invalid("EXPORT_CONCURRENCY_INVALID");
        return new WebsiteExportSettings(enabled, endpoint, sourceId, token, connect, request,
                retryBase, retryMaximum, poll, batchSize);
    }

    private static Object value(ConfigurationSection section, String key, Object fallback) {
        return section == null ? fallback : section.get(key, fallback);
    }

    private static String string(ConfigurationSection section, String key, String fallback) {
        Object raw = value(section, key, fallback);
        if (!(raw instanceof String result)) throw invalid("EXPORT_CONFIG_INVALID");
        return result;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        Object raw = value(section, key, fallback);
        if (!(raw instanceof Boolean result)) throw invalid("EXPORT_CONFIG_INVALID");
        return result;
    }

    private static Duration seconds(ConfigurationSection section, String key, int fallback, int min, int max) {
        return Duration.ofSeconds(integer(section, key, fallback, min, max));
    }

    private static int integer(ConfigurationSection section, String key, int fallback, int min, int max) {
        try {
            int result = new BigDecimal(String.valueOf(value(section, key, fallback))).intValueExact();
            if (result < min || result > max) throw invalid("EXPORT_CONFIG_BOUNDS_INVALID");
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid("EXPORT_CONFIG_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String safeCode) { return new IllegalArgumentException(safeCode); }

    public boolean enabled() { return enabled; }
    public URI endpoint() { return endpoint; }
    public UUID sourceId() { return sourceId; }
    String token() { return token; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public Duration retryBase() { return retryBase; }
    public Duration retryMaximum() { return retryMaximum; }
    public Duration pollInterval() { return pollInterval; }
    public int batchSize() { return batchSize; }
    public int concurrency() { return 1; }

    @Override public String toString() { return "WebsiteExportSettings[enabled=" + enabled + ", credentials=redacted]"; }
}
