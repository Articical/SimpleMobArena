package cloud.mineplay.mobarena.economy;

import cloud.mineplay.mobarena.messages.ArenaMessages;
import cloud.mineplay.mobarena.storage.SettlementStore;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Best-effort online notices. Durable match history remains the source of truth. */
final class SettlementNotifier {
    private final JavaPlugin plugin;
    private final BooleanSupplier closing;
    private final Map<UUID, Notice> queued = new ConcurrentHashMap<>();
    // Accessed only by scheduled main-thread tasks; bounded and never persisted.
    private final Map<UUID, Settlement.Status> delivered = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Settlement.Status> eldest) { return size() > 4096; }
    };

    SettlementNotifier(JavaPlugin plugin, BooleanSupplier closing) {
        this.plugin = plugin;
        this.closing = closing;
    }

    void prepared(SettlementStore store, String arenaId, UUID sessionId, EconomySettings policy) {
        try {
            for (Settlement settlement : store.payouts(sessionId)) publish(arenaId, settlement, policy);
        } catch (Exception error) { failed(error); }
    }

    void payment(SettlementStore store, UUID settlementId, boolean onlyWithheld) {
        try {
            Settlement settlement = store.get(settlementId);
            if (settlement == null || (onlyWithheld && settlement.status() != Settlement.Status.WITHHELD)) return;
            MatchDetails match = store.match(settlement.sessionId());
            if (match != null) publish(match.arenaId(), settlement);
        } catch (Exception error) { failed(error); }
    }

    void committed(UUID settlementId, Settlement.Status status) {
        // A receipt read can fail after commit. Do not let an older queued notice still say pending.
        try {
            if (terminal(status)) queued.computeIfPresent(settlementId, (id, notice) ->
                    terminal(notice.settlement().status()) ? notice : null);
        } catch (RuntimeException error) { failed(error); }
    }

    void publish(String arenaId, Settlement settlement) {
        publish(arenaId, settlement, null);
    }

    private void publish(String arenaId, Settlement settlement, EconomySettings policy) {
        Notice notice = new Notice(arenaId, settlement, policy);
        try {
            if (closing.getAsBoolean() || !plugin.isEnabled()) return;
            Notice selected = queued.compute(settlement.settlementId(), (id, previous) -> previous != null
                    && terminal(previous.settlement().status()) && !terminal(settlement.status()) ? previous : notice);
            if (selected != notice) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> deliver(notice));
        } catch (RuntimeException error) {
            queued.remove(settlement.settlementId(), notice);
            failed(error);
        }
    }

    private void deliver(Notice notice) {
        Settlement settlement = notice.settlement();
        if (!queued.remove(settlement.settlementId(), notice)) return;
        try {
            if (closing.getAsBoolean() || !plugin.isEnabled()) return;
            Settlement.Status previous = delivered.get(settlement.settlementId());
            if (previous == settlement.status() || (terminal(previous) && !terminal(settlement.status()))) return;
            Player player = plugin.getServer().getPlayer(settlement.playerId());
            if (player == null || !player.isOnline()) return;
            player.sendMessage(message(notice.arenaId(), settlement, notice.policy()));
            delivered.put(settlement.settlementId(), settlement.status());
        } catch (RuntimeException error) { failed(error); }
    }

    static Component message(String arenaId, Settlement settlement) {
        return message(arenaId, settlement, null);
    }

    static Component message(String arenaId, Settlement settlement, EconomySettings policy) {
        String context = arenaId + " #" + settlement.sessionId().toString().substring(0, 8) + ": ";
        String amount = settlement.intendedAmount().toPlainString();
        String history = " Use /ma history for details.";
        String caps = reasons(settlement.capsApplied(), policy);
        return switch (settlement.status()) {
            case CONFIRMED -> ArenaMessages.success(context + "Paid $" + amount + "." + history);
            case PENDING, PROCESSING -> ArenaMessages.info(context + "Reward $" + amount + " awaiting payment."
                    + (caps.isEmpty() ? "" : " Adjustments: " + caps + ".") + history);
            case WITHHELD -> ArenaMessages.warning(context + "Reward $0; no payment: "
                    + (caps.isEmpty() ? "no payable reward was allocated" : caps) + "." + history);
            case FAILED -> ArenaMessages.error(context + "Payment failed; no confirmed payout. Ask an administrator to check it." + history);
            case UNCERTAIN -> ArenaMessages.warning(context + "Payment outcome is unconfirmed. Ask an administrator to verify it." + history);
            case VOID -> ArenaMessages.warning(context + "Reward $0; payment was voided after administrator review." + history);
        };
    }

    private static boolean terminal(Settlement.Status status) {
        return status != null && status != Settlement.Status.PENDING && status != Settlement.Status.PROCESSING;
    }

    private static String reasons(String codes, EconomySettings policy) {
        if (codes == null || codes.isBlank()) return "";
        return String.join("; ", Arrays.stream(codes.split(",")).map(String::strip).filter(code -> !code.isEmpty()).map(code -> switch (code) {
            case "CANCELLED" -> "the match was cancelled";
            case "PAYOUTS_DISABLED" -> "rewards were disabled";
            case "MINIMUM_WAVE" -> policy == null ? "the minimum completed-wave requirement was not reached"
                    : "at least " + policy.minimumRewardWave() + " completed " + (policy.minimumRewardWave() == 1 ? "wave is" : "waves are") + " required";
            case "ZERO_KILLS" -> "no credited kills in the match";
            case "MINIMUM_PARTICIPATION" -> policy == null ? "minimum participation time was not reached"
                    : "at least " + policy.minimumParticipationSeconds() + " seconds of participation are required";
            case "NO_KILLS" -> "you had no credited kills";
            case "REWARD_COOLDOWN" -> "your reward cooldown was active";
            case "DAILY_CAP" -> "your daily reward limit applied";
            case "PLAYER_SESSION_CAP" -> "your per-match reward limit applied";
            case "SESSION_POT_CAP" -> "the match reward limit applied";
            case "SHORT_SESSION_REDUCTION" -> "the short-match reduction applied";
            case "CAP_RECHECK_AT_PAYMENT" -> "reward limits were reapplied before payment";
            default -> "an eligibility or reward limit applied";
        }).distinct().toList());
    }

    private void failed(Exception error) {
        try {
            plugin.getLogger().log(Level.WARNING, "Could not deliver an arena reward notice; /ma history retains the recorded status", error);
        } catch (RuntimeException ignored) { /* Presentation must never change a durable payment outcome. */ }
    }

    private record Notice(String arenaId, Settlement settlement, EconomySettings policy) { }
}
