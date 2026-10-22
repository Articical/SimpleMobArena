package cloud.mineplay.mobarena.mobs;

import cloud.mineplay.mobarena.config.ArenaDefinition.MobRule;
import java.util.List;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobWaveSelectorTest {
    private static final MobRule ZOMBIE = new MobRule(EntityType.ZOMBIE, 1, 40, 1.2);
    private static final MobRule GOAT = new MobRule(EntityType.GOAT, 4, 4, .8);
    private static final MobRule LLAMA = new MobRule(EntityType.LLAMA, 4, 3, .9);
    private static final MobRule BEE = new MobRule(EntityType.BEE, 5, 2, .7);
    private static final MobWaveSelector.Theme GOATS = new MobWaveSelector.Theme("Goat Stampede", 1, List.of(EntityType.GOAT));
    private static final MobWaveSelector.Theme LLAMAS = new MobWaveSelector.Theme("Llama Drama", 3, List.of(EntityType.LLAMA));

    @Test void missingConfigurationRetainsOrdinaryLegacyWavesWithoutRolling() {
        var random = mock(RandomGenerator.class);
        var result = MobWaveSelector.select(MobWaveSelector.Settings.read(null), List.of(ZOMBIE, GOAT), 4, 0, random);
        assertFalse(result.meme());
        assertEquals(List.of(ZOMBIE, GOAT), result.mobs());
        verifyNoInteractions(random);
    }

    @Test void themedRoundPreservesMobRulesAndNeverAddsUnlistedTypes() {
        var random = mock(RandomGenerator.class);
        var settings = new MobWaveSelector.Settings(true, 1, 4, 3, List.of(GOATS));
        var result = MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT, LLAMA), 4, 0, random);
        assertTrue(result.meme());
        assertEquals("Goat Stampede", result.name());
        assertEquals(List.of(GOAT), result.mobs());
        assertSame(GOAT, result.mobs().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> result.mobs().clear());
        verify(random).nextDouble();
        verify(random).nextInt(1);
    }

    @Test void themesWithAnyMissingOrLockedMobAreExcludedAsAWhole() {
        var random = mock(RandomGenerator.class);
        var mixed = new MobWaveSelector.Theme("Barnyard", 1, List.of(EntityType.GOAT, EntityType.LLAMA));
        var bees = new MobWaveSelector.Theme("Bee Swarm", 1, List.of(EntityType.BEE));
        var settings = new MobWaveSelector.Settings(true, 1, 1, 0, List.of(mixed, bees));
        var result = MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT, BEE), 4, 0, random);
        assertFalse(result.meme());
        assertEquals(List.of(ZOMBIE, GOAT), result.mobs());
        verifyNoInteractions(random);
        assertEquals(List.of(BEE), MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT, BEE), 5, 0, random).mobs());
    }

    @Test void minimumWaveAndCooldownRequireThreeOrdinaryWavesButNotBeforeTheFirstTheme() {
        var random = mock(RandomGenerator.class);
        var settings = new MobWaveSelector.Settings(true, 1, 4, 3, List.of(GOATS));
        assertFalse(MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT), 3, 0, random).meme());
        for (int wave : List.of(5, 6, 7))
            assertFalse(MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT), wave, 4, random).meme());
        verifyNoInteractions(random);
        assertTrue(MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT), 4, 0, random).meme());
        assertTrue(MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT), 8, 4, random).meme());
        var firstWave = new MobWaveSelector.Settings(true, 1, 1, 10, List.of(new MobWaveSelector.Theme("Zombies", 1, List.of(EntityType.ZOMBIE))));
        assertTrue(MobWaveSelector.select(firstWave, List.of(ZOMBIE), 1, 0, random).meme());
    }

    @Test void probabilityBoundaryAndZeroChanceArePredictable() {
        var random = mock(RandomGenerator.class);
        var settings = new MobWaveSelector.Settings(true, .12, 4, 0, List.of(GOATS));
        when(random.nextDouble()).thenReturn(.12, .119);
        assertFalse(MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT), 4, 0, random).meme());
        assertTrue(MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT), 4, 0, random).meme());
        verify(random,times(1)).nextInt(1);
        reset(random);
        assertFalse(MobWaveSelector.select(new MobWaveSelector.Settings(true, 0, 4, 0, List.of(GOATS)), List.of(GOAT), 4, 0, random).meme());
        verifyNoInteractions(random);
    }

    @Test void themeWeightsUseTheWholeConfiguredRange() {
        var random = mock(RandomGenerator.class);
        var settings = new MobWaveSelector.Settings(true, 1, 4, 0, List.of(GOATS, LLAMAS));
        when(random.nextInt(4)).thenReturn(0, 1, 3);
        assertEquals("Goat Stampede", MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT, LLAMA), 4, 0, random).name());
        assertEquals("Llama Drama", MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT, LLAMA), 4, 0, random).name());
        assertEquals("Llama Drama", MobWaveSelector.select(settings, List.of(ZOMBIE, GOAT, LLAMA), 4, 0, random).name());
    }

    @Test void parserReadsConfiguredThemesAndRejectsMalformedSettings() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
            enabled: true
            chance: 0.12
            minimum-wave: 4
            cooldown-waves: 3
            types:
              goats: {name: 'Goat Stampede', weight: 2, mobs: [GOAT]}
              mixed: {name: Barnyard, weight: 1, mobs: [goat, llama]}
            """);
        var settings = MobWaveSelector.Settings.read(config);
        assertTrue(settings.enabled());
        assertEquals(.12, settings.chance());
        assertEquals(List.of(EntityType.GOAT, EntityType.LLAMA), settings.themes().get(1).mobs());
        String original = config.saveToString();
        for (var invalid : List.of(
            java.util.Map.entry("enabled", "true"), java.util.Map.entry("chance", "0.12"),
            java.util.Map.entry("chance", Double.NaN), java.util.Map.entry("chance", 1.1),
            java.util.Map.entry("minimum-wave", 0), java.util.Map.entry("cooldown-waves", -1),
            java.util.Map.entry("minimum-wave", 4.5), java.util.Map.entry("types.goats.weight", 0),
            java.util.Map.entry("types.goats.name", "bad\nname"),
            java.util.Map.entry("types.goats.mobs", List.of()),
            java.util.Map.entry("types.goats.mobs", List.of("GOAT", "goat")),
            java.util.Map.entry("types.goats.mobs", List.of("NOT_A_MOB")),
            java.util.Map.entry("types.goats.mobs", "GOAT"))) {
            config.loadFromString(original);
            config.set(invalid.getKey(), invalid.getValue());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MobWaveSelector.Settings.read(config), invalid.toString());
            assertTrue(error.getMessage().startsWith("waves.meme-rounds:"));
        }
    }
}
