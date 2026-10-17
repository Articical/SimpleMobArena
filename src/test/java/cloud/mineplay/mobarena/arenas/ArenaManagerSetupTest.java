package cloud.mineplay.mobarena.arenas;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.config.SpawnSafety;
import cloud.mineplay.mobarena.equipment.EquipmentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises setup through real YAML saves and arena reloads. Terrain is checked separately. */
class ArenaManagerSetupTest {
    @TempDir Path directory;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<SpawnSafety> safety;
    private ArenaManager manager;
    private World world;

    @BeforeEach void setup() {
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        safety = mockStatic(SpawnSafety.class);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("waves.mobs.ZOMBIE.unlock-wave",1);
        SimpleMobArena plugin = mock(SimpleMobArena.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(defaults);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getEquipmentService()).thenReturn(mock(EquipmentService.class));
        manager = new ArenaManager(plugin);
        manager.loadArenas();
    }

    @AfterEach void cleanup() { safety.close(); bukkit.close(); }

    @Test void replacingExampleSpawnsThenAddingMoreSurvivesReload() throws Exception {
        manager.create("spawn");
        YamlConfiguration example = saved("spawn");
        example.set("player-spawns",List.of("world,0.5,181,0.5,0,0"));
        example.set("mob-spawns",List.of("world,10.5,181,10.5,180,0","world,-10.5,181,-10.5,0,0"));
        example.save(file("spawn").toFile());
        manager.loadArenas();

        Location player = at(50.5,181,50.5), mob = at(60.5,181,60.5);
        manager.setLocation("spawn","player-spawns",player);
        manager.setLocation("spawn","mob-spawns",mob);
        assertEquals(List.of(ArenaDefinition.write(player)),saved("spawn").getStringList("player-spawns"));
        assertEquals(List.of(ArenaDefinition.write(mob)),saved("spawn").getStringList("mob-spawns"));

        manager.addSpawn("spawn","player-spawns",at(51.5,181,50.5));
        manager.addSpawn("spawn","mob-spawns",at(61.5,181,60.5));
        manager.addSpawn("spawn","mob-spawns",mob); // Repeating the exact point is harmless.
        manager.loadArenas();
        assertEquals(2,manager.required("spawn").definition().playerSpawns.size());
        assertEquals(2,manager.required("spawn").definition().mobSpawns.size());
        assertEquals(mob,manager.required("spawn").definition().mobSpawns.getFirst());
    }

    @Test void clearingOneKindKeepsTheOtherAndAllClearsBoth() {
        configured("spawn",0);
        manager.clearSpawns("spawn","mob");
        assertTrue(saved("spawn").getStringList("mob-spawns").isEmpty());
        assertEquals(1,saved("spawn").getStringList("player-spawns").size());
        manager.addSpawn("spawn","mob-spawns",at(8.5,181,8.5));
        manager.clearSpawns("spawn","player");
        assertEquals(1,saved("spawn").getStringList("mob-spawns").size());
        manager.addSpawn("spawn","player-spawns",at(2.5,181,2.5));
        manager.clearSpawns("spawn","all");
        assertTrue(manager.required("spawn").definition().playerSpawns.isEmpty());
        assertTrue(manager.required("spawn").definition().mobSpawns.isEmpty());
    }

    @Test void sharingExitRemovesTheOverrideAndFollowsSubsequentExitChanges() {
        configured("spawn",0);
        manager.setLocation("spawn","outsider-exit",at(50.5,65,50.5));
        manager.useExitForOutsiders("spawn");
        manager.setLocation("spawn","exit",at(60.5,65,60.5));
        assertFalse(saved("spawn").contains("outsider-exit"));
        assertEquals(at(60.5,65,60.5),manager.required("spawn").definition().exit);
        assertTrue(manager.required("spawn").getValidationErrors().isEmpty());
        Player outsider = mock(Player.class);
        when(outsider.teleport(at(60.5,65,60.5))).thenReturn(true);
        manager.eject(outsider,manager.required("spawn"));
        verify(outsider).teleport(at(60.5,65,60.5));
    }

    @Test void invalidModesAndCoordinatesDoNotChangeSavedConfiguration() throws Exception {
        configured("spawn",0);
        String original = Files.readString(file("spawn"));
        assertThrows(IllegalArgumentException.class,() -> manager.clearSpawns("spawn","everything"));
        assertThrows(IllegalArgumentException.class,() -> manager.addSpawn("spawn","exit",at(2,181,2)));
        assertThrows(IllegalArgumentException.class,() -> manager.setLocation("spawn","mob-spawns",at(Double.NaN,181,2)));
        assertThrows(IllegalArgumentException.class,() -> manager.setLocation("spawn","exit",new Location(null,2,181,2)));
        assertEquals(original,Files.readString(file("spawn")));
    }

    @Test void incompleteArenaCannotBeEnabledAndPointsToTheSetupChecklist() {
        manager.create("spawn");
        IllegalStateException failure = assertThrows(IllegalStateException.class,() -> manager.setEnabled("spawn",true));
        assertTrue(failure.getMessage().contains("/ma admin setup spawn"));
        assertFalse(saved("spawn").getBoolean("enabled"));
        assertFalse(failure.getMessage().contains(";"));
    }

    @Test void overlappingDisabledArenaCannotBeEnabledOverAnExistingEnabledArena() {
        configured("first",0);
        manager.setEnabled("first",true);
        configured("second",5);
        assertTrue(manager.required("second").getValidationErrors().stream().anyMatch(e -> e.contains("overlaps enabled arena first")));
        assertThrows(IllegalStateException.class,() -> manager.setEnabled("second",true));
        assertFalse(saved("second").getBoolean("enabled"));
    }

    @Test void outsiderExitInsideAnotherArenaIsExplainedAndCanBeResetToSharedExit() {
        configured("first",0);
        manager.setEnabled("first",true);
        configured("second",50);
        manager.setLocation("second","outsider-exit",at(5.5,181,5.5));
        assertTrue(manager.required("second").getValidationErrors().stream().anyMatch(e -> e.contains("Outsider exit is inside enabled arena first")));
        manager.useExitForOutsiders("second");
        assertTrue(manager.required("second").getValidationErrors().isEmpty());
        manager.setEnabled("second",true);
        assertTrue(saved("second").getBoolean("enabled"));
    }

    private void configured(String id,int offset) {
        manager.create(id);
        manager.setLocation(id,"pos1",at(offset,180,offset));
        manager.setLocation(id,"pos2",at(offset+10,190,offset+10));
        manager.setLocation(id,"player-spawns",at(offset+2.5,181,offset+2.5));
        manager.setLocation(id,"mob-spawns",at(offset+8.5,181,offset+8.5));
        manager.setLocation(id,"exit",at(offset+20.5,65,offset+20.5));
    }
    private Location at(double x,double y,double z) { return new Location(world,x,y,z); }
    private Path file(String id) { return directory.resolve("arenas/"+id+".yml"); }
    private YamlConfiguration saved(String id) { return YamlConfiguration.loadConfiguration(file(id).toFile()); }
}
