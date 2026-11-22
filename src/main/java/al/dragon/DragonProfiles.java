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
        "scout", List.of("rift-mark", "crystal-revival"), "storm", List.of("thunder", "thunderstorm", "thunder-chain", "electric-aura"),
        "abyss", List.of("gravity-well"), "sovereign", List.of("end-cross"));

    record Profile(String id, double spawnChance, double eggChance, Settings combat, DragonRewards.Policy rewards) {}

    private final Map<String, Profile> profiles;

    private DragonProfiles(Map<String, Profile> profiles) {
        this.profiles = Map.copyOf(profiles);
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
        return new DragonProfiles(profiles);
    }

    Profile get(String id) {
        return id == null ? null : profiles.get(id);
    }

    Profile select(double roll) {
        Profile last = null;
        for (String id : IDS) {
            Profile profile = profiles.get(id);
            if (profile.spawnChance() == 0) continue;
            last = profile;
            if (roll < profile.spawnChance()) return profile;
            roll -= profile.spawnChance();
        }
        return last;
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
