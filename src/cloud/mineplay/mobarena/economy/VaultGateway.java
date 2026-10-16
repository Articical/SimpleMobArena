package cloud.mineplay.mobarena.economy;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses Vault's published service interface without bundling or hard-linking Vault classes. */
public final class VaultGateway {
    private final JavaPlugin plugin;
    private String description = "Vault economy unavailable; pending settlements are paused";

    public VaultGateway(JavaPlugin plugin) { this.plugin = plugin; }

    public boolean available(int scale) {
        requireMainThread();
        try {
            Provider provider = findProvider();
            if (provider == null) return false;
            int fraction = ((Number) provider.type().getMethod("fractionalDigits").invoke(provider.instance())).intValue();
            if (fraction >= 0 && fraction < scale) {
                description = "Vault provider supports " + fraction + " decimal places; configured scale " + scale + " is unsafe";
                return false;
            }
            description = "Vault provider: " + provider.type().getMethod("getName").invoke(provider.instance());
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            description = "Vault provider unavailable: " + message(exception);
            return false;
        }
    }

    public PaymentOutcome deposit(UUID playerId, BigDecimal amount, int scale) {
        requireMainThread();
        if (!available(scale)) return new PaymentOutcome(Settlement.Status.PENDING, description);
        Provider provider;
        OfflinePlayer player;
        java.lang.reflect.Method deposit;
        try {
            provider = findProvider();
            if (provider == null) return new PaymentOutcome(Settlement.Status.PENDING, description);
            player = plugin.getServer().getOfflinePlayer(playerId);
            deposit = provider.type().getMethod("depositPlayer", OfflinePlayer.class, double.class);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return new PaymentOutcome(Settlement.Status.PENDING, "No payment attempted: " + message(exception));
        }
        // The only conversion to binary floating point is Vault's required API boundary.
        // Any exception from this point may occur after the provider has changed the balance.
        try {
            Object response = deposit.invoke(provider.instance(), player, amount.doubleValue());
            if (response == null) return new PaymentOutcome(Settlement.Status.UNCERTAIN, "Vault returned no response");
            boolean success = (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
            String error = String.valueOf(response.getClass().getField("errorMessage").get(response));
            if (!success) return new PaymentOutcome(Settlement.Status.FAILED, error);
            double reported = response.getClass().getField("amount").getDouble(response);
            if (!Double.isFinite(reported) || BigDecimal.valueOf(reported).compareTo(amount) != 0) {
                return new PaymentOutcome(Settlement.Status.UNCERTAIN, "Vault reported success with unexpected amount: " + reported + "; intended=" + amount);
            }
            return new PaymentOutcome(Settlement.Status.CONFIRMED, "");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return new PaymentOutcome(Settlement.Status.UNCERTAIN, "Deposit outcome unknown: " + message(exception));
        }
    }

    public String description() { return description; }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Provider findProvider() throws ReflectiveOperationException {
        for (Class<?> service : plugin.getServer().getServicesManager().getKnownServices()) {
            if (!service.getName().equals("net.milkbowl.vault.economy.Economy")) continue;
            RegisteredServiceProvider registration = plugin.getServer().getServicesManager().getRegistration((Class) service);
            if (registration == null || !registration.getPlugin().isEnabled()) continue;
            Object instance = registration.getProvider();
            if (!Boolean.TRUE.equals(service.getMethod("isEnabled").invoke(instance))) continue;
            return new Provider(service, instance);
        }
        description = "Vault economy unavailable; pending settlements are paused";
        return null;
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : error;
        return cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Vault must be called on the server thread");
    }

    private record Provider(Class<?> type, Object instance) { }
}
