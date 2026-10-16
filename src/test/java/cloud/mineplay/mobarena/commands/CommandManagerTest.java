package cloud.mineplay.mobarena.commands;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.economy.EconomyService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandManagerTest {
    private MockedStatic<Bukkit> bukkit;
    private SimpleMobArena plugin;
    private CommandSender sender;
    private CommandManager commands;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        plugin = mock(SimpleMobArena.class);
        sender = mock(CommandSender.class);
        commands = new CommandManager(plugin);
    }

    @AfterEach void cleanup() { bukkit.close(); }

    @Test void deniedAdministrativeActionDoesNotTouchArenaManager() {
        assertTrue(commands.onCommand(sender, null, "ma", new String[]{"admin", "stop", "spawn"}));
        verify(plugin, never()).getArenaManager();
        verify(sender).sendMessage(any(Component.class));
    }

    @Test void legacyStopAliasRequiresTheSameExplicitPermission() {
        commands.onCommand(sender, null, "ma", new String[]{"stop", "spawn"});
        verify(sender).hasPermission("mobarena.admin.stop");
        verify(plugin, never()).getArenaManager();
    }

    @Test void permittedStopCancelsExactlyOneSelectedArena() {
        when(sender.hasPermission("mobarena.admin.stop")).thenReturn(true);
        ArenaManager manager = mock(ArenaManager.class);
        Arena arena = mock(Arena.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(manager.getArena("spawn")).thenReturn(arena);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "stop", "spawn"});
        verify(arena, times(1)).endArena();
        verify(arena, never()).startArena();
    }

    @Test void reconciliationWithoutDecisionNeverCallsPaymentService() {
        when(sender.hasPermission("mobarena.admin.reconcile")).thenReturn(true);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "reconcile", UUID.randomUUID().toString()});
        verify(plugin, never()).getEconomyService();
    }

    @Test void reconcileRejectsRetryDecision() {
        when(sender.hasPermission("mobarena.admin.reconcile")).thenReturn(true);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "reconcile", UUID.randomUUID().toString(), "RETRY"});
        verify(plugin, never()).getEconomyService();
    }

    @Test void explicitReconciliationForwardsRecordedDecisionAndActor() {
        when(sender.hasPermission("mobarena.admin.reconcile")).thenReturn(true);
        when(sender.getName()).thenReturn("operator");
        EconomyService economy = mock(EconomyService.class);
        when(plugin.getEconomyService()).thenReturn(economy);
        UUID settlement = UUID.randomUUID();
        when(economy.reconcile(settlement, "CONFIRM", "operator")).thenReturn(new CompletableFuture<>());
        commands.onCommand(sender, null, "ma", new String[]{"admin", "reconcile", settlement.toString(), "confirm"});
        verify(economy).reconcile(settlement, "CONFIRM", "operator");
    }

    @Test void anotherPlayersHistoryRequiresAdditionalPermission() {
        when(sender.hasPermission("mobarena.history")).thenReturn(true);
        commands.onCommand(sender, null, "ma", new String[]{"history", UUID.randomUUID().toString()});
        verify(sender).hasPermission("mobarena.stats.others");
        verify(plugin, never()).getEconomyService();
    }

    @Test void administrativeCompletionOnlyOffersAuthorizedActions() {
        when(sender.hasPermission("mobarena.admin.status")).thenReturn(true);
        assertEquals(java.util.List.of("status"), commands.onTabComplete(sender, null, "ma", new String[]{"admin", ""}));
    }
}
