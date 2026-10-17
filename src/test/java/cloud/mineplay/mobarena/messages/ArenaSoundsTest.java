package cloud.mineplay.mobarena.messages;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static cloud.mineplay.mobarena.messages.ArenaSounds.Cue.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArenaSoundsTest {
    private JavaPlugin plugin;
    private YamlConfiguration config;
    private Logger logger;
    private Player player;
    private Location location;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        config = new YamlConfiguration();
        logger = mock(Logger.class);
        player = mock(Player.class);
        location = new Location(mock(World.class), 5, 65, 5);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(location);
    }

    @Test void defaultsPlaySoftSoundsOnlyToTheGivenPlayer() {
        ArenaSounds sounds = new ArenaSounds(plugin);

        sounds.play(JOIN, player);
        sounds.play(ELIMINATED, player);

        verify(player).playSound(location, "minecraft:block.note_block.pling", SoundCategory.MASTER, 0.55F, 1.2F);
        verify(player).playSound(location, "minecraft:block.note_block.bass", SoundCategory.MASTER, 0.7F, 0.7F);
        verifyNoInteractions(location.getWorld());
        verifyNoInteractions(logger);
    }

    @Test void globalAndPerCueDisablesSuppressPlayback() {
        config.set("sounds.enabled", false);
        ArenaSounds globallyDisabled = new ArenaSounds(plugin);
        globallyDisabled.play(JOIN, player);
        globallyDisabled.play(MATCH_START, List.of(player));
        verify(player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());

        config.set("sounds.enabled", true);
        config.set("sounds.countdown.enabled", false);
        ArenaSounds selective = new ArenaSounds(plugin);
        selective.play(COUNTDOWN, player);
        selective.play(MATCH_START, player);

        verify(player, times(1)).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(player).playSound(location, "minecraft:block.note_block.bell", SoundCategory.MASTER, 0.7F, 1.0F);
    }

    @Test void customResourcePackKeyAndBoundedLevelsAreCachedUntilRecreated() {
        config.set("sounds.wave-start.sound", "mineplay:arena/wave_begin");
        config.set("sounds.wave-start.volume", 2.0);
        config.set("sounds.wave-start.pitch", 0.5);
        ArenaSounds original = new ArenaSounds(plugin);
        config.set("sounds.wave-start.sound", "mineplay:arena/new_wave");
        config.set("sounds.wave-start.volume", 0.0);
        config.set("sounds.wave-start.pitch", 2.0);

        original.play(WAVE_START, player);
        new ArenaSounds(plugin).play(WAVE_START, player);

        verify(player).playSound(location, "mineplay:arena/wave_begin", SoundCategory.MASTER, 2.0F, 0.5F);
        verify(player).playSound(location, "mineplay:arena/new_wave", SoundCategory.MASTER, 0.0F, 2.0F);
        verifyNoInteractions(logger);
    }

    @Test void malformedTypesKeysAndNonFiniteNumbersWarnAndUseDefaults() {
        config.set("sounds.enabled", "yes");
        config.set("sounds.join.enabled", 1);
        config.set("sounds.join.sound", "Minecraft:bad sound");
        config.set("sounds.join.volume", Double.NaN);
        config.set("sounds.join.pitch", Double.POSITIVE_INFINITY);
        config.set("sounds.cancelled.sound", 17);
        config.set("sounds.cancelled.volume", "0.5");
        config.set("sounds.cancelled.pitch", true);

        ArenaSounds sounds = new ArenaSounds(plugin);
        sounds.play(JOIN, player);
        sounds.play(CANCELLED, player);

        verify(player).playSound(location, "minecraft:block.note_block.pling", SoundCategory.MASTER, 0.55F, 1.2F);
        verify(player).playSound(location, "minecraft:block.note_block.didgeridoo", SoundCategory.MASTER, 0.5F, 0.8F);
        verify(logger, times(8)).warning(startsWith("Invalid sounds."));
    }

    @Test void outOfRangeLevelsAndUnqualifiedKeysFallBack() {
        config.set("sounds.countdown.sound", "block.note_block.hat");
        config.set("sounds.countdown.volume", -0.1);
        config.set("sounds.countdown.pitch", 2.1);
        config.set("sounds.match-end.volume", 2.1);
        config.set("sounds.match-end.pitch", 0.49);

        ArenaSounds sounds = new ArenaSounds(plugin);
        sounds.play(COUNTDOWN, player);
        sounds.play(MATCH_END, player);

        verify(player).playSound(location, "minecraft:block.note_block.hat", SoundCategory.MASTER, 0.5F, 1.2F);
        verify(player).playSound(location, "minecraft:entity.player.levelup", SoundCategory.MASTER, 0.7F, 1.0F);
        verify(logger, times(5)).warning(startsWith("Invalid sounds."));
    }

    @Test void offlinePlayersAreSkippedAndOneConnectionFailureDoesNotSilenceOthers() {
        Player disconnected = mock(Player.class);
        Player broken = mock(Player.class);
        when(broken.isOnline()).thenReturn(true);
        when(broken.getLocation()).thenReturn(location);
        doThrow(new IllegalStateException("connection closed")).when(broken)
                .playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        ArenaSounds sounds = new ArenaSounds(plugin);

        assertDoesNotThrow(() -> sounds.play(WAVE_COMPLETE, List.of(disconnected, broken, player)));

        verify(disconnected, never()).getLocation();
        verify(disconnected, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        verify(player).playSound(location, "minecraft:entity.experience_orb.pickup", SoundCategory.MASTER, 0.7F, 1.2F);
    }
}
