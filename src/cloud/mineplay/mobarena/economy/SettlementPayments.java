package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.storage.SettlementStore;
import java.sql.SQLException;
import java.time.Instant;

/** The durable half of the payment protocol, independently testable without a running server. */
public final class SettlementPayments {
    private SettlementPayments() { }

    public static Settlement claimNext(SettlementStore store, EconomySettings settings,
            boolean providerAvailable, Instant now) throws SQLException {
        if (!settings.enabled() || !providerAvailable) return null;
        for (Settlement pending : store.pending(25)) {
            Settlement claimed = store.claim(pending.settlementId(), settings, now);
            if (claimed != null) return claimed;
        }
        return null;
    }
}
