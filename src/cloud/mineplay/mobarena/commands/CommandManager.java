package cloud.mineplay.mobarena.commands;

import cloud.mineplay.mobarena.SimpleMobArena;
import cloud.mineplay.mobarena.arenas.Arena;
import cloud.mineplay.mobarena.gui.ArenaMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Command dispatch owns permission checks; arena services validate all mutations again. */
public final class CommandManager implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_COMMANDS = List.of("join", "leave", "list", "info", "stats", "history", "match", "top", "admin");
    private static final List<String> ADMIN_COMMANDS = List.of("create", "delete", "setplayer", "setmob", "setexit", "setoutsider", "setpos1", "setpos2", "start", "stop", "forcewave", "reload", "status", "enable", "economy", "payouts", "reconcile");
    private final SimpleMobArena plugin;
    private final ArenaMenu menu;

    public CommandManager(SimpleMobArena plugin) {
        this.plugin = plugin;
        this.menu = new ArenaMenu(plugin);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0) {
                requirePermission(sender, "mobarena.use");
                if (sender instanceof Player player) menu.open(player, 0);
                else message(sender, "Commands: join, leave, list, info, stats, history, match, top, admin.");
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
                    if (choice == null && plugin.getEquipmentService().availableChoices().stream().anyMatch("personal"::equalsIgnoreCase)) choice = "personal";
                    if (choice == null) menu.openEquipment(player, arena.getId());
                    else plugin.getArenaManager().join(player, arena.getId(), choice);
                }
                // Leaving stays available even if permissions change during the match.
                case "leave" -> plugin.getArenaManager().leave(requirePlayer(sender));
                case "list" -> {
                    requirePermission(sender, "mobarena.use");
                    if (plugin.getArenaManager().getArenas().isEmpty()) message(sender, "No arenas are configured.");
                    for (Arena arena : plugin.getArenaManager().getArenas()) message(sender, arena.getId() + " - " + arena.getName() + " | " + arena.getArenaState() + " | " + arena.getPlayers().size() + "/" + arena.getMaxPlayers());
                }
                case "info" -> {
                    requirePermission(sender, "mobarena.use");
                    requireArgs(args, 2, "/ma info <arena>");
                    info(sender, requireArena(args[1]));
                }
                case "stats" -> stats(sender, args);
                case "history" -> history(sender, args);
                case "match" -> match(sender, args);
                case "top" -> {
                    requirePermission(sender, "mobarena.top");
                    reply(sender, plugin.getEconomyService().top(10), records -> {
                        message(sender, "Arena leaderboard (kills; earnings are confirmed):");
                        int rank = 1;
                        for (var record : records) message(sender, rank++ + ". " + playerName(record.playerId()) + " | " + record.earned().toPlainString() + " | " + record.kills() + " kills | best wave " + record.highestWave());
                        if (records.isEmpty()) message(sender, "No recorded sessions yet.");
                    });
                }
                case "admin" -> admin(sender, args);
                default -> throw new IllegalArgumentException("Commands: /ma join, leave, list, info, stats, history, match, top, admin.");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            sender.sendMessage(Component.text("[MobArena] " + ex.getMessage(), NamedTextColor.RED));
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "MobArena command failed", ex);
            sender.sendMessage(Component.text("[MobArena] Action failed safely; ask an administrator to check the server log.", NamedTextColor.RED));
        }
        return true;
    }

    private void stats(CommandSender sender, String[] args) {
        requirePermission(sender, "mobarena.stats");
        UUID id;
        if (args.length > 1) {
            try { id = UUID.fromString(args[1]); }
            catch (IllegalArgumentException ignored) {
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) throw new IllegalArgumentException("Unknown cached player; supply their UUID instead.");
                id = target.getUniqueId();
            }
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(id)) requirePermission(sender, "mobarena.stats.others");
        } else id = requirePlayer(sender).getUniqueId();
        UUID playerId = id;
        reply(sender, plugin.getEconomyService().stats(id), stats -> {
            message(sender, "Statistics for " + playerName(playerId));
            message(sender, "Sessions: " + stats.sessions() + " | Kills: " + stats.kills() + " | Best completed wave: " + stats.highestWave());
            message(sender, "Confirmed earnings: " + stats.earned().toPlainString() + " | Damage: " + String.format(Locale.ROOT, "%.1f", stats.damage()));
        });
    }

    private void history(CommandSender sender, String[] args) {
        requirePermission(sender, "mobarena.history");
        int page = 1;
        UUID playerId;
        if (args.length <= 1) playerId = requirePlayer(sender).getUniqueId();
        else if (args[1].matches("[0-9]+")) {
            playerId = requirePlayer(sender).getUniqueId();
            page = historyPage(args[1]);
        } else {
            try { playerId = UUID.fromString(args[1]); }
            catch (IllegalArgumentException ignored) {
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null) throw new IllegalArgumentException("Unknown cached player; supply their UUID instead.");
                playerId = player.getUniqueId();
            }
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(playerId)) requirePermission(sender, "mobarena.stats.others");
            if (args.length > 2) page = historyPage(args[2]);
        }
        UUID target = playerId;
        int selectedPage = page;
        reply(sender, plugin.getEconomyService().history(playerId, page), rows -> {
            message(sender, "Match history for " + playerName(target) + " - page " + selectedPage + ":");
            for (var match : rows) {
                message(sender, match.finishedAt() + " | " + match.arenaId() + " | completed wave " + match.completedWaves() + " | " + match.kills() + " kills | " + match.status() + (match.cancelled() ? " | cancelled" : ""));
                message(sender, "Intended: " + match.intendedAmount().toPlainString() + " | Confirmed: " + match.confirmedAmount().toPlainString() + " | Caps: " + match.capsApplied() + " | Session: " + match.sessionId());
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
        reply(sender, plugin.getEconomyService().match(parseUuid(args[1])), match -> {
            if (match == null) { message(sender, "Unknown match."); return; }
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
        if (!ADMIN_COMMANDS.contains(action)) throw new IllegalArgumentException("Unknown administrative action.");
        requirePermission(sender, "mobarena.admin." + action);
        switch (action) {
            case "reload" -> {
                plugin.getArenaManager().reloadArenas();
                message(sender, "Configuration and arenas reloaded. Check the console and /ma admin economy for validation errors.");
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
                    message(sender, "First compare this settlement with the provider's audit records. CONFIRM marks an already successful deposit; VOID closes an unpaid deposit. Neither sends money.");
                    message(sender, "Then use /ma admin reconcile " + settlement + " <CONFIRM|VOID>.");
                    return;
                }
                String decision = args[3].toUpperCase(Locale.ROOT);
                if (!decision.equals("CONFIRM") && !decision.equals("VOID")) throw new IllegalArgumentException("Reconciliation decision must be CONFIRM or VOID.");
                reply(sender, plugin.getEconomyService().reconcile(settlement, decision, sender.getName()), result -> message(sender, result));
            }
            default -> {
                requireArgs(args, 3, "/ma admin " + action + " <arena>");
                String id = args[2];
                switch (action) {
                    case "create" -> plugin.getArenaManager().create(id);
                    case "delete" -> plugin.getArenaManager().delete(id);
                    case "setplayer", "setmob", "setexit", "setoutsider", "setpos1", "setpos2" -> {
                        String key = switch (action) {
                            case "setplayer" -> "player-spawns";
                            case "setmob" -> "mob-spawns";
                            case "setexit" -> "exit";
                            case "setoutsider" -> "outsider-exit";
                            case "setpos1" -> "pos1";
                            default -> "pos2";
                        };
                        plugin.getArenaManager().setLocation(id, key, requirePlayer(sender).getLocation());
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
                        if (arena.getValidationErrors().isEmpty()) message(sender, "Arena configuration is valid.");
                        else arena.getValidationErrors().forEach(error -> message(sender, "Configuration: " + error));
                        return;
                    }
                    default -> throw new IllegalArgumentException("Unknown administrative action.");
                }
                message(sender, action + " completed for " + id + ".");
            }
        }
    }

    public static void message(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[MobArena] " + text, NamedTextColor.GOLD));
    }

    private void info(CommandSender sender, Arena arena) {
        message(sender, "Arena: " + arena.getName() + " (" + arena.getId() + ") | " + arena.getArenaState());
        message(sender, "Wave: " + arena.getCurrentWave() + " | Completed: " + arena.getCompletedWaves() + " | Players: " + arena.getPlayers().size() + "/" + arena.getMaxPlayers() + " | Pot: " + arena.getPrizePot().toPlainString());
        if (sender instanceof Player player) message(sender, "Your kills: " + arena.getKills(player.getUniqueId()) + " | Estimated reward: " + estimatedShare(arena, player.getUniqueId()).toPlainString() + " (before eligibility and caps; not guaranteed)");
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
                    message(sender, "Database action failed; check the console. No unsafe retry was attempted.");
                } else success.accept(value);
            });
        });
    }

    private Arena requireArena(String id) {
        Arena arena = plugin.getArenaManager().getArena(id);
        if (arena == null) throw new IllegalArgumentException("Unknown arena: " + id);
        return arena;
    }
    private static void requireArgs(String[] args, int count, String usage) { if (args.length < count) throw new IllegalArgumentException("Usage: " + usage); }
    private static Player requirePlayer(CommandSender sender) { if (sender instanceof Player player) return player; throw new IllegalArgumentException("This action requires an in-game player."); }
    private static void requirePermission(CommandSender sender, String permission) { if (!sender.hasPermission(permission)) throw new IllegalArgumentException("Missing permission: " + permission); }
    private static UUID parseUuid(String text) { try { return UUID.fromString(text); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Enter a complete valid UUID."); } }
    private static String playerName(UUID id) { String name = Bukkit.getOfflinePlayer(id).getName(); return name == null ? id.toString() : name; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return matches(PLAYER_COMMANDS.stream().filter(s -> !s.equals("admin") || ADMIN_COMMANDS.stream().anyMatch(a -> sender.hasPermission("mobarena.admin." + a))).toList(), args[0]);
        if (args[0].equalsIgnoreCase("admin")) {
            if (args.length == 2) return matches(ADMIN_COMMANDS.stream().filter(a -> sender.hasPermission("mobarena.admin." + a)).toList(), args[1]);
            if (!sender.hasPermission("mobarena.admin." + args[1].toLowerCase(Locale.ROOT))) return List.of();
            if (args.length == 4 && args[1].equalsIgnoreCase("enable")) return matches(List.of("true", "false"), args[3]);
            if (args.length == 4 && args[1].equalsIgnoreCase("reconcile")) return matches(List.of("CONFIRM", "VOID"), args[3]);
            if (args.length == 3 && !List.of("create", "payouts", "reconcile", "economy", "reload").contains(args[1].toLowerCase(Locale.ROOT))) return arenaMatches(args[2]);
        }
        if (args.length == 2 && List.of("join", "info", "start", "stop", "forcewave", "setspawn").contains(args[0].toLowerCase(Locale.ROOT))) return arenaMatches(args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("join")) return matches(plugin.getEquipmentService().availableChoices(), args[2]);
        return List.of();
    }
    private List<String> arenaMatches(String prefix) { return matches(plugin.getArenaManager().getArenas().stream().map(Arena::getId).toList(), prefix); }
    private static List<String> matches(List<String> values, String prefix) { return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList(); }
}
