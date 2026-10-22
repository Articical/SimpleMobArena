package cloud.mineplay.mobarena.mobs;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobSpawnEquipmentTest {
    @Test void customSpawnsHaveTheirRequiredWeaponsWhenNoHandListIsConfigured() {
        for (EntityType type : List.of(EntityType.SKELETON, EntityType.STRAY, EntityType.PILLAGER,
                EntityType.VINDICATOR, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.VEX)) {
            Mob mob = mock(Mob.class);
            EntityEquipment equipment = mock(EntityEquipment.class);
            when(mob.getType()).thenReturn(type);
            when(mob.getEquipment()).thenReturn(equipment);
            try (var constructed = mockConstruction(ItemStack.class, (item, context) ->
                    assertEquals(MobSpawn.defaultWeapon(type), context.arguments().getFirst()))) {
                MobSpawn.equip(mob, new YamlConfiguration());
                assertEquals(1, constructed.constructed().size(), type.toString());
                verify(equipment).setItem(EquipmentSlot.HAND, constructed.constructed().getFirst());
                for (EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
                        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
                    verify(equipment).setDropChance(slot, 0);
            }
        }
        assertEquals(Material.CROSSBOW, MobSpawn.defaultWeapon(EntityType.PILLAGER));
        assertEquals(Material.GOLDEN_SWORD, MobSpawn.defaultWeapon(EntityType.PIGLIN));
        assertNull(MobSpawn.defaultWeapon(EntityType.CREEPER));
    }

    @Test void explicitEmptyHandListsDisableTheDefaultWeapon() {
        Mob mob = mock(Mob.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(mob.getType()).thenReturn(EntityType.PILLAGER);
        when(mob.getEquipment()).thenReturn(equipment);
        YamlConfiguration config = new YamlConfiguration();
        config.set("equipment.hand", List.of());
        MobSpawn.equip(mob, config);
        verify(equipment).setItem(EquipmentSlot.HAND, null);
    }

    @Test void creeperSettingsAreValidatedAgainWhileLegacyDrownedRulesRemainInactive() {
        YamlConfiguration mobs = new YamlConfiguration();
        mobs.set("CREEPER.baby-chance", 2);
        mobs.set("DROWNED.baby-chance", 2);
        assertEquals(List.of("CREEPER.baby-chance must be between 0 and 1."), MobSpawn.validate(mobs));
    }
}
