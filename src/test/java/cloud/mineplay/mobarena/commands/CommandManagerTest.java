package cloud.mineplay.mobarena.commands;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.economy.EconomyService;
import cloud.mineplay.mobarena.gui.ArenaMenu;
import cloud.mineplay.mobarena.integration.CitizensArenaNpc;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandManagerTest {
    private MockedStatic<Bukkit> bukkit;
    private SimpleMobArena plugin;
    private CommandSender sender;
    private CommandManager commands;
    private ArenaMenu menu;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        plugin = mock(SimpleMobArena.class);
        sender = mock(CommandSender.class);
        menu = mock(ArenaMenu.class);
        commands = new CommandManager(plugin, menu);
    }

    @AfterEach void cleanup() { bukkit.close(); }

    @Test void playerReadCommandsOpenTheirMenusWithoutSendingChatReports() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn(".BedrockPlayer");
        when(player.hasPermission(anyString())).thenReturn(true);
        ArenaManager manager = mock(ArenaManager.class);
        Arena arena = mock(Arena.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(manager.getArena("castle")).thenReturn(arena);
        when(arena.getId()).thenReturn("castle");

        commands.onCommand(player, null, "ma", new String[]{});
        commands.onCommand(player, null, "ma", new String[]{"list"});
        commands.onCommand(player, null, "ma", new String[]{"info", "castle"});
        commands.onCommand(player, null, "ma", new String[]{"classes"});
        commands.onCommand(player, null, "ma", new String[]{"stats"});
        commands.onCommand(player, null, "ma", new String[]{"history", "3"});
        commands.onCommand(player, null, "ma", new String[]{"match", sessionId.toString()});
        commands.onCommand(player, null, "ma", new String[]{"top"});

        verify(menu, times(2)).open(player, 0);
        verify(menu).openInfo(player, "castle");
        verify(menu).openClasses(player);
        verify(menu).openStats(player, playerId, ".BedrockPlayer");
        verify(menu).openHistory(player, playerId, ".BedrockPlayer", 3);
        verify(menu).openMatch(player, sessionId);
        verify(menu).openLeaderboard(player);
        verifyNoMoreInteractions(menu);
        verify(player, never()).sendMessage(any(Component.class));
        verify(plugin, never()).getEconomyService();
        bukkit.verify(() -> Bukkit.getOfflinePlayer(any(UUID.class)), never());
    }

    @Test void menuCommandsStillRequireTheirExistingPermissions() {
        Player player = mock(Player.class);
        for (String[] args : List.of(new String[]{}, new String[]{"list"}, new String[]{"info", "castle"},
                new String[]{"classes"}, new String[]{"stats"}, new String[]{"history"},
                new String[]{"match", UUID.randomUUID().toString()}, new String[]{"top"}, new String[]{"join", "castle"})) {
            commands.onCommand(player, null, "ma", args);
        }
        verifyNoInteractions(menu);
        verify(plugin, never()).getEconomyService();
        verify(plugin, never()).getArenaManager();
    }

    @Test void cachedPlayerReportsNeedOthersPermissionBeforeOpeningMenus() {
        Player player = mock(Player.class);
        UUID ownId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(ownId);
        when(player.hasPermission("mobarena.stats")).thenReturn(true);
        when(player.hasPermission("mobarena.history")).thenReturn(true);
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn(".BedrockFriend");
        bukkit.when(() -> Bukkit.getOfflinePlayerIfCached(".BedrockFriend")).thenReturn(target);

        commands.onCommand(player, null, "ma", new String[]{"stats", ".BedrockFriend"});
        commands.onCommand(player, null, "ma", new String[]{"history", ".BedrockFriend", "2"});
        verifyNoInteractions(menu);

        when(player.hasPermission("mobarena.stats.others")).thenReturn(true);
        commands.onCommand(player, null, "ma", new String[]{"stats", ".BedrockFriend"});
        commands.onCommand(player, null, "ma", new String[]{"history", ".BedrockFriend", "2"});
        verify(menu).openStats(player, targetId, ".BedrockFriend");
        verify(menu).openHistory(player, targetId, ".BedrockFriend", 2);
        verify(plugin, never()).getEconomyService();
        bukkit.verify(() -> Bukkit.getOfflinePlayer(any(UUID.class)), never());
    }

    @Test void uuidReportsUseOnlineNamesOrUuidFallbackWithoutLoadingOfflinePlayerData() {
        Player viewer = mock(Player.class), online = mock(Player.class);
        UUID onlineId = UUID.randomUUID(), offlineId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.hasPermission(anyString())).thenReturn(true);
        when(online.getName()).thenReturn(".OnlineFriend");
        bukkit.when(() -> Bukkit.getPlayer(onlineId)).thenReturn(online);

        commands.onCommand(viewer, null, "ma", new String[]{"stats", onlineId.toString()});
        commands.onCommand(viewer, null, "ma", new String[]{"history", onlineId.toString()});
        commands.onCommand(viewer, null, "ma", new String[]{"stats", offlineId.toString()});
        commands.onCommand(viewer, null, "ma", new String[]{"history", offlineId.toString()});

        verify(menu).openStats(viewer, onlineId, ".OnlineFriend");
        verify(menu).openHistory(viewer, onlineId, ".OnlineFriend", 1);
        verify(menu).openStats(viewer, offlineId, offlineId.toString());
        verify(menu).openHistory(viewer, offlineId, offlineId.toString(), 1);
        bukkit.verify(() -> Bukkit.getOfflinePlayer(any(UUID.class)), never());
        verify(plugin, never()).getEconomyService();
    }

    @Test void ownUuidDoesNotRequireOthersPermissionAndBadTargetsCannotOpenMenus() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Alex");
        when(player.hasPermission("mobarena.stats")).thenReturn(true);
        when(player.hasPermission("mobarena.history")).thenReturn(true);
        when(player.hasPermission("mobarena.match")).thenReturn(true);

        commands.onCommand(player, null, "ma", new String[]{"stats", id.toString()});
        commands.onCommand(player, null, "ma", new String[]{"history", id.toString(), "2"});
        verify(player, never()).hasPermission("mobarena.stats.others");
        verify(menu).openStats(player, id, "Alex");
        verify(menu).openHistory(player, id, "Alex", 2);
        clearInvocations(menu);

        commands.onCommand(player, null, "ma", new String[]{"stats", "UnknownPlayer"});
        commands.onCommand(player, null, "ma", new String[]{"history", "0"});
        commands.onCommand(player, null, "ma", new String[]{"match", "not-a-uuid"});
        verifyNoInteractions(menu);
        verify(plugin, never()).getEconomyService();
    }

    @Test void joinWithoutAnEquipmentChoiceOpensSelectionAndExplicitChoiceStillJoins() {
        Player player = mock(Player.class);
        when(player.hasPermission("mobarena.join")).thenReturn(true);
        ArenaManager manager = mock(ArenaManager.class);
        Arena arena = mock(Arena.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(manager.getArena("castle")).thenReturn(arena);
        when(arena.getId()).thenReturn("castle");

        commands.onCommand(player, null, "ma", new String[]{"join", "castle"});
        verify(menu).openEquipment(player, "castle");
        verify(manager, never()).join(any(), anyString(), anyString());

        commands.onCommand(player, null, "ma", new String[]{"join", "castle", "personal"});
        commands.onCommand(player, null, "ma", new String[]{"join", "castle", "knight"});
        verify(manager).join(player, "castle", "personal");
        verify(manager).join(player, "castle", "knight");
        verifyNoMoreInteractions(menu);
    }

    @Test void consoleKeepsTextAndAsynchronousReportsWithoutOpeningInventories() {
        when(sender.hasPermission(anyString())).thenReturn(true);
        ArenaManager manager = mock(ArenaManager.class);
        EconomyService economy = mock(EconomyService.class);
        Arena arena = mock(Arena.class);
        UUID target = UUID.randomUUID(), session = UUID.randomUUID();
        when(plugin.getArenaManager()).thenReturn(manager);
        when(plugin.getEconomyService()).thenReturn(economy);
        when(manager.getArenas()).thenReturn(List.of(arena));
        when(manager.getArena("castle")).thenReturn(arena);
        when(arena.getId()).thenReturn("castle");
        when(arena.getName()).thenReturn("Castle Arena");
        when(arena.getPrizePot()).thenReturn(java.math.BigDecimal.ZERO);
        when(economy.stats(target)).thenReturn(new CompletableFuture<>());
        when(economy.history(target, 2)).thenReturn(new CompletableFuture<>());
        when(economy.match(session)).thenReturn(new CompletableFuture<>());
        when(economy.top(10)).thenReturn(new CompletableFuture<>());

        commands.onCommand(sender, null, "ma", new String[]{"list"});
        commands.onCommand(sender, null, "ma", new String[]{"info", "castle"});
        commands.onCommand(sender, null, "ma", new String[]{"stats", target.toString()});
        commands.onCommand(sender, null, "ma", new String[]{"history", target.toString(), "2"});
        commands.onCommand(sender, null, "ma", new String[]{"match", session.toString()});
        commands.onCommand(sender, null, "ma", new String[]{"top"});

        verify(sender, atLeast(3)).sendMessage(any(Component.class));
        verify(economy).stats(target);
        verify(economy).history(target, 2);
        verify(economy).match(session);
        verify(economy).top(10);
        verifyNoInteractions(menu);
    }

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

    @Test void websiteAdministrationRequiresPermissionBeforeAccessingTheLedger() {
        commands.onCommand(sender, null, "ma", new String[]{"admin", "website", "backfill"});
        verify(sender).hasPermission("mobarena.admin.website");
        verify(plugin, never()).getEconomyService();
        assertEquals(List.of(), commands.onTabComplete(sender, null, "ma", new String[]{"admin", "website", ""}));
    }

    @Test void websiteBackfillAndEventRetryDispatchAsynchronousOperationsOnly() {
        when(sender.hasPermission("mobarena.admin.website")).thenReturn(true);
        EconomyService economy = mock(EconomyService.class);
        when(plugin.getEconomyService()).thenReturn(economy);
        when(economy.websiteBackfill()).thenReturn(new CompletableFuture<>());
        UUID event = UUID.randomUUID();
        when(economy.websiteRetry(event)).thenReturn(new CompletableFuture<>());
        commands.onCommand(sender, null, "ma", new String[]{"admin", "website", "backfill"});
        commands.onCommand(sender, null, "ma", new String[]{"admin", "website", "retry", event.toString()});
        verify(economy).websiteBackfill();
        verify(economy).websiteRetry(event);
        verify(economy, never()).reconcile(any(), anyString(), anyString());
        assertEquals(List.of("backfill", "retry", "status"),
                commands.onTabComplete(sender, null, "ma", new String[]{"admin", "website", ""}));
    }

    @Test void replaceAndAddSpawnsDispatchToDistinctManagerOperations() {
        ArenaManager manager = mock(ArenaManager.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location spot = new Location(world, 2.5, 181, 3.5);
        when(player.getLocation()).thenReturn(spot);

        commands.onCommand(player, null, "ma", new String[]{"admin", "setplayer", "spawn"});
        commands.onCommand(player, null, "ma", new String[]{"admin", "setmob", "spawn"});
        commands.onCommand(player, null, "ma", new String[]{"admin", "addplayer", "spawn"});
        commands.onCommand(player, null, "ma", new String[]{"admin", "addmob", "spawn"});

        verify(manager).setLocation("spawn", "player-spawns", spot);
        verify(manager).setLocation("spawn", "mob-spawns", spot);
        verify(manager).addSpawn("spawn", "player-spawns", spot);
        verify(manager).addSpawn("spawn", "mob-spawns", spot);
        verifyNoMoreInteractions(manager);
    }

    @Test void newSpawnActionsRequireTheirOwnPermissionsBeforeMutation() {
        for (String action : List.of("addplayer", "addmob", "clearspawns", "setup")) {
            commands.onCommand(sender, null, "ma", new String[]{"admin", action, "spawn", "all"});
            verify(sender).hasPermission("mobarena.admin." + action);
        }
        verify(plugin, never()).getArenaManager();
    }

    @Test void clearingSpawnsChecksChoiceAndExactArgumentCount() {
        ArenaManager manager = mock(ArenaManager.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(sender.hasPermission("mobarena.admin.clearspawns")).thenReturn(true);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "clearspawns", "spawn"});
        commands.onCommand(sender, null, "ma", new String[]{"admin", "clearspawns", "spawn", "wrong"});
        commands.onCommand(sender, null, "ma", new String[]{"admin", "clearspawns", "spawn", "all", "extra"});
        verifyNoInteractions(manager);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "clearspawns", "spawn", "ALL"});
        verify(manager).clearSpawns("spawn", "all");
    }

    @Test void consoleCanResetOutsidersToExitWithoutAPlayerLocation() {
        ArenaManager manager = mock(ArenaManager.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(sender.hasPermission("mobarena.admin.setoutsider")).thenReturn(true);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "setoutsider", "spawn", "EXIT"});
        verify(manager).useExitForOutsiders("spawn");
        verify(manager, never()).setLocation(anyString(), anyString(), any());
    }

    @Test void unknownOutsiderModeCannotOverwriteSavedLocation() {
        when(sender.hasPermission("mobarena.admin.setoutsider")).thenReturn(true);
        commands.onCommand(sender, null, "ma", new String[]{"admin", "setoutsider", "spawn", "wrong"});
        verify(plugin, never()).getArenaManager();
        verify(sender).sendMessage(ArenaMessages.error("Usage: /ma admin setoutsider <arena> [exit]"));
    }

    @Test void setupShowsHeightBoundsExistingSpawnsAndActionableRepairs() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", "world");
        config.set("pos1", "world,10,180,10");
        config.set("pos2", "world,30,190,30");
        config.set("player-spawns", List.of("world,0.5,181,0.5"));
        config.set("mob-spawns", List.of("world,10.5,181,10.5", "world,-10.5,181,-10.5"));
        config.set("outsider-exit", "world,15,181,15");
        ArenaDefinition definition = new ArenaDefinition("spawn", config, new YamlConfiguration());
        Arena arena = mock(Arena.class);
        when(arena.getId()).thenReturn("spawn");
        when(arena.definition()).thenReturn(definition);
        when(arena.getValidationErrors()).thenReturn(definition.errors);
        ArenaManager manager = mock(ArenaManager.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(manager.getArena("spawn")).thenReturn(arena);
        when(sender.hasPermission("mobarena.admin.setup")).thenReturn(true);

        commands.onCommand(sender, null, "ma", new String[]{"admin", "setup", "spawn"});

        ArgumentCaptor<Component> output = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(output.capture());
        List<String> lines = output.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        assertTrue(lines.stream().anyMatch(line -> line.contains("Y 180..190")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Mob spawns: 2")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Player #1: world 0.5, 181, 0.5")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Player spawn #1") && line.contains("/ma admin setplayer spawn to replace this spawn list")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Mob spawn #2") && line.contains("/ma admin setmob spawn to replace this spawn list")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("/ma admin setoutsider spawn exit")));
        assertEquals(definition.errors.size(), lines.stream().filter(line -> line.contains("Needs fix:")).count());
        assertEquals(1, lines.stream().filter(line -> line.contains("/ma admin setplayer spawn")).count());
        assertEquals(1, lines.stream().filter(line -> line.contains("/ma admin setoutsider spawn exit")).count());
        assertTrue(lines.stream().noneMatch(line -> line.contains("Stand safely INSIDE")), "Actionable validation errors should not get duplicate repair hints");
        verify(sender).sendMessage(ArenaMessages.warning("Check again with /ma admin setup spawn. Enable only when it says Ready."));
    }

    @Test void createConfirmsTheChangeAndKeepsTheSetupInstruction() {
        ArenaManager manager = mock(ArenaManager.class);
        when(plugin.getArenaManager()).thenReturn(manager);
        when(sender.hasPermission("mobarena.admin.create")).thenReturn(true);

        commands.onCommand(sender, null, "ma", new String[]{"admin", "create", "castle"});

        verify(manager).create("castle");
        verify(sender).sendMessage(ArenaMessages.success("Created castle. The arena is disabled until setup is complete."));
        verify(sender).sendMessage(ArenaMessages.info("Set both boundary corners, then use /ma admin setup castle."));
    }

    @Test void invalidJoinKeepsTheFullUsageAsAnError() {
        Player player = mock(Player.class);
        when(player.hasPermission("mobarena.join")).thenReturn(true);

        commands.onCommand(player, null, "ma", new String[]{"join"});

        verify(plugin, never()).getArenaManager();
        verify(player).sendMessage(ArenaMessages.error("Usage: /ma join <arena> [personal|class]"));
    }

    @Test void setupArgumentCompletionOffersOnlySupportedModesWithPermission() {
        assertTrue(commands.onTabComplete(sender, null, "ma", new String[]{"admin", "clearspawns", "spawn", ""}).isEmpty());
        when(sender.hasPermission("mobarena.admin.clearspawns")).thenReturn(true);
        when(sender.hasPermission("mobarena.admin.setoutsider")).thenReturn(true);
        assertEquals(List.of("all", "mob", "player"), commands.onTabComplete(sender, null, "ma", new String[]{"admin", "clearspawns", "spawn", ""}));
        assertEquals(List.of("exit"), commands.onTabComplete(sender, null, "ma", new String[]{"admin", "setoutsider", "spawn", "e"}));
    }

    @Test void npcOperationsRequirePermissionBeforeAccessingTheIntegration() {
        for (String operation : List.of("create", "bind", "unbind", "list"))
            commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", operation, "7"});

        verify(sender, times(4)).hasPermission("mobarena.admin.npc");
        verify(plugin, never()).getCitizensArenaNpc();
        verify(sender, times(4)).sendMessage(ArenaMessages.error("Missing permission: mobarena.admin.npc"));
    }

    @Test void permittedPlayerCanCreateDefaultAndMultiwordNamedNpcs() {
        CitizensArenaNpc integration = mock(CitizensArenaNpc.class);
        when(plugin.getCitizensArenaNpc()).thenReturn(integration);
        Player player = mock(Player.class);
        when(player.hasPermission("mobarena.admin.npc")).thenReturn(true);
        when(integration.create(player, "MobArena")).thenReturn("Created MobArena NPC 1.");
        when(integration.create(player, "Arena Guide")).thenReturn("Created Arena Guide NPC 2.");

        commands.onCommand(player, null, "ma", new String[]{"admin", "npc", "create"});
        commands.onCommand(player, null, "ma", new String[]{"admin", "npc", "CREATE", "Arena", "Guide"});

        verify(integration).create(player, "MobArena");
        verify(integration).create(player, "Arena Guide");
        verifyNoMoreInteractions(integration);
        verify(player).sendMessage(ArenaMessages.success("Created MobArena NPC 1."));
        verify(player).sendMessage(ArenaMessages.success("Created Arena Guide NPC 2."));
    }

    @Test void npcCreationRequiresAnInGamePlayerEvenWithPermission() {
        CitizensArenaNpc integration = mock(CitizensArenaNpc.class);
        when(plugin.getCitizensArenaNpc()).thenReturn(integration);
        when(sender.hasPermission("mobarena.admin.npc")).thenReturn(true);

        commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", "create", "Arena", "Guide"});

        verifyNoInteractions(integration);
        verify(sender).sendMessage(ArenaMessages.error("This action requires an in-game player."));
    }

    @Test void invalidNpcIdsAndArgumentCountsCannotMutateBindings() {
        CitizensArenaNpc integration = mock(CitizensArenaNpc.class);
        when(plugin.getCitizensArenaNpc()).thenReturn(integration);
        when(sender.hasPermission("mobarena.admin.npc")).thenReturn(true);
        List<String[]> invalid = List.of(
            new String[]{"admin", "npc"},
            new String[]{"admin", "npc", "bind"},
            new String[]{"admin", "npc", "bind", "3", "extra"},
            new String[]{"admin", "npc", "unbind"},
            new String[]{"admin", "npc", "unbind", "3", "extra"},
            new String[]{"admin", "npc", "list", "extra"},
            new String[]{"admin", "npc", "delete", "3"});
        for (String[] args : invalid) commands.onCommand(sender, null, "ma", args);
        for (String id : List.of("-1", "not-a-number", "2147483648", "1.5"))
            for (String operation : List.of("bind", "unbind"))
                commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", operation, id});

        verifyNoInteractions(integration);
        verify(sender, times(8)).sendMessage(ArenaMessages.error("Enter a non-negative Citizens NPC id."));
    }

    @Test void consoleCanBindZeroUnbindAndListNpcBindings() {
        CitizensArenaNpc integration = mock(CitizensArenaNpc.class);
        when(plugin.getCitizensArenaNpc()).thenReturn(integration);
        when(sender.hasPermission("mobarena.admin.npc")).thenReturn(true);
        when(integration.bind(0)).thenReturn("NPC 0 linked.");
        when(integration.unbind(17)).thenReturn("NPC 17 unlinked.");
        when(integration.bindings()).thenReturn(List.of("NPC 0: Arena Guide", "NPC 42: Dungeon Guide"));

        commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", "BIND", "0"});
        commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", "unbind", "17"});
        commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", "list"});

        verify(integration).bind(0);
        verify(integration).unbind(17);
        verify(integration).bindings();
        verifyNoMoreInteractions(integration);
        verify(sender).sendMessage(ArenaMessages.success("NPC 0 linked."));
        verify(sender).sendMessage(ArenaMessages.success("NPC 17 unlinked."));
        verify(sender).sendMessage(ArenaMessages.info("NPC 0: Arena Guide"));
        verify(sender).sendMessage(ArenaMessages.info("NPC 42: Dungeon Guide"));
    }

    @Test void emptyNpcBindingListExplainsHowToCreateOrBindOne() {
        CitizensArenaNpc integration = mock(CitizensArenaNpc.class);
        when(plugin.getCitizensArenaNpc()).thenReturn(integration);
        when(sender.hasPermission("mobarena.admin.npc")).thenReturn(true);
        when(integration.bindings()).thenReturn(List.of());

        commands.onCommand(sender, null, "ma", new String[]{"admin", "npc", "list"});

        verify(integration).bindings();
        verifyNoMoreInteractions(integration);
        verify(sender).sendMessage(ArenaMessages.info("No arena menu NPCs are linked. Use /ma admin npc create or /ma admin npc bind <id>."));
    }

    @Test void npcCompletionOffersOnlyAuthorizedOperationsWithoutAccessingBindings() {
        assertTrue(commands.onTabComplete(sender, null, "ma", new String[]{"admin", "np"}).isEmpty());
        assertTrue(commands.onTabComplete(sender, null, "ma", new String[]{"admin", "npc", ""}).isEmpty());
        when(sender.hasPermission("mobarena.admin.npc")).thenReturn(true);

        assertEquals(List.of("admin"), commands.onTabComplete(sender, null, "ma", new String[]{"adm"}));
        assertEquals(List.of("npc"), commands.onTabComplete(sender, null, "ma", new String[]{"admin", "np"}));
        assertEquals(List.of("bind", "create", "list", "unbind"), commands.onTabComplete(sender, null, "ma", new String[]{"admin", "npc", ""}));
        assertEquals(List.of("unbind"), commands.onTabComplete(sender, null, "ma", new String[]{"admin", "npc", "UN"}));
        assertTrue(commands.onTabComplete(sender, null, "ma", new String[]{"admin", "npc", "bind", ""}).isEmpty());
        verify(plugin, never()).getCitizensArenaNpc();
        verify(plugin, never()).getArenaManager();
    }
}
