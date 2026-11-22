package al.dragon;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class Language {
    private final YamlConfiguration messages;
    private final YamlConfiguration defaults;

    Language(JavaPlugin plugin, YamlConfiguration config) {
        String selected = config.getString("language", "zh_CN");
        String locale = switch (selected.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "en", "en_us" -> "en_US";
            case "zh", "zh_cn" -> "zh_CN";
            default -> {
                plugin.getLogger().warning("不支持的语言：" + selected + "，将使用简体中文（zh_CN）。");
                yield "zh_CN";
            }
        };
        for (String name : new String[]{"zh_CN", "en_US"}) {
            String resource = "lang/" + name + ".yml";
            if (!new File(plugin.getDataFolder(), resource).exists()) plugin.saveResource(resource, false);
        }
        String resource = "lang/" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), resource);
        messages = new YamlConfiguration();
        try {
            messages.load(file);
            try (var reader = new InputStreamReader(plugin.getResource(resource), StandardCharsets.UTF_8)) {
                defaults = new YamlConfiguration();
                defaults.load(reader);
            }
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not load " + resource, exception);
        }
    }

    String text(String key) {
        return messages.getString(key, defaults.getString(key, key));
    }

    Component message(String key, NamedTextColor color) {
        return Component.text(text(key), color);
    }
}
