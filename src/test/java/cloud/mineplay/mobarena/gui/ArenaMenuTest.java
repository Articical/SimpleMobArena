package cloud.mineplay.mobarena.gui;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.economy.MatchDetails;
import cloud.mineplay.mobarena.economy.MatchPerformance;
import cloud.mineplay.mobarena.economy.PlayerStatistics;
import cloud.mineplay.mobarena.economy.Settlement;
import cloud.mineplay.mobarena.equipment.ClassChoice;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
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
            doAnswer(set -> { slots.put(set.getArgument(0), cards.get(set.<ItemStack>getArgument(1))); return null; }).when(inventory).setItem(anyInt(), any(ItemStack.class));
            doAnswer(clear -> { slots.clear(); return null; }).when(inventory).clear();
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
        click(1); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(any(), any(), any());
        click(53); runTasks();
        assertEquals("Class 45", card(0).title()); assertEquals("Class 47", card(2).title());
        assertNotNull(card(45)); assertNull(card(53));
        click(45); runTasks(); click(0); click(0); runTasks();
        verify(arenas, times(1)).join(player, "spawn", "knight");
    }

    @Test void browseClassesDoesNotJoinAndPersonalGearIsNotAdvertisedAsAnUnlock() {
        when(equipment.availableChoices()).thenReturn(List.of("personal", "knight"));
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(500)));
        menus.openClasses(player); runTasks();
        assertEquals("Knight", card(0).title()); assertNull(card(1));
        click(0); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(any(), any(), any());
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
        click(2); assertTrue(tasks.isEmpty());
        click(0); runTasks(); verify(arenas).join(player, "spawn", "personal");

        when(economy.stats(owner)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SQL unavailable")));
        menus.openEquipment(player, "spawn"); runTasks();
        assertEquals("Kill count unavailable", card(50).title());
        assertEquals(Material.CHEST, card(0).material()); assertEquals(Material.IRON_SWORD, card(1).material());
        assertEquals(Material.BARRIER, card(2).material()); assertTrue(card(2).lore().contains("Kill count unavailable"));
        click(2); assertTrue(tasks.isEmpty()); verify(arenas, never()).join(player, "spawn", "archer");
        click(1); runTasks(); verify(arenas).join(player, "spawn", "knight");
    }

    @Test void aClosedMenuCannotCompleteItsQueuedJoin() {
        Arena arena = mock(Arena.class); when(arenas.getArena("spawn")).thenReturn(arena); when(arena.getName()).thenReturn("Spawn");
        when(economy.stats(owner)).thenReturn(CompletableFuture.completedFuture(stats(0)));
        menus.openEquipment(player, "spawn"); runTasks(); click(0);
        InventoryCloseEvent close = mock(InventoryCloseEvent.class); when(close.getInventory()).thenReturn(open.get()); menus.close(close);
        runTasks(); verify(arenas, never()).join(any(), any(), any());
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
