package cloud.mineplay.mobarena.gui;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.commands.CommandManager;
import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.duels.DuelTeams.Team;
import cloud.mineplay.mobarena.economy.MatchDetails;
import cloud.mineplay.mobarena.economy.MatchPerformance;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.economy.Settlement;
import cloud.mineplay.mobarena.equipment.ClassChoice;
import cloud.mineplay.mobarena.equipment.ClassPreview;
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
        put(menu, 46, Material.IRON_CHESTPLATE, "Classes", List.of("Unlock more classes with all-time mob kills."), this::openClasses);
        if (player.hasPermission("mobarena.top")) put(menu, 47, Material.GOLD_INGOT, "Leaderboard", List.of("Top killers and their earnings"), this::openLeaderboard);
        put(menu, 48, Material.BARRIER, "Leave arena", List.of("Your kills still count for this match."), p -> plugin.getArenaManager().leave(p));
        if (player.hasPermission("mobarena.stats")) put(menu, 49, Material.BOOK, "Your stats", List.of("Kills, best wave and total earnings"), p -> openStats(p, p.getUniqueId(), p.getName()));
        put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> open(p, page));
        if (player.hasPermission("mobarena.history")) put(menu, 51, Material.WRITABLE_BOOK, "Mob arena matches", List.of("Recent results, players and payouts"), p -> openHistory(p, p.getUniqueId(), p.getName(), 1));
        if (player.hasPermission("mobarena.history")) put(menu, 52, Material.SHIELD, "Recent duels", List.of("Teams, winners and your performance"), p -> openDuels(p, 1));
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
        boolean chooseTeam = arenaId != null && arena(arenaId).isDuel() && arena(arenaId).definition().chooseTeams;
        for (int i = page * PAGE_SIZE; i < Math.min(choices.size(), (page + 1) * PAGE_SIZE); i++) {
            ClassChoice choice = choices.get(i);
            boolean personal = choice.id().equalsIgnoreCase("personal");
            boolean unlocked = choice.requiredKills() == 0 || (kills != null && kills >= choice.requiredKills());
            List<String> lore = new ArrayList<>();
            if (personal) lore.add("Fight with the equipment you're carrying.");
            else {
                lore.add(unlocked ? "Unlocked" : kills == null ? (failed ? "Kill count unavailable" : "Checking your kills...")
                        : "Locked - " + (choice.requiredKills() - kills) + " more kills");
                lore.add(kills == null ? "Requires " + choice.requiredKills() + " all-time mob kills"
                        : "All-time mob kills: " + kills + " / " + choice.requiredKills());
                lore.add("Your gear is saved until you leave.");
            }
            lore.add("");
            lore.add(personal ? chooseTeam ? "Click to choose your team." : "Click to join with your own gear." : "Click to preview this class.");
            put(menu, i % PAGE_SIZE, unlocked ? choice.icon() : Material.BARRIER, choice.displayName(), lore,
                    !personal ? p -> openClassPreview(p, arenaId, choice.id(), page, null) : arenaId != null ? p -> {
                        require(p, "mobarena.join");
                        Arena selected = arena(arenaId);
                        if (selected.isDuel() && selected.definition().chooseTeams) openTeamSelection(p, arenaId, choice.id());
                        else plugin.getArenaManager().join(p, arenaId, choice.id());
                    } : null);
        }
        if (choices.isEmpty()) {
            boolean personalAvailable = plugin.getEquipmentService().availableChoices().stream().anyMatch("personal"::equalsIgnoreCase);
            if (personalAvailable) put(menu, 22, Material.CHEST, "No classes available", List.of("You can join with your own gear."), null);
            else put(menu, 22, Material.BARRIER, "No equipment available",
                    List.of("An admin needs to check the equipment settings.", "Try again once the settings are fixed."), null);
        }
        Consumer<Player> previous = p -> { if (arenaId == null) openClasses(p, page - 1); else openEquipment(p, arenaId, page - 1); };
        Consumer<Player> next = p -> { if (arenaId == null) openClasses(p, page + 1); else openEquipment(p, arenaId, page + 1); };
        if (page > 0) put(menu, 45, Material.ARROW, "Previous page", List.of(), previous);
        back(menu, p -> open(p, 0));
        put(menu, 50, kills == null ? Material.CLOCK : Material.IRON_SWORD,
                kills == null ? failed ? "Kill count unavailable" : "Checking all-time mob kills..." : kills + " all-time mob kills",
                List.of("Mob arena kills update after a match ends.", "Click to refresh."),
                p -> { if (arenaId == null) openClasses(p, page); else openEquipment(p, arenaId, page); });
        if (page < lastPage(choices.size())) put(menu, 53, Material.ARROW, "Next page", List.of(), next);
    }

    public void openClassPreview(Player player, String arenaId, String classId) {
        openClassPreview(player, arenaId, classId, 0, null);
    }

    public void openClassPreview(Player player, String arenaId, String classId, String team) {
        openClassPreview(player, arenaId, classId, 0, team);
    }

    private void openClassPreview(Player player, String arenaId, String classId, int choicePage, String team) {
        String permission = arenaId == null ? "mobarena.use" : "mobarena.join";
        require(player, permission);
        if (arenaId != null) arena(arenaId);
        ClassPreview preview = preview(classId);
        Menu menu = menu(player, "Class | " + preview.choice().displayName());
        renderPreview(menu, preview, null, false, arenaId, choicePage, team);
        player.openInventory(menu.inventory);
        load(player, menu, () -> plugin.getEconomyService().stats(player.getUniqueId()), () -> {
            require(player, permission);
            if (arenaId != null) arena(arenaId);
        }, stats -> renderPreview(menu, preview(classId), stats.kills(), false, arenaId, choicePage, team),
                () -> renderPreview(menu, preview(classId), null, true, arenaId, choicePage, team));
    }

    private ClassPreview preview(String classId) {
        ClassPreview preview = plugin.getEquipmentService().classPreview(classId);
        if (preview == null) throw new IllegalArgumentException("That class is no longer available. Choose another class.");
        return preview;
    }

    private void renderPreview(Menu menu, ClassPreview preview, Long kills, boolean failed, String arenaId, int choicePage, String team) {
        menu.clear();
        ClassChoice choice = preview.choice();
        ItemStack[] armor = preview.armor();
        String[] slots = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int slot = 0; slot < slots.length; slot++) previewItem(menu, slot, armor[3 - slot], slots[slot]);
        previewItem(menu, 4, preview.offhand(), "Offhand");
        List<String> effects = preview.effects().stream().map(effect -> label(effect.getType().getKey().getKey())
                + " " + (effect.getAmplifier() + 1) + " | "
                + (effect.isInfinite() ? "Until you leave" : duration((effect.getDuration() + 19L) / 20))).toList();
        put(menu, 6, Material.BREWING_STAND, "Class effects", effects.isEmpty() ? List.of("No extra effects.") : effects, null);
        boolean unlocked = choice.requiredKills() == 0 || kills != null && choice.unlocked(kills);
        List<String> progress = new ArrayList<>();
        progress.add("Requires " + choice.requiredKills() + " all-time mob kills");
        progress.add(kills == null ? failed ? "Kill count unavailable" : "Checking your kills..." : "Your kills: " + kills);
        if (!unlocked && kills != null) progress.add((choice.requiredKills() - kills) + " more kills to unlock");
        progress.add("Hover over the items to see their details.");
        progress.add("Bottom row: your hotbar.");
        put(menu, 8, choice.icon(), choice.displayName(), progress, null);
        ItemStack[] inventory = preview.inventory();
        for (int slot = 0; slot < inventory.length; slot++) {
            if (inventory[slot] != null) menu.inventory.setItem(slot < 9 ? 36 + slot : slot, inventory[slot]);
        }
        back(menu, p -> { if (arenaId == null) openClasses(p, choicePage); else openEquipment(p, arenaId, choicePage); });
        put(menu, 47, Material.CLOCK, "Refresh", List.of("Check your unlock progress again."),
                p -> openClassPreview(p, arenaId, choice.id(), choicePage, team));
        if (!unlocked) put(menu, 51, Material.BARRIER, "Class locked", List.of(kills == null
                ? failed ? "Couldn't check your kills. Try refreshing." : "Checking your unlock progress..."
                : "Earn " + (choice.requiredKills() - kills) + " more mob kills to use this class."), null);
        else if (arenaId == null) put(menu, 51, Material.LIME_DYE, "Choose an arena", List.of("This class is unlocked."), p -> open(p, 0));
        else {
            Arena selected = arena(arenaId);
            boolean chooseTeam = selected.isDuel() && selected.definition().chooseTeams && team == null;
            put(menu, 51, Material.LIME_DYE, chooseTeam ? "Choose team" : "Join as " + choice.displayName(),
                    List.of("Arena: " + selected.getName(), "Your own gear is saved until you leave."), p -> {
                        require(p, "mobarena.join");
                        preview(choice.id()); // A reload may have removed this kit while its preview was open.
                        Arena currentArena = arena(arenaId);
                        if (team != null) plugin.getArenaManager().join(p, arenaId, choice.id(), team);
                        else if (currentArena.isDuel() && currentArena.definition().chooseTeams) openTeamSelection(p, arenaId, choice.id(), choicePage);
                        else plugin.getArenaManager().join(p, arenaId, choice.id());
                    });
        }
    }

    private void previewItem(Menu menu, int slot, ItemStack item, String name) {
        if (item == null || item.getType() == Material.AIR) put(menu, slot, Material.GRAY_STAINED_GLASS_PANE, name,
                List.of("Nothing equipped in this slot."), null);
        else menu.inventory.setItem(slot, item);
    }

    private static String label(String key) {
        return java.util.Arrays.stream(key.split("_")).map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    public void openTeamSelection(Player player, String arenaId, String choice) {
        openTeamSelection(player, arenaId, choice, 0);
    }

    private void openTeamSelection(Player player, String arenaId, String choice, int choicePage) {
        require(player, "mobarena.join");
        Arena arena = arena(arenaId);
        if (!arena.isDuel() || !arena.definition().chooseTeams)
            throw new IllegalArgumentException("This arena assigns teams automatically. Choose your class again.");
        Menu menu = menu(player, "Choose a team | " + arena.getName());
        String className = choice.equalsIgnoreCase("personal") ? "Your own gear" : plugin.getEquipmentService().classChoices().stream()
                .filter(kit -> kit.id().equalsIgnoreCase(choice)).map(ClassChoice::displayName).findFirst().orElse(choice);
        for (Team team : Team.values()) {
            int count = arena.teamCount(team), size = arena.definition().teamSize;
            boolean full = count >= size;
            List<String> lore = List.of("Players: " + count + "/" + size, "Class: " + className,
                    full ? "This team is full." : "Click to join " + team.label().toLowerCase(Locale.ROOT) + " team.");
            put(menu, team == Team.RED ? 20 : 24, full ? Material.BARRIER : team == Team.RED ? Material.RED_WOOL : Material.BLUE_WOOL,
                    team.label() + " team", lore, full ? null : p -> {
                        require(p, "mobarena.join");
                        plugin.getArenaManager().join(p, arenaId, choice, team.name());
                    });
        }
        put(menu, 13, Material.SHIELD, "Pick your side", List.of("Both teams need " + arena.definition().teamSize + " players to start.",
                "Friendly fire is off.", "No cash rewards in duels."), null);
        back(menu, p -> { if (choice.equalsIgnoreCase("personal")) openEquipment(p, arenaId, choicePage);
            else openClassPreview(p, arenaId, choice, choicePage, null); });
        put(menu, 50, Material.CLOCK, "Refresh teams", List.of(), p -> openTeamSelection(p, arenaId, choice, choicePage));
        player.openInventory(menu.inventory);
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
            put(menu, 10, Material.IRON_SWORD, stats.kills() + " kills", List.of("Across mob arena matches"), null);
            put(menu, 12, Material.BOOK, stats.sessions() + " matches", List.of("Mob arena matches you took part in"), null);
            put(menu, 14, Material.DIAMOND, "Best wave: " + stats.highestWave(), List.of("Highest completed wave"), null);
            put(menu, 16, Material.GOLD_INGOT, money(stats.earned()) + " earned", List.of("Paid to your balance"), null);
            put(menu, 22, Material.IRON_AXE, decimal(stats.damage()) + " damage", List.of("Damage dealt in mob arena matches"), null);
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

    public void openDuels(Player player, int requestedPage) {
        require(player, "mobarena.history");
        int page = Math.max(1, Math.min(1_000_000, requestedPage));
        Menu menu = loading(player, "MobArena | Recent duels " + page, p -> open(p, 0));
        load(player, menu, () -> plugin.getEconomyService().duelHistory(player.getUniqueId(), page),
                () -> require(player, "mobarena.history"), rows -> {
                    menu.clear();
                    int slot = 0;
                    for (DuelResult duel : rows) {
                        List<String> lore = new ArrayList<>(List.of(date(duel.finishedAt()), duelMode(duel), duelOutcome(duel),
                                "Red: " + duelCount(duel, "RED") + " | Blue: " + duelCount(duel, "BLUE")));
                        duel.participants().stream().filter(row -> row.playerId().equals(player.getUniqueId())).findFirst().ifPresent(row -> {
                            lore.add("Your team: " + teamLabel(row.team()));
                            lore.add("Kills: " + row.kills() + " | Damage: " + decimal(row.damage()));
                            lore.add("Time played: " + duration(row.participationSeconds()));
                        });
                        lore.add(""); lore.add("Click to see both teams");
                        put(menu, slot++, duel.cancelled() ? Material.GRAY_DYE : Material.SHIELD, duel.arenaId(), lore,
                                p -> openMatch(p, duel.sessionId(), 0, q -> openDuels(q, page)));
                    }
                    if (rows.isEmpty()) put(menu, 22, Material.PAPER, "No duels on this page",
                            List.of(page == 1 ? "Play a duel to start your history." : "Go back to see earlier results."), null);
                    if (page > 1) put(menu, 45, Material.ARROW, "Previous page", List.of(), p -> openDuels(p, page - 1));
                    back(menu, p -> open(p, 0));
                    put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> openDuels(p, page));
                    if (rows.size() == 10 && page < 1_000_000) put(menu, 53, Material.ARROW, "Next page", List.of(), p -> openDuels(p, page + 1));
                });
    }

    private void openMatch(Player player, UUID session, int requestedPage, Consumer<Player> back) {
        require(player, "mobarena.match");
        Menu menu = loading(player, "MobArena | Match results", back);
        load(player, menu, () -> plugin.getEconomyService().match(session).thenCompose(match -> match != null
                ? CompletableFuture.completedFuture(new ResultPage(match, null))
                : plugin.getEconomyService().duelMatch(session).thenApply(duel -> new ResultPage(null, duel))),
                () -> require(player, "mobarena.match"), result -> {
            menu.clear();
            MatchDetails match = result.mob();
            if (match == null) {
                if (result.duel() != null) { renderDuelMatch(player, menu, result.duel(), requestedPage, back); return; }
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

    private void renderDuelMatch(Player player, Menu menu, DuelResult duel, int requestedPage, Consumer<Player> back) {
        int page = page(requestedPage, duel.participants().size());
        for (int i = page * PAGE_SIZE; i < Math.min(duel.participants().size(), (page + 1) * PAGE_SIZE); i++) {
            DuelResult.Participant row = duel.participants().get(i);
            List<String> lore = new ArrayList<>(List.of("Team: " + teamLabel(row.team()), "Kills: " + row.kills(),
                    "Damage: " + decimal(row.damage()), "Time played: " + duration(row.participationSeconds()),
                    row.eliminated() ? "Eliminated" : "Not eliminated"));
            put(menu, i % PAGE_SIZE, row.team().equals("RED") ? Material.RED_WOOL : Material.BLUE_WOOL,
                    playerLabel(row.playerId(), row.playerName()), lore, null);
        }
        if (page > 0) put(menu, 45, Material.ARROW, "Previous players", List.of(), p -> openMatch(p, duel.sessionId(), page - 1, back));
        List<String> summary = new ArrayList<>(List.of(date(duel.finishedAt()), duelMode(duel),
                "Red: " + duelCount(duel, "RED") + " | Blue: " + duelCount(duel, "BLUE"),
                "Match time: " + duration(java.time.Duration.between(duel.startedAt(), duel.finishedAt()).getSeconds()), "No cash rewards"));
        if (duel.endReason().equals("TIME_LIMIT")) summary.add("Time limit reached");
        summary.add("Match: " + duel.sessionId());
        put(menu, 48, Material.SHIELD, duel.arenaId() + " | " + duelOutcome(duel), summary, null);
        back(menu, back);
        put(menu, 50, Material.CLOCK, "Refresh", List.of(), p -> openMatch(p, duel.sessionId(), page, back));
        if (page < lastPage(duel.participants().size())) put(menu, 53, Material.ARROW, "Next players", List.of(), p -> openMatch(p, duel.sessionId(), page + 1, back));
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
        if (arena.isDuel()) {
            put(menu, 21, Material.SHIELD, "How duels work", List.of("Last team standing wins.", "Friendly fire is off.",
                    "Time limit: " + duration(arena.definition().duelMaxSeconds), "A draw is called when time runs out.", "No cash rewards in duels."), null);
        } else {
            var rewards = plugin.getEconomyService().rewardSettings();
            put(menu, 21, Material.GOLD_INGOT, "How payouts work", List.of("Clear " + rewards.minimumRewardWave() + " waves and play " + duration(rewards.minimumParticipationSeconds()) + ".",
                    "Your share depends on your kills.", "Payouts are sent when the match ends.", "Cooldowns and payout limits apply."), null);
        }
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
        List<String> lines = new ArrayList<>(List.of("Mode: " + arena.definition().mode.label(),
                "Status: " + arena.getArenaState().name().replace('_', ' ').toLowerCase(Locale.ROOT),
                "Players: " + arena.getPlayers().size() + "/" + arena.getMaxPlayers()));
        if (arena.isDuel()) {
            lines.add("Red: " + arena.teamCount(Team.RED) + "/" + arena.definition().teamSize
                    + " | Blue: " + arena.teamCount(Team.BLUE) + "/" + arena.definition().teamSize);
            lines.add(arena.definition().chooseTeams ? "Choose your team" : "Teams assigned automatically");
            if (arena.team(player.getUniqueId()) != null) lines.add("Your team: " + arena.team(player.getUniqueId()).label());
            lines.add("Time limit: " + duration(arena.definition().duelMaxSeconds));
            lines.add("No cash rewards in duels");
        } else {
            lines.add("Wave: " + arena.getCurrentWave() + " | Cleared: " + arena.getCompletedWaves());
            lines.add("Prize pool: " + money(arena.getPrizePot()));
            if (arena.definition().config.contains("waves.mobs")) lines.add("Mob pool: set for this arena");
            else if (!arena.definition().mobSet.isEmpty()) lines.add("Mob set: " + arena.definition().mobSet);
            if (arena.isParticipant(player.getUniqueId())) lines.add("Your share so far: " + money(CommandManager.estimatedShare(arena, player.getUniqueId())) + " before limits");
        }
        lines.add("Your kills: " + arena.getKills(player.getUniqueId()));
        if (!arena.getValidationErrors().isEmpty()) lines.add("Closed for setup");
        return lines;
    }
    private static String duelMode(DuelResult duel) { return duel.mode().equals("TEAM_DUEL") ? "Team duel" : "Duel"; }
    private static String teamLabel(String team) { return team.equals("RED") ? "Red" : "Blue"; }
    private static long duelCount(DuelResult duel, String team) { return duel.participants().stream().filter(row -> row.team().equals(team)).count(); }
    private static String duelOutcome(DuelResult duel) {
        return duel.cancelled() ? "Cancelled" : duel.winner().equals("DRAW") ? "Draw" : teamLabel(duel.winner()) + " team won";
    }
    private record ResultPage(MatchDetails mob, DuelResult duel) { }
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
