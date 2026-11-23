package al.dragon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

final class LightningCast {
    static final int WAVE_COUNT = 5;
    static final int BOLTS_PER_WAVE = 6;
    static final int BOLTS_PER_TICK = 2;
    private static final int BOLT_GAP = 3;
    private static final int WAVE_PAUSE = 5;
    private static final double FIELD_RADIUS = 13;
    private static final int ENTRANCE_DURATION = 60;
    private static final int ENTRANCE_BOLT_GAP = 4;
    private static final double ENTRANCE_RADIUS = 5;
    private static final double ENTRANCE_HIT_RADIUS = 2.1;

    private final Location center;
    private final boolean storm;
    private final Set<UUID> hit = new HashSet<>();
    private List<Location> spots;
    private int tick;
    private int wave;
    private int next;

    LightningCast(Location center, boolean storm) {
        this.center = center;
        this.storm = storm;
    }

    static void playEntrance(ALDragon plugin, EnderDragon dragon, Location center, double damage) {
        var world = center.getWorld();
        new BukkitRunnable() {
            private final Set<UUID> struck = new HashSet<>();
            private final Location[] impacts = new Location[BOLTS_PER_TICK];
            private int elapsed;

            @Override
            public void run() {
                if (!dragon.isValid() || dragon.isDead() || dragon.getHealth() <= 0
                    || dragon.getWorld() != world || Bukkit.getWorld(world.getUID()) != world) {
                    cancel();
                    return;
                }
                var random = ThreadLocalRandom.current();
                int impactCount = 0;
                for (int bolt = 0; bolt < BOLTS_PER_TICK; bolt++) {
                    double angle = random.nextDouble(Math.TAU);
                    double distance = ENTRANCE_RADIUS * Math.sqrt(random.nextDouble());
                    Location spot = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                    if (!DragonParticles.loaded(spot)) continue;
                    Location impact = ground(spot, 4);
                    if (world.strikeLightningEffect(impact).isValid()) impacts[impactCount++] = impact;
                }
                if (damage > 0 && impactCount > 0) {
                    for (Player player : world.getNearbyPlayers(center, ENTRANCE_RADIUS, 12, ENTRANCE_RADIUS)) {
                        GameMode mode = player.getGameMode();
                        if (player.isDead() || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE)
                            || struck.contains(player.getUniqueId())) continue;
                        Location position = player.getLocation();
                        if (DragonSkills.horizontalDistanceSquared(position, center) > ENTRANCE_RADIUS * ENTRANCE_RADIUS) continue;
                        for (int index = 0; index < impactCount; index++) {
                            Location impact = impacts[index];
                            if (Math.abs(position.getY() - impact.getY()) <= 3 && DragonSkills.horizontalDistanceSquared(position, impact)
                                <= ENTRANCE_HIT_RADIUS * ENTRANCE_HIT_RADIUS) {
                                struck.add(player.getUniqueId());
                                DragonSkills.hurt(player, dragon, damage);
                                break;
                            }
                        }
                    }
                }
                if ((elapsed += ENTRANCE_BOLT_GAP) >= ENTRANCE_DURATION) cancel();
            }
        }.runTaskTimer(plugin, 1, ENTRANCE_BOLT_GAP);
    }

    boolean tick(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale) {
        if (spots == null) {
            spots = planWave(players);
            center.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .9f, storm ? .8f : 1.4f);
        }
        if (tick < skill.warningTicks()) {
            if (tick % 5 == 0) for (Location spot : spots) {
                var audience = DragonParticles.warningViewers(spot, 48);
                DragonSkills.warningRing(spot, skill.radius(), storm ? 14 : 24, DragonSkills.THUNDER_WARNING, audience);
                if (!audience.isEmpty()) spot.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, audience, null,
                    spot.getX(), spot.getY() + .55, spot.getZ(), 3, .08, .35, .08, .035, null, true);
            }
        } else if ((tick - skill.warningTicks()) % BOLT_GAP == 0 && next < spots.size()) {
            List<Location> resolved = new ArrayList<>(BOLTS_PER_TICK);
            int end = Math.min(spots.size(), next + BOLTS_PER_TICK);
            for (; next < end; next++) {
                Location spot = spots.get(next);
                if (!DragonParticles.loaded(spot)) continue;
                var bolt = spot.getWorld().strikeLightningEffect(spot);
                if (storm) bolt.setSilent(true);
                resolved.add(spot);
            }
            if (storm && !resolved.isEmpty() && end == Math.min(BOLTS_PER_TICK, spots.size())) {
                center.getWorld().playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.15f);
            }
            if (!resolved.isEmpty()) for (Player player : players) {
                if (hit.contains(player.getUniqueId())) continue;
                Location position = player.getLocation();
                for (Location spot : resolved) {
                    if (Math.abs(position.getY() - spot.getY()) <= 4
                        && DragonSkills.horizontalDistanceSquared(position, spot) <= skill.radius() * skill.radius()) {
                        hit.add(player.getUniqueId());
                        DragonSkills.hurt(player, dragon, skill.damage() * scale);
                        break;
                    }
                }
            }
        }
        int duration = skill.warningTicks() + (storm ? BOLT_GAP * (BOLTS_PER_WAVE / BOLTS_PER_TICK - 1) + WAVE_PAUSE : 0);
        if (tick++ < duration) return false;
        if (!storm || ++wave >= WAVE_COUNT) return true;
        tick = 0;
        next = 0;
        spots = null;
        hit.clear();
        return false;
    }

    private List<Location> planWave(List<Player> players) {
        if (!storm) return DragonParticles.loaded(center) ? List.of(ground(center, .5)) : List.of();
        var random = ThreadLocalRandom.current();
        List<Location> planned = new ArrayList<>(BOLTS_PER_WAVE);
        Location closest = null;
        double best = FIELD_RADIUS * FIELD_RADIUS;
        for (Player player : players) {
            Location position = player.getLocation();
            double distance = DragonSkills.horizontalDistanceSquared(position, center);
            if (distance <= best && DragonParticles.loaded(position)) {
                closest = position;
                best = distance;
            }
        }
        if (closest != null) planned.add(ground(closest, .5));
        double rotation = random.nextDouble(Math.TAU);
        int randomBolts = BOLTS_PER_WAVE - planned.size();
        for (int index = 0; index < randomBolts; index++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                double angle = rotation + (index + random.nextDouble()) * Math.TAU / randomBolts;
                double distance = index % 2 == 0 ? random.nextDouble(1.5, 7) : random.nextDouble(7, FIELD_RADIUS);
                Location spot = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                if (!DragonParticles.loaded(spot)) continue;
                planned.add(ground(spot, 4));
                break;
            }
        }
        return planned;
    }

    private static Location ground(Location spot, double startAbove) {
        Location top = spot.clone();
        top.setY(Math.min(spot.getWorld().getMaxHeight() - .5, spot.getY() + startAbove));
        var ground = spot.getWorld().rayTraceBlocks(top, new Vector(0, -1, 0), 12, FluidCollisionMode.NEVER, true);
        return ground == null ? spot : ground.getHitPosition().toLocation(spot.getWorld()).add(0, .08, 0);
    }
}
