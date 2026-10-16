package cloud.mineplay.mobarena.session;

import cloud.mineplay.mobarena.mobs.MobSpawn;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.AbstractSkeleton;
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
}
