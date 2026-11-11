package cloud.mineplay.mobarena.commands;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.config.ArenaDefinition;
import cloud.mineplay.mobarena.gui.ArenaMenu;
import cloud.mineplay.mobarena.messages.ArenaMessages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Command dispatch owns permission checks; arena services validate all mutations again. */
public final class CommandManager implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_COMMANDS = List.of("join", "leave", "list", "info", "classes", "stats", "history", "duels", "match", "top", "admin");
    private static final List<String> ADMIN_COMMANDS = List.of("create", "delete", "mode", "mobset", "teamselection", "setteam", "addteam", "setplayer", "addplayer", "setmob", "addmob", "clearspawns", "setexit", "setoutsider", "setpos1", "setpos2", "setup", "start", "stop", "forcewave", "reload", "status", "enable", "economy", "payouts", "reconcile", "npc", "website", "achievements");
    private final SimpleMobArena plugin;
    private final ArenaMenu menu;

    public CommandManager(SimpleMobArena plugin) {
        this(plugin, new ArenaMenu(plugin));
        Bukkit.getPluginManager().registerEvents(menu, plugin);
    }

    CommandManager(SimpleMobArena plugin, ArenaMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
    }

    public void openMenu(Player player) { menu.open(player,0); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0) {
                requirePermission(sender, "mobarena.use");
                if (sender instanceof Player player) menu.open(player, 0);
                else message(sender, "Commands: join, leave, list, info, classes, stats, history, duels, match, top, admin.");
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("create", "setspawn", "start", "stop", "forcewave").contains(sub)) {
                String[] legacy = new String[args.length + 1];
                legacy[0] = "admin";
                System.arraycopy(args, 0, legacy, 1, args.length);
                if (sub.equals("setspawn")) legacy[1] = "setmob";
                admin(sender, legacy);
                return true;
            }
            switch (sub) {
                case "join" -> {
                    requirePermission(sender, "mobarena.join");
                    Player player = requirePlayer(sender);
                    requireArgs(args, 2, "/ma join <arena> [personal|class]");
                    Arena arena = requireArena(args[1]);
                    String choice = args.length > 2 ? args[2] : null;
                    if (choice == null) menu.openEquipment(player, arena.getId());
                    else if (args.length > 3) {
                        requireExactArgs(args,4,"/ma join <arena> <personal|class> <red|blue>");
                        if (choice.equalsIgnoreCase("personal")) plugin.getArenaManager().join(player,arena.getId(),choice,args[3]);
                        else menu.openClassPreview(player, arena.getId(), choice, args[3]);
                    }
                    else if (!choice.equalsIgnoreCase("personal")) menu.openClassPreview(player, arena.getId(), choice);
                    else if (arena.isDuel() && arena.definition().chooseTeams) menu.openTeamSelection(player,arena.getId(),choice);
                    else plugin.getArenaManager().join(player, arena.getId(), choice);
                }
                // Leaving stays available even if permissions change during the match.
                case "leave" -> plugin.getArenaManager().leave(requirePlayer(sender));
                case "list" -> {
                    requirePermission(sender, "mobarena.use");
                    if (sender instanceof Player player) { menu.open(player, 0); return true; }
                    if (plugin.getArenaManager().getArenas().isEmpty()) warning(sender, "No arenas are configured.");
                    for (Arena arena : plugin.getArenaManager().getArenas()) message(sender, arena.getId() + " - " + arena.getName() + " | " + arena.getArenaState() + " | " + arena.getPlayers().size() + "/" + arena.getMaxPlayers());
                }
                case "info" -> {
                    requirePermission(sender, "mobarena.use");
                    requireArgs(args, 2, "/ma info <arena>");
                    Arena arena = requireArena(args[1]);
                    if (sender instanceof Player player) menu.openInfo(player, arena.getId());
                    else info(sender, arena);
                }
                case "classes" -> {
                    requirePermission(sender, "mobarena.use");
                    menu.openClasses(requirePlayer(sender));
                }
                case "stats" -> stats(sender, args);
                case "history" -> history(sender, args);
                case "duels" -> {
                    requirePermission(sender,"mobarena.history");
                    requireExactArgs(args,args.length == 1 ? 1 : 2,"/ma duels [page]");
                    menu.openDuels(requirePlayer(sender),args.length == 1 ? 1 : historyPage(args[1]));
                }
                case "match" -> match(sender, args);
                case "top" -> {
                    requirePermission(sender, "mobarena.top");
                    if (sender instanceof Player player) { menu.openLeaderboard(player); return true; }
                    reply(sender, plugin.getEconomyService().top(10), records -> {
                        message(sender, "Arena leaderboard by kills (paid earnings):");
                        int rank = 1;
                        for (var record : records) message(sender, rank++ + ". " + record.playerName() + " | $" + record.earned().toPlainString() + " | " + record.kills() + " kills | best wave " + record.highestWave());
                        if (records.isEmpty()) message(sender, "No matches recorded yet.");
                    });
                }
                case "admin" -> admin(sender, args);
                default -> throw new IllegalArgumentException("Commands: /ma join, leave, list, info, classes, stats, history, duels, match, top, admin.");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            error(sender, ex.getMessage());
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "MobArena command failed", ex);
            error(sender, "Something went wrong. Ask an admin to check the server log.");
        }
        return true;
    }

    private void stats(CommandSender sender, String[] args) {
        requirePermission(sender, "mobarena.stats");
        UUID id;
        String cachedName = null;
        if (args.length > 1) {
            try { id = UUID.fromString(args[1]); }
            catch (IllegalArgumentException ignored) {
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) throw new IllegalArgumentException("That player isn't in the server cache. Use their UUID instead.");
                id = target.getUniqueId();
                cachedName = target.getName();
            }
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(id)) requirePermission(sender, "mobarena.stats.others");
        } else id = requirePlayer(sender).getUniqueId();
        UUID playerId = id;
        if (sender instanceof Player player) {
            menu.openStats(player, playerId, cachedOrOnlineName(sender, playerId, cachedName));
            return;
        }
        reply(sender, plugin.getEconomyService().stats(id), stats -> {
            message(sender, "Statistics for " + stats.playerName());
            message(sender, "Sessions: " + stats.sessions() + " | Kills: " + stats.kills() + " | Best completed wave: " + stats.highestWave());
            message(sender, "Confirmed earnings: $" + stats.earned().toPlainString() + " | Damage: " + String.format(Locale.ROOT, "%.1f", stats.damage()));
        });
    }

    private void history(CommandSender sender, String[] args) {
        requirePermission(sender, "mobarena.history");
        int page = 1;
        UUID playerId;
        String cachedName = null;
        if (args.length <= 1) playerId = requirePlayer(sender).getUniqueId();
        else if (args[1].matches("[0-9]+")) {
            playerId = requirePlayer(sender).getUniqueId();
            page = historyPage(args[1]);
        } else {
            try { playerId = UUID.fromString(args[1]); }
            catch (IllegalArgumentException ignored) {
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null) throw new IllegalArgumentException("That player isn't in the server cache. Use their UUID instead.");
                playerId = player.getUniqueId();
                cachedName = player.getName();
            }
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(playerId)) requirePermission(sender, "mobarena.stats.others");
            if (args.length > 2) page = historyPage(args[2]);
        }
        UUID target = playerId;
        int selectedPage = page;
        String targetName = cachedOrOnlineName(sender, target, cachedName);
        if (sender instanceof Player player) {
            menu.openHistory(player, target, targetName, selectedPage);
            return;
        }
        reply(sender, plugin.getEconomyService().history(playerId, page), rows -> {
            message(sender, "Match history for " + targetName + " - page " + selectedPage + ":");
            for (var match : rows) {
                message(sender, match.finishedAt() + " | " + match.arenaId() + " | completed wave " + match.completedWaves() + " | " + match.kills() + " kills | " + match.status() + (match.cancelled() ? " | cancelled" : ""));
                message(sender, "Reward: $" + match.intendedAmount().toPlainString() + " | Paid: $" + match.confirmedAmount().toPlainString() + " | Limits: " + match.capsApplied() + " | Match: " + match.sessionId());
            }
            if (rows.isEmpty()) message(sender, "No matches on this page.");
            else message(sender, "Use /ma history " + target + " " + (selectedPage + 1) + " for the next page; /ma match <session UUID> for the full roster.");
        });
    }

    private static int historyPage(String text) {
        try { int page = Integer.parseInt(text); if (page < 1 || page > 1000000) throw new NumberFormatException(); return page; }
        catch (NumberFormatException error) { throw new IllegalArgumentException("History page must be between 1 and 1000000."); }
    }

    private void match(CommandSender sender, String[] args) {
        requirePermission(sender, "mobarena.match");
        requireArgs(args, 2, "/ma match <session UUID>");
        UUID sessionId = parseUuid(args[1]);
        if (sender instanceof Player player) { menu.openMatch(player, sessionId); return; }
        reply(sender, plugin.getEconomyService().match(sessionId), match -> {
            if (match == null) {
                reply(sender,plugin.getEconomyService().duelMatch(sessionId),duel -> {
                    if (duel == null) { warning(sender,"No recorded match has that session ID."); return; }
                    message(sender,duel.mode()+" | "+duel.arenaId()+" | winner: "+duel.winner()+" | "+duel.endReason());
                    for (var participant : duel.participants()) message(sender,participant.playerName()+" | "+participant.team()
                        +" | "+participant.kills()+" kills | "+participant.damage()+" damage | "+participant.participationSeconds()+"s played");
                });
                return;
            }
            message(sender, "Match " + match.sessionId() + " | " + match.arenaId() + " | " + match.startedAt() + " to " + match.finishedAt());
            message(sender, "Completed waves: " + match.completedWaves() + " | Kills: " + match.totalKills() + " | Pot: " + match.prizePot().toPlainString() + " | Unallocated: " + match.unallocatedPot().toPlainString());
            message(sender, "Outcome: " + match.endReason() + (match.cancelled() ? " (cancelled)" : ""));
            for (var participant : match.participants()) {
                var settlement = participant.settlement();
                message(sender, participant.playerName() + " | " + participant.kills() + " kills | " + String.format(Locale.ROOT, "%.1f", participant.damage()) + " damage | " + participant.participationSeconds() + "s active");
                message(sender, "Share: " + settlement.share().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.DOWN).toPlainString() + "% | Intended: " + settlement.intendedAmount().toPlainString() + " | " + settlement.status() + " | Caps: " + settlement.capsApplied());
            }
        });
    }

    private void admin(CommandSender sender, String[] args) {
        requireArgs(args, 2, "/ma admin <" + String.join("|", ADMIN_COMMANDS) + ">");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!ADMIN_COMMANDS.contains(action)) throw new IllegalArgumentException("Unknown admin command.");
        requirePermission(sender, "mobarena.admin." + action);
        switch (action) {
            case "npc" -> npc(sender,args);
            case "website" -> website(sender,args);
            case "achievements" -> achievements(sender,args);
            case "reload" -> {
                plugin.getArenaManager().reloadArenas();
                success(sender, "Configuration and arenas reloaded.");
                List<String> equipmentErrors = plugin.getEquipmentService().configurationErrors();
                if (equipmentErrors.isEmpty()) success(sender, "Equipment settings: no errors.");
                else {
                    warning(sender, "Equipment settings: " + equipmentErrors.size() + (equipmentErrors.size() == 1 ? " error." : " errors."));
                    equipmentErrors.stream().limit(5).forEach(error -> warning(sender, error));
                    if (equipmentErrors.size() > 5) message(sender, "See the console for the remaining equipment errors.");
                    warning(sender, "Affected equipment choices are unavailable. Fix the settings, then run /ma admin reload.");
                }
                message(sender, "Check the console and /ma admin economy for validation errors.");
                message(sender, "Achievements, website export and storage settings take effect after a server restart.");
            }
            case "economy" -> message(sender, plugin.getEconomyService().status());
            case "payouts" -> {
                requireArgs(args, 3, "/ma admin payouts <session UUID>");
                UUID session = parseUuid(args[2]);
                reply(sender, plugin.getEconomyService().payouts(session), records -> {
                    message(sender, "Settlements for " + session + ":");
                    for (var record : records) {
                        message(sender, record.settlementId() + " | " + playerName(record.playerId()) + " | " + record.intendedAmount().toPlainString() + " | " + record.status());
                        message(sender, "Share: " + record.share().toPlainString() + "; caps: " + record.capsApplied() + "; provider error: " + record.error());
                    }
                    if (records.isEmpty()) message(sender, "No settlement records for that session.");
                });
            }
            case "reconcile" -> {
                requireArgs(args, 3, "/ma admin reconcile <settlement UUID> [CONFIRM|VOID]");
                UUID settlement = parseUuid(args[2]);
                if (args.length < 4) {
                    warning(sender, "Check the provider's audit records first. CONFIRM marks an existing deposit; VOID closes an unpaid deposit. Neither sends money.");
                    message(sender, "Then use /ma admin reconcile " + settlement + " <CONFIRM|VOID>.");
                    return;
                }
                String decision = args[3].toUpperCase(Locale.ROOT);
                if (!decision.equals("CONFIRM") && !decision.equals("VOID")) throw new IllegalArgumentException("Reconciliation decision must be CONFIRM or VOID.");
                reply(sender, plugin.getEconomyService().reconcile(settlement, decision, sender.getName()), result -> success(sender, result));
            }
            default -> {
                requireArgs(args, 3, "/ma admin " + action + " <arena>");
                String id = args[2];
                switch (action) {
                    case "create" -> {
                        plugin.getArenaManager().create(id);
                        success(sender, "Created " + id + ". The arena is disabled until setup is complete.");
                        message(sender, "Set both boundary corners, then use /ma admin setup " + id + ".");
                        return;
                    }
                    case "delete" -> plugin.getArenaManager().delete(id);
                    case "mode", "mobset", "teamselection" -> {
                        requireExactArgs(args,4,"/ma admin "+action+" <arena> <value>");
                        if (action.equals("mode")) plugin.getArenaManager().setMode(id,args[3]);
                        else if (action.equals("mobset")) plugin.getArenaManager().setMobSet(id,args[3]);
                        else plugin.getArenaManager().setTeamSelection(id,args[3]);
                        success(sender,action+" saved for "+id+". Check /ma admin setup "+id+".");
                        if (action.equals("mode")) message(sender,"The arena is disabled until you check its spawns and enable it again.");
                        if (action.equals("mobset")) {
                            Arena arena = requireArena(id);
                            if (arena.isDuel()) message(sender,"Mob sets take effect when this arena uses MOB_ARENA mode.");
                            else if (arena.definition().config.contains("waves.mobs")) warning(sender,
                                "This arena still uses its own waves.mobs pool. Remove waves.mobs from arenas/"+arena.getId()+".yml and reload to use the saved mob-set selection.");
                            else message(sender,"Mob pool: "+mobPoolDescription(arena.definition())+".");
                        }
                        return;
                    }
                    case "setteam", "addteam" -> {
                        requireExactArgs(args,4,"/ma admin "+action+" <arena> <red|blue>");
                        String team = args[3].toLowerCase(Locale.ROOT);
                        if (!List.of("red","blue").contains(team)) throw new IllegalArgumentException("Choose red or blue.");
                        Location location = requirePlayer(sender).getLocation();
                        if (action.equals("setteam")) plugin.getArenaManager().setLocation(id,"duel."+team+"-spawns",location);
                        else plugin.getArenaManager().addSpawn(id,"duel."+team+"-spawns",location);
                        success(sender,"Saved "+team+" spawn for "+id+". Check /ma admin setup "+id+".");
                        return;
                    }
                    case "setplayer", "setmob", "addplayer", "addmob", "setexit", "setpos1", "setpos2" -> {
                        requireExactArgs(args, 3, "/ma admin " + action + " <arena>");
                        String key = switch (action) {
                            case "setplayer", "addplayer" -> "player-spawns";
                            case "setmob", "addmob" -> "mob-spawns";
                            case "setexit" -> "exit";
                            case "setpos1" -> "pos1";
                            default -> "pos2";
                        };
                        Location location = requirePlayer(sender).getLocation();
                        if (action.startsWith("add")) plugin.getArenaManager().addSpawn(id, key, location);
                        else plugin.getArenaManager().setLocation(id, key, location);
                        if (action.equals("setplayer") || action.equals("setmob")) {
                            String type = action.equals("setplayer") ? "player" : "mob";
                            success(sender, "Replaced all " + type + " spawns for " + id + " with " + coordinates(location) + ".");
                            message(sender, "For extra spawn points, use /ma admin add" + type + " " + id + ". Check /ma admin setup " + id + ".");
                        } else success(sender, action + " saved for " + id + " at " + coordinates(location) + ". Check /ma admin setup " + id + ".");
                        return;
                    }
                    case "setoutsider" -> {
                        if (args.length == 4 && args[3].equalsIgnoreCase("exit")) {
                            plugin.getArenaManager().useExitForOutsiders(id);
                            success(sender, "Outsiders now use " + id + "'s exit. Future setexit changes update both destinations.");
                        } else {
                            requireExactArgs(args, 3, "/ma admin setoutsider <arena> [exit]");
                            Location location = requirePlayer(sender).getLocation();
                            plugin.getArenaManager().setLocation(id, "outsider-exit", location);
                            success(sender, "Outsider exit saved for " + id + " at " + coordinates(location) + ".");
                        }
                        message(sender, "Check /ma admin setup " + id + ".");
                        return;
                    }
                    case "clearspawns" -> {
                        requireExactArgs(args, 4, "/ma admin clearspawns <arena> <player|mob|red|blue|all>");
                        String type = args[3].toLowerCase(Locale.ROOT);
                        if (!List.of("player", "mob", "red", "blue", "all").contains(type)) throw new IllegalArgumentException("Choose player, mob, red, blue or all for clearspawns.");
                        Arena arena = requireArena(id);
                        plugin.getArenaManager().clearSpawns(id, type);
                        success(sender, "Cleared " + type + " spawns for " + id + ".");
                        clearedSpawnHelp(sender,arena,type);
                        return;
                    }
                    case "start" -> requireArena(id).startArena();
                    case "stop" -> requireArena(id).endArena();
                    case "forcewave" -> requireArena(id).forceWave();
                    case "enable" -> {
                        requireArgs(args, 4, "/ma admin enable <arena> <true|false>");
                        if (!args[3].equalsIgnoreCase("true") && !args[3].equalsIgnoreCase("false")) throw new IllegalArgumentException("Enabled must be true or false.");
                        plugin.getArenaManager().setEnabled(id, Boolean.parseBoolean(args[3]));
                    }
                    case "status" -> {
                        Arena arena = requireArena(id);
                        info(sender, arena);
                        message(sender, "Session: " + arena.getSessionId() + " | Starting players: " + arena.getStartingPlayerCount());
                        setup(sender, arena);
                        return;
                    }
                    case "setup" -> {
                        requireExactArgs(args, 3, "/ma admin setup <arena>");
                        setup(sender, requireArena(id));
                        return;
                    }
                    default -> throw new IllegalArgumentException("Unknown admin command.");
                }
                success(sender, action + " completed for " + id + ".");
            }
        }
    }

    public static void message(CommandSender sender, String text) {
        sender.sendMessage(ArenaMessages.info(text));
    }

    private static void success(CommandSender sender, String text) { sender.sendMessage(ArenaMessages.success(text)); }
    private static void warning(CommandSender sender, String text) { sender.sendMessage(ArenaMessages.warning(text)); }
    private static void error(CommandSender sender, String text) { sender.sendMessage(ArenaMessages.error(text)); }

    private void setup(CommandSender sender, Arena arena) {
        ArenaDefinition definition = arena.definition();
        String id = arena.getId();
        message(sender, "Setup: " + id + " | " + (definition.enabled ? "enabled" : "disabled")
            + " | World: " + definition.config.getString("world", "world"));
        message(sender,"Mode: "+definition.mode.label()+(arena.isDuel() ? " | Team size: "+definition.teamSize+" | Selection: "+(definition.chooseTeams ? "CHOOSE" : "AUTO")
            : " | Mob pool: "+mobPoolDescription(definition)));
        message(sender, "Corner 1: " + configuredLocation(definition, "pos1") + " | Corner 2: " + configuredLocation(definition, "pos2"));
        if (definition.pos1 != null && definition.pos2 != null) {
            Location first = definition.pos1, second = definition.pos2;
            message(sender, "Included blocks: X " + Math.min(first.getBlockX(), second.getBlockX()) + ".." + Math.max(first.getBlockX(), second.getBlockX())
                + " | Y " + Math.min(first.getBlockY(), second.getBlockY()) + ".." + Math.max(first.getBlockY(), second.getBlockY())
                + " | Z " + Math.min(first.getBlockZ(), second.getBlockZ()) + ".." + Math.max(first.getBlockZ(), second.getBlockZ()));
        }
        showSpawns(sender, definition, "player-spawns", "Player");
        if (arena.isDuel()) {
            showSpawns(sender,definition,"duel.red-spawns","Red team");
            showSpawns(sender,definition,"duel.blue-spawns","Blue team");
        } else showSpawns(sender, definition, "mob-spawns", "Mob");
        message(sender, "Exit: " + configuredLocation(definition, "exit"));
        boolean separateExit = !definition.config.getString("outsider-exit", "").isBlank();
        message(sender, "Outsider exit: " + (separateExit ? configuredLocation(definition, "outsider-exit") : "uses exit above"));
        if (arena.getValidationErrors().isEmpty()) {
            success(sender, definition.enabled ? "Ready: arena configuration is valid." : "Ready: /ma admin enable " + id + " true");
            return;
        }
        LinkedHashSet<String> repairs = new LinkedHashSet<>();
        for (String error : arena.getValidationErrors()) {
            error(sender, "Needs fix: " + error);
            // Geometry validation already includes precise repair commands; reserve extra hints
            // for older/integration errors that otherwise leave the administrator guessing.
            if (!error.contains("/ma admin ")) repairs.add(repairInstruction(error, id));
        }
        repairs.forEach(repair -> message(sender, repair));
        warning(sender, "Check again with /ma admin setup " + id + ". Enable only when it says Ready.");
    }

    private static String mobPoolDescription(ArenaDefinition definition) {
        String selected = definition.mobSet.isEmpty() ? "global waves.mobs" : "named set " + definition.mobSet;
        return definition.config.contains("waves.mobs") ? "arena waves.mobs override (saved selection: " + selected + ")" : selected;
    }

    private static void clearedSpawnHelp(CommandSender sender, Arena arena, String type) {
        String id = arena.getId();
        if (type.equals("red") || type.equals("blue")) {
            warning(sender,"Set a new "+type+" team spawn with /ma admin setteam "+id+" "+type+".");
        } else if (arena.isDuel()) {
            if (type.equals("all")) warning(sender,"Set both team spawns with /ma admin setteam "+id+" red and /ma admin setteam "+id+" blue. Separate lobby spawns are optional.");
            else if (type.equals("player")) message(sender,"The lobby now uses the team spawns. Use /ma admin setplayer "+id+" for separate waiting positions.");
            else message(sender,"Duels do not require mob spawns.");
        } else if (type.equals("all")) {
            warning(sender,"Set the required spawns with /ma admin setplayer "+id+" and /ma admin setmob "+id+".");
        } else warning(sender,"Set the required "+type+" spawn with /ma admin set"+type+" "+id+".");
    }

    private static void showSpawns(CommandSender sender, ArenaDefinition definition, String key, String label) {
        List<String> spawns = definition.config.getStringList(key);
        message(sender, label + " spawns: " + spawns.size());
        for (int index = 0; index < spawns.size(); index++) {
            Location location = ArenaDefinition.read(spawns.get(index));
            message(sender, "  " + label + " #" + (index + 1) + ": " + (location == null ? spawns.get(index) : coordinates(location)));
        }
    }

    private static String configuredLocation(ArenaDefinition definition, String key) {
        String raw = definition.config.getString(key);
        if (raw == null || raw.isBlank()) return "not set";
        Location location = ArenaDefinition.read(raw);
        return location == null ? raw : coordinates(location);
    }

    private static String coordinates(Location location) {
        return location.getWorld().getName() + " " + coordinate(location.getX()) + ", " + coordinate(location.getY()) + ", " + coordinate(location.getZ());
    }

    private static String coordinate(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }

    private static String repairInstruction(String error, String id) {
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("player spawn") || lower.contains("player-spawns"))
            return "Stand safely INSIDE and /ma admin setplayer " + id + " (replaces old points); addplayer adds more.";
        if (lower.contains("mob spawn") || lower.contains("mob-spawns"))
            return "Stand safely INSIDE and /ma admin setmob " + id + " (replaces old points); addmob adds more.";
        if (lower.contains("outsider"))
            return "Use a safe exit outside ALL arenas: /ma admin setoutsider " + id + " exit; or setoutsider " + id + " at a separate safe spot.";
        if (lower.contains("exit"))
            return "Stand safely OUTSIDE ALL arenas and /ma admin setexit " + id + ". Use a dry, harmless floor with clear standing room.";
        if (lower.contains("boundar") || lower.contains("corner") || lower.contains("overlap"))
            return "Use /ma admin setpos1 " + id + " and /ma admin setpos2 " + id + " at opposite corners, with DIFFERENT Y heights, enclosing all spawns.";
        if (lower.contains("worldguard") || lower.contains("region"))
            return "Check the configured WorldGuard region in arenas/" + id + ".yml, then /ma admin reload.";
        if (lower.contains("world"))
            return "Use a loaded arena world for both corners and all spawns; check arenas/" + id + ".yml and /ma admin reload.";
        return "Review arenas/" + id + ".yml and config.yml, then /ma admin reload.";
    }

    private void info(CommandSender sender, Arena arena) {
        message(sender, "Arena: " + arena.getName() + " (" + arena.getId() + ") | " + arena.getArenaState());
        if (arena.isDuel()) {
            message(sender,arena.definition().mode.label()+" | Red: "+arena.teamCount(cloud.mineplay.mobarena.duels.DuelTeams.Team.RED)
                +" | Blue: "+arena.teamCount(cloud.mineplay.mobarena.duels.DuelTeams.Team.BLUE)+" | No cash rewards");
            return;
        }
        message(sender, "Wave: " + arena.getCurrentWave() + " | Completed: " + arena.getCompletedWaves() + " | Players: " + arena.getPlayers().size() + "/" + arena.getMaxPlayers() + " | Pot: $" + arena.getPrizePot().toPlainString());
        if (sender instanceof Player player) message(sender, "Your kills: " + arena.getKills(player.getUniqueId()) + " | Estimated reward: $" + estimatedShare(arena, player.getUniqueId()).toPlainString() + " (your final reward may be lower)");
    }

    public static BigDecimal estimatedShare(Arena arena, UUID playerId) {
        int total = arena.getTotalKills();
        if (total <= 0) return BigDecimal.ZERO.setScale(2);
        return arena.getPrizePot().multiply(BigDecimal.valueOf(arena.getKills(playerId))).divide(BigDecimal.valueOf(total), 2, RoundingMode.DOWN);
    }

    private <T> void reply(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (failure != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Arena database command failed", failure);
                    error(sender, "Couldn't complete that request. Ask an admin to check the server log.");
                } else success.accept(value);
            });
        });
    }

    private Arena requireArena(String id) {
        Arena arena = plugin.getArenaManager().getArena(id);
        if (arena == null) throw new IllegalArgumentException("Unknown arena: " + id);
        return arena;
    }

    private void npc(CommandSender sender,String[] args) {
        requireArgs(args,3,"/ma admin npc <create [name]|bind <id>|unbind <id>|list>");
        var integration = plugin.getCitizensArenaNpc();
        if (integration == null) throw new IllegalStateException("Citizens arena menus are unavailable. Check the server console and restart after correcting the integration.");
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                Player player = requirePlayer(sender);
                String name = args.length > 3 ? String.join(" ",java.util.Arrays.copyOfRange(args,3,args.length)) : "MobArena";
                success(sender,integration.create(player,name));
            }
            case "bind", "unbind" -> {
                requireExactArgs(args,4,"/ma admin npc "+args[2]+" <Citizens NPC id>");
                int id;
                try { id = Integer.parseInt(args[3]); if (id < 0) throw new NumberFormatException(); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Enter a non-negative Citizens NPC id."); }
                success(sender,args[2].equalsIgnoreCase("bind") ? integration.bind(id) : integration.unbind(id));
            }
            case "list" -> {
                requireExactArgs(args,3,"/ma admin npc list");
                List<String> bindings = integration.bindings();
                if (bindings.isEmpty()) message(sender,"No arena menu NPCs are linked. Use /ma admin npc create or /ma admin npc bind <id>.");
                else bindings.forEach(line -> message(sender,line));
            }
            default -> throw new IllegalArgumentException("Usage: /ma admin npc <create [name]|bind <id>|unbind <id>|list>");
        }
    }

    private void website(CommandSender sender, String[] args) {
        requireArgs(args, 3, "/ma admin website <status|backfill|retry <event UUID>>");
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                requireExactArgs(args, 3, "/ma admin website status");
                reply(sender, plugin.getEconomyService().websiteStatus(), status -> {
                    var queue = status.queue();
                    message(sender, "Website export: " + (status.paused() ? "PAUSED" : "RUNNING")
                            + " | configured enabled: " + status.enabled() + " | source: " + queue.sourceId());
                    message(sender, "Pending: " + queue.pending() + " | quarantined: " + queue.quarantined()
                            + " | backfill: " + (queue.backfillActive() ? "active" : "idle"));
                    String age = queue.oldestPendingAt() == null ? "none"
                            : Math.max(0, java.time.Duration.between(queue.oldestPendingAt(), java.time.Instant.now()).getSeconds()) + "s";
                    message(sender, "Oldest pending: " + age + " | last committed acknowledgement: " + queue.lastAcknowledgedAt());
                    String failure = status.failureCode() != null ? status.failureCode() : queue.lastFailure();
                    if (failure != null && !failure.isBlank()) warning(sender, "Last export diagnostic: " + failure
                            + " | event: " + queue.lastFailureEventId());
                    message(sender, "Queued events are only delivered after a valid website acknowledgement is saved.");
                });
            }
            case "backfill" -> {
                requireExactArgs(args, 3, "/ma admin website backfill");
                reply(sender, plugin.getEconomyService().websiteBackfill(), ignored -> success(sender,
                        "Historical match export queued in resumable batches. Check /ma admin website status for progress."));
            }
            case "retry" -> {
                requireExactArgs(args, 4, "/ma admin website retry <event UUID>");
                UUID event = parseUuid(args[3]);
                reply(sender, plugin.getEconomyService().websiteRetry(event), retried -> {
                    if (retried) success(sender, "Website event " + event + " queued for another attempt with its original bytes. This does not retry a payout.");
                    else warning(sender, "Event cannot be retried: it is delivered, unknown, or has invalid source data. Inspect the retained event and diagnostic.");
                });
            }
            default -> throw new IllegalArgumentException("Usage: /ma admin website <status|backfill|retry <event UUID>>");
        }
    }
    private void achievements(CommandSender sender, String[] args) {
        requireArgs(args,3,"/ma admin achievements <status|sync [player UUID]>");
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                requireExactArgs(args,3,"/ma admin achievements status");
                message(sender,plugin.achievementStatus());
            }
            case "sync" -> {
                requireExactArgs(args,args.length==3 ? 3 : 4,"/ma admin achievements sync [player UUID]");
                UUID player = args.length==4 ? parseUuid(args[3]) : requirePlayer(sender).getUniqueId();
                plugin.syncAchievements(player);
                success(sender,"Saved arena progress queued for achievement sync: " + player);
            }
            default -> throw new IllegalArgumentException("Usage: /ma admin achievements <status|sync [player UUID]>");
        }
    }
    private static void requireArgs(String[] args, int count, String usage) { if (args.length < count) throw new IllegalArgumentException("Usage: " + usage); }
    private static void requireExactArgs(String[] args, int count, String usage) { if (args.length != count) throw new IllegalArgumentException("Usage: " + usage); }
    private static Player requirePlayer(CommandSender sender) { if (sender instanceof Player player) return player; throw new IllegalArgumentException("This action requires an in-game player."); }
    private static void requirePermission(CommandSender sender, String permission) { if (!sender.hasPermission(permission)) throw new IllegalArgumentException("Missing permission: " + permission); }
    private static UUID parseUuid(String text) { try { return UUID.fromString(text); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Enter a complete valid UUID."); } }
    private static String cachedOrOnlineName(CommandSender sender, UUID id, String cachedName) {
        if (sender instanceof Player player && player.getUniqueId().equals(id)) return player.getName();
        if (cachedName != null && !cachedName.isBlank()) return cachedName;
        Player online = Bukkit.getPlayer(id);
        return online == null ? id.toString() : online.getName();
    }
    private static String playerName(UUID id) { String name = Bukkit.getOfflinePlayer(id).getName(); return name == null ? id.toString() : name; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return matches(PLAYER_COMMANDS.stream().filter(s -> !s.equals("admin") || ADMIN_COMMANDS.stream().anyMatch(a -> sender.hasPermission("mobarena.admin." + a))).toList(), args[0]);
        if (args[0].equalsIgnoreCase("admin")) {
            if (args.length == 2) return matches(ADMIN_COMMANDS.stream().filter(a -> sender.hasPermission("mobarena.admin." + a)).toList(), args[1]);
            if (!sender.hasPermission("mobarena.admin." + args[1].toLowerCase(Locale.ROOT))) return List.of();
            if (args[1].equalsIgnoreCase("npc")) return args.length == 3
                ? matches(List.of("create","bind","unbind","list"),args[2]) : List.of();
            if (args[1].equalsIgnoreCase("website")) return args.length == 3
                ? matches(List.of("status","backfill","retry"),args[2]) : List.of();
            if (args[1].equalsIgnoreCase("achievements")) return args.length == 3
                ? matches(List.of("status","sync"),args[2]) : List.of();
            if (args.length == 4 && args[1].equalsIgnoreCase("enable")) return matches(List.of("true", "false"), args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("reconcile")) return matches(List.of("CONFIRM", "VOID"), args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("clearspawns")) return matches(List.of("player", "mob", "red", "blue", "all"), args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("setoutsider")) return matches(List.of("exit"), args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("mode")) return matches(List.of("MOB_ARENA","DUEL","TEAM_DUEL"),args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("teamselection")) return matches(List.of("AUTO","CHOOSE"),args[3]);
            if (args.length == 4 && List.of("setteam","addteam").contains(args[1].toLowerCase(Locale.ROOT))) return matches(List.of("red","blue"),args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("mobset")) {
                var sets = plugin.getConfig().getConfigurationSection("mob-sets");
                var names = new java.util.ArrayList<String>(); names.add("default");
                if (sets != null) names.addAll(sets.getKeys(false));
                return matches(names,args[3]);
            }
            if (args.length == 3 && !List.of("create", "payouts", "reconcile", "economy", "reload").contains(args[1].toLowerCase(Locale.ROOT))) return arenaMatches(args[2]);
        }
        if (args.length == 2 && List.of("join", "info", "start", "stop", "forcewave", "setspawn").contains(args[0].toLowerCase(Locale.ROOT))) return arenaMatches(args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("join")) return matches(plugin.getEquipmentService().availableChoices(), args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("join")) {
            Arena arena = plugin.getArenaManager().getArena(args[1]);
            return arena != null && arena.isDuel() && arena.definition().chooseTeams ? matches(List.of("red","blue"),args[3]) : List.of();
        }
        return List.of();
    }
    private List<String> arenaMatches(String prefix) { return matches(plugin.getArenaManager().getArenas().stream().map(Arena::getId).toList(), prefix); }
    private static List<String> matches(List<String> values, String prefix) { return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList(); }
}
