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

    Language(JavaPlugin plugin) {
        this(plugin, false);
    }

    Language(JavaPlugin plugin, boolean strict) {
        plugin.saveDefaultConfig();
        String selected = plugin.getConfig().getString("language", "zh_CN");
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
        if (strict) {
            messages = new YamlConfiguration();
            try {
                messages.load(file);
            } catch (IOException | InvalidConfigurationException exception) {
                throw new IllegalStateException("Could not load " + resource, exception);
            }
        } else {
            messages = YamlConfiguration.loadConfiguration(file);
        }
        defaults = template(plugin, resource);

        var config = plugin.getConfig();
        var source = template(plugin, "config.yml");
        String versionKey = "config-version";
        int bundledVersion = source.getInt(versionKey);
        if (config.contains(versionKey, true) && config.getInt(versionKey, -1) != bundledVersion) {
            plugin.getLogger().warning("配置文件版本与插件内置版本不一致（当前：" + config.get(versionKey)
                + "，内置：" + bundledVersion + "）。请备份 config.yml 后核对配置。");
        }
        boolean changed = false;
        for (String key : source.getKeys(true)) {
            if (!config.contains(key, true) && !source.isConfigurationSection(key)) {
                config.set(key, source.get(key));
                changed = true;
            }
            if (config.contains(key, true) && !config.getComments(key).equals(source.getComments(key))) {
                config.setComments(key, source.getComments(key));
                changed = true;
            }
        }
        if (changed) plugin.saveConfig();
    }

    private static YamlConfiguration template(JavaPlugin plugin, String resource) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(plugin.getResource(resource), StandardCharsets.UTF_8));
    }

    String text(String key) {
        return messages.getString(key, defaults.getString(key, key));
    }

    Component message(String key, NamedTextColor color) {
        return Component.text(text(key), color);
    }
}
