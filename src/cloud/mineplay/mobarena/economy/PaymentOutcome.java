package cloud.mineplay.mobarena.economy;

public record PaymentOutcome(Settlement.Status status, String detail) {
    public PaymentOutcome {
        if (status != Settlement.Status.PENDING && status != Settlement.Status.CONFIRMED
                && status != Settlement.Status.FAILED && status != Settlement.Status.UNCERTAIN) {
            throw new IllegalArgumentException("Invalid payment outcome");
        }
    }
}
