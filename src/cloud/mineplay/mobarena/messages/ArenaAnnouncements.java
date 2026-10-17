package cloud.mineplay.mobarena.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.time.Duration;

/** Main-thread event presentation. The arena owns timing and decides when each event occurs. */
public final class ArenaAnnouncements {
    private final JavaPlugin plugin;
    private final String arenaId;
    private final String arenaName;
    private final boolean publicJoin;
    private final boolean publicStart;
    private final boolean publicEnd;
    private final Set<UUID> countdownTitles = new HashSet<>();

    public ArenaAnnouncements(JavaPlugin plugin, String arenaId, String arenaName) {
        this.plugin = plugin;
        this.arenaId = arenaId;
        this.arenaName = arenaName;
        var configuration = plugin.getConfig();
        publicJoin = publicSetting(configuration, "join");
        publicStart = publicSetting(configuration, "start");
        publicEnd = publicSetting(configuration, "end");
    }

    public void joined(Player player, int count, int max, Collection<Player> participants) {
        Component body = Component.text(player.getName(), NamedTextColor.WHITE)
            .append(Component.text(" joined ", NamedTextColor.GRAY))
            .append(Component.text(arenaName, NamedTextColor.GOLD))
            .append(Component.text(" · " + count + "/" + max + " players", NamedTextColor.GRAY));
        sendOpen(publicJoin, body, count < max, participants);
    }

    public void countdown(int seconds, int count, int max, Collection<Player> participants) {
        if (seconds <= 0) return;
        Title title = Title.title(Component.text("STARTING IN " + seconds + "s", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text(arenaName, NamedTextColor.GOLD)
                .append(Component.text(" · " + count + "/" + max + " players", NamedTextColor.GRAY)),
            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1100), Duration.ofMillis(150)));
        showTitle(title, participants, true);
    }

    public void countdownPaused(int count, int minimum, Collection<Player> participants) {
        Component body = Component.text("Countdown paused in ", NamedTextColor.YELLOW)
            .append(Component.text(arenaName, NamedTextColor.GOLD))
            .append(Component.text(" · " + count + "/" + minimum + " players needed to start", NamedTextColor.GRAY));
        showTitle(Title.title(Component.text("COUNTDOWN PAUSED", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text(arenaName + " · " + count + "/" + minimum + " players needed", NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(150))), participants, true);
        send(false, participants, ignored -> ArenaMessages.info(body));
    }

    public void started(int count, Collection<Player> participants) {
        showTitle(Title.title(Component.text("FIGHT!", NamedTextColor.GREEN, TextDecoration.BOLD),
            Component.text(arenaName + " · " + count + " players", NamedTextColor.GOLD),
            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1100), Duration.ofMillis(250))), participants, false);
        Component body = Component.text(arenaName, NamedTextColor.GOLD)
            .append(Component.text(" has started with " + count + " players. Joining is now closed.", NamedTextColor.GREEN));
        send(publicStart, participants, ignored -> ArenaMessages.info(body));
    }

    public void finished(boolean cancelled, int waves, Collection<Player> participants) {
        for (Player participant : recipients(copyParticipants(participants)).values()) clearCountdown(participant);
        Component body = Component.text(arenaName, NamedTextColor.GOLD)
            .append(Component.text((cancelled ? " was cancelled after " : " finished after ") + waves + " completed waves.",
                cancelled ? NamedTextColor.RED : NamedTextColor.GREEN));
        send(publicEnd, participants, ignored -> ArenaMessages.info(body));
    }

    /** Clear only titles owned by this lobby; match/elimination titles must remain untouched. */
    public void clearCountdown(Player player) {
        if (player == null) return;
        try {
            if (countdownTitles.remove(player.getUniqueId()) && player.isOnline()) player.clearTitle();
        } catch (RuntimeException failure) {
            warn("Could not clear a countdown title for arena " + arenaId + "; match cleanup continues", failure);
        }
    }

    private void showTitle(Title title, Collection<Player> participants, boolean countdown) {
        for (Map.Entry<UUID, Player> entry : recipients(copyParticipants(participants)).entrySet()) {
            try {
                Player participant = entry.getValue();
                if (!participant.isOnline()) {
                    if (!countdown) countdownTitles.remove(entry.getKey());
                    continue;
                }
                if (countdown) countdownTitles.add(entry.getKey());
                participant.showTitle(title);
                if (!countdown) countdownTitles.remove(entry.getKey());
            } catch (RuntimeException failure) {
                warn("Could not display an arena title for " + arenaId + "; other participants are unaffected", failure);
            }
        }
    }

    private void sendOpen(boolean publicly, Component body, boolean hasRoom, Collection<Player> participants) {
        Set<UUID> playing = recipients(copyParticipants(participants)).keySet();
        Component status = hasRoom ? body : body.append(Component.text(" · Arena full", NamedTextColor.YELLOW));
        send(publicly, participants, recipient -> {
            Component message = status;
            if (hasRoom && !playing.contains(recipient.getUniqueId()) && recipient.hasPermission("mobarena.join")) {
                String command = "/ma join " + arenaId;
                message = message.append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(command, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to fill in the join command", NamedTextColor.GRAY))));
            }
            return ArenaMessages.info(message);
        });
    }

    private void send(boolean publicly, Collection<Player> participants, Function<Player, Component> message) {
        Collection<? extends Player> audience = resolveAudience(publicly, participants);
        for (Player recipient : recipients(audience).values()) {
            try {
                if (recipient.isOnline()) recipient.sendMessage(message.apply(recipient));
            } catch (RuntimeException failure) {
                warn("Could not send an announcement for arena " + arenaId + "; other recipients are unaffected", failure);
            }
        }
    }

    private Collection<? extends Player> resolveAudience(boolean publicly, Collection<Player> participants) {
        if (publicly) {
            try { return List.copyOf(plugin.getServer().getOnlinePlayers()); }
            catch (RuntimeException failure) {
                warn("Could not resolve the public arena audience; using participants for " + arenaId, failure);
            }
        }
        return copyParticipants(participants);
    }

    private List<Player> copyParticipants(Collection<Player> participants) {
        try { return List.copyOf(participants); }
        catch (RuntimeException failure) {
            warn("Could not resolve arena participants for announcement " + arenaId, failure);
            return List.of();
        }
    }

    private Map<UUID, Player> recipients(Collection<? extends Player> audience) {
        var recipients = new LinkedHashMap<UUID, Player>();
        for (Player player : audience) {
            try {
                recipients.putIfAbsent(player.getUniqueId(), player);
            } catch (RuntimeException failure) {
                warn("Could not identify an arena announcement recipient for " + arenaId, failure);
            }
        }
        return recipients;
    }

    private boolean publicSetting(ConfigurationSection configuration, String event) {
        String path = "announcements.public." + event;
        if (configuration.contains(path) && !configuration.isBoolean(path)) {
            warn(path + " must be true or false; using true for arena " + arenaId, null);
            return true;
        }
        return configuration.getBoolean(path, true);
    }

    private void warn(String message, Throwable failure) {
        try { plugin.getLogger().log(Level.WARNING, message, failure); }
        catch (RuntimeException ignored) { /* Optional presentation must never interrupt arena lifecycle. */ }
    }
}
