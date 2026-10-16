package cloud.mineplay.mobarena.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Settlement(UUID settlementId, UUID sessionId, UUID playerId, BigDecimal share,
        BigDecimal grossAmount, BigDecimal intendedAmount, String capsApplied, Status status,
        Instant paidAt, String error) {
    public enum Status { PENDING, PROCESSING, CONFIRMED, UNCERTAIN, FAILED, WITHHELD, VOID }
}
