package cloud.mineplay.mobarena.messages;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.ConfigurationSection;

/** One countdown per arena. Missed thresholds collapse into a single current-time notice. */
public final class CountdownSchedule {
    private static final List<Integer> DEFAULTS = List.of(30,15,10,5,3,2,1);
    private final Set<Integer> thresholds;
    private int previous;

    public CountdownSchedule(ConfigurationSection config) {
        Object value = config.get("announcements.countdown-seconds",DEFAULTS);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("announcements.countdown-seconds must be a list of seconds");
        Set<Integer> parsed = new TreeSet<>();
        for (Object entry : list) {
            if (!(entry instanceof Integer seconds) || seconds < 1 || seconds > 600)
                throw new IllegalArgumentException("announcements.countdown-seconds entries must be integers from 1 to 600");
            parsed.add(seconds);
        }
        thresholds = Set.copyOf(parsed);
    }

    public void reset(int seconds) { previous = Math.max(0,seconds); }

    public boolean advance(int seconds) {
        int before = previous;
        previous = Math.max(0,seconds);
        return seconds > 0 && seconds < before
            && thresholds.stream().anyMatch(threshold -> threshold < before && threshold >= seconds);
    }
}
