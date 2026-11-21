package al.dragon;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class PodiumCrystal {
    private final Location center;
    private final EnderCrystal crystal;
    private final Settings.PodiumCharge settings;
    private final Language language;
    private final Set<UUID> passed = new HashSet<>();
    private final Set<UUID> blocked = new HashSet<>();
    private int remaining;
    private int breathAge;
    private double waveRadius;
    private boolean detonated;

    PodiumCrystal(Location eggSpot, Settings.PodiumCharge settings, Language language) {
        this.settings = settings;
        this.language = language;
        center = eggSpot.clone();
        if (center.getBlock().getType() == Material.DRAGON_EGG) center.add(0, 1, 0);
        remaining = settings.fuseTicks();
        crystal = DragonParticles.loaded(center) ? center.getWorld().spawn(center, EnderCrystal.class, entity -> {
            entity.setShowingBottom(false);
            entity.setGlowing(true);
            entity.setPersistent(false);
        }) : null;
    }

    boolean isValid() {
        return crystal != null && crystal.isValid();
    }

    boolean matches(Entity entity) {
        return crystal != null && crystal.equals(entity);
    }

    boolean isResolving() {
        return detonated ? waveRadius < settings.radius() : isValid();
    }

    void defuse() {
        if (!isValid()) return;
        crystal.remove();
        for (Player player : victims()) player.sendActionBar(language.message("podium-crystal-defused", NamedTextColor.GREEN));
    }

    boolean tick(boolean dragonAlive) {
        if (!dragonAlive) {
            cleanup();
            return true;
        }
        if (detonated) {
            if (waveRadius < settings.radius()) {
                advanceWave();
                return false;
            }
            if (++breathAge > settings.breathTicks()) return true;
            if (breathAge % 10 == 0) for (Player player : victims()) {
                if (blocked.contains(player.getUniqueId())) continue;
                player.spawnParticle(Particle.DRAGON_BREATH, player.getLocation().add(0, .6, 0), 7, 3, .35, 3, .025, 1f);
                if (breathAge % 20 == 0 && settings.breathDamage() > 0) {
                    player.damage(settings.breathDamage(), DamageSource.builder(DamageType.DRAGON_BREATH).build());
                }
            }
            return false;
        }
        if (!isValid()) {
            cleanup();
            return true;
        }
        if (remaining > 0) {
            if (remaining % 10 == 0) renderRing(settings.radius(), spot -> spot.getWorld().spawnParticle(Particle.END_ROD, spot, 1));
            if (remaining % 5 == 0) for (Player player : victims()) {
                int seconds = (remaining + 19) / 20;
                player.sendActionBar(Component.text(
                    language.text("podium-wave-countdown").replace("{seconds}", Integer.toString(seconds)), NamedTextColor.RED));
            }
            remaining--;
            return false;
        }
        detonated = true;
        crystal.remove();
        var world = center.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 5, .65f);
        advanceWave();
        return false;
    }

    private void advanceWave() {
        double previous = waveRadius;
        waveRadius = Math.min(settings.radius(), waveRadius + settings.waveSpeed());
        DragonParticles particles = new DragonParticles(center.getWorld().getPlayers());
        renderRing(waveRadius, particles::groundWave);
        for (Player player : victims()) {
            double distance = Math.sqrt(horizontalDistanceSquared(player.getLocation()));
            if (distance < previous || distance > waveRadius || !passed.add(player.getUniqueId())) continue;
            if (blocksWave(player)) {
                blocked.add(player.getUniqueId());
                player.setHealth(player.getHealth() * .1);
                player.sendActionBar(language.message("podium-wave-blocked", NamedTextColor.GREEN));
            } else if (settings.blastDamage() > 0) {
                player.damage(settings.blastDamage(), DamageSource.builder(DamageType.EXPLOSION).build());
            }
        }
    }

    private boolean blocksWave(Player player) {
        if (!player.isBlocking() || player.getActiveItem().getType() != Material.SHIELD) return false;
        Location eyes = player.getEyeLocation();
        var facing = eyes.getDirection().setY(0);
        var toward = center.toVector().subtract(eyes.toVector()).setY(0);
        return toward.lengthSquared() < 1e-6 || (facing.lengthSquared() > 1e-6 && facing.normalize().dot(toward.normalize()) >= .5);
    }

    private void renderRing(double radius, Consumer<Location> emit) {
        int points = Math.clamp((int) Math.ceil(2 * Math.PI * radius), 12, 144);
        for (int point = 0; point < points; point++) {
            double angle = 2 * Math.PI * point / points;
            Location spot = center.clone().add(radius * Math.cos(angle), 0, radius * Math.sin(angle));
            if (!DragonParticles.loaded(spot)) continue;
            var ground = spot.getWorld().getHighestBlockAt(spot, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (ground.getType().isAir()) continue;
            spot.setY(ground.getY() + 1.2);
            emit.accept(spot);
        }
    }

    void cleanup() {
        if (isValid()) crystal.remove();
    }

    private List<Player> victims() {
        double radiusSquared = settings.radius() * settings.radius();
        return center.getWorld().getPlayers().stream().filter(player -> !player.isDead()
            && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
            && horizontalDistanceSquared(player.getLocation()) <= radiusSquared).toList();
    }

    private double horizontalDistanceSquared(Location location) {
        double offsetX = location.getX() - center.getX();
        double offsetZ = location.getZ() - center.getZ();
        return offsetX * offsetX + offsetZ * offsetZ;
    }
}
