package cloud.mineplay.mobarena.session;

import cloud.mineplay.mobarena.mobs.MobSpawn;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Husk;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class MobSunlightTest {
    @Test void zombiesAreProtectedFromSunlightEvenWithoutHelmet() {
        Zombie zombie=mock(Zombie.class);
        MobSpawn.equip(zombie,null);
        verify(zombie).setShouldBurnInDay(false);
        verify(zombie).setCanBreakDoors(false);
    }
    @Test void skeletonVariantsAreProtectedFromSunlightWithoutHelmet() {
        AbstractSkeleton skeleton=mock(AbstractSkeleton.class);
        MobSpawn.equip(skeleton,null);
        verify(skeleton).setShouldBurnInDay(false);
    }
    @Test void babyVariationIsControlledByConfiguration() {
        Zombie zombie=mock(Zombie.class); YamlConfiguration config=new YamlConfiguration();
        config.set("baby-chance",1.0); MobSpawn.equip(zombie,config); verify(zombie).setBaby(true);
    }
    @Test void drownedAndHusksReceiveTheSameSunProtectionAsZombies() {
        for (Zombie zombie : java.util.List.of(mock(Drowned.class),mock(Husk.class))) {
            MobSpawn.equip(zombie,null);
            verify(zombie).setShouldBurnInDay(false);
        }
    }
    @Test void postSpawnProtectionDoesNotRerollEquipmentOrBabyVariation() {
        Zombie zombie=mock(Drowned.class);
        MobSpawn.protectFromSunlight(zombie);
        verify(zombie).setShouldBurnInDay(false);
        verify(zombie,never()).setBaby(anyBoolean());
        verify(zombie,never()).getEquipment();
    }
}
