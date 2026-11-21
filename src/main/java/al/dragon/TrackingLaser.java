package al.dragon;

import java.util.ArrayDeque;
import java.util.List;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class TrackingLaser {
    private static final int AIM_DELAY_TICKS = 10;
    private final EnderDragon dragon;
    private final Player target;
    private final Settings.TrackingBeam settings;
    private final double damage;
    private final EnderDragon.Phase previousPhase;
    private final boolean hadAi;
    private final ArrayDeque<Location> aimHistory = new ArrayDeque<>();
    private int age;
    private boolean finished;

    TrackingLaser(EnderDragon dragon, Player target, Language language, Settings.TrackingBeam settings, double damageScale) {
        this.dragon = dragon;
        this.target = target;
        this.settings = settings;
        damage = settings.skill().damage() * damageScale;
        previousPhase = dragon.getPhase();
        hadAi = dragon.hasAI();
        dragon.setPhase(EnderDragon.Phase.HOVER);
        dragon.setAI(false);
        dragon.setVelocity(new Vector());
        target.sendActionBar(language.message("tracking-laser-target", NamedTextColor.RED));
    }

    boolean tick(List<Player> players, DragonParticles particles) {
        if (!dragon.isValid() || dragon.isDead() || dragon.getPhase() == EnderDragon.Phase.DYING
            || !target.isOnline() || !players.contains(target) || !dragon.getWorld().equals(target.getWorld())) return true;
        int frame = ++age - settings.skill().warningTicks();
        Location origin = dragon.getLocation().add(0, 2, 0);
        if (!DragonParticles.loaded(origin)) return true;
        Location sample = target.getLocation();
        var hitbox = target.getBoundingBox();
        sample.setY((hitbox.getMinY() + hitbox.getMaxY()) * .5);
        aimHistory.addLast(sample);
        if (aimHistory.size() > AIM_DELAY_TICKS + 1) aimHistory.removeFirst();
        if (frame <= 0) {
            if (age % 4 == 1) particles.laserCharge(origin);
            return false;
        }
        if (frame == 1) origin.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_GROWL, 3, 1.25f);
        var start = origin.toVector();
        var direction = aimHistory.getFirst().toVector().subtract(start);
        double targetDistance = direction.length();
        if (targetDistance < .001) return frame >= settings.durationTicks();
        direction.multiply(1 / targetDistance);
        double length = Math.min(targetDistance + 1.5, settings.range());
        Location probe = origin.clone();
        for (double distance = 0; distance < length;) {
            double advance = Math.min(1, length - distance);
            probe.add(direction.getX() * advance, direction.getY() * advance, direction.getZ() * advance);
            if (!DragonParticles.loaded(probe)) {
                length = distance;
                break;
            }
            distance += advance;
        }
        if (length > .001) {
            var hit = origin.getWorld().rayTraceBlocks(origin, direction, length, FluidCollisionMode.NEVER, true);
            Location end = hit == null ? origin.clone().add(direction.clone().multiply(length)) : hit.getHitPosition().toLocation(origin.getWorld());
            double beamLength = origin.distance(end);
            if (frame == 1 || frame % 2 == 0) particles.trackingBeam(origin, direction, beamLength, end, hit != null);
            if (frame % 10 == 0 && beamLength > .001) {
                var contact = hitbox.rayTrace(start, direction, beamLength);
                if (hitbox.contains(start) || (contact != null
                    && (hit == null || contact.getHitPosition().distanceSquared(start) < beamLength * beamLength))) {
                    DragonSkills.hurt(target, dragon, damage);
                    particles.laserEntityHit(target.getLocation());
                }
            }
        }
        return frame >= settings.durationTicks();
    }

    void finish() {
        if (finished) return;
        finished = true;
        if (!dragon.isValid() || dragon.isDead()) return;
        dragon.setAI(hadAi);
        if (dragon.getPhase() == EnderDragon.Phase.HOVER) dragon.setPhase(previousPhase);
    }
}
