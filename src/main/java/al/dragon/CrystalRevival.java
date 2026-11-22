package al.dragon;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.persistence.PersistentDataType;

final class CrystalRevival {
    private static final NamespacedKey SPENT = ALDragon.storedKey("crystal_revival_used");
    private static final NamespacedKey REVIVED = ALDragon.storedKey("revived_healing_crystal");

    record Options(Settings.AreaSkill cast, double healPerSecond) {
        static Options read(org.bukkit.configuration.ConfigurationSection config) {
            double heal = config.getDouble("skills.crystal-revival.heal-per-second", 2);
            return new Options(Settings.AreaSkill.added(config, "crystal-revival", 55, 30, 2, 0, 2),
                Double.isFinite(heal) ? Math.clamp(heal, 0, 20) : 2);
        }
    }

    private final Location spot;
    private EnderCrystal crystal;
    private boolean finished;
    private int tick;

    private CrystalRevival(Location spot) {
        this.spot = spot;
    }

    static boolean isRevived(EnderCrystal crystal) {
        return crystal.getPersistentDataContainer().has(REVIVED);
    }

    static boolean available(EnderDragon dragon, Options options, double health) {
        return options.cast().available(health) && !dragon.getPersistentDataContainer().has(SPENT);
    }

    static CrystalRevival choose(World world) {
        List<Location> choices = new ArrayList<>();
        for (Location spot : NativeSpikes.positions(world)) if (vacant(spot)) choices.add(spot);
        return choices.isEmpty() ? null : new CrystalRevival(choices.get(ThreadLocalRandom.current().nextInt(choices.size())));
    }

    boolean tickCast(EnderDragon dragon, Options options, List<Player> players, Language language) {
        if (!DragonParticles.loaded(spot)) return true;
        if (tick++ < options.cast().warningTicks()) {
            if (tick % 5 == 1) {
                DragonSkills.ring(spot, 1.6, 14, DragonSkills.SAFE);
                spot.getWorld().spawnParticle(Particle.REVERSE_PORTAL, spot.clone().add(0, 1, 0), 16, .55, .8, .55, .12);
            }
            return false;
        }
        if (!vacant(spot)) return true;
        crystal = spot.getWorld().spawn(spot, EnderCrystal.class, entity -> {
            entity.setPersistent(false);
            entity.setShowingBottom(true);
            entity.getPersistentDataContainer().set(REVIVED, PersistentDataType.BYTE, (byte) 1);
        });
        if (crystal.isValid()) {
            dragon.getPersistentDataContainer().set(SPENT, PersistentDataType.BYTE, (byte) 1);
            for (Player player : players) player.sendActionBar(language.message("crystal-revival-done", NamedTextColor.AQUA));
            spot.getWorld().playSound(spot, Sound.BLOCK_BEACON_ACTIVATE, 2, 1.4f);
        }
        return true;
    }

    void tickHealing(EnderDragon dragon, Options options) {
        if (finished || crystal == null) return;
        if (!crystal.isValid()) {
            finished = true;
            crystal = null;
            return;
        }
        var health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health == null || dragon.getHealth() <= 0) return;
        boolean inRange = dragon.getLocation().distanceSquared(spot) <= 112 * 112;
        boolean needsHealing = dragon.getHealth() < health.getValue() && inRange;
        Location beam = needsHealing ? dragon.getLocation() : null;
        if (beam != null || crystal.getBeamTarget() != null) crystal.setBeamTarget(beam);
        if (!needsHealing || options.healPerSecond() <= 0) return;
        var event = new EntityRegainHealthEvent(dragon, options.healPerSecond(), EntityRegainHealthEvent.RegainReason.ENDER_CRYSTAL);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled() && Double.isFinite(event.getAmount()) && event.getAmount() > 0 && dragon.isValid() && !dragon.isDead()) {
            dragon.setHealth(Math.min(health.getValue(), dragon.getHealth() + event.getAmount()));
        }
    }

    void cleanup() {
        if (crystal != null && crystal.isValid()) crystal.remove();
    }

    private static boolean vacant(Location spot) {
        return DragonParticles.loaded(spot) && DragonParticles.loaded(spot.clone().subtract(0, 1, 0))
            && spot.clone().subtract(0, 1, 0).getBlock().getType() == Material.BEDROCK
            && spot.getBlock().isPassable()
            && spot.getWorld().getNearbyEntitiesByType(EnderCrystal.class, spot, 1.8, 2, 1.8).isEmpty();
    }

    private record NativeSpikes(Method handle, Method spikes, Method centerX, Method centerZ, Method height) {
        private static final NativeSpikes ACCESS = load();
        private static boolean failed;

        private static NativeSpikes load() {
            try {
                var craft = Class.forName("org.bukkit.craftbukkit.CraftWorld");
                var level = Class.forName("net.minecraft.world.level.WorldGenLevel");
                var feature = Class.forName("net.minecraft.world.level.levelgen.feature.EndSpikeFeature");
                var spike = Class.forName(feature.getName() + "$EndSpike");
                return new NativeSpikes(craft.getMethod("getHandle"), feature.getMethod("getSpikesForLevel", level),
                    spike.getMethod("getCenterX"), spike.getMethod("getCenterZ"), spike.getMethod("getHeight"));
            } catch (ReflectiveOperationException | LinkageError exception) {
                Bukkit.getLogger().warning("ALSuperDragon crystal revival unavailable: native End spike metadata not found");
                return null;
            }
        }

        private static List<Location> positions(World world) {
            if (ACCESS == null || failed) return List.of();
            try {
                List<?> nativeSpikes = (List<?>) ACCESS.spikes.invoke(null, ACCESS.handle.invoke(world));
                List<Location> positions = new ArrayList<>(nativeSpikes.size());
                for (Object spike : nativeSpikes) {
                    positions.add(new Location(world, (int) ACCESS.centerX.invoke(spike) + .5,
                        (int) ACCESS.height.invoke(spike) + 1, (int) ACCESS.centerZ.invoke(spike) + .5));
                }
                return positions;
            } catch (ReflectiveOperationException | LinkageError exception) {
                failed = true;
                Bukkit.getLogger().warning("ALSuperDragon crystal revival unavailable: could not read End spike coordinates");
                return List.of();
            }
        }
    }
}
