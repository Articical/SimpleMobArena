package cloud.mineplay.mobarena.equipment;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EquipmentUnlockTest {
    @TempDir Path directory;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<PlayerSnapshot> snapshots;
    private Player player;
    private JavaPlugin plugin;
    private UUID playerId;
    private EquipmentService equipment;
    private SnapshotStore store;
    private ExecutorService worker;
    private CompletableFuture<Long> kills;
    private Queue<Runnable> mainTasks;
    private Runnable ready;
    private Consumer<String> failed;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        mainTasks = new ArrayDeque<>();
        when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(call -> {
            mainTasks.add(call.getArgument(1));
            return null;
        });
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        player = mock(Player.class);
        playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getOpenInventory()).thenReturn(mock(InventoryView.class));
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
        snapshots = mockStatic(PlayerSnapshot.class);
        snapshots.when(() -> PlayerSnapshot.empty(null)).thenReturn(true);
        PlayerSnapshot original = mock(PlayerSnapshot.class);
        when(original.encode()).thenReturn(new byte[] { 1, 2, 3 });
        snapshots.when(() -> PlayerSnapshot.capture(player, false)).thenReturn(original);
        store = mock(SnapshotStore.class);
        worker = mock(ExecutorService.class);
        equipment = new EquipmentService(plugin, store, worker,
                Map.of("archer", kit("archer", 100), "knight", kit("knight", 0)), "BOTH");
        kills = new CompletableFuture<>();
        equipment.setLifetimeKillsLookup(id -> kills);
        ready = mock(Runnable.class);
        failed = mock(Consumer.class);
    }

    @AfterEach void cleanup() {
        snapshots.close();
        bukkit.close();
    }

    private static ClassKit kit(String id, long requiredKills) {
        return new ClassKit(new ClassChoice(id, id.equals("archer") ? "Archer" : "Knight", Material.IRON_SWORD, requiredKills),
                new ItemStack[36], new ItemStack[4], null, List.of());
    }

    private void completeLookup(long value) {
        kills.complete(value);
        drainMain();
    }

    private void drainMain() {
        while (!mainTasks.isEmpty()) mainTasks.remove().run();
    }

    @ParameterizedTest @ValueSource(strings = { "archer", "ARCHER", "CLASS:archer", " cLaSs: ArChEr " })
    void lockedClassesCannotBeEquippedThroughAliases(String selection) {
        equipment.prepare(player, selection, ready, failed);
        assertTrue(equipment.isLocked(playerId));
        assertFalse(equipment.hasSnapshot(playerId));
        snapshots.verify(() -> PlayerSnapshot.capture(any(), anyBoolean()), never());
        completeLookup(99);
        verify(failed).accept("Archer unlocks at 100 arena kills. You have 99.");
        verifyNoInteractions(store, worker, ready);
        snapshots.verify(() -> PlayerSnapshot.capture(any(), anyBoolean()), never());
        verify(player, never()).closeInventory();
        assertFalse(equipment.isLocked(playerId));
    }

    @Test void reachingThresholdQueuesSnapshotStorageOnlyAfterLookupAndMainThreadHandoff() {
        equipment.prepare(player, "archer", ready, failed);
        verifyNoInteractions(worker, store);
        kills.complete(100L);
        snapshots.verify(() -> PlayerSnapshot.capture(any(), anyBoolean()), never());
        drainMain();
        snapshots.verify(() -> PlayerSnapshot.capture(player, false));
        verify(worker).execute(any(Runnable.class));
        verifyNoInteractions(store, ready, failed);
        assertTrue(equipment.hasSnapshot(playerId));
    }

    @ParameterizedTest @ValueSource(strings = { "knight", "PERSONAL", "CLASS:PERSONAL" })
    @SuppressWarnings("unchecked") void freeClassesAndPersonalEquipmentDoNotDependOnTheKillDatabase(String choice) {
        Function<UUID, CompletableFuture<Long>> unavailable = mock(Function.class);
        equipment.setLifetimeKillsLookup(unavailable);
        equipment.prepare(player, choice, ready, failed);
        verifyNoInteractions(unavailable, failed);
        verify(worker).execute(any(Runnable.class));
    }

    @Test void unavailableKillHistoryLeavesTheInventoryUntouched() {
        equipment.prepare(player, "archer", ready, failed);
        kills.completeExceptionally(new IllegalStateException("Database unavailable"));
        drainMain();
        verify(failed).accept(contains("Couldn't check your arena kills"));
        verifyNoInteractions(store, worker, ready);
        snapshots.verify(() -> PlayerSnapshot.capture(any(), anyBoolean()), never());
        assertFalse(equipment.isLocked(playerId));
    }

    @Test void cancellingAndRejoiningCannotLetTheOldLookupEquipOrReleaseTheNewRequest() {
        CompletableFuture<Long> second = new CompletableFuture<>();
        Queue<CompletableFuture<Long>> lookups = new ArrayDeque<>(List.of(kills, second));
        equipment.setLifetimeKillsLookup(id -> lookups.remove());
        equipment.prepare(player, "archer", ready, failed);
        Runnable restored = mock(Runnable.class);
        equipment.restore(player, null, restored);
        verify(restored).run();
        assertFalse(equipment.isLocked(playerId));
        equipment.prepare(player, "archer", ready, failed);
        completeLookup(100);
        assertTrue(equipment.isLocked(playerId));
        verifyNoInteractions(failed, worker, store, ready);
        second.complete(99L);
        drainMain();
        verify(failed).accept(contains("You have 99"));
        snapshots.verify(() -> PlayerSnapshot.capture(any(), anyBoolean()), never());
    }

    @Test void reconnectingCannotEquipThePreviousPlayerInstance() {
        equipment.prepare(player, "archer", ready, failed);
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(mock(Player.class));
        completeLookup(100);
        verifyNoInteractions(store, worker, ready);
        snapshots.verify(() -> PlayerSnapshot.capture(any(), anyBoolean()), never());
        assertFalse(equipment.isLocked(playerId));
    }

    @Test void quittingCancelsTheLookupWithoutRunningItsCallbacks() {
        equipment.prepare(player, "archer", ready, failed);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        equipment.quit(quit);
        completeLookup(100);
        verifyNoInteractions(store, worker, ready, failed);
        assertFalse(equipment.isLocked(playerId));
    }

    @Test void classReloadCancelsAnOutstandingUnlockCheck() throws Exception {
        equipment.prepare(player, "archer", ready, failed);
        Files.writeString(directory.resolve("classes.yml"), "classes:\n  knight:\n    required-kills: 0\n");
        equipment.reloadClasses();
        verify(failed).accept("Classes have changed. Please choose your class again.");
        completeLookup(100);
        verifyNoMoreInteractions(failed);
        verifyNoInteractions(store, worker, ready);
        assertFalse(equipment.isLocked(playerId));
    }

    @Test void overlappingRequestsDoNotReplaceThePendingCheck() {
        equipment.prepare(player, "archer", ready, failed);
        Consumer<String> secondFailure = mock(Consumer.class);
        equipment.prepare(player, "knight", ready, secondFailure);
        verify(secondFailure).accept(anyString());
        verifyNoInteractions(store, worker, ready);
        completeLookup(99);
        verify(failed).accept(contains("You have 99"));
    }

    @ParameterizedTest @ValueSource(strings = { "/ma leave", "/mobarena leave", "/simplemobarena:ma leave", "/simplemobarena:mobarena leave" })
    void playersCanCancelAPendingKillLookupWithThePluginsLeaveCommand(String commandText) {
        equipment.prepare(player, "archer", ready, failed);
        PluginCommand command = mock(PluginCommand.class);
        when(command.getPlugin()).thenReturn(plugin);
        String commandName = commandText.substring(1, commandText.indexOf(' '));
        bukkit.when(() -> Bukkit.getPluginCommand(commandName)).thenReturn(command);
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(commandText);
        equipment.command(event);
        verify(event, never()).setCancelled(true);
    }

    @ParameterizedTest @ValueSource(strings = { "/home", "/ma info", "/other:ma leave", "/ma leave extra" })
    void unrelatedCommandsRemainBlockedDuringAnUnlockLookup(String commandText) {
        equipment.prepare(player, "archer", ready, failed);
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(commandText);
        equipment.command(event);
        verify(event).setCancelled(true);
    }

    @Test void leaveCommandsRemainBlockedWhileAnInventorySnapshotIsBeingWritten() {
        equipment.prepare(player, "archer", ready, failed);
        completeLookup(100);
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn("/ma leave");
        equipment.command(event);
        verify(event).setCancelled(true);
    }

    @Test void metadataUsesTheSameNormalizedChoiceAsJoining() {
        assertEquals(List.of("knight", "archer"), equipment.classChoices().stream().map(ClassChoice::id).toList());
        assertEquals(100, equipment.choice(" CLASS:ARCHER ").requiredKills());
        assertNull(equipment.choice("PERSONAL"));
    }
}
