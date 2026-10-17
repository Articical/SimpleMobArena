package cloud.mineplay.mobarena.gui;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.commands.CommandManager;
import cloud.mineplay.mobarena.economy.MatchDetails;
import cloud.mineplay.mobarena.economy.MatchPerformance;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.economy.Settlement;
import cloud.mineplay.mobarena.equipment.ClassChoice;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Inventory identity authorizes clicks; asynchronous reads can only fill their original open menu. */
public final class ArenaMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'", Locale.UK).withZone(ZoneOffset.UTC);
    private final SimpleMobArena plugin;
    private final Items items;

    public ArenaMenu(SimpleMobArena plugin) { this(plugin, ArenaMenu::item); }
    ArenaMenu(SimpleMobArena plugin, Items items) { this.plugin = plugin; this.items = items; }
    public static boolean isMenu(Inventory inventory) { return inventory.getHolder() instanceof Menu; }

    public void open(Player player, int requestedPage) {
        require(player, "mobarena.use");
        List<Arena> arenas = plugin.getArenaManager().getArenas().stream().sorted(Comparator.comparing(Arena::getId)).toList();
        int page = page(requestedPage, arenas.size());
        Menu menu = menu(player, "MobArena | Arenas " + (page + 1));
        for (int i = page * PAGE_SIZE; i < Math.min(arenas.size(), (page + 1) * PAGE_SIZE); i++) {
            Arena arena = arenas.get(i);
            List<String> lore = arenaLines(player, arena);
            lore.add(""); lore.add("Left-click to choose a class"); lore.add("Right-click for arena details");
            put(menu, i % PAGE_SIZE, Material.IRON_SWORD, arena.getName(), lore, p -> openEquipment(p, arena.getId()));
            menu.rightActions.put(i % PAGE_SIZE, p -> openInfo(p, arena.getId()));
        }
        if (arenas.isEmpty()) put(menu, 22, Material.PAPER, "No arenas yet", List.of("Check back after an admin sets one up."), null);
        if (page > 0) put(menu, 45, Material.ARROW, "Previous page", List.of(), p -> open(p, page - 1));
        put(menu, 46, Material.IRON_CHESTPLATE, "Classes", List.of("Unlock more classes with all-time kills."), this::openClasses);
        if (player.hasPermission("mobarena.top")) put(menu, 47, Material.GOLD_INGOT, "Leaderboard", List.of("Top killers and their earnings"), this::openLeaderboard);
        put(menu, 48, Material.BARRIER, "Leave arena", List.of("Your kills still count for this match."), p -> plugin.getArenaManager().leave(p));
        if (player.hasPermission("mobarena.stats")) put(menu, 49, Material.BOOK, "Your stats", List.of("Kills, best wave and total earnings"), p -> openStats(p, p.getUniqueId(), p.getName()));
        put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> open(p, page));
        if (player.hasPermission("mobarena.history")) put(menu, 51, Material.WRITABLE_BOOK, "Recent matches", List.of("Results, players and payouts"), p -> openHistory(p, p.getUniqueId(), p.getName(), 1));
        if (page < lastPage(arenas.size())) put(menu, 53, Material.ARROW, "Next page", List.of(), p -> open(p, page + 1));
        player.openInventory(menu.inventory);
    }

    public void openEquipment(Player player, String arenaId) { openEquipment(player, arenaId, 0); }

    private void openEquipment(Player player, String arenaId, int requestedPage) {
        require(player, "mobarena.join");
        Arena arena = arena(arenaId);
        Menu menu = menu(player, "Join " + arena.getName());
        renderChoices(menu, equipmentChoices(), null, false, requestedPage, arenaId);
        player.openInventory(menu.inventory);
        load(player, menu, () -> plugin.getEconomyService().stats(player.getUniqueId()), () -> require(player, "mobarena.join"), stats -> {
            arena(arenaId); // A reload may have removed it while the statistics were loading.
            renderChoices(menu, equipmentChoices(), stats.kills(), false, requestedPage, arenaId);
        }, () -> renderChoices(menu, equipmentChoices(), null, true, requestedPage, arenaId));
    }

    private List<ClassChoice> equipmentChoices() {
        List<ClassChoice> choices = new ArrayList<>();
        if (plugin.getEquipmentService().availableChoices().stream().anyMatch("personal"::equalsIgnoreCase))
            choices.add(new ClassChoice("personal", "Your own gear", Material.CHEST, 0));
        choices.addAll(plugin.getEquipmentService().classChoices());
        return choices;
    }

    public void openClasses(Player player) { openClasses(player, 0); }

    private void openClasses(Player player, int requestedPage) {
        require(player, "mobarena.use");
        Menu menu = menu(player, "MobArena | Classes");
        renderChoices(menu, plugin.getEquipmentService().classChoices(), null, false, requestedPage, null);
        player.openInventory(menu.inventory);
        load(player, menu, () -> plugin.getEconomyService().stats(player.getUniqueId()), () -> require(player, "mobarena.use"),
                stats -> renderChoices(menu, plugin.getEquipmentService().classChoices(), stats.kills(), false, requestedPage, null),
                () -> renderChoices(menu, plugin.getEquipmentService().classChoices(), null, true, requestedPage, null));
    }

    private void renderChoices(Menu menu, List<ClassChoice> choices, Long kills, boolean failed, int requestedPage, String arenaId) {
        menu.clear();
        int page = page(requestedPage, choices.size());
        for (int i = page * PAGE_SIZE; i < Math.min(choices.size(), (page + 1) * PAGE_SIZE); i++) {
            ClassChoice choice = choices.get(i);
            boolean personal = choice.id().equalsIgnoreCase("personal");
            boolean unlocked = choice.requiredKills() == 0 || (kills != null && kills >= choice.requiredKills());
            List<String> lore = new ArrayList<>();
            if (personal) lore.add("Fight with the equipment you're carrying.");
            else {
                lore.add(unlocked ? "Unlocked" : kills == null ? (failed ? "Kill count unavailable" : "Checking your kills...")
                        : "Locked - " + (choice.requiredKills() - kills) + " more kills");
                lore.add(kills == null ? "Requires " + choice.requiredKills() + " all-time kills"
                        : "All-time kills: " + kills + " / " + choice.requiredKills());
                lore.add("Your gear is saved until you leave.");
            }
            lore.add("");
            lore.add(!unlocked ? kills == null ? "Refresh to check your progress." : "Keep playing to unlock this class."
                    : arenaId == null ? "Choose this class when joining an arena." : "Click to join as " + choice.displayName());
            put(menu, i % PAGE_SIZE, unlocked ? choice.icon() : Material.BARRIER, choice.displayName(), lore,
                    unlocked && arenaId != null ? p -> {
                        require(p, "mobarena.join");
                        plugin.getArenaManager().join(p, arenaId, choice.id());
                    } : null);
        }
        if (choices.isEmpty()) put(menu, 22, Material.CHEST, "No classes available", List.of("This server uses personal equipment."), null);
        Consumer<Player> previous = p -> { if (arenaId == null) openClasses(p, page - 1); else openEquipment(p, arenaId, page - 1); };
        Consumer<Player> next = p -> { if (arenaId == null) openClasses(p, page + 1); else openEquipment(p, arenaId, page + 1); };
        if (page > 0) put(menu, 45, Material.ARROW, "Previous page", List.of(), previous);
        back(menu, p -> open(p, 0));
        put(menu, 50, kills == null ? Material.CLOCK : Material.IRON_SWORD,
                kills == null ? failed ? "Kill count unavailable" : "Checking all-time kills..." : kills + " all-time kills",
                List.of("Kills update after a match ends.", "Click to refresh."),
                p -> { if (arenaId == null) openClasses(p, page); else openEquipment(p, arenaId, page); });
        if (page < lastPage(choices.size())) put(menu, 53, Material.ARROW, "Next page", List.of(), next);
    }

    public void openStats(Player player, UUID target, String targetName) {
        openStats(player, target, targetName, p -> open(p, 0));
    }

    private void openStats(Player player, UUID target, String targetName, Consumer<Player> back) {
        requireTarget(player, "mobarena.stats", target);
        Menu menu = loading(player, "Stats | " + playerLabel(target, targetName), back);
        load(player, menu, () -> plugin.getEconomyService().stats(target), () -> requireTarget(player, "mobarena.stats", target), stats -> {
            menu.clear();
            put(menu, 4, Material.PLAYER_HEAD, playerLabel(target, stats.playerName()), List.of("Player ID: " + target), null);
            put(menu, 10, Material.IRON_SWORD, stats.kills() + " kills", List.of("Across all matches"), null);
            put(menu, 12, Material.BOOK, stats.sessions() + " matches", List.of("Matches you took part in"), null);
            put(menu, 14, Material.DIAMOND, "Best wave: " + stats.highestWave(), List.of("Highest completed wave"), null);
            put(menu, 16, Material.GOLD_INGOT, money(stats.earned()) + " earned", List.of("Paid to your balance"), null);
            put(menu, 22, Material.IRON_AXE, decimal(stats.damage()) + " damage", List.of("Damage dealt across all matches"), null);
            if (stats.sessions() == 0) put(menu, 31, Material.PAPER, "No matches yet", List.of("Join an arena to start your record."), null);
            back(menu, back);
            put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> openStats(p, target, targetName, back));
            if (player.hasPermission("mobarena.history")) put(menu, 51, Material.WRITABLE_BOOK, "Recent matches", List.of(), p -> openHistory(p, target, targetName, 1));
            if (target.equals(player.getUniqueId()) && player.hasPermission("mobarena.use"))
                put(menu, 48, Material.IRON_CHESTPLATE, "Your classes", List.of("See your unlock progress"), this::openClasses);
        });
    }

    public void openHistory(Player player, UUID target, String targetName, int requestedPage) {
        requireTarget(player, "mobarena.history", target);
        int page = Math.max(1, Math.min(1_000_000, requestedPage));
        Menu menu = loading(player, "Matches | " + playerLabel(target, targetName) + " | " + page, p -> open(p, 0));
        load(player, menu, () -> plugin.getEconomyService().history(target, page), () -> requireTarget(player, "mobarena.history", target), rows -> {
            menu.clear();
            int slot = 0;
            for (MatchPerformance match : rows) {
                List<String> lore = new ArrayList<>(List.of(date(match.finishedAt()), "Waves cleared: " + match.completedWaves(),
                        "Kills: " + match.kills() + " | Damage: " + decimal(match.damage()), "Time played: " + duration(match.participationSeconds()),
                        "Kill share: " + percent(match.share()), payout(match.status(), match.intendedAmount())));
                if (match.cancelled()) lore.add("Match cancelled");
                lore.add(""); lore.add("Click to view everyone in this match");
                put(menu, slot++, match.cancelled() ? Material.GRAY_DYE : Material.FILLED_MAP, match.arenaId(), lore,
                        p -> openMatch(p, match.sessionId(), 0, q -> openHistory(q, target, targetName, page)));
            }
            if (rows.isEmpty()) put(menu, 22, Material.PAPER, "No matches on this page", List.of(page == 1 ? "Play a match to start your history." : "Go back to see earlier results."), null);
            if (page > 1) put(menu, 45, Material.ARROW, "Previous page", List.of(), p -> openHistory(p, target, targetName, page - 1));
            back(menu, p -> open(p, 0));
            put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> openHistory(p, target, targetName, page));
            if (rows.size() == 10 && page < 1_000_000) put(menu, 53, Material.ARROW, "Next page", List.of(), p -> openHistory(p, target, targetName, page + 1));
        });
    }

    public void openMatch(Player player, UUID session) { openMatch(player, session, 0, p -> open(p, 0)); }

    private void openMatch(Player player, UUID session, int requestedPage, Consumer<Player> back) {
        require(player, "mobarena.match");
        Menu menu = loading(player, "MobArena | Match results", back);
        load(player, menu, () -> plugin.getEconomyService().match(session), () -> require(player, "mobarena.match"), match -> {
            menu.clear();
            if (match == null) {
                put(menu, 22, Material.PAPER, "Match not found", List.of("It may not have been saved yet."), null);
                back(menu, back); return;
            }
            int page = page(requestedPage, match.participants().size());
            for (int i = page * PAGE_SIZE; i < Math.min(match.participants().size(), (page + 1) * PAGE_SIZE); i++) {
                MatchDetails.MatchParticipant row = match.participants().get(i);
                Settlement payment = row.settlement();
                List<String> lore = new ArrayList<>(List.of("Kills: " + row.kills(), "Damage: " + decimal(row.damage()),
                        "Time played: " + duration(row.participationSeconds()), "Kill share: " + percent(payment.share()),
                        "Before limits: " + money(payment.grossAmount()), payout(payment.status(), payment.intendedAmount())));
                if (payment.paidAt() != null) lore.add("Paid: " + date(payment.paidAt()));
                if (payment.capsApplied() != null && !payment.capsApplied().isBlank())
                    lore.addAll(limitLines(payment.capsApplied()));
                boolean statsAllowed = player.hasPermission("mobarena.stats") && (player.getUniqueId().equals(row.playerId()) || player.hasPermission("mobarena.stats.others"));
                if (statsAllowed) { lore.add(""); lore.add("Click for player stats"); }
                String name = row.playerName() == null || row.playerName().isBlank() ? row.playerId().toString() : row.playerName();
                if (name.equals(row.playerId().toString())) lore.add("Player ID: " + row.playerId());
                put(menu, i % PAGE_SIZE, Material.PLAYER_HEAD, playerLabel(row.playerId(), name), lore,
                        statsAllowed ? p -> openStats(p, row.playerId(), name, q -> openMatch(q, session, page, back)) : null);
            }
            if (page > 0) put(menu, 45, Material.ARROW, "Previous players", List.of(), p -> openMatch(p, session, page - 1, back));
            put(menu, 48, Material.BOOK, match.arenaId() + " | " + (match.cancelled() ? "Cancelled" : "Finished"),
                    List.of(date(match.finishedAt()), "Waves cleared: " + match.completedWaves(), "Total kills: " + match.totalKills(),
                            "Players: " + match.participants().size(), "Prize pool: " + money(match.prizePot()),
                            "Unawarded: " + money(match.unallocatedPot()), "Match: " + match.sessionId()), null);
            back(menu, back);
            put(menu, 50, Material.CLOCK, "Refresh payouts", List.of(), p -> openMatch(p, session, page, back));
            if (page < lastPage(match.participants().size())) put(menu, 53, Material.ARROW, "Next players", List.of(), p -> openMatch(p, session, page + 1, back));
        });
    }

    public void openLeaderboard(Player player) { openLeaderboard(player, 0); }

    private void openLeaderboard(Player player, int requestedPage) {
        require(player, "mobarena.top");
        Menu menu = loading(player, "MobArena | Top killers", p -> open(p, 0));
        load(player, menu, () -> plugin.getEconomyService().top(100), () -> require(player, "mobarena.top"), rows -> {
            menu.clear();
            int page = page(requestedPage, rows.size());
            for (int i = page * PAGE_SIZE; i < Math.min(rows.size(), (page + 1) * PAGE_SIZE); i++) {
                PlayerStatistics row = rows.get(i);
                String name = row.playerName();
                boolean statsAllowed = player.hasPermission("mobarena.stats") && (player.getUniqueId().equals(row.playerId()) || player.hasPermission("mobarena.stats.others"));
                List<String> lore = new ArrayList<>(List.of(row.kills() + " kills", "Best wave: " + row.highestWave(),
                        "Matches: " + row.sessions(), "Earnings: " + money(row.earned())));
                if (name.equals(row.playerId().toString())) lore.add("Player ID: " + row.playerId());
                if (statsAllowed) { lore.add(""); lore.add("Click for player stats"); }
                put(menu, i % PAGE_SIZE, i < 3 ? Material.GOLD_INGOT : Material.PLAYER_HEAD, "#" + (i + 1) + " " + playerLabel(row.playerId(), name), lore,
                        statsAllowed ? p -> openStats(p, row.playerId(), name, q -> openLeaderboard(q, page)) : null);
            }
            if (rows.isEmpty()) put(menu, 22, Material.PAPER, "No scores yet", List.of("Finish a match to get on the board."), null);
            if (page > 0) put(menu, 45, Material.ARROW, "Previous page", List.of(), p -> openLeaderboard(p, page - 1));
            back(menu, p -> open(p, 0));
            put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> openLeaderboard(p, page));
            if (page < lastPage(rows.size())) put(menu, 53, Material.ARROW, "Next page", List.of(), p -> openLeaderboard(p, page + 1));
        });
    }

    public void openInfo(Player player, String arenaId) {
        require(player, "mobarena.use");
        Arena arena = arena(arenaId);
        Menu menu = menu(player, "Arena | " + arena.getName());
        put(menu, 13, Material.IRON_SWORD, arena.getName(), arenaLines(player, arena), null);
        var rewards = plugin.getEconomyService().rewardSettings();
        put(menu, 21, Material.GOLD_INGOT, "How payouts work", List.of("Clear " + rewards.minimumRewardWave() + " waves and play " + duration(rewards.minimumParticipationSeconds()) + ".",
                "Your share depends on your kills.", "Payouts are sent when the match ends.", "Cooldowns and payout limits apply."), null);
        if (player.hasPermission("mobarena.join")) put(menu, 23, Material.IRON_CHESTPLATE, "Choose a class", List.of("Pick your gear and join."), p -> openEquipment(p, arenaId));
        back(menu, p -> open(p, 0));
        put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> openInfo(p, arenaId));
        player.openInventory(menu.inventory);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())
                || menu.closed || menu.transitioning || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= menu.inventory.getSize()) return;
        Consumer<Player> action = (event.getClick() == ClickType.RIGHT ? menu.rightActions : menu.actions).get(slot);
        if (action == null) return;
        menu.transitioning = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!current(player, menu)) return;
            menu.closed = true;
            player.closeInventory();
            try { action.accept(player); }
            catch (IllegalArgumentException | IllegalStateException error) { player.sendMessage(ArenaMessages.error(error.getMessage())); }
        });
    }

    @EventHandler public void drag(InventoryDragEvent event) { if (isMenu(event.getView().getTopInventory())) event.setCancelled(true); }
    @EventHandler public void close(InventoryCloseEvent event) { if (event.getInventory().getHolder() instanceof Menu menu) menu.closed = true; }

    private <T> void load(Player player, Menu menu, Supplier<CompletableFuture<T>> query, Runnable permission, Consumer<T> render) {
        load(player, menu, query, permission, render, () -> failed(menu));
    }

    private <T> void load(Player player, Menu menu, Supplier<CompletableFuture<T>> query, Runnable permission, Consumer<T> render, Runnable failure) {
        CompletableFuture<T> future;
        try { future = query.get(); }
        catch (RuntimeException error) { failure.run(); return; }
        future.whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!current(player, menu) || menu.transitioning) return;
                    try {
                        permission.run();
                        if (error != null) failure.run(); else render.accept(result);
                    } catch (IllegalArgumentException | IllegalStateException rejected) {
                        menu.clear();
                        put(menu, 22, Material.BARRIER, "Can't open this page", List.of(rejected.getMessage()), null);
                        back(menu, p -> open(p, 0));
                    }
                });
            } catch (IllegalStateException ignored) { /* Plugin stopped between completion and scheduling. */ }
        });
    }

    private boolean current(Player player, Menu menu) {
        return plugin.isEnabled() && !menu.closed && player.isOnline() && plugin.getServer().getPlayer(menu.owner) == player
                && player.getOpenInventory().getTopInventory() == menu.inventory;
    }

    private Menu loading(Player player, String title, Consumer<Player> back) {
        Menu menu = menu(player, title);
        put(menu, 22, Material.CLOCK, "Loading...", List.of(), null);
        back(menu, back);
        player.openInventory(menu.inventory);
        return menu;
    }

    private void failed(Menu menu) {
        // Keep the existing back action and never expose SQL diagnostics to players.
        put(menu, 22, Material.BARRIER, "Couldn't load this page", List.of("Try opening it again in a moment."), null);
    }

    private Menu menu(Player player, String title) { return new Menu(player.getUniqueId(), title); }
    private void back(Menu menu, Consumer<Player> action) { put(menu, 49, Material.ARROW, "Back", List.of(), action); }
    private void put(Menu menu, int slot, Material material, String title, List<String> lore, Consumer<Player> action) {
        menu.inventory.setItem(slot, items.create(material, title, lore));
        menu.actions.remove(slot); if (action != null) menu.actions.put(slot, action);
    }
    private Arena arena(String id) {
        Arena arena = plugin.getArenaManager().getArena(id);
        if (arena == null) throw new IllegalArgumentException("That arena no longer exists.");
        return arena;
    }
    private static void require(Player player, String permission) {
        if (!player.hasPermission(permission)) throw new IllegalArgumentException("You don't have permission to use this.");
    }
    private static void requireTarget(Player player, String permission, UUID target) {
        require(player, permission);
        if (!player.getUniqueId().equals(target)) require(player, "mobarena.stats.others");
    }
    private static int lastPage(int size) { return Math.max(0, (size - 1) / PAGE_SIZE); }
    private static String playerLabel(UUID id, String name) { return name == null || name.isBlank() || name.equals(id.toString()) ? "Player " + id.toString().substring(0, 8) : name; }
    private static int page(int requested, int size) { return Math.max(0, Math.min(requested, lastPage(size))); }
    private static String date(Instant at) { return DATE.format(at); }
    static String money(BigDecimal value) { return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String percent(BigDecimal value) { return value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%"; }
    static String duration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
    static String payout(Settlement.Status status, BigDecimal amount) {
        return switch (status) {
            case CONFIRMED -> "Paid: " + money(amount);
            case PENDING, PROCESSING -> "Payment queued: " + money(amount);
            case UNCERTAIN -> "Payout needs checking: " + money(amount);
            case FAILED -> "Payment failed: " + money(amount);
            case WITHHELD -> "No payout";
            case VOID -> "Payout cancelled";
        };
    }
    private static List<String> limitLines(String caps) {
        return java.util.Arrays.stream(caps.split(",")).filter(s -> !s.isBlank()).map(s -> "Limit: " + switch (s.trim()) {
            case "REWARD_COOLDOWN" -> "reward cooldown";
            case "DAILY_CAP" -> "daily payout limit";
            case "PLAYER_CAP", "PER_PLAYER_CAP", "PLAYER_SESSION_CAP" -> "match payout limit";
            case "CAP_RECHECK_AT_PAYMENT" -> "payout limit reached";
            case "MINIMUM_WAVE", "MIN_WAVE" -> "not enough waves cleared";
            case "MINIMUM_PARTICIPATION", "MIN_PARTICIPATION" -> "not enough time played";
            case "NO_KILLS", "ZERO_KILLS" -> "no kills this match";
            case "CANCELLED" -> "match cancelled";
            case "ECONOMY_DISABLED" -> "payouts disabled";
            case "SHORT_SESSION_REDUCTION" -> "short match reduction";
            default -> s.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        }).toList();
    }
    private static List<String> arenaLines(Player player, Arena arena) {
        List<String> lines = new ArrayList<>(List.of("Status: " + arena.getArenaState().name().replace('_', ' ').toLowerCase(Locale.ROOT),
                "Players: " + arena.getPlayers().size() + "/" + arena.getMaxPlayers(), "Wave: " + arena.getCurrentWave() + " | Cleared: " + arena.getCompletedWaves(),
                "Prize pool: " + money(arena.getPrizePot()), "Your kills: " + arena.getKills(player.getUniqueId())));
        if (arena.isParticipant(player.getUniqueId())) lines.add("Your share so far: " + money(CommandManager.estimatedShare(arena, player.getUniqueId())) + " before limits");
        if (!arena.getValidationErrors().isEmpty()) lines.add("Closed for setup");
        return lines;
    }
    private static ItemStack item(Material material, String name, List<String> lines) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }
    @FunctionalInterface interface Items { ItemStack create(Material material, String name, List<String> lore); }
    private final class Menu implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        private final Map<Integer, Consumer<Player>> rightActions = new HashMap<>();
        private boolean closed;
        private boolean transitioning;
        private Menu(UUID owner, String title) { this.owner = owner; this.inventory = plugin.getServer().createInventory(this, 54, Component.text(title)); }
        private void clear() { inventory.clear(); actions.clear(); rightActions.clear(); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
