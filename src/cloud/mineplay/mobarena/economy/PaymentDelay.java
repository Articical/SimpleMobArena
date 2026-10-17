package cloud.mineplay.mobarena.economy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** A short presentation pause before claiming a payment. Accessed only by the ledger worker. */
final class PaymentDelay {
    private final Map<UUID, Instant> deadlines = new LinkedHashMap<>();

    static int seconds(ConfigurationSection section) {
        Object value = section == null ? null : section.get("payment-delay-seconds");
        if (value == null) return 3;
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 30)
            throw new IllegalArgumentException("payment-delay-seconds must be a whole number from 0 to 30");
        return ((Number) value).intValue();
    }

    boolean ready(UUID settlementId, Instant now, int seconds) {
        if (seconds == 0) { forget(settlementId); return true; }
        Instant deadline = deadlines.get(settlementId);
        if (deadline == null) {
            // Removed/reconciled payments cannot grow this presentation-only cache indefinitely.
            if (deadlines.size() >= 256) deadlines.remove(deadlines.keySet().iterator().next());
            deadline = now.plusSeconds(seconds);
            deadlines.put(settlementId, deadline);
        }
        return !now.isBefore(deadline);
    }

    void forget(UUID settlementId) { deadlines.remove(settlementId); }
}
