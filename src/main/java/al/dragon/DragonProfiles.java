package al.dragon;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class DragonProfiles {
    static final List<String> IDS = List.of("scout", "storm", "abyss", "sovereign");

    private static final Map<String, List<String>> ADDED_SKILLS = Map.of(
        "scout", List.of("rift-mark", "crystal-revival", "ender-roar"), "storm", List.of("thunder", "thunderstorm", "thunder-chain", "electric-aura"),
        "abyss", List.of("gravity-well"), "sovereign", List.of("end-cross"));

    record Profile(String id, double spawnChance, double eggChance, Settings combat, DragonRewards.Policy rewards) {}

    private final Map<String, Profile> profiles;
    private final int scoutStreakLimit;
    private final double nonScoutWeight;

    private DragonProfiles(Map<String, Profile> profiles, int scoutStreakLimit) {
        this.profiles = Map.copyOf(profiles);
        this.nonScoutWeight = IDS.stream().filter(id -> !id.equals("scout"))
            .mapToDouble(id -> profiles.get(id).spawnChance()).sum();
        this.scoutStreakLimit = nonScoutWeight > 0 ? scoutStreakLimit : 0;
    }

    static DragonProfiles load(JavaPlugin plugin, YamlConfiguration config) throws IOException, InvalidConfigurationException {
        Map<String, Profile> profiles = new LinkedHashMap<>();
        double total = 0;
        for (String id : IDS) {
            String resource = "dragons/" + id + ".yml";
            File file = new File(plugin.getDataFolder(), resource);
            if (!file.exists()) plugin.saveResource(resource, false);
            var data = new YamlConfiguration();
            data.load(file);
            List<String> missing = ADDED_SKILLS.get(id).stream().filter(key -> !data.contains("skills." + key)).toList();
            var missingRhythm = List.of("skills.min-cast-gap-seconds", "skills.recovery-seconds", "skills.major-cooldown-seconds")
                .stream().filter(key -> !data.contains(key)).toList();
            if (!missing.isEmpty() || !missingRhythm.isEmpty()) try (var reader = new InputStreamReader(plugin.getResource(resource), StandardCharsets.UTF_8)) {
                var bundled = new YamlConfiguration();
                bundled.load(reader);
                for (String key : missingRhythm) data.set(key, bundled.get(key));
                for (String key : missing) {
                    String path = "skills." + key;
                    data.createSection(path, bundled.getConfigurationSection(path).getValues(false));
                }
            }
            double spawn = percent(config, "dragons." + id + ".spawn-chance-percent");
            double egg = percent(config, "dragons." + id + ".egg-chance-percent");
            profiles.put(id, new Profile(id, spawn, egg, Settings.load(data), DragonRewards.Policy.load(data, resource)));
            total += spawn;
        }
        if (Math.abs(total - 100) > 1e-9) throw new IllegalArgumentException("Dragon spawn chances must add up to 100");
        Object rawLimit = config.get("random-summon.scout-streak-limit", 2);
        if (!(rawLimit instanceof Integer limit) || limit < 0) {
            throw new IllegalArgumentException("random-summon.scout-streak-limit must be a non-negative integer");
        }
        return new DragonProfiles(profiles, limit);
    }

    Profile get(String id) {
        return id == null ? null : profiles.get(id);
    }

    Profile select(double unitRoll, int scoutStreak) {
        boolean skipScout = scoutStreakLimit > 0 && scoutStreak >= scoutStreakLimit;
        double roll = unitRoll * (skipScout ? nonScoutWeight : 100);
        Profile last = null;
        for (String id : IDS) {
            if (skipScout && id.equals("scout")) continue;
            Profile profile = profiles.get(id);
            if (profile.spawnChance() == 0) continue;
            last = profile;
            if (roll < profile.spawnChance()) return profile;
            roll -= profile.spawnChance();
        }
        return last;
    }

    int nextScoutStreak(String selected, int previous) {
        if (scoutStreakLimit == 0 || !selected.equals("scout")) return 0;
        return Math.min(previous, scoutStreakLimit - 1) + 1;
    }

    private static double percent(YamlConfiguration config, String path) {
        Object raw = config.get(path);
        double value = raw instanceof Number number ? number.doubleValue() : Double.NaN;
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(path + " must be a number from 0 to 100");
        }
        return value;
    }
}
