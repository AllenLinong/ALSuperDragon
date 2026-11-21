package al.dragon;

import java.util.List;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class SkyBite {
    private static final double CAST_RANGE = 24;
    private static final double REACH = 5.5;
    private static final int CHARGE_LIMIT = 65;
    private static final double CLIMB_PER_TICK = .32;
    private final EnderDragon dragon;
    private final Player target;
    private final Language language;
    private final Settings.AreaSkill skill;
    private final double height;
    private final double damage;
    private final EnderDragon.Phase phase;
    private final boolean hadAi;
    private int age;
    private int heldTicks;
    private Location held;
    private double grabY;
    private boolean finished;

    SkyBite(EnderDragon dragon, Player target, Language language, Settings.ExtraSkills settings, double damageScale) {
        this.dragon = dragon;
        this.target = target;
        this.language = language;
        skill = settings.skyBite();
        height = settings.liftHeight();
        damage = skill.damage() * damageScale;
        phase = dragon.getPhase();
        hadAi = dragon.hasAI();
        dragon.setPhase(EnderDragon.Phase.HOVER);
        dragon.setAI(false);
        dragon.setVelocity(new Vector());
        target.sendActionBar(language.message("sky-bite-target", NamedTextColor.RED));
    }

    static boolean canReach(Location origin, Player target) {
        return !target.isInsideVehicle() && DragonSkills.clearCastPath(origin.clone().add(0, .9, 0),
            target.getLocation().add(0, .9, 0), CAST_RANGE);
    }

    boolean tick(List<Player> players) {
        if (!players.contains(target) || !target.isOnline() || !dragon.isValid() || dragon.isDead()
            || !dragon.getWorld().equals(target.getWorld())) return finish(false);
        if (++age <= skill.warningTicks()) {
            if (age % 4 == 1) target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation().add(0, 1, 0), 12, .65, .5, .65, .04);
            return false;
        }
        if (held != null) return lift();
        if (age > skill.warningTicks() + CHARGE_LIMIT) return finish(false);
        if (age == skill.warningTicks() + 1) {
            if (!canReach(dragon.getLocation(), target)) return finish(false);
            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 3, 1.15f);
        }
        Location body = dragon.getLocation();
        Location feet = target.getLocation();
        Vector toward = feet.toVector().subtract(body.toVector());
        toward.setY(0);
        Vector forward = toward.lengthSquared() > .0001 ? toward.normalize() : new Vector(1, 0, 0);
        Location face = body.clone().add(forward.clone().multiply(REACH));
        if (clearPath(face.clone().add(0, .9, 0), feet.clone().add(0, .9, 0))
            && face.distanceSquared(feet) <= skill.radius() * skill.radius()) {
            held = feet;
            grabY = feet.getY();
            target.sendActionBar(language.message("sky-bite-grabbed", NamedTextColor.RED));
            return false;
        }
        Vector delta = feet.toVector().subtract(face.toVector());
        if (delta.lengthSquared() < .0001) return finish(false);
        Location next = body.clone().add(delta.normalize().multiply(Math.min(1.9, face.distance(feet))));
        next.setDirection(forward.multiply(-1));
        if (!DragonParticles.loaded(next) || !clearPath(body, next) || !dragon.teleport(next)) return finish(false);
        next.getWorld().spawnParticle(Particle.DRAGON_BREATH, next, 12, .65, .45, .65, .06, 1f);
        return false;
    }

    private boolean lift() {
        if (target.getLocation().distanceSquared(held) > 9 || target.isInsideVehicle()) return finish(true);
        double rise = Math.min(CLIMB_PER_TICK, height - (held.getY() - grabY));
        Location body = dragon.getLocation();
        Location next = body.clone().add(0, rise, 0);
        Location passenger = held.clone().add(0, rise, 0);
        passenger.setDirection(target.getLocation().getDirection());
        if (rise < .001 || !DragonParticles.loaded(next) || !DragonParticles.loaded(passenger.clone().add(0, 1.8, 0))
            || target.collidesAt(passenger) || !clearPath(body, next) || !dragon.teleport(next)
            || !target.teleport(passenger)) return finish(true);
        target.setVelocity(new Vector());
        target.setFallDistance(0);
        held = passenger;
        if (++heldTicks % 10 == 0) DragonSkills.hurt(target, dragon, damage * .5);
        if (held.getY() - grabY >= height - .001) return finish(true);
        return false;
    }

    private static boolean clearPath(Location from, Location to) {
        if (!DragonParticles.loaded(from) || !DragonParticles.loaded(to)) return false;
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        return distance < .001 || from.getWorld().rayTraceBlocks(from, direction.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    boolean finish(boolean notify) {
        if (finished) return true;
        finished = true;
        if (dragon.isValid() && !dragon.isDead()) {
            dragon.setAI(hadAi);
            if (dragon.getPhase() == EnderDragon.Phase.HOVER) dragon.setPhase(phase);
        }
        if (notify && held != null && target.isOnline() && !target.isDead()) {
            target.sendActionBar(language.message("sky-bite-drop", NamedTextColor.GOLD));
        }
        return true;
    }
}
