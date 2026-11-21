package al.dragon;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Locale;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;

record Settings(double baseChance, int pityKills, double healthBonus, double damageBonus,
                double highHealthThreshold, double highHealthBonus,
                boolean includeFirst, Range health, Range damage, double maxDamagePerHitPercent, IdleCritical idleCritical,
                boolean skills, int intervalTicks, int majorRecoveryTicks,
                double fireball, double shockwave, double laser, double dive, double finale,
                boolean crystals, boolean trail, int phantoms, String music, Tuning tuning, DragonRush rush, ExtraSkills extra,
                PodiumCharge podiumCharge) {
    static Settings load(ConfigurationSection config) {
        return new Settings(number(config, "egg.base-chance-percent", 20, 0, 100),
            (int) number(config, "egg.pity-kills", 10, 0, Integer.MAX_VALUE),
            number(config, "egg.bonus-per-health-multiplier", 5, 0, 100),
            number(config, "egg.bonus-per-damage-multiplier", 5, 0, 100),
            number(config, "egg.high-health-threshold", 5, 0.1, 5.12),
            number(config, "egg.high-health-bonus-percent", 10, 0, 100),
            config.getBoolean("dragon.include-first-dragon", true),
            Range.read(config, "dragon.health", 2, 1.5, 3, 5.12),
            Range.read(config, "dragon.damage", 1.5, 1, 2.5, 100),
            number(config, "dragon.max-damage-per-hit-percent", 20, 0, 100),
            IdleCritical.read(config),
            config.getBoolean("skills.enabled", true),
            (int) number(config, "skills.interval-seconds", 14, 4, 3600) * 20,
            (int) Math.round(number(config, "skills.major-recovery-seconds", 3, 0, 30) * 20),
            skill(config, "fireball", 8), skill(config, "shockwave", 6), skill(config, "laser", 10),
            skill(config, "dive", 9), skill(config, "finale", 36),
            config.getBoolean("skills.crystals.enabled", true), config.getBoolean("skills.trail.enabled", true),
            config.getBoolean("skills.phantoms.enabled", true) ? (int) number(config, "skills.phantoms.count", 3, 1, 6) : 0,
            config.getBoolean("skills.music.enabled", false) ? config.getString("skills.music.sound", "minecraft:music.dragon") : "",
            Tuning.read(config), DragonRush.read(config), ExtraSkills.read(config), PodiumCharge.read(config));
    }

    boolean wins(int misses, double healthMultiplier, double damageMultiplier, double roll) {
        return (pityKills > 0 && misses >= pityKills - 1) || roll < Math.min(100, baseChance
            + Math.max(0, healthMultiplier - 1) * healthBonus
            + Math.max(0, damageMultiplier - 1.5) * damageBonus
            + (healthMultiplier > highHealthThreshold ? highHealthBonus : 0));
    }

    private static double skill(ConfigurationSection config, String name, double fallback) {
        String path = "skills." + name;
        return config.getBoolean(path + ".enabled", true) ? number(config, path + ".damage", fallback, 0, 100) : -1;
    }

    private static double number(ConfigurationSection config, String key, double fallback, double min, double max) {
        double value = config.getDouble(key, fallback);
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    record IdleCritical(boolean enabled, long idleMillis, double multiplier) {
        private static IdleCritical read(ConfigurationSection config) {
            String path = "dragon.idle-critical.";
            return new IdleCritical(config.getBoolean(path + "enabled", true),
                Math.round(number(config, path + "idle-seconds", 30, .1, 3600) * 1000),
                number(config, path + "multiplier", 3, 1, 100));
        }
    }

    record AreaSkill(boolean enabled, double healthThreshold, int cooldownTicks, int warningTicks, double damage, double radius) {
        private static AreaSkill read(ConfigurationSection config, String name, double health, double cooldown, double warning, double damage, double radius) {
            String path = "skills." + name + ".";
            return new AreaSkill(config.getBoolean(path + "enabled", true), number(config, path + "health-percent", health, 1, 100) / 100,
                (int) Math.round(number(config, path + "cooldown-seconds", cooldown, 5, 3600) * 20),
                (int) Math.round(number(config, path + "warning-seconds", warning, .5, 10) * 20),
                number(config, path + "damage", damage, 0, 100), number(config, path + "radius", radius, 1, 8));
        }

        boolean available(double health) {
            return enabled && health <= healthThreshold;
        }
    }

    record TrackingBeam(AreaSkill skill, int durationTicks, double range) {
        private static TrackingBeam read(ConfigurationSection config) {
            return new TrackingBeam(AreaSkill.read(config, "tracking-laser", 30, 30, 1, 2, 1),
                (int) Math.round(number(config, "skills.tracking-laser.duration-seconds", 5, 1, 15) * 20),
                number(config, "skills.tracking-laser.range", 64, 8, 96));
        }
    }

    record ExtraSkills(AreaSkill levitation, AreaSkill breath, AreaSkill crystal, AreaSkill skyBite, double liftHeight,
                       TrackingBeam trackingLaser, boolean roarEnabled, double roarRange) {
        private static ExtraSkills read(ConfigurationSection config) {
            return new ExtraSkills(AreaSkill.read(config, "levitation-field", 85, 28, 1.5, 0, 5),
                AreaSkill.read(config, "breath-eruption", 65, 35, 1.75, 4, 4),
                AreaSkill.read(config, "crystal-burst", 40, 40, 2, 5, 3),
                AreaSkill.read(config, "sky-bite", 75, 45, 1, 1, 3),
                number(config, "skills.sky-bite.lift-height", 20, 4, 32), TrackingBeam.read(config),
                config.getBoolean("skills.ender-roar.enabled", true), number(config, "skills.ender-roar.range", 100, 1, 160));
        }
    }

    record PodiumCharge(boolean enabled, double health, int fuseTicks, double radius, double waveSpeed,
                        double blastDamage, int breathTicks, double breathDamage) {
        private static PodiumCharge read(ConfigurationSection config) {
            String path = "skills.podium-crystal.";
            return new PodiumCharge(config.getBoolean(path + "enabled", true), number(config, path + "health-percent", 55, 1, 100) / 100,
                (int) Math.round(number(config, path + "wave-fuse-seconds", 10, 1, 30) * 20),
                number(config, path + "blast-radius", 50, 1, 160), number(config, path + "wave-speed", .5, .1, 5),
                number(config, path + "blast-damage", 99_999, 0, 99_999),
                (int) Math.round(number(config, path + "breath-seconds", 20, 1, 120) * 20), number(config, path + "breath-damage", 2, 0, 100));
        }
    }

    record DragonRush(boolean enabled, boolean realDragons, int warningTicks, int orbitTicks, double damage, double radius) {
        private static DragonRush read(ConfigurationSection config) {
            String key = "skills.dragon-rush.";
            return new DragonRush(config.getBoolean(key + "enabled", true), config.getBoolean(key + "real-dragons", false),
                (int) Math.round(number(config, key + "warning-seconds", 2, .5, 10) * 20),
                (int) Math.round(number(config, key + "orbit-seconds", 3, 0, 15) * 20),
                number(config, key + "damage", 6, 0, 100), number(config, key + "radius", 3.5, 1, 12));
        }
    }

    record Tuning(double damageScale, double cooldownScale, double arenaRadius, double titleRadius, int titleTicks, int titleFadeTicks, double soundRadius,
                  double fireballHp, double laserHp, double diveHp, double finaleHp,
                  double fireballRadius, double shockwaveRadius, double laserRadius, double diveRadius, double finaleRadius,
                  int fireballWarning, int shockwaveWarning, int laserWarning, int diveWarning, int finaleWarning,
                  int diveCount, int phantomSeconds) {
        static Tuning read(ConfigurationSection config) {
            String difficulty = config.getString("difficulty.preset", "normal").toLowerCase(Locale.ROOT);
            double damage = switch (difficulty) { case "easy" -> 0.75; case "hard" -> 1.25; default -> 1; };
            double cooldown = switch (difficulty) { case "easy" -> 1.3; case "hard" -> 0.8; default -> 1; };
            return new Tuning(damage * number(config, "difficulty.skill-damage-multiplier", 1, 0, 10), cooldown,
                number(config, "skills.arena-radius", 112, 16, 128), number(config, "notifications.title-radius", 50, 0, 128),
                ticks(config, "notifications.title-seconds", 1, 0, 10), ticks(config, "notifications.title-fade-out-seconds", 2, 0, 10),
                number(config, "notifications.death-sound-radius", 128, 0, 128),
                threshold(config, "fireball", 66.67), threshold(config, "laser", 33.33),
                threshold(config, "dive", 66.67), threshold(config, "finale", 10),
                radius(config, "fireball", 4), radius(config, "shockwave", 16), radius(config, "laser", 1.45),
                radius(config, "dive", 5), radius(config, "finale", 20),
                warning(config, "fireball", 1.25), warning(config, "shockwave", 1.25), warning(config, "laser", 1.5),
                warning(config, "dive", 1.25), warning(config, "finale", 4),
                (int) number(config, "skills.dive.combo-count", 3, 1, 6),
                (int) number(config, "skills.phantoms.lifetime-seconds", 30, 5, 120));
        }

        Title.Times titleTimes() {
            return Title.Times.times(Duration.ZERO, Duration.ofMillis(titleTicks * 50L), Duration.ofMillis(titleFadeTicks * 50L));
        }

        private static double threshold(ConfigurationSection config, String key, double fallback) {
            return number(config, "skills." + key + ".health-percent", fallback, 1, 100) / 100;
        }

        private static double radius(ConfigurationSection config, String key, double fallback) {
            return number(config, "skills." + key + ".radius", fallback, 0.5, 32);
        }

        private static int warning(ConfigurationSection config, String key, double fallback) {
            return ticks(config, "skills." + key + ".warning-seconds", fallback, 0.5, 15);
        }

        private static int ticks(ConfigurationSection config, String key, double fallback, double min, double max) {
            return (int) Math.round(number(config, key, fallback, min, max) * 4) * 5;
        }
    }

    record Range(double fixed, boolean random, double min, double max) {
        static Range read(ConfigurationSection config, String path, double fixed, double min, double max, double cap) {
            double low = number(config, path + ".min", min, 0.1, cap);
            double high = number(config, path + ".max", max, 0.1, cap);
            return new Range(number(config, path + ".fixed", fixed, 0.1, cap),
                config.getBoolean(path + ".random", true), Math.min(low, high), Math.max(low, high));
        }

        double roll() {
            return !random ? fixed : min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);
        }
    }
}
