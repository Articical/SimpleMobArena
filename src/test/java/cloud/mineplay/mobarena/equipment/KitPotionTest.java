package cloud.mineplay.mobarena.equipment;

import io.papermc.paper.registry.RegistryAccess;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KitPotionTest {
    @BeforeAll static void initializeEffectRegistry() {
        // Only registry identities are supplied; ItemStack and inventory behavior remain mocked.
        try (MockedStatic<RegistryAccess> registryAccess = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, RETURNS_DEEP_STUBS);
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            Registry<PotionEffectType> effects = Registry.MOB_EFFECT;
            doAnswer(call -> new TestEffectType(call.getArgument(0)))
                    .when(effects).getOrThrow(any(Key.class));
            assertNotNull(PotionEffectType.SPEED);
            doAnswer(call -> {
                NamespacedKey key = call.getArgument(0);
                return key.getNamespace().equals("minecraft") && Set.of("speed", "regeneration", "instant_health").contains(key.getKey())
                        ? new TestEffectType(key) : null;
            }).when(effects).get(any(NamespacedKey.class));
        }
    }

    @ParameterizedTest @EnumSource(value = Material.class, names = { "POTION", "SPLASH_POTION" })
    void normalAndSplashItemsKeepTheExplicitBaseTypeAndColor(Material material) {
        YamlConfiguration item = base("STRONG_HEALING");
        item.set("potion.color", "#12AbEF");
        KitPotion recipe = KitPotion.read(item, material);
        assertEquals(PotionType.STRONG_HEALING, recipe.baseType());
        assertTrue(recipe.effects().isEmpty());
        assertEquals(Color.fromRGB(0x12abef), recipe.color());

        ItemStack stack = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(meta)).thenReturn(true);
        recipe.apply(stack);
        verify(meta).setBasePotionType(PotionType.STRONG_HEALING);
        verify(meta).setColor(Color.fromRGB(0x12abef));
        verify(meta).clearCustomEffects();
        verify(meta, never()).addCustomEffect(any(), anyBoolean());
        verify(stack).setItemMeta(meta);
    }

    @Test void customEffectsUseExplicitDurationsAndPreserveTheirDisplayOptions() {
        YamlConfiguration item = new YamlConfiguration();
        item.set("potion.effects.speed.amplifier", 1);
        item.set("potion.effects.speed.duration-ticks", 400);
        item.set("potion.effects.speed.ambient", true);
        item.set("potion.effects.speed.particles", false);
        item.set("potion.effects.speed.icon", false);
        item.set("potion.effects.minecraft:regeneration.duration-ticks", "100");
        KitPotion recipe = KitPotion.read(item, Material.SPLASH_POTION);
        assertEquals(PotionType.WATER, recipe.baseType());
        assertEquals(2, recipe.effects().size());
        PotionEffect speed = recipe.effects().getFirst();
        assertEquals(NamespacedKey.minecraft("speed"), speed.getType().getKey());
        assertEquals(1, speed.getAmplifier());
        assertEquals(400, speed.getDuration());
        assertTrue(speed.isAmbient());
        assertFalse(speed.hasParticles());
        assertFalse(speed.hasIcon());
        assertEquals(0, recipe.effects().getLast().getAmplifier());
        assertTrue(recipe.effects().getLast().hasParticles());
        assertTrue(recipe.effects().getLast().hasIcon());

        ItemStack stack = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(meta)).thenReturn(true);
        recipe.apply(stack);
        ArgumentCaptor<PotionEffect> applied = ArgumentCaptor.forClass(PotionEffect.class);
        verify(meta, times(2)).addCustomEffect(applied.capture(), eq(true));
        assertEquals(recipe.effects(), applied.getAllValues());
        verify(meta).setBasePotionType(PotionType.WATER);
    }

    @Test void baseEffectsAndCustomEffectsCanBeCombined() {
        YamlConfiguration item = base("healing");
        item.set("potion.effects.regeneration.duration-ticks", 200);
        KitPotion recipe = KitPotion.read(item, Material.POTION);
        assertEquals(PotionType.HEALING, recipe.baseType());
        assertEquals(1, recipe.effects().size());
    }

    @Test void ordinaryItemsDoNotNeedPotionMetadataButCannotSilentlyIgnoreIt() {
        assertNull(KitPotion.read(new YamlConfiguration(), Material.IRON_SWORD));
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(base("HEALING"), Material.IRON_SWORD));
    }

    @Test void lingeringItemsAreRejectedBecauseArenaCloudsAreDisabled() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> KitPotion.read(base("HEALING"), Material.LINGERING_POTION)).getMessage().contains("lingering potions are disabled"));
    }

    @Test void potionItemsNeverAccidentallyBecomeWaterFromMissingOrMalformedMetadata() {
        for (Object value : List.of(true, 3, "HEALING", List.of("HEALING"))) {
            YamlConfiguration item = new YamlConfiguration();
            item.set("potion", value);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
        }
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(new YamlConfiguration(), Material.POTION));
        YamlConfiguration empty = new YamlConfiguration();
        empty.createSection("potion");
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(empty, Material.POTION));
        empty.set("potion.color", "#ffffff");
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(empty, Material.POTION));
        assertEquals(PotionType.WATER, KitPotion.read(base("WATER"), Material.POTION).baseType());
    }

    @Test void unknownBaseTypesAndNonStringTypesAreRejected() {
        for (Object value : List.of("STRONG_HEALNG", "minecraft:healing", "", true, 4, List.of("HEALING"))) {
            YamlConfiguration item = new YamlConfiguration();
            item.set("potion.base-type", value);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.SPLASH_POTION), "Value: " + value);
        }
    }

    @ParameterizedTest @ValueSource(strings = { "#fff", "ffffff", "#gg0000", "#11223344", "red", "" })
    void malformedColorsAreRejected(String color) {
        YamlConfiguration item = base("HEALING");
        item.set("potion.color", color);
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
    }

    @Test void effectsRequireKnownNamesAndConfigurationSections() {
        for (String name : List.of("speeed", "other:speed", "not valid")) {
            YamlConfiguration item = base("WATER");
            item.set("potion.effects." + name + ".duration-ticks", 100);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
        }
        for (Object value : List.of(true, 100, "speed", List.of("speed"))) {
            YamlConfiguration item = base("WATER");
            item.set("potion.effects", value);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
            item.set("potion.effects", null);
            item.set("potion.effects.speed", value);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
        }
    }

    @Test void effectAliasesCannotOverrideOneAnother() {
        YamlConfiguration item = base("WATER");
        item.set("potion.effects.speed.duration-ticks", 100);
        item.set("potion.effects.minecraft:speed.duration-ticks", 200);
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
    }

    @Test void effectDurationsRejectMissingZeroInfiniteFractionalAndOverflowValues() {
        YamlConfiguration missing = base("WATER");
        missing.set("potion.effects.speed.amplifier", 0);
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(missing, Material.POTION));
        for (Object value : List.of(0, -1, 1.1, 100.0, true, "100.5", "100t", 2147483648L,
                new BigInteger("999999999999999999999999"))) {
            YamlConfiguration item = base("WATER");
            item.set("potion.effects.speed.duration-ticks", value);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION), "Value: " + value);
        }
    }

    @Test void amplifiersAndDisplayFlagsDoNotSilentlyCoerceInvalidValues() {
        for (Object value : List.of(-1, 11, 0.5, 1.0, false, "lots")) {
            YamlConfiguration item = base("WATER");
            item.set("potion.effects.speed.duration-ticks", 100);
            item.set("potion.effects.speed.amplifier", value);
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
        }
        for (String flag : List.of("ambient", "particles", "icon")) {
            YamlConfiguration item = base("WATER");
            item.set("potion.effects.speed.duration-ticks", 100);
            item.set("potion.effects.speed." + flag, "true");
            assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
        }
    }

    @Test void unknownPotionOrEffectKeysAreRejectedWithTheirConfigurationPath() {
        YamlConfiguration item = base("HEALING");
        item.set("potion.base-typ", "STRONG_HEALING");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION))
                .getMessage().contains("potion"));
        item.set("potion.base-typ", null);
        item.set("potion.effects.speed.duration-ticks", 100);
        item.set("potion.effects.speed.amplifer", 2);
        assertThrows(IllegalArgumentException.class, () -> KitPotion.read(item, Material.POTION));
    }

    @Test void itemMetadataFailureCannotReturnAnUnconfiguredPotion() {
        KitPotion recipe = KitPotion.read(base("HEALING"), Material.POTION);
        ItemStack item = mock(ItemStack.class);
        when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));
        assertThrows(IllegalArgumentException.class, () -> recipe.apply(item));
        when(item.getItemMeta()).thenReturn(mock(PotionMeta.class));
        when(item.setItemMeta(any())).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> recipe.apply(item));
    }

    private static YamlConfiguration base(String type) {
        YamlConfiguration item = new YamlConfiguration();
        item.set("potion.base-type", type);
        return item;
    }

    @SuppressWarnings({ "deprecation", "removal" })
    private static final class TestEffectType extends PotionEffectType {
        private final Key key;
        TestEffectType(Key key) { this.key = key; }
        @Override public NamespacedKey getKey() { return new NamespacedKey(key.namespace(), key.value()); }
        @Override public String getTranslationKey() { return "effect.minecraft." + key.value(); }
        @Override public String translationKey() { return getTranslationKey(); }
        @Override public PotionEffect createEffect(int duration, int amplifier) { return new PotionEffect(this, duration, amplifier); }
        @Override public boolean isInstant() { return key.value().startsWith("instant_"); }
        @Override public org.bukkit.potion.PotionEffectTypeCategory getCategory() { return null; }
        @Override public Color getColor() { return Color.BLUE; }
        @Override public double getDurationModifier() { return 1; }
        @Override public int getId() { return 1; }
        @Override public String getName() { return key.value(); }
        @Override public Map<Attribute, AttributeModifier> getEffectAttributes() { return Map.of(); }
        @Override public double getAttributeModifierAmount(Attribute attribute, int amplifier) { return 0; }
        @Override public Category getEffectCategory() { return Category.BENEFICIAL; }
    }
}
