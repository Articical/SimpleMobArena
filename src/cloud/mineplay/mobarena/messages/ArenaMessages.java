package cloud.mineplay.mobarena.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared chat presentation. All strings remain literal, including names and error details. */
public final class ArenaMessages {
    private static final Component PREFIX = Component.text("MobArena", NamedTextColor.GOLD, TextDecoration.BOLD)
        .append(Component.text(" » ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
    // Match command names, not paths, URLs, fractions, or formatting tags. No click/run events.
    private static final Pattern COMMAND = Pattern.compile("(?<![\\p{Alnum}_./:<>-])/[A-Za-z][A-Za-z0-9_:-]*");

    private ArenaMessages() {}

    public static Component info(String text) { return format(text, NamedTextColor.GRAY); }
    public static Component success(String text) { return format(text, NamedTextColor.GREEN); }
    public static Component warning(String text) { return format(text, NamedTextColor.YELLOW); }
    public static Component error(String text) { return format(text, NamedTextColor.RED); }

    /** Wrap an explicitly constructed component; existing child colours are preserved. */
    public static Component info(Component body) {
        return Component.empty().append(PREFIX).append(body.colorIfAbsent(NamedTextColor.GRAY));
    }

    public static void sendInfo(CommandSender sender, String text) { sender.sendMessage(info(text)); }

    private static Component format(String text, NamedTextColor colour) {
        if (text == null || text.isBlank()) text = "An unexpected error occurred. Check the server console.";
        TextComponent.Builder body = Component.text().color(colour).decoration(TextDecoration.BOLD, false);
        Matcher commands = COMMAND.matcher(text);
        int position = 0;
        while (commands.find()) {
            body.append(Component.text(text.substring(position, commands.start())));
            body.append(Component.text(commands.group(), NamedTextColor.AQUA));
            position = commands.end();
        }
        body.append(Component.text(text.substring(position)));
        return Component.empty().append(PREFIX).append(body.build());
    }
}
