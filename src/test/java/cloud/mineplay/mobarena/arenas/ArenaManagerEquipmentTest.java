package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaManagerEquipmentTest {
    @TempDir Path directory;
    private MockedStatic<Bukkit> bukkit;
    private ArenaManager manager;
    private EquipmentService equipment;
    private Arena arena;
    private Player player;
    private UUID playerId;
    private AtomicReference<Runnable> equipmentReady;
    private AtomicReference<Consumer<String>> equipmentFailed;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
        SimpleMobArena plugin = mock(SimpleMobArena.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        equipment = mock(EquipmentService.class);
        when(plugin.getEquipmentService()).thenReturn(equipment);
        manager = spy(new ArenaManager(plugin));
        arena = mock(Arena.class);
        when(arena.getSessionId()).thenReturn(UUID.randomUUID());
        when(arena.definition()).thenReturn(mock(ArenaDefinition.class));
        doReturn(arena).when(manager).required("spawn");
        player = mock(Player.class);
        playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission("mobarena.join")).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
        equipmentReady = new AtomicReference<>();
        equipmentFailed = new AtomicReference<>();
        doAnswer(call -> { equipmentReady.set(call.getArgument(2)); equipmentFailed.set(call.getArgument(3)); return null; })
                .when(equipment).prepare(eq(player),eq("knight"),any(),any());
    }
    @AfterEach void cleanup() { bukkit.close(); }

    @Test void disconnectDuringSnapshotWriteRequestsRecoveryAndCannotJoinLater() {
        manager.join(player,"spawn","knight");
        assertSame(arena,manager.getPlayerArena(playerId));
        manager.disconnected(player);
        assertNull(manager.getPlayerArena(playerId));
        verify(arena).releaseReservation();
        verify(equipment).restore(eq(player),isNull(),any());
        equipmentReady.get().run();
        verify(arena,never()).joined(any());
        assertNull(manager.getPlayerArena(playerId));
    }

    @Test void administrativeCancellationDuringPreparationCannotResurrectMembership() {
        manager.join(player,"spawn","knight");
        manager.cancelReservations(arena);
        equipmentReady.get().run();
        verify(arena,never()).joined(any());
        assertNull(manager.getPlayerArena(playerId));
        verify(arena,times(1)).releaseReservation();
    }

    @Test void lateJoinRejectionHappensBeforeAnyInventorySnapshotOrMutation() {
        doThrow(new IllegalStateException("This session has already started.")).when(arena).reserve();
        assertThrows(IllegalStateException.class,() -> manager.join(player,"spawn","knight"));
        verify(equipment,never()).prepare(any(),anyString(),any(),any());
        verify(player,never()).closeInventory();
        assertNull(manager.getPlayerArena(playerId));
    }

    @Test void normalDisconnectAfterAdmissionUsesEliminationPath() {
        manager.join(player,"spawn","knight");
        equipmentReady.get().run();
        verify(arena).joined(player);
        manager.disconnected(player);
        verify(arena).eliminate(player,"left the match");
    }

    @Test void previousReadyCallbackCannotAdmitOrRestoreANewerReservation() {
        manager.join(player,"spawn","knight");
        Runnable previousReady = equipmentReady.get();
        manager.leave(player);
        manager.join(player,"spawn","knight");
        Runnable currentReady = equipmentReady.get();

        previousReady.run();
        assertSame(arena,manager.getPlayerArena(playerId));
        verify(arena,never()).joined(any());
        verify(arena,times(1)).releaseReservation();
        verify(equipment,times(1)).restore(eq(player),isNull(),any());

        currentReady.run();
        assertSame(arena,manager.getPlayerArena(playerId));
        verify(arena,times(1)).joined(player);
    }

    @Test void previousFailureCallbackCannotRemoveANewerReservation() {
        manager.join(player,"spawn","knight");
        Consumer<String> previousFailure = equipmentFailed.get();
        manager.leave(player);
        manager.join(player,"spawn","knight");

        previousFailure.accept("Old lookup failed");
        assertSame(arena,manager.getPlayerArena(playerId));
        verify(arena,times(1)).releaseReservation();
        equipmentReady.get().run();
        verify(arena,times(1)).joined(player);
        assertSame(arena,manager.getPlayerArena(playerId));
    }

    @Test void duplicateReadyAndFailureCallbacksCannotRemoveAnAdmittedPlayer() {
        manager.join(player,"spawn","knight");
        equipmentReady.get().run();
        equipmentReady.get().run();
        equipmentFailed.get().accept("Duplicate callback");

        verify(arena,times(1)).joined(player);
        verify(arena,never()).releaseReservation();
        verify(equipment,never()).restore(any(),any(),any());
        assertSame(arena,manager.getPlayerArena(playerId));
    }

    @Test void cancellingOfflinePreparationsAlsoCancelsTheirPendingKillLookup() {
        manager.join(player,"spawn","knight");
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(null);
        manager.cancelReservations(arena);
        equipmentReady.get().run();

        verify(equipment).cancelPreparation(playerId);
        verify(arena,times(1)).releaseReservation();
        verify(arena,never()).joined(any());
        assertNull(manager.getPlayerArena(playerId));
    }

    @Test void replacingThePlayerInstanceDuringPreparationCannotAdmitTheStaleInstance() {
        manager.join(player,"spawn","knight");
        Player reconnected = mock(Player.class);
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(reconnected);
        equipmentReady.get().run();
        verify(arena,never()).joined(any());
        assertNull(manager.getPlayerArena(playerId));
        verify(equipment).restore(eq(player),isNull(),any());
    }

    @Test void ownedTridentInFlightPreventsSnapshottingAnIncompletePersonalInventory() {
        World world = mock(World.class);
        Trident trident = mock(Trident.class);
        when(trident.getShooter()).thenReturn(player);
        when(world.getEntitiesByClasses(Trident.class,FishHook.class)).thenReturn(List.of(trident));
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
        assertThrows(IllegalStateException.class,() -> manager.join(player,"spawn","knight"));
        verify(arena,never()).reserve();
        verify(equipment,never()).prepare(any(),anyString(),any(),any());
    }
}
