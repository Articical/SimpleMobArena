package cloud.mineplay.mobarena.equipment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EquipmentStartupTest {
    @TempDir Path directory;

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        return plugin;
    }

    @Test void brokenClassFileStillLoadsOutstandingInventoryRecoveryAndRegistersProtection() throws Exception {
        Files.writeString(directory.resolve("classes.yml"), "classes: [broken\n");
        UUID player = UUID.randomUUID();
        var entry = new SnapshotStore.Entry(player,UUID.randomUUID(),"scout",new byte[]{1},null,SnapshotStore.Status.PREPARED);
        try (var bukkit = mockStatic(Bukkit.class);
             var stores = mockConstruction(SnapshotStore.class, (store,context) -> when(store.outstanding()).thenReturn(List.of(entry)))) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            PluginManager manager = mock(PluginManager.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            JavaPlugin plugin = plugin();
            EquipmentService equipment = new EquipmentService(plugin);
            try {
                assertEquals(List.of("PERSONAL"),equipment.availableChoices());
                assertFalse(equipment.configurationErrors().isEmpty());
                assertTrue(equipment.isLocked(player));
                assertTrue(equipment.hasSnapshot(player));
                verify(stores.constructed().getFirst()).outstanding();
                verify(manager).registerEvents(equipment,plugin);
                verify(scheduler).runTask(eq(plugin),any(Runnable.class));
            } finally { equipment.shutdown(); }
        }
    }

    @Test void actualSnapshotFailureStillRefusesUnsafeStartup() throws Exception {
        Files.writeString(directory.resolve("classes.yml"), "classes: [broken\n");
        try (var bukkit = mockStatic(Bukkit.class);
             var stores = mockConstruction(SnapshotStore.class, (store,context) -> {
                 when(store.outstanding()).thenThrow(new IllegalStateException("snapshot read failed"));
             })) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            JavaPlugin plugin = plugin();
            IllegalStateException error = assertThrows(IllegalStateException.class,() -> new EquipmentService(plugin));
            assertTrue(error.getMessage().contains("Equipment recovery could not initialize"));
            assertEquals("snapshot read failed",error.getCause().getMessage());
            verify(stores.constructed().getFirst()).close();
            bukkit.verify(Bukkit::getPluginManager,never());
        }
    }
}
