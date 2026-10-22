package cloud.mineplay.mobarena.mobs;

import org.bukkit.configuration.ConfigurationSection;

/** Validated, bounded settings for the optional skeleton variant. */
record RapidFireSettings(double chance, int minimumWave, int intervalTicks) {
    static RapidFireSettings read(ConfigurationSection mobSettings) {
        if (mobSettings == null || !mobSettings.contains("rapid-fire")) return null;
        ConfigurationSection section = mobSettings.getConfigurationSection("rapid-fire");
        if (section == null) throw new IllegalArgumentException("rapid-fire must be a section.");
        double chance = number(section, "chance", .02, 0, 1, false);
        int minimumWave = (int) number(section, "minimum-wave", 8, 3, 10_000, true);
        int intervalTicks = (int) number(section, "interval-ticks", 8, 4, 100, true);
        return new RapidFireSettings(chance, minimumWave, intervalTicks);
    }

    private static double number(ConfigurationSection section, String key, double fallback,
                                 double minimum, double maximum, boolean integral) {
        Object value = section.get(key);
        double result = value == null ? fallback : value instanceof Number number ? number.doubleValue() : Double.NaN;
        if (!Double.isFinite(result) || result < minimum || result > maximum || integral && result != Math.rint(result)) {
            String bounds = integral ? (int) minimum + " and " + (int) maximum : minimum + " and " + maximum;
            throw new IllegalArgumentException("rapid-fire." + key + " must be "
                    + (integral ? "a whole number" : "a number") + " between " + bounds + ".");
        }
        return result;
    }
}
