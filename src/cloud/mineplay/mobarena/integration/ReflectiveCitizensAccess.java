package cloud.mineplay.mobarena.integration;

import cloud.mineplay.mobarena.SimpleMobArena;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

import java.lang.reflect.Method;
import java.util.UUID;

/** CitizensAPI interfaces are resolved only after the optional plugin has enabled. */
final class ReflectiveCitizensAccess implements CitizensArenaNpc.Access {
    private final Method registry;
    private final Class<?> registryType, npcType;
    private final Class<? extends Event> clickType;
    ReflectiveCitizensAccess(SimpleMobArena plugin) {
        try {
            ClassLoader loader = plugin.getServer().getPluginManager().getPlugin("Citizens").getClass().getClassLoader();
            registry = Class.forName("net.citizensnpcs.api.CitizensAPI", true, loader).getMethod("getNPCRegistry");
            registryType = Class.forName("net.citizensnpcs.api.npc.NPCRegistry", true, loader);
            npcType = Class.forName("net.citizensnpcs.api.npc.NPC", true, loader);
            clickType = Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent", true, loader).asSubclass(Event.class);
            // Fail before creating anything if the persistence hook is unavailable.
            registryType.getMethod("saveToStore");
            currentRegistry();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Citizens API", failure); }
    }
    private Object currentRegistry() {
        Object result = invoke(registry, null);
        if (result == null) throw new IllegalStateException("Citizens has not finished loading its NPC registry.");
        return result;
    }
    @Override public Class<? extends Event> eventType() { return clickType; }
    @Override public Player clicker(Event event) { return (Player) call(clickType, event, "getClicker"); }
    @Override public CitizensArenaNpc.Npc clicked(Event event) { return new Handle(call(clickType, event, "getNPC")); }
    @Override public CitizensArenaNpc.Npc lookup(int id) {
        Object result = call(registryType, currentRegistry(), "getById", new Class<?>[]{int.class}, id);
        return result == null ? null : new Handle(result);
    }
    @Override public CitizensArenaNpc.Npc lookup(UUID uuid) {
        Object result = call(registryType, currentRegistry(), "getByUniqueId", new Class<?>[]{UUID.class}, uuid);
        return result == null ? null : new Handle(result);
    }
    @Override public CitizensArenaNpc.Npc create(String name) {
        return new Handle(call(registryType, currentRegistry(), "createNPC", new Class<?>[]{EntityType.class, String.class}, EntityType.PLAYER, name));
    }
    @Override public boolean isNpc(Player player) { return (boolean) call(registryType, currentRegistry(), "isNPC", new Class<?>[]{Entity.class}, player); }
    @Override public void save() { call(registryType, currentRegistry(), "saveToStore"); }
    private final class Handle implements CitizensArenaNpc.Npc {
        private final Object npc;
        Handle(Object npc) { this.npc = npc; }
        @Override public int id() { return (int) call(npcType, npc, "getId"); }
        @Override public UUID uuid() { return (UUID) call(npcType, npc, "getUniqueId"); }
        @Override public boolean spawned() { return (boolean) call(npcType, npc, "isSpawned"); }
        @Override public Location location() { return (Location) call(npcType, npc, "getStoredLocation"); }
        @Override public boolean spawn(Location location) { return (boolean) call(npcType, npc, "spawn", new Class<?>[]{Location.class}, location); }
        @Override public void protect() { call(npcType, npc, "setProtected", new Class<?>[]{boolean.class}, true); }
        @Override public void destroy() { call(npcType, npc, "destroy"); }
    }
    private static Object call(Class<?> type, Object target, String name) { return call(type, target, name, new Class<?>[0]); }
    private static Object call(Class<?> type, Object target, String name, Class<?>[] arguments, Object... values) {
        try { return invoke(type.getMethod(name, arguments), target, values); }
        catch (NoSuchMethodException failure) { throw new IllegalStateException("Citizens API method is unavailable: " + name, failure); }
    }
    private static Object invoke(Method method, Object target, Object... values) {
        try { return method.invoke(target, values); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Citizens API call failed: " + method.getName(), failure); }
    }
}
