package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/** The durable half of the payment protocol, independently testable without a running server. */
public final class SettlementPayments {
    private SettlementPayments() { }

    public static Settlement claimNext(SettlementStore store, EconomySettings settings,
            boolean providerAvailable, Instant now) throws SQLException {
        return claimNext(store, settings, providerAvailable, now, ignored -> { });
    }

    static Settlement claimNext(SettlementStore store, EconomySettings settings,
            boolean providerAvailable, Instant now, Consumer<UUID> afterClaim) throws SQLException {
        if (!settings.enabled() || !providerAvailable) return null;
        for (Settlement pending : store.pending(25)) {
            Settlement claimed = store.claim(pending.settlementId(), settings, now);
            // Optional presentation observes the committed state and cannot alter payment progression.
            try { afterClaim.accept(pending.settlementId()); } catch (RuntimeException ignored) { }
            if (claimed != null) return claimed;
        }
        return null;
    }
}
