package cloud.mineplay.mobarena.website;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

class WebsiteExportSettingsTest {
    static final String TOKEN = "test-secret-not-for-production-0123456789";
    static final UUID SOURCE = UUID.fromString("7b240c2f-8b5f-442e-9204-62f5ec7618e5");

    static MemoryConfiguration config() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("enabled", true);
        config.set("source-id", SOURCE.toString());
        return config;
    }

    static WebsiteExportSettings settings() { return WebsiteExportSettings.load(config(), ignored -> TOKEN); }

    @Test void defaultsDisableExportWithoutReadingEnvironment() {
        WebsiteExportSettings settings = WebsiteExportSettings.load(null, ignored -> fail("Disabled export read a secret"));
        assertFalse(settings.enabled());
        assertNull(settings.sourceId());
        assertEquals(WebsiteExportSettings.DEFAULT_ENDPOINT, settings.endpoint().toString());
        assertEquals(1, settings.concurrency());
        assertFalse(settings.toString().contains(TOKEN));
    }

    @Test void capturesSourceAndEnvironmentCredentialWithoutPrintingEither() {
        MemoryConfiguration config = config();
        config.set("token-env", "MY_ARENA_TOKEN");
        WebsiteExportSettings settings = WebsiteExportSettings.load(config, key -> {
            assertEquals("MY_ARENA_TOKEN", key);
            return TOKEN;
        });
        assertEquals(SOURCE, settings.sourceId());
        assertEquals(TOKEN, settings.token());
        assertFalse(settings.toString().contains(TOKEN));
        assertFalse(settings.toString().contains(SOURCE.toString()));
    }

    @Test void rejectsInsecureOrCredentialBearingEndpointsWithSafeErrors() {
        for (String endpoint : new String[]{"http://www.mineplay.co.uk/api", "https://secret@mineplay.co.uk/api",
                "https://mineplay.co.uk/api?token=" + TOKEN, "https://mineplay.co.uk/api#fragment",
                "https://mineplay.co.uk", "https://mineplay.co.uk:0/api", "https://mineplay.co.uk:99999/api", "%%%"}) {
            MemoryConfiguration config = config();
            config.set("endpoint", endpoint);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> WebsiteExportSettings.load(config, ignored -> TOKEN));
            assertEquals("EXPORT_ENDPOINT_INVALID", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test void requiresExplicitNonemptyCanonicalSourceAndSecret() {
        for (String source : new String[]{"", "00000000-0000-0000-0000-000000000000", "1-1-1-1-1", SOURCE.toString().toUpperCase()}) {
            MemoryConfiguration config = config();
            config.set("source-id", source);
            assertThrows(IllegalArgumentException.class, () -> WebsiteExportSettings.load(config, ignored -> TOKEN));
        }
        for (String token : new String[]{null, "short", "a".repeat(1025), TOKEN + "\r\nInjected: true", TOKEN + " "}) {
            assertEquals("EXPORT_TOKEN_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> WebsiteExportSettings.load(config(), ignored -> token)).getMessage());
        }
        MemoryConfiguration config = config();
        config.set("token", TOKEN);
        assertEquals("EXPORT_USE_TOKEN_ENV", assertThrows(IllegalArgumentException.class,
                () -> WebsiteExportSettings.load(config, ignored -> TOKEN)).getMessage());
    }

    @Test void boundsConcurrencyTimeoutsBatchAndRetryConfiguration() {
        for (String key : new String[]{"connect-timeout-seconds", "request-timeout-seconds", "retry-base-seconds",
                "retry-max-seconds", "poll-interval-seconds", "batch-size", "concurrency"}) {
            MemoryConfiguration config = config();
            config.set(key, 0);
            assertThrows(IllegalArgumentException.class, () -> WebsiteExportSettings.load(config, ignored -> TOKEN));
            config.set(key, 1.5);
            assertThrows(IllegalArgumentException.class, () -> WebsiteExportSettings.load(config, ignored -> TOKEN));
            config.set(key, 999999999);
            assertThrows(IllegalArgumentException.class, () -> WebsiteExportSettings.load(config, ignored -> TOKEN));
        }
        MemoryConfiguration timeoutConfig = config();
        timeoutConfig.set("connect-timeout-seconds", 20);
        assertEquals("EXPORT_TIMEOUT_INVALID", assertThrows(IllegalArgumentException.class,
                () -> WebsiteExportSettings.load(timeoutConfig, ignored -> TOKEN)).getMessage());
        MemoryConfiguration invalidRetry = config();
        invalidRetry.set("retry-base-seconds", 10);
        invalidRetry.set("retry-max-seconds", 5);
        assertEquals("EXPORT_RETRY_BOUNDS_INVALID", assertThrows(IllegalArgumentException.class,
                () -> WebsiteExportSettings.load(invalidRetry, ignored -> TOKEN)).getMessage());
    }
}
