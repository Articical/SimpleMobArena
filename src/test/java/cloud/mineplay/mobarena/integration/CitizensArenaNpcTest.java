package cloud.mineplay.mobarena.integration;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.arenas.ArenaManager;
import cloud.mineplay.mobarena.config.SpawnSafety;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CitizensArenaNpcTest {
    @TempDir Path folder;
    private SimpleMobArena plugin;
    private Server server;
    private PluginManager plugins;
    private ArenaManager manager;
    private EquipmentService equipment;
    private Player player;
    private Location location;
    private CitizensArenaNpc menus;
    private FakeAccess access;
    private EventExecutor listener;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Player> opened = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000);
    private MockedStatic<SpawnSafety> safety;

    @BeforeEach void setup() {
        safety = mockStatic(SpawnSafety.class);
        plugin = mock(SimpleMobArena.class); server = mock(Server.class); plugins = mock(PluginManager.class);
        manager = mock(ArenaManager.class); equipment = mock(EquipmentService.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getArenaManager()).thenReturn(manager); when(plugin.getEquipmentService()).thenReturn(equipment);
        when(plugin.isEnabled()).thenReturn(true); when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(server.isPrimaryThread()).thenReturn(true); when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.isPluginEnabled("Citizens")).thenReturn(true);
        doAnswer(call -> { listener = call.getArgument(3); return null; }).when(plugins)
            .registerEvent(any(), any(), any(), any(), eq(plugin), eq(true));
        BukkitScheduler scheduler = mock(BukkitScheduler.class); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            scheduled.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        location = new Location(mock(World.class), 20.5, 64, 20.5);
        player = mock(Player.class); UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId); when(player.getLocation()).thenReturn(location);
        when(player.isOnline()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayer(playerId)).thenReturn(player);
        access = new FakeAccess(); access.add(10);
        menus = service(folder.resolve("npc-menus.yml")); menus.start();
    }
    @AfterEach void cleanup() { menus.close(); safety.close(); }

    @Test void validClickIsDeferredDebouncedAndUsesTheExistingMenuConsumer() throws Exception {
        menus.bind(10);
        assertTrue(click(access.lookup(10)).isCancelled());
        assertTrue(click(access.lookup(10)).isCancelled());
        assertTrue(opened.isEmpty()); assertEquals(1, scheduled.size());
        scheduled.removeFirst().run(); assertEquals(List.of(player), opened);
        assertTrue(click(access.lookup(10)).isCancelled()); assertTrue(scheduled.isEmpty());
        clock.addAndGet(500_000_000); click(access.lookup(10));
        scheduled.removeFirst().run(); assertEquals(2, opened.size());
    }

    @Test void savedUuidSurvivesRestartAndNumericIdChangesButNeverTransfersToAReusedId() throws Exception {
        FakeNpc original = access.lookup(10); menus.bind(10); menus.close();
        access.npcs.remove(10); original.id = 12; access.npcs.put(12, original);
        FakeNpc reused = access.add(10);
        menus = service(folder.resolve("npc-menus.yml")); menus.start();
        click(reused); assertTrue(scheduled.isEmpty());
        click(original); scheduled.removeFirst().run(); assertEquals(1, opened.size());
        assertThrows(IllegalStateException.class, () -> menus.bind(10));
        assertTrue(Files.readString(folder.resolve("npc-menus.yml")).contains(original.uuid.toString()));
        menus.unbind(10); assertFalse(original.destroyed); assertFalse(reused.destroyed);
        assertDoesNotThrow(() -> menus.bind(10));
    }

    @Test void permissionMembershipRecoveryAndNpcPlayersCannotQueueMenus() throws Exception {
        menus.bind(10);
        when(player.hasPermission("mobarena.join")).thenReturn(false); click(access.lookup(10));
        when(player.hasPermission("mobarena.join")).thenReturn(true);
        when(manager.getPlayerArena(player.getUniqueId())).thenReturn(mock(Arena.class)); click(access.lookup(10));
        when(manager.getPlayerArena(player.getUniqueId())).thenReturn(null);
        when(equipment.isLocked(player.getUniqueId())).thenReturn(true); click(access.lookup(10));
        when(equipment.isLocked(player.getUniqueId())).thenReturn(false);
        when(equipment.hasSnapshot(player.getUniqueId())).thenReturn(true); click(access.lookup(10));
        when(equipment.hasSnapshot(player.getUniqueId())).thenReturn(false);
        access.fakePlayer = true; click(access.lookup(10));
        assertTrue(scheduled.isEmpty()); assertTrue(opened.isEmpty());
    }

    @Test void deferredTaskRechecksPermissionsRecoveryBindingAndOnlineIdentity() throws Exception {
        menus.bind(10); click(access.lookup(10));
        when(equipment.hasSnapshot(player.getUniqueId())).thenReturn(true);
        scheduled.removeFirst().run(); assertTrue(opened.isEmpty());
        when(equipment.hasSnapshot(player.getUniqueId())).thenReturn(false);
        clock.addAndGet(600_000_000); click(access.lookup(10));
        when(player.hasPermission("mobarena.use")).thenReturn(false);
        scheduled.removeFirst().run(); assertTrue(opened.isEmpty());
        when(player.hasPermission("mobarena.use")).thenReturn(true);
        clock.addAndGet(600_000_000); click(access.lookup(10)); menus.unbind(10);
        scheduled.removeFirst().run(); assertTrue(opened.isEmpty());
        menus.bind(10); clock.addAndGet(600_000_000); click(access.lookup(10));
        when(server.getPlayer(player.getUniqueId())).thenReturn(mock(Player.class));
        scheduled.removeFirst().run(); assertTrue(opened.isEmpty());
    }

    @Test void linkedClicksAreConsumedWithoutTouchingUnboundOrAlreadyCancelledEvents() throws Exception {
        verify(plugins).registerEvent(eq(TestClick.class), any(), eq(EventPriority.HIGHEST), any(), eq(plugin), eq(true));
        TestClick unbound = click(access.lookup(10));
        assertFalse(unbound.isCancelled()); assertTrue(scheduled.isEmpty());
        menus.bind(10);
        TestClick cancelled = new TestClick(access.lookup(10), player); cancelled.setCancelled(true);
        listener.execute(null, cancelled); assertTrue(cancelled.isCancelled()); assertTrue(scheduled.isEmpty());
        when(player.hasPermission("mobarena.join")).thenReturn(false);
        assertTrue(click(access.lookup(10)).isCancelled()); assertTrue(scheduled.isEmpty());
        when(player.hasPermission("mobarena.join")).thenReturn(true);
        TestClick handled = click(access.lookup(10)); assertTrue(handled.isCancelled());
        scheduled.removeFirst().run(); assertEquals(List.of(player), opened);
    }

    @Test void createSpawnsProtectedNpcAndPersistsCitizensAndTheUuidLink() throws Exception {
        String response = menus.create(player, "Arena Guide");
        FakeNpc created = access.lookup(11);
        assertTrue(created.protectedNpc); assertTrue(created.spawned);
        assertEquals(location, created.location); assertEquals(1, access.saves);
        assertTrue(response.contains("11"));
        String saved = Files.readString(folder.resolve("npc-menus.yml"));
        assertTrue(saved.contains(created.uuid.toString())); assertTrue(saved.contains("id: 11"));
    }

    @Test void unsafeOrInsideArenaPlacementCannotCreateOrBindAnNpc() {
        safety.when(() -> SpawnSafety.problem(any())).thenReturn("no floor");
        assertThrows(IllegalArgumentException.class, () -> menus.create(player, "Guide"));
        assertEquals(1, access.npcs.size());
        safety.when(() -> SpawnSafety.problem(any())).thenReturn(null);
        when(manager.at(any())).thenReturn(mock(Arena.class));
        assertThrows(IllegalArgumentException.class, () -> menus.create(player, "Guide"));
        assertThrows(IllegalArgumentException.class, () -> menus.bind(10));
        assertEquals(1, access.npcs.size());
    }

    @Test void spawnFailureDestroysOnlyTheNewNpcAndDoesNotSaveALink() {
        access.spawnSucceeds = false;
        assertThrows(IllegalStateException.class, () -> menus.create(player, "Guide"));
        assertTrue(access.lastCreated.destroyed); assertFalse(access.lookup(10).destroyed);
        assertFalse(Files.exists(folder.resolve("npc-menus.yml")));
    }

    @Test void bindingSaveFailureRollsBackNewNpcAndPreservesExistingLinks() throws Exception {
        menus.bind(10);
        // A nonempty directory at the atomic destination forces replacement to fail.
        menus.close(); Path target = folder.resolve("blocked.yml");
        menus = service(target); menus.start(); Files.createDirectory(target); Files.writeString(target.resolve("keep"), "keep");
        assertThrows(IllegalStateException.class, () -> menus.create(player, "Guide"));
        assertTrue(access.lastCreated.destroyed); assertFalse(access.lookup(10).destroyed);
        assertTrue(Files.readString(folder.resolve("npc-menus.yml")).contains(access.lookup(10).uuid.toString()));
        assertEquals("keep", Files.readString(target.resolve("keep")));
    }

    @Test void unbindNeverDestroysOrSavesTheCitizensNpcAndPersistsRemoval() throws Exception {
        menus.bind(10); menus.unbind(10);
        assertFalse(access.lookup(10).destroyed); assertEquals(0, access.saves);
        assertFalse(Files.readString(folder.resolve("npc-menus.yml")).contains(access.lookup(10).uuid.toString()));
    }

    @Test void missingCitizensAndCorruptBindingsStayOptionalAndActionable() throws Exception {
        menus.close(); when(plugins.isPluginEnabled("Citizens")).thenReturn(false);
        menus = service(folder.resolve("absent.yml")); assertDoesNotThrow(menus::start);
        assertTrue(assertThrows(IllegalStateException.class, () -> menus.bind(10)).getMessage().contains("Install"));
        menus.close(); when(plugins.isPluginEnabled("Citizens")).thenReturn(true);
        Path corrupt = folder.resolve("corrupt.yml"); Files.writeString(corrupt, "schema-version: 99\n");
        menus = service(corrupt); assertDoesNotThrow(menus::start);
        assertTrue(assertThrows(IllegalStateException.class, () -> menus.create(player, "Guide")).getMessage().contains("Repair"));
        assertThrows(IllegalArgumentException.class, () -> menus.unbind(10));
        assertEquals("schema-version: 99\n", Files.readString(corrupt));
    }

    @Test void closeCancelsQueuedMenuAndIsIdempotent() throws Exception {
        menus.bind(10); click(access.lookup(10)); menus.close(); menus.close();
        scheduled.removeFirst().run(); assertTrue(opened.isEmpty());
    }

    private CitizensArenaNpc service(Path file) { return new CitizensArenaNpc(plugin, opened::add, file, () -> access, clock::get); }
    private TestClick click(FakeNpc npc) throws Exception {
        TestClick event = new TestClick(npc, player); listener.execute(null, event); return event;
    }

    private final class FakeAccess implements CitizensArenaNpc.Access {
        final Map<Integer, FakeNpc> npcs = new LinkedHashMap<>();
        boolean fakePlayer, spawnSucceeds = true; int saves; FakeNpc lastCreated;
        FakeNpc add(int id) { FakeNpc npc = new FakeNpc(id); npcs.put(id, npc); return npc; }
        public Class<? extends Event> eventType() { return TestClick.class; }
        public Player clicker(Event event) { return ((TestClick) event).player; }
        public CitizensArenaNpc.Npc clicked(Event event) { return ((TestClick) event).npc; }
        public FakeNpc lookup(int id) { return npcs.get(id); }
        public FakeNpc lookup(UUID uuid) { return npcs.values().stream().filter(npc -> npc.uuid.equals(uuid) && !npc.destroyed).findFirst().orElse(null); }
        public FakeNpc create(String name) { lastCreated = add(npcs.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1); lastCreated.spawned = false; return lastCreated; }
        public boolean isNpc(Player player) { return fakePlayer; }
        public void save() { saves++; }
    }
    private final class FakeNpc implements CitizensArenaNpc.Npc {
        int id; final UUID uuid = UUID.randomUUID(); boolean spawned = true, protectedNpc, destroyed;
        Location location = CitizensArenaNpcTest.this.location;
        FakeNpc(int id) { this.id = id; }
        public int id() { return id; } public UUID uuid() { return uuid; } public boolean spawned() { return spawned; }
        public Location location() { return location; }
        public boolean spawn(Location at) { location = at; spawned = access.spawnSucceeds; return spawned; }
        public void protect() { protectedNpc = true; }
        public void destroy() { destroyed = true; spawned = false; }
    }
    public static final class TestClick extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        final CitizensArenaNpc.Npc npc; final Player player; boolean cancelled;
        TestClick(CitizensArenaNpc.Npc npc, Player player) { this.npc = npc; this.player = player; }
        public boolean isCancelled() { return cancelled; } public void setCancelled(boolean value) { cancelled = value; }
        public HandlerList getHandlers() { return HANDLERS; } public static HandlerList getHandlerList() { return HANDLERS; }
    }
}
