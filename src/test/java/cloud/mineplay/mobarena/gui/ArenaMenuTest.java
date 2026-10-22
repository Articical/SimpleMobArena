package cloud.mineplay.mobarena.gui;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.duels.DuelResult;
import cloud.mineplay.mobarena.duels.DuelTeams.Team;
import cloud.mineplay.mobarena.enums.GameState;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.economy.MatchDetails;
import cloud.mineplay.mobarena.economy.MatchPerformance;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.economy.Settlement;
import cloud.mineplay.mobarena.equipment.ClassChoice;
import cloud.mineplay.mobarena.equipment.ClassPreview;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaMenuTest {
    private SimpleMobArena plugin;
    private Server server;
    private EconomyService economy;
    private EquipmentService equipment;
    private ArenaManager arenas;
    private Player player;
    private UUID owner;
    private InventoryView view;
    private ArenaMenu menus;
    private final AtomicReference<Inventory> open = new AtomicReference<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final Map<Inventory, Map<Integer, Card>> contents = new IdentityHashMap<>();
    private final Map<ItemStack, Card> cards = new IdentityHashMap<>();
    private final Map<Inventory, Map<Integer, ItemStack>> stacks = new IdentityHashMap<>();

    @BeforeEach void setup() {
        plugin = mock(SimpleMobArena.class); server = mock(Server.class);
        economy = mock(EconomyService.class); equipment = mock(EquipmentService.class); arenas = mock(ArenaManager.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getEconomyService()).thenReturn(economy); when(plugin.getEquipmentService()).thenReturn(equipment);
        when(plugin.getArenaManager()).thenReturn(arenas); when(arenas.getArenas()).thenReturn(List.of());
        BukkitScheduler scheduler = mock(BukkitScheduler.class); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { tasks.add(call.getArgument(1)); return mock(BukkitTask.class); });
        when(server.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(call -> {
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(call.getArgument(0)); when(inventory.getSize()).thenReturn(54);
            Map<Integer, Card> slots = new HashMap<>(); contents.put(inventory, slots);
            Map<Integer, ItemStack> rawSlots = new HashMap<>(); stacks.put(inventory, rawSlots);
            doAnswer(set -> { slots.put(set.getArgument(0), cards.get(set.<ItemStack>getArgument(1)));
                rawSlots.put(set.getArgument(0), set.getArgument(1)); return null; }).when(inventory).setItem(anyInt(), any(ItemStack.class));
            doAnswer(clear -> { slots.clear(); rawSlots.clear(); return null; }).when(inventory).clear();
            return inventory;
        });
        owner = UUID.randomUUID(); player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner); when(player.getName()).thenReturn(".Bedrock Player");
        when(player.isOnline()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayer(owner)).thenReturn(player);
        view = mock(InventoryView.class); when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenAnswer(call -> open.get());
        when(player.openInventory(any(Inventory.class))).thenAnswer(call -> { open.set(call.getArgument(0)); return view; });
        menus = new ArenaMenu(plugin, (material, title, lore) -> {
            ItemStack item = mock(ItemStack.class); cards.put(item, new Card(material, title, List.copyOf(lore))); return item;
        });
        when(equipment.availableChoices()).thenReturn(List.of("knight"));
        when(equipment.classChoices()).thenReturn(List.of(new ClassChoice("knight", "Knight", Material.IRON_SWORD, 0)));
        when(equipment.classPreview(anyString())).thenAnswer(call -> equipment.classChoices().stream()
                .filter(choice -> choice.id().equalsIgnoreCase(call.getArgument(0))).findFirst()
                .map(choice -> new ClassPreview(choice, new ItemStack[36], new ItemStack[4], null, List.of())).orElse(null));
    }

    @Test void dataLoadsWithoutBlockingAndOnlyRendersOnTheScheduledServerTask() {
        CompletableFuture<PlayerStatistics> result = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(result);
        menus.openStats(player, owner, player.getName());
        assertEquals("Loading...", card(22).title());
        assertTrue(tasks.isEmpty());
        result.complete(stats(123));
        assertEquals("Loading...", card(22).title());
        runTasks();
        assertEquals("123 kills", card(10).title());
        assertEquals("$12.50 earned", card(16).title());
        verify(player, times(1)).openInventory(any(Inventory.class));
    }

    @Test void closedOrReplacedInventoriesNeverGetReopenedByLateResults() {
        CompletableFuture<PlayerStatistics> result = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(result);
        menus.openStats(player, owner, player.getName()); Inventory first = open.get();
        InventoryCloseEvent close = mock(InventoryCloseEvent.class); when(close.getInventory()).thenReturn(first); menus.close(close);
        result.complete(stats(12)); runTasks();
        assertEquals("Loading...", contents.get(first).get(22).title());
        verify(player, times(1)).openInventory(any(Inventory.class));
        CompletableFuture<PlayerStatistics> second = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(second);
        menus.openStats(player, owner, player.getName()); Inventory stale = open.get();
        menus.open(player, 0); Inventory latest = open.get();
        second.complete(stats(50)); runTasks();
        assertSame(latest, open.get()); assertEquals("Loading...", contents.get(stale).get(22).title());
    }

    @Test void reconnectWithSameUuidDoesNotReceiveThePreviousSessionsMenu() {
        CompletableFuture<PlayerStatistics> result = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(result);
        menus.openStats(player, owner, player.getName());
        when(server.getPlayer(owner)).thenReturn(mock(Player.class));
        result.complete(stats(5)); runTasks();
        assertEquals("Loading...", card(22).title());
        verify(player, times(1)).openInventory(any(Inventory.class));
    }

    @Test void othersPermissionIsCheckedAgainBeforeAsyncResultsAreShown() {
        UUID other = UUID.randomUUID(); CompletableFuture<PlayerStatistics> result = new CompletableFuture<>();
        when(economy.stats(other)).thenReturn(result);
        menus.openStats(player, other, "Other");
        when(player.hasPermission("mobarena.stats.others")).thenReturn(false);
        result.complete(stats(9)); runTasks();
        assertEquals("Can't open this page", card(22).title()); assertNull(card(10));
    }

    @Test void failedReadsKeepNavigationAndNeverExposeDatabaseErrors() {
        when(economy.stats(owner)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("jdbc:secret=password")));
        menus.openStats(player, owner, player.getName()); runTasks();
        assertEquals("Couldn't load this page", card(22).title()); assertEquals("Back", card(49).title());
        assertFalse(contents.get(open.get()).values().toString().contains("password"));
    }

    @Test void mainIconsOpenScreensDirectlyAndQueuedClicksRecheckPermissions() {
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(8)));
        menus.open(player, 0);
        InventoryClickEvent event = click(49); verify(event).setCancelled(true);
        assertEquals(1, tasks.size()); verify(economy, never()).stats(any());
        runTasks(); assertEquals("8 kills", card(10).title());
        verify(player, never()).performCommand(anyString());
        menus.open(player, 0); click(49);
        when(player.hasPermission("mobarena.stats")).thenReturn(false); runTasks();
        verify(economy, times(1)).stats(owner);
    }

    @Test void lockedClassesCannotJoinAndAllEquipmentChoicesArePaginated() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        List<ClassChoice> choices = new ArrayList<>();
        choices.add(new ClassChoice("knight", "Knight", Material.IRON_SWORD, 0));
        for (int i = 1; i < 48; i++) choices.add(new ClassChoice("class" + i, "Class " + i, Material.BOW, 100));
        when(equipment.classChoices()).thenReturn(choices); when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(50)));
        menus.openEquipment(player, "spawn"); runTasks();
        assertEquals(Material.BARRIER, card(1).material()); assertTrue(card(1).lore().contains("Locked - 50 more kills"));
        click(1); runTasks(); assertEquals("Class locked", card(51).title());
        click(51); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(any(), any(), any());
        click(49); runTasks();
        click(53); runTasks();
        assertEquals("Class 45", card(0).title()); assertEquals("Class 47", card(2).title());
        assertNotNull(card(45)); assertNull(card(53));
        click(45); runTasks(); click(0); click(0); runTasks();
        verify(arenas, never()).join(any(), any(), any());
        click(51); click(51); runTasks();
        verify(arenas, times(1)).join(player, "spawn", "knight");
    }

    @Test void browseClassesDoesNotJoinAndPersonalGearIsNotAdvertisedAsAnUnlock() {
        when(equipment.availableChoices()).thenReturn(List.of("personal", "knight"));
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(500)));
        menus.openClasses(player); runTasks();
        assertEquals("Knight", card(0).title()); assertNull(card(1));
        click(0); runTasks(); assertEquals("Choose an arena", card(51).title());
        verify(arenas, never()).join(any(), any(), any());
        InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
        menus.drag(drag); verify(drag).setCancelled(true);
    }

    @Test void slowOrUnavailableStatisticsKeepPersonalAndFreeClassesPlayable() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        when(equipment.availableChoices()).thenReturn(List.of("personal", "knight", "archer"));
        when(equipment.classChoices()).thenReturn(List.of(new ClassChoice("knight", "Knight", Material.IRON_SWORD, 0),
                new ClassChoice("archer", "Archer", Material.BOW, 100)));
        CompletableFuture<PlayerStatistics> slow = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(slow);
        menus.openEquipment(player, "spawn");
        assertEquals("Your own gear", card(0).title()); assertEquals(Material.IRON_SWORD, card(1).material());
        assertEquals(Material.BARRIER, card(2).material()); assertTrue(card(2).lore().contains("Checking your kills..."));
        click(2); runTasks(); assertEquals("Class locked", card(51).title()); click(49); runTasks();
        click(0); runTasks(); verify(arenas).join(player, "spawn", "personal");

        when(economy.stats(owner)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SQL unavailable")));
        menus.openEquipment(player, "spawn"); runTasks();
        assertEquals("Kill count unavailable", card(50).title());
        assertEquals(Material.CHEST, card(0).material()); assertEquals(Material.IRON_SWORD, card(1).material());
        assertEquals(Material.BARRIER, card(2).material()); assertTrue(card(2).lore().contains("Kill count unavailable"));
        click(2); runTasks(); assertEquals("Class locked", card(51).title()); click(49); runTasks();
        verify(arenas, never()).join(player, "spawn", "archer");
        click(1); runTasks(); click(51); runTasks(); verify(arenas).join(player, "spawn", "knight");
    }

    @Test void emptyClassesScreenOnlyOffersPersonalGearWhenItIsAvailable() {
        when(equipment.classChoices()).thenReturn(List.of());
        when(equipment.availableChoices()).thenReturn(List.of("PERSONAL"));
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));

        menus.openClasses(player); runTasks();

        assertEquals("No classes available", card(22).title());
        assertEquals(Material.CHEST, card(22).material());
        assertTrue(card(22).lore().contains("You can join with your own gear."));
        click(22); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(any(), any(), any());

        when(equipment.availableChoices()).thenReturn(List.of());
        menus.openClasses(player); runTasks();

        assertEquals("No equipment available", card(22).title());
        assertEquals(Material.BARRIER, card(22).material());
        assertTrue(card(22).lore().stream().anyMatch(line -> line.contains("equipment settings")));
        assertFalse(card(22).lore().stream().anyMatch(line -> line.contains("own gear") || line.contains("personal")));
        assertEquals("Back", card(49).title());
        click(22); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(any(), any(), any());
    }

    @Test void unavailableEquipmentCannotJoinAndRefreshPicksUpRepairedClasses() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        when(equipment.classChoices()).thenReturn(List.of());
        when(equipment.availableChoices()).thenReturn(List.of());
        CompletableFuture<PlayerStatistics> lookup = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(lookup);

        menus.openEquipment(player, "spawn");
        assertEquals("No equipment available", card(22).title());
        click(22); assertTrue(tasks.isEmpty());
        lookup.complete(stats(0)); runTasks();
        assertEquals("No equipment available", card(22).title());
        click(22); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(any(), any(), any());

        when(equipment.availableChoices()).thenReturn(List.of("knight"));
        when(equipment.classChoices()).thenReturn(List.of(new ClassChoice("knight", "Knight", Material.IRON_SWORD, 0)));
        click(50); runTasks();

        assertNull(card(22)); assertEquals("Knight", card(0).title());
        click(0); runTasks(); click(51); runTasks(); verify(arenas).join(player, "spawn", "knight");
    }

    @Test void aClosedMenuCannotCompleteItsQueuedJoin() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));
        menus.openEquipment(player, "spawn"); runTasks(); click(0);
        InventoryCloseEvent close = mock(InventoryCloseEvent.class); when(close.getInventory()).thenReturn(open.get()); menus.close(close);
        runTasks(); verify(arenas, never()).join(any(), any(), any());
    }

    @Test void previewShowsFullStorageArmorOffhandAndPotionStacksWithoutGrantingEquipment() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(10)));
        ItemStack[] inventory = new ItemStack[36];
        inventory[0] = previewStack(Material.IRON_SWORD, 1);
        inventory[8] = previewStack(Material.SPLASH_POTION, 1);
        inventory[9] = previewStack(Material.POTION, 1);
        inventory[35] = previewStack(Material.COOKED_BEEF, 16);
        ItemStack[] armor = {previewStack(Material.IRON_BOOTS, 1), null, null, previewStack(Material.IRON_HELMET, 1)};
        ClassPreview preview = new ClassPreview(equipment.classChoices().getFirst(), inventory, armor, previewStack(Material.SHIELD, 1), List.of());
        doReturn(preview).when(equipment).classPreview("knight");

        menus.openClassPreview(player, "spawn", "knight"); runTasks();

        Map<Integer, ItemStack> shown = stacks.get(open.get());
        assertEquals(Material.IRON_HELMET, shown.get(0).getType());
        assertEquals(Material.IRON_BOOTS, shown.get(3).getType());
        assertEquals(Material.SHIELD, shown.get(4).getType());
        assertEquals(Material.IRON_SWORD, shown.get(36).getType());
        assertEquals(Material.SPLASH_POTION, shown.get(44).getType());
        assertEquals(Material.POTION, shown.get(9).getType());
        assertEquals(Material.COOKED_BEEF, shown.get(35).getType());
        assertEquals(16, shown.get(35).getAmount());
        assertNotSame(inventory[8], shown.get(44));
        assertEquals(List.of("No extra effects."), card(6).lore());
        verify(arenas, never()).join(any(), any(), any());
        verify(equipment, never()).prepare(any(), any(), any(), any());
        for (int slot : List.of(0, 4, 9, 35, 36, 44)) verify(click(slot)).setCancelled(true);
        assertTrue(tasks.isEmpty());
        InventoryClickEvent shift = click(44); when(shift.getClick()).thenReturn(ClickType.SHIFT_LEFT); menus.click(shift);
        verify(shift, times(2)).setCancelled(true);
        InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
        menus.drag(drag); verify(drag).setCancelled(true);
    }

    @Test void previewBackReturnsToTheSameClassPageAndArena() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        List<ClassChoice> choices = new ArrayList<>();
        for (int i = 0; i < 48; i++) choices.add(new ClassChoice("class" + i, "Class " + i, Material.BOW, 0));
        when(equipment.classChoices()).thenReturn(choices);
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));
        menus.openEquipment(player, "spawn"); runTasks(); click(53); runTasks(); click(1); runTasks();
        assertEquals("Class 46", card(8).title());
        click(49); runTasks();
        assertEquals("Class 45", card(0).title()); assertEquals("Class 47", card(2).title());
        click(1); runTasks(); click(51); runTasks();
        verify(arenas).join(player, "spawn", "class46");
    }

    @Test void previewConfirmationRechecksPermissionAvailabilityAndMenuIdentity() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));
        menus.openClassPreview(player, "spawn", "knight"); runTasks(); click(51);
        when(player.hasPermission("mobarena.join")).thenReturn(false); runTasks();
        verify(arenas, never()).join(any(), any(), any());
        when(player.hasPermission("mobarena.join")).thenReturn(true);
        menus.openClassPreview(player, "spawn", "knight"); runTasks(); click(51);
        doReturn(null).when(equipment).classPreview("knight"); runTasks();
        verify(arenas, never()).join(any(), any(), any());
        doReturn(new ClassPreview(equipment.classChoices().getFirst(), new ItemStack[36], new ItemStack[4], null, List.of()))
                .when(equipment).classPreview("knight");
        menus.openClassPreview(player, "spawn", "knight"); runTasks(); click(51);
        menus.open(player, 0); runTasks();
        verify(arenas, never()).join(any(), any(), any());
    }

    @Test void previewRetainsExplicitTeamAndTeamBackReturnsToThePreview() {
        duelArena(true);
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));
        menus.openClassPreview(player, "duel", "knight", "blue"); runTasks();
        click(51); runTasks(); verify(arenas).join(player, "duel", "knight", "blue");
        menus.openClassPreview(player, "duel", "knight"); runTasks(); click(51); runTasks();
        assertEquals("Red team", card(20).title()); click(49); runTasks();
        assertEquals("Knight", card(8).title()); assertEquals("Choose team", card(51).title());
    }

    @Test void latePreviewStatisticsCannotReplaceANewerScreen() {
        CompletableFuture<PlayerStatistics> result = new CompletableFuture<>(); when(economy.stats(owner)).thenReturn(result);
        menus.openClassPreview(player, null, "knight"); Inventory preview = open.get();
        menus.open(player, 0); Inventory current = open.get();
        result.complete(stats(10)); runTasks();
        assertSame(current, open.get());
        assertTrue(contents.get(preview).get(8).lore().contains("Checking your kills..."));
    }

    private static ItemStack previewStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material); when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenAnswer(call -> previewStack(material, amount));
        return stack;
    }

    @Test void historyOpensTheWholeMatchAndBackReturnsToTheSameHistoryPage() {
        UUID match = UUID.randomUUID(); MatchPerformance row = performance(match);
        when(economy.history(owner, 3)).thenReturn(CompletableFuture.completedFuture(List.of(row)));
        when(economy.match(match)).thenReturn(CompletableFuture.completedFuture(details(match, 1)));
        menus.openHistory(player, owner, player.getName(), 3); runTasks();
        assertTrue(card(0).lore().contains("Payment queued: $12.50"));
        click(0); runTasks(); assertEquals(".Player 0", card(0).title());
        click(49); runTasks(); verify(economy, times(2)).history(owner, 3);
        assertEquals("spawn", card(0).title());
    }

    @Test void fullMatchRosterPagesBeyondFortyFivePlayersAndKeepsPayoutStatesDistinct() {
        UUID match = UUID.randomUUID(); when(economy.match(match)).thenReturn(CompletableFuture.completedFuture(details(match, 48)));
        menus.openMatch(player, match); runTasks();
        assertEquals(".Player 0", card(0).title()); assertTrue(card(0).lore().contains("Paid: $12.50"));
        assertTrue(card(1).lore().contains("Payment queued: $12.50"));
        click(53); runTasks();
        assertEquals(".Player 45", card(0).title()); assertEquals(".Player 47", card(2).title());
        assertNotNull(card(45)); assertNull(card(53));
    }

    @Test void leaderboardUsesStoredNamesAndOnlyConfirmedEarnings() {
        when(economy.top(100)).thenReturn(CompletableFuture.completedFuture(List.of(new PlayerStatistics(owner, 1, 12, 23, 3, new BigDecimal("12.50"), ".Offline Player"))));
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(12)));
        menus.openLeaderboard(player); runTasks();
        assertEquals("#1 .Offline Player", card(0).title()); assertTrue(card(0).lore().contains("Earnings: $12.50"));
        click(0); runTasks(); assertEquals("12 kills", card(10).title());
        click(49); runTasks(); assertEquals("#1 .Offline Player", card(0).title());
        verify(economy, times(2)).top(100);
        verify(server, never()).getOfflinePlayer(any(UUID.class));
    }

    @Test void pendingUncertainAndFailedAmountsAreNeverDescribedAsPaid() {
        assertEquals("Paid: $12.50", ArenaMenu.payout(Settlement.Status.CONFIRMED, new BigDecimal("12.5")));
        for (Settlement.Status status : Settlement.Status.values()) if (status != Settlement.Status.CONFIRMED)
            assertFalse(ArenaMenu.payout(status, BigDecimal.TEN).startsWith("Paid:"));
        assertEquals("1m 0s", ArenaMenu.duration(60)); assertEquals("1h 2m", ArenaMenu.duration(3720));
    }

    @Test void chosenTeamsOpenAfterClassSelectionAndFullTeamsCannotBeClicked() {
        Arena arena = duelArena(true);
        when(arena.teamCount(Team.RED)).thenReturn(2);
        when(arena.teamCount(Team.BLUE)).thenReturn(1);
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));

        menus.openEquipment(player, "duel"); runTasks();
        assertTrue(card(0).lore().contains("Click to preview this class."));
        click(0); runTasks();
        assertEquals("Choose team", card(51).title()); click(51); runTasks();

        verify(arenas, never()).join(any(), any(), any());
        assertEquals("Red team", card(20).title());
        assertEquals(Material.BARRIER, card(20).material());
        assertEquals(Material.BLUE_WOOL, card(24).material());
        assertTrue(card(24).lore().contains("Class: Knight"));
        click(20); assertTrue(tasks.isEmpty());
        click(24); runTasks();
        verify(arenas).join(player, "duel", "knight", "BLUE");
    }

    @Test void automaticTeamsJoinAfterPreviewAndTeamClicksRecheckPermission() {
        duelArena(false);
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));
        menus.openEquipment(player, "duel"); runTasks(); click(0); runTasks();
        verify(arenas, never()).join(any(), any(), any()); click(51); runTasks();
        verify(arenas).join(player, "duel", "knight");
        assertThrows(IllegalArgumentException.class, () -> menus.openTeamSelection(player, "duel", "knight"));

        duelArena(true);
        menus.openTeamSelection(player, "duel", "knight"); click(20);
        when(player.hasPermission("mobarena.join")).thenReturn(false); runTasks();
        verify(arenas, never()).join(any(), any(), any(), any());
    }

    @Test void duelCardsAndRulesShowTeamsInsteadOfWavesOrCashPrizes() {
        Arena arena = duelArena(true);
        when(arena.teamCount(Team.RED)).thenReturn(1);
        when(arena.teamCount(Team.BLUE)).thenReturn(2);
        when(arena.team(owner)).thenReturn(Team.BLUE);
        when(arenas.getArenas()).thenReturn(List.of(arena));

        menus.open(player, 0);

        assertTrue(card(0).lore().contains("Mode: Team duel"));
        assertTrue(card(0).lore().contains("Red: 1/2 | Blue: 2/2"));
        assertTrue(card(0).lore().contains("Choose your team"));
        assertTrue(card(0).lore().contains("Your team: Blue"));
        assertFalse(card(0).lore().stream().anyMatch(line -> line.contains("Wave:") || line.contains("Prize pool:")));
        assertEquals("Recent duels", card(52).title());
        menus.openInfo(player, "duel");
        assertEquals("How duels work", card(21).title());
        assertTrue(card(21).lore().contains("No cash rewards in duels."));
        verify(economy, never()).rewardSettings();
    }

    @Test void duelHistoryLoadsOnServerTaskAndOpensCompleteTeamResults() {
        UUID session = UUID.randomUUID(); DuelResult duel = duelResult(session, "RED", false);
        CompletableFuture<List<DuelResult>> history = new CompletableFuture<>();
        when(economy.duelHistory(owner, 3)).thenReturn(history);
        when(economy.match(session)).thenReturn(CompletableFuture.completedFuture(null));
        when(economy.duelMatch(session)).thenReturn(CompletableFuture.completedFuture(duel));

        menus.openDuels(player, 3);
        history.complete(List.of(duel));
        assertEquals("Loading...", card(22).title()); runTasks();
        assertTrue(card(0).lore().contains("Red team won"));
        assertTrue(card(0).lore().contains("Your team: Red"));
        click(0); runTasks();

        assertEquals(".Opponent", card(0).title());
        assertTrue(card(0).lore().contains("Team: Blue"));
        assertTrue(card(0).lore().contains("Damage: 23.5"));
        assertEquals(player.getName(), card(1).title());
        assertEquals("duel | Red team won", card(48).title());
        assertFalse(contents.get(open.get()).values().toString().contains("Payment queued"));
        click(49); runTasks();
        verify(economy, times(2)).duelHistory(owner, 3);
        assertEquals("duel", card(0).title());
    }

    @Test void duelHistoryPermissionAndClosedMenusAreRecheckedWhenTheReadFinishes() {
        CompletableFuture<List<DuelResult>> history = new CompletableFuture<>();
        when(economy.duelHistory(owner, 1)).thenReturn(history);
        menus.openDuels(player, 1); Inventory first = open.get();
        menus.open(player, 0);
        history.complete(List.of(duelResult(UUID.randomUUID(), "DRAW", false))); runTasks();
        assertEquals("Loading...", contents.get(first).get(22).title());

        CompletableFuture<List<DuelResult>> next = new CompletableFuture<>();
        when(economy.duelHistory(owner, 1)).thenReturn(next);
        menus.openDuels(player, 1);
        when(player.hasPermission("mobarena.history")).thenReturn(false);
        next.complete(List.of()); runTasks();
        assertEquals("Can't open this page", card(22).title());
    }

    @Test void missingDuelAndCancelledDuelHaveDistinctResultPages() {
        UUID session = UUID.randomUUID();
        when(economy.match(session)).thenReturn(CompletableFuture.completedFuture(null));
        when(economy.duelMatch(session)).thenReturn(CompletableFuture.completedFuture(null));
        menus.openMatch(player, session); runTasks();
        assertEquals("Match not found", card(22).title());

        DuelResult cancelled = duelResult(session, "DRAW", true);
        when(economy.duelMatch(session)).thenReturn(CompletableFuture.completedFuture(cancelled));
        menus.openMatch(player, session); runTasks();
        assertEquals("duel | Cancelled", card(48).title());
        DuelResult draw = duelResult(session, "DRAW", false);
        when(economy.duelMatch(session)).thenReturn(CompletableFuture.completedFuture(draw));
        menus.openMatch(player, session); runTasks();
        assertEquals("duel | Draw", card(48).title());
        assertTrue(card(48).lore().contains("Time limit reached"));
    }

    @Test void mobMatchDoesNotQueryTheDuelStoreAndHomeDuelIconUsesItsOwnHistory() {
        UUID session = UUID.randomUUID();
        when(economy.match(session)).thenReturn(CompletableFuture.completedFuture(details(session, 1)));
        menus.openMatch(player, session); runTasks();
        verify(economy, never()).duelMatch(any());

        when(economy.duelHistory(owner, 1)).thenReturn(CompletableFuture.completedFuture(List.of()));
        menus.open(player, 0); click(52); runTasks();
        verify(economy).duelHistory(owner, 1);
        assertEquals("No duels on this page", card(22).title());
    }

    private Arena duelArena(boolean choose) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("mode", "TEAM_DUEL"); config.set("duel.team-size", 2);
        config.set("duel.team-selection", choose ? "CHOOSE" : "AUTO");
        ArenaDefinition definition;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            definition = new ArenaDefinition("duel", config, new YamlConfiguration());
        }
        Arena arena = mock(Arena.class);
        when(arena.definition()).thenReturn(definition); when(arena.isDuel()).thenReturn(true);
        when(arena.getId()).thenReturn("duel"); when(arena.getName()).thenReturn("Duel");
        when(arena.getArenaState()).thenReturn(GameState.WAITING); when(arena.getMaxPlayers()).thenReturn(4);
        when(arenas.getArena("duel")).thenReturn(arena);
        return arena;
    }

    private DuelResult duelResult(UUID session, String winner, boolean cancelled) {
        return new DuelResult(session, "duel", "TEAM_DUEL", Instant.parse("2026-10-21T18:00:00Z"), Instant.parse("2026-10-21T18:02:00Z"),
                winner, cancelled, winner.equals("DRAW") ? "TIME_LIMIT" : "TEAM_ELIMINATED", List.of(
                new DuelResult.Participant(owner, player.getName(), "RED", 1, 40, 120, false),
                new DuelResult.Participant(UUID.randomUUID(), ".Opponent", "BLUE", 0, 23.5, 100, true)));
    }

    private PlayerStatistics stats(long kills) { return new PlayerStatistics(owner, 1, kills, 40, 3, new BigDecimal("12.50")); }
    private MatchPerformance performance(UUID id) {
        return new MatchPerformance(id, "spawn", Instant.parse("2026-10-17T18:00:00Z"), Instant.parse("2026-10-17T18:03:00Z"),
                3, false, 12, 250, 180, BigDecimal.ONE, new BigDecimal("12.50"), new BigDecimal("12.50"), BigDecimal.ZERO,
                "", Settlement.Status.PENDING, null, UUID.randomUUID());
    }
    private MatchDetails details(UUID match, int count) {
        List<MatchDetails.MatchParticipant> participants = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID playerId = UUID.randomUUID(); Settlement.Status status = i == 0 ? Settlement.Status.CONFIRMED : Settlement.Status.PENDING;
            Settlement payout = new Settlement(UUID.randomUUID(), match, playerId, BigDecimal.ONE, new BigDecimal("12.50"), new BigDecimal("12.50"), "", status, null, null);
            participants.add(new MatchDetails.MatchParticipant(playerId, ".Player " + i, 12, 250, 180, payout));
        }
        return new MatchDetails(match, "spawn", Instant.parse("2026-10-17T18:00:00Z"), Instant.parse("2026-10-17T18:03:00Z"), 3,
                count * 12L, new BigDecimal("600"), BigDecimal.ZERO, false, "Finished", participants);
    }
    private InventoryClickEvent click(int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(slot); when(event.getClick()).thenReturn(ClickType.LEFT);
        menus.click(event); return event;
    }
    private Card card(int slot) { return contents.get(open.get()).get(slot); }
    private void runTasks() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    private record Card(Material material, String title, List<String> lore) { }
}
