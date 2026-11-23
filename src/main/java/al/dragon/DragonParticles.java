package al.dragon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class DragonParticles {
    private static final Color PINK = Color.fromRGB(255, 0, 248);
    private static final Color BLUE = Color.fromRGB(0, 26, 255);
    private static final Particle.DustTransition TRAIL = new Particle.DustTransition(PINK, BLUE, 3);
    private static final Particle.DustTransition ARC = new Particle.DustTransition(Color.fromRGB(68, 157, 255), Color.fromRGB(225, 252, 255), 1.55f);
    private static final Particle.DustTransition ABYSS_TRAIL = new Particle.DustTransition(Color.fromRGB(133, 63, 218), Color.fromRGB(38, 14, 83), 2);
    private static final Particle.DustTransition SOVEREIGN_TRAIL = new Particle.DustTransition(Color.fromRGB(255, 173, 38), Color.fromRGB(255, 244, 187), 2);
    private static final int BODY_RANGE = 64;
    private static final Particle.DustTransition FIREBALL = new Particle.DustTransition(PINK, BLUE, 2);
    private static final Particle.DustOptions LASER = new Particle.DustOptions(Color.fromRGB(255, 19, 248), 1);
    private static final Particle.DustOptions WAVE = new Particle.DustOptions(Color.fromRGB(255, 19, 249), 1.2f);
    private static final float[] OFFSETS = {-5, -4.5f, -4, -3.5f, -3, 0, 3, 3.5f, 4, 4.5f, 5};

    private final List<Player> viewers;
    private List<Location> positions;
    private final List<Player> receivers = new ArrayList<>();
    private List<Player> laserSparkViewers;
    private List<Player> laserRodViewers;

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
        send(particle, receivers, spot, count, dx, dy, dz, speed, data, force);
    }

    private static <T> void send(Particle particle, List<Player> audience, Location spot, int count, double dx, double dy,
                                 double dz, double speed, T data, boolean force) {
        if (!audience.isEmpty()) spot.getWorld().spawnParticle(particle, audience, null, spot.getX(), spot.getY(), spot.getZ(),
            count, dx, dy, dz, speed, particle == Particle.DRAGON_BREATH && data == null ? 1f : data, force);
    }

    private void directional(Particle particle, Location spot, Vector direction, double magnitude, double speed, double range) {
        emit(particle, spot, 0, direction.getX() * magnitude, direction.getY() * magnitude,
            direction.getZ() * magnitude, speed, null, range, true);
    }

    static void tickBody(EnderDragon dragon, DragonProfiles.Profile profile) {
        var settings = profile.combat();
        int age = dragon.getTicksLived();
        int interval = settings.electricAuraTicks();
        boolean electric = interval > 0;
        if (electric ? age % interval != 0 : !settings.trail() || age % 4 != 0) return;
        Location center = dragon.getLocation();
        if (!loaded(center)) return;
        List<Player> audience = warningViewers(center, BODY_RANGE);
        if (audience.isEmpty()) return;
        if (electric) electricAura(center, age, audience);
        else bodyTrail(center, profile.id(), audience);
    }

    static List<Player> warningViewers(Location center, double range) {
        return center.getWorld().getNearbyPlayers(center, range).stream()
            .filter(player -> player.getLocation().distanceSquared(center) <= range * range).toList();
    }

    private static void bodyTrail(Location center, String profile, List<Player> audience) {
        var dust = switch (profile) {
            case "abyss" -> ABYSS_TRAIL;
            case "sovereign" -> SOVEREIGN_TRAIL;
            case "storm" -> ARC;
            default -> TRAIL;
        };
        Particle accent = switch (profile) {
            case "abyss" -> Particle.PORTAL;
            case "sovereign" -> Particle.END_ROD;
            case "storm" -> Particle.ELECTRIC_SPARK;
            default -> Particle.DRAGON_BREATH;
        };
        double yaw = Math.toRadians(center.getYaw());
        double wingX = Math.cos(yaw), wingZ = Math.sin(yaw);
        double bodyY = center.getY() + 2;
        var world = center.getWorld();
        for (int side = -1; side <= 1; side += 2) {
            for (int segment = 0; segment < 3; segment++) {
                double across = side * (2.4 + segment * 1.25);
                double trailing = .65 * segment;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, audience, null,
                    center.getX() + across * wingX + trailing * wingZ, bodyY + segment * .18,
                    center.getZ() + across * wingZ - trailing * wingX, 1, 0, 0, 0, 0, dust, true);
            }
        }
        world.spawnParticle(accent, audience, null, center.getX(), bodyY, center.getZ(),
            4, .7, .45, .7, .04, accent == Particle.DRAGON_BREATH ? 1f : null, true);
    }

    private static void electricAura(Location center, int age, List<Player> audience) {
        double yaw = Math.toRadians(center.getYaw());
        double headingX = -Math.sin(yaw), headingZ = Math.cos(yaw);
        var world = center.getWorld();
        double phase = age * .18;
        for (int arc = 0; arc < 2; arc++) {
            for (int segment = 0; segment < 12; segment++) {
                double angle = phase + arc * Math.PI + segment * .23;
                double along = (segment - 5.5) * .87;
                double across = Math.cos(angle) * (4.2 + (segment % 2 == 0 ? .48 : -.48));
                double x = center.getX() + headingX * along + headingZ * across;
                double y = center.getY() + 2.6 + 1.6 * Math.sin(angle) + (segment % 2 == 0 ? .22 : -.22);
                double z = center.getZ() + headingZ * along - headingX * across;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, audience, null, x, y, z, 1, 0, 0, 0, 0, ARC, true);
                if (segment == 5 || segment == 11) world.spawnParticle(Particle.ELECTRIC_SPARK, audience, null,
                    x, y, z, 2, .13, .22, .13, .045, null, true);
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
        if (viewers.isEmpty()) return;
        var random = ThreadLocalRandom.current();
        boolean breath = random.nextInt(10) == 0;
        boolean rod = random.nextBoolean();
        if (positions == null) positions = viewers.stream().map(Player::getLocation).toList();
        if (laserSparkViewers == null) laserSparkViewers = new ArrayList<>();
        if (rod && laserRodViewers == null) laserRodViewers = new ArrayList<>();
        receivers.clear();
        laserSparkViewers.clear();
        if (rod) laserRodViewers.clear();
        for (int index = 0; index < viewers.size(); index++) {
            double distance = positions.get(index).distanceSquared(spot);
            Player viewer = viewers.get(index);
            if (distance <= 32 * 32) receivers.add(viewer);
            if (distance <= 128 * 128) laserSparkViewers.add(viewer);
            if (rod && distance <= 256 * 256) laserRodViewers.add(viewer);
        }
        send(Particle.DUST, receivers, spot, 2, .3, .3, .3, 0, LASER, true);
        send(Particle.ELECTRIC_SPARK, laserSparkViewers, spot, 1, .3, .3, .3, 0, null, true);
        if (breath) send(Particle.DRAGON_BREATH, laserSparkViewers, spot, 1, .3, .3, .3, .1, null, true);
        if (rod) send(Particle.END_ROD, laserRodViewers, spot, 0,
            direction.getX() * 1e22, direction.getY() * 1e22, direction.getZ() * 1e22, 9e-22, null, true);
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
        int segments = Math.max(1, (int) Math.ceil(length / 2));
        double spacing = length / segments;
        double stepX = direction.getX() * spacing, stepY = direction.getY() * spacing, stepZ = direction.getZ() * spacing;
        Location point = origin.clone();
        for (int index = 0; index < segments; index++) {
            laser(point, direction);
            point.add(stepX, stepY, stepZ);
        }
        laser(end, direction);
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
