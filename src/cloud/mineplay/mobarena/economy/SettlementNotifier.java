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
        String context = arenaId + ": ";
        String amount = settlement.intendedAmount().toPlainString();
        String caps = reasons(settlement.capsApplied(), policy);
        return switch (settlement.status()) {
            case CONFIRMED -> ArenaMessages.success(context + "Paid $" + amount + "."
                    + (hasReason(settlement.capsApplied(), "CAP_RECHECK_AT_PAYMENT") ? " Your reward limit changed before payment." : ""));
            case PENDING, PROCESSING -> ArenaMessages.info(context + "Your reward: $" + amount + ". Payment queued."
                    + (caps.isEmpty() ? "" : " Reward reduced: " + caps + "."));
            case WITHHELD -> ArenaMessages.warning(context + "No reward this match: "
                    + (caps.isEmpty() ? "there was no money to pay out" : caps) + ".");
            case FAILED -> ArenaMessages.error(context + "Your payment failed. Ask an admin to check /ma history.");
            case UNCERTAIN -> ArenaMessages.warning(context + "We couldn't confirm your payment. Ask an admin to check /ma history.");
            case VOID -> ArenaMessages.warning(context + "An admin cancelled this payout.");
        };
    }

    private static boolean terminal(Settlement.Status status) {
        return status != null && status != Settlement.Status.PENDING && status != Settlement.Status.PROCESSING;
    }

    private static boolean hasReason(String codes, String reason) {
        return codes != null && Arrays.stream(codes.split(",")).map(String::strip).anyMatch(reason::equals);
    }

    private static String reasons(String codes, EconomySettings policy) {
        if (codes == null || codes.isBlank()) return "";
        return String.join("; ", Arrays.stream(codes.split(",")).map(String::strip).filter(code -> !code.isEmpty()).map(code -> switch (code) {
            case "CANCELLED" -> "the match was cancelled";
            case "PAYOUTS_DISABLED" -> "rewards were disabled";
            case "MINIMUM_WAVE" -> policy == null ? "your team didn't finish enough waves"
                    : "your team needed to finish " + policy.minimumRewardWave() + (policy.minimumRewardWave() == 1 ? " wave" : " waves");
            case "ZERO_KILLS" -> "there were no kills in this match";
            case "MINIMUM_PARTICIPATION" -> policy == null ? "you didn't play long enough"
                    : "you needed to play for at least " + policy.minimumParticipationSeconds() + " seconds";
            case "NO_KILLS" -> "you didn't get a kill";
            case "REWARD_COOLDOWN" -> "your reward cooldown hadn't ended";
            case "DAILY_CAP" -> "you reached your daily reward limit";
            case "PLAYER_SESSION_CAP" -> "you reached the reward limit for one match";
            case "SESSION_POT_CAP" -> "the prize pot reached its limit";
            case "SHORT_SESSION_REDUCTION" -> "short matches pay less";
            case "CAP_RECHECK_AT_PAYMENT" -> "your reward limit changed before payment";
            default -> "a reward rule or limit applied";
        }).distinct().toList());
    }

    private void failed(Exception error) {
        try {
            plugin.getLogger().log(Level.WARNING, "Could not deliver an arena reward notice; /ma history retains the recorded status", error);
        } catch (RuntimeException ignored) { /* Presentation must never change a durable payment outcome. */ }
    }

    private record Notice(String arenaId, Settlement settlement, EconomySettings policy) { }
}
