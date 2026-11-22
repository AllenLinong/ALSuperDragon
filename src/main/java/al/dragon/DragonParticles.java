package al.dragon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class DragonParticles {
    private static final Color PINK = Color.fromRGB(255, 0, 248);
    private static final Color BLUE = Color.fromRGB(0, 26, 255);
    private static final Particle.DustTransition TRAIL = new Particle.DustTransition(PINK, BLUE, 3);
    private static final Particle.DustTransition ARC = new Particle.DustTransition(Color.fromRGB(45, 138, 255), Color.fromRGB(218, 250, 255), 1.25f);
    private static final Particle.DustTransition FIREBALL = new Particle.DustTransition(PINK, BLUE, 2);
    private static final Particle.DustOptions LASER = new Particle.DustOptions(Color.fromRGB(255, 19, 248), 1);
    private static final Particle.DustOptions WAVE = new Particle.DustOptions(Color.fromRGB(255, 19, 249), 1.2f);
    private static final Set<Material> LASER_STOPS = Set.of(Material.OBSIDIAN, Material.BEDROCK,
        Material.END_GATEWAY, Material.END_PORTAL, Material.END_PORTAL_FRAME, Material.NETHER_PORTAL,
        Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
        Material.BARRIER, Material.END_STONE);
    private static final float[] OFFSETS = {-5, -4.5f, -4, -3.5f, -3, 0, 3, 3.5f, 4, 4.5f, 5};

    private final List<Player> viewers;
    private List<Location> positions;
    private final List<Player> receivers = new ArrayList<>();

    DragonParticles(List<Player> viewers) {
        this.viewers = viewers;
    }

    private <T> void emit(Particle particle, Location spot, int count, double dx, double dy, double dz,
                          double speed, T data, double range, boolean force) {
        if (positions == null) positions = viewers.stream().map(Player::getLocation).toList();
        receivers.clear();
        for (int index = 0; index < viewers.size(); index++) {
            if (positions.get(index).distanceSquared(spot) <= range * range) receivers.add(viewers.get(index));
        }
        if (!receivers.isEmpty()) spot.getWorld().spawnParticle(particle, receivers, null, spot.getX(), spot.getY(), spot.getZ(),
            count, dx, dy, dz, speed, particle == Particle.DRAGON_BREATH && data == null ? 1f : data, force);
    }

    private void directional(Particle particle, Location spot, Vector direction, double magnitude, double speed, double range) {
        emit(particle, spot, 0, direction.getX() * magnitude, direction.getY() * magnitude,
            direction.getZ() * magnitude, speed, null, range, true);
    }

    void trail(Location spot) {
        emit(Particle.DUST_COLOR_TRANSITION, spot, 2, .6, .6, .6, 1, TRAIL, 256, true);
        emit(Particle.DRAGON_BREATH, spot, 2, .3, .3, .3, .02, null, 32, false);
    }

    void electricAura(Location center, int age) {
        if (!loaded(center)) return;
        if (positions == null) positions = viewers.stream().map(Player::getLocation).toList();
        receivers.clear();
        for (int index = 0; index < viewers.size(); index++) {
            if (positions.get(index).distanceSquared(center) <= 64 * 64) receivers.add(viewers.get(index));
        }
        if (receivers.isEmpty()) return;
        double yaw = Math.toRadians(center.getYaw());
        double headingX = -Math.sin(yaw), headingZ = Math.cos(yaw);
        var world = center.getWorld();
        double phase = age * .18;
        for (int side : new int[]{-1, 1}) {
            for (int segment = 0; segment < 5; segment++) {
                double along = (segment - 2) * 2.1;
                double across = side * (2.1 + .7 * Math.sin(phase + segment * 1.6));
                double x = center.getX() + headingX * along + headingZ * across;
                double y = center.getY() + 2.3 + .8 * Math.sin(phase + segment * 1.4 + side);
                double z = center.getZ() + headingZ * along - headingX * across;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, receivers, null, x, y, z, 1, 0, 0, 0, 0, ARC, true);
                if (segment == 2) world.spawnParticle(Particle.ELECTRIC_SPARK, receivers, null, x, y, z, 2, .2, .35, .2, .025, null, true);
            }
        }
    }

    void phaseBurst(Location spot) {
        emit(Particle.DRAGON_BREATH, spot, 100, 0, 0, 0, .2, null, 128, true);
        emit(Particle.DRAGON_BREATH, spot, 100, 0, 0, 0, .4, null, 128, true);
    }

    void fireball(Location spot) {
        emit(Particle.DUST_COLOR_TRANSITION, spot, 1, 0, 0, 0, 1, FIREBALL, 128, true);
        emit(Particle.LARGE_SMOKE, spot, 1, 0, 0, 0, .04, null, 128, false);
        emit(Particle.DRAGON_BREATH, spot, 2, 0, 0, 0, .02, null, 32, false);
        emit(Particle.ELECTRIC_SPARK, spot, 3, .3, .3, .3, 0, null, 128, true);
    }

    void fireballHit(Location spot) {
        emit(Particle.DRAGON_BREATH, spot, 30, 0, 0, 0, .8, null, 128, false);
        emit(Particle.DRAGON_BREATH, spot, 30, 0, 0, 0, .4, null, 128, true);
        emit(Particle.LARGE_SMOKE, spot, 10, 0, 0, 0, .2, null, 128, true);
    }

    void laser(Location spot, Vector direction) {
        var random = ThreadLocalRandom.current();
        emit(Particle.DUST, spot, 2, .3, .3, .3, 0, LASER, 32, true);
        emit(Particle.ELECTRIC_SPARK, spot, 1, .3, .3, .3, 0, null, 128, true);
        if (random.nextInt(10) == 0) emit(Particle.DRAGON_BREATH, spot, 1, .3, .3, .3, .1, null, 128, true);
        if (random.nextBoolean()) directional(Particle.END_ROD, spot, direction, 1e22, 9e-22, 256);
    }

    void laserHit(Location spot) {
        Location above = spot.clone().add(0, 1, 0);
        above.setY(above.getBlockY());
        emit(Particle.END_ROD, above, 2, 0, 0, 0, .2, null, 256, true);
        emit(Particle.END_ROD, above, 2, 0, 0, 0, .4, null, 256, true);
        emit(Particle.DRAGON_BREATH, above.add(0, 1, 0), 10, 0, 0, 0, .2, null, 32, false);
    }

    void laserCharge(Location spot) {
        emit(Particle.DRAGON_BREATH, spot, 10, 0, 0, 0, .15, null, 128, true);
    }

    void trackingBeam(Location origin, Vector direction, double length, Location end, boolean blocked) {
        int segments = Math.max(1, (int) Math.ceil(length));
        for (int index = 0; index <= segments; index++) {
            Location point = origin.clone().add(direction.clone().multiply(length * index / segments));
            laser(point, direction);
        }
        if (blocked) laserHit(end);
    }

    void laserEntityHit(Location position) {
        Location spot = position.clone().add(0, 1, 0);
        emit(Particle.EXPLOSION, spot, 1, 0, 0, 0, 0, null, 128, true);
        for (double speed : new double[] {1, .5, .25}) emit(Particle.END_ROD, spot, 30, 0, 0, 0, speed, null, 128, true);
        emit(Particle.FLASH, spot, 6, 0, 0, 0, .25, Color.WHITE, 128, true);
        emit(Particle.DRAGON_BREATH, spot, 10, 0, 0, 0, .3, null, 32, false);
    }

    void diveWarning(Location spot, float yaw, float pitch) {
        var random = ThreadLocalRandom.current();
        Location rotation = spot.clone();
        for (int angle = 20; angle <= 360; angle += 20) {
            if (!random.nextBoolean()) continue;
            rotation.setYaw(yaw + angle);
            rotation.setPitch(0);
            Location emission = spot.clone().add(rotation.getDirection().multiply(5));
            rotation.setPitch(pitch);
            directional(Particle.DRAGON_BREATH, emission, rotation.getDirection(), 1e23, 5e-24, 64);
        }
    }

    static boolean loaded(Location spot) {
        return spot.getBlockY() >= spot.getWorld().getMinHeight() && spot.getBlockY() < spot.getWorld().getMaxHeight()
            && spot.getWorld().isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4);
    }

    static boolean stopsLaser(Location spot) {
        return LASER_STOPS.contains(spot.getBlock().getType());
    }

    void groundWave(Location spot) {
        emit(Particle.DUST, spot, 1, .5, .05, .5, 0, WAVE, 48, true);
        emit(Particle.ELECTRIC_SPARK, spot, 2, .5, .05, .5, 0, null, 128, true);
    }

    void groundWaveEnd(Location spot) {
        emit(Particle.POOF, spot, 1, 0, 0, 0, 0, null, 32, false);
    }

    void diveHit(Location spot) {
        emit(Particle.EXPLOSION_EMITTER, spot, 1, 0, 0, 0, 0, null, 128, true);
        emit(Particle.EXPLOSION, spot, 15, 6, 3, 6, 0, null, 128, true);
        emit(Particle.DRAGON_BREATH, spot.clone().add(0, .5, 0), 120, 6, .2, 6, .2, null, 64, true);
    }

    void mirrorTrail(Location spot) {
        emit(Particle.DUST_COLOR_TRANSITION, spot, 2, .3, .3, .3, 1, TRAIL, 256, true);
    }

    static float offset() {
        return OFFSETS[ThreadLocalRandom.current().nextInt(OFFSETS.length)];
    }
}
