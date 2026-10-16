package cloud.mineplay.mobarena.config;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/** Optional region lookup without loading WorldGuard classes when it is absent. */
public final class WorldGuardSupport {
    private WorldGuardSupport() {}
    public static void validate(ArenaDefinition definition) {
        String id=definition.config.getString("worldguard-region","");
        if(id.isBlank()) return;
        if(!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            definition.errors.add("worldguard-region is configured but WorldGuard is not enabled."); return;
        }
        if(definition.world==null || definition.pos1==null || definition.pos2==null) return;
        try {
            Class<?> wg=Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance=wg.getMethod("getInstance").invoke(null);
            Object platform=wg.getMethod("getPlatform").invoke(instance);
            Object container=Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform").getMethod("getRegionContainer").invoke(platform);
            Class<?> adapter=Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object world=adapter.getMethod("adapt",org.bukkit.World.class).invoke(null,definition.world);
            Object manager=Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer").getMethod("get",Class.forName("com.sk89q.worldedit.world.World")).invoke(container,world);
            Object region=manager==null?null:Class.forName("com.sk89q.worldguard.protection.managers.RegionManager").getMethod("getRegion",String.class).invoke(manager,id);
            if(region==null) { definition.errors.add("WorldGuard region does not exist or region data is unavailable: "+id); return; }
            Method contains=Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion").getMethod("contains",int.class,int.class,int.class);
            int minY=Math.min(definition.pos1.getBlockY(),definition.pos2.getBlockY()),maxY=Math.max(definition.pos1.getBlockY(),definition.pos2.getBlockY());
            // Validate every x/z column at both heights, also covering concave polygon regions.
            for(int x=Math.min(definition.pos1.getBlockX(),definition.pos2.getBlockX());x<=Math.max(definition.pos1.getBlockX(),definition.pos2.getBlockX());x++)
                for(int z=Math.min(definition.pos1.getBlockZ(),definition.pos2.getBlockZ());z<=Math.max(definition.pos1.getBlockZ(),definition.pos2.getBlockZ());z++)
                    if(!(boolean)contains.invoke(region,x,minY,z) || !(boolean)contains.invoke(region,x,maxY,z)) {
                        definition.errors.add("Arena boundary must be fully inside WorldGuard region "+id); return;
                    }
        } catch(ReflectiveOperationException | LinkageError error) {
            definition.errors.add("WorldGuard region could not be verified: "+error.getClass().getSimpleName());
        }
    }
}
