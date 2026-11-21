package al.dragon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class SkyBite {
    private static final double CAST_RANGE = 24;
    private static final int CHARGE_LIMIT = 80;
    private static final int CARRY_LIMIT = 100;
    private static final double MAX_BITE_MARGIN = .5;
    private static final FlightAccess FLIGHT = FlightAccess.load();
    private final EnderDragon dragon;
    private final Player target;
    private final Language language;
    private final Settings.AreaSkill skill;
    private final double height;
    private final double damage;
    private int age;
    private int heldTicks;
    private Location held;
    private Vector holdOffset;
    private Location lastHead;
    private boolean charging;
    private double grabY;

    SkyBite(EnderDragon dragon, Player target, Language language, Settings.ExtraSkills settings, double damageScale) {
        this.dragon = dragon;
        this.target = target;
        this.language = language;
        skill = settings.skyBite();
        height = settings.liftHeight();
        damage = skill.damage() * damageScale;
        target.sendActionBar(language.message("sky-bite-target", NamedTextColor.RED));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, .8f, .75f);
    }

    static boolean canReach(Location origin, Player target) {
        return FLIGHT != null && !target.isInsideVehicle() && DragonSkills.clearCastPath(origin.clone().add(0, .9, 0),
            target.getLocation().add(0, .9, 0), CAST_RANGE);
    }

    boolean tick(List<Player> players) {
        if (!players.contains(target) || !target.isOnline() || target.isInsideVehicle() || !dragon.isValid() || dragon.isDead()
            || dragon.getPhase() == EnderDragon.Phase.DYING || !dragon.getWorld().equals(target.getWorld())) return finish(held != null);
        if (++age <= skill.warningTicks()) {
            if (age % 4 == 1) markTarget();
            return false;
        }
        if (FLIGHT == null) return finish(false);
        try {
            return fly();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "ALSuperDragon could not steer the dragon for Sky Bite", exception);
            return finish(held != null);
        }
    }

    private void markTarget() {
        Location feet = target.getLocation();
        double radius = 1.25 - .7 * age / skill.warningTicks();
        for (int point = 0; point < 12; point++) {
            double angle = point * Math.PI / 6;
            Location dot = feet.clone().add(Math.cos(angle) * radius, .15, Math.sin(angle) * radius);
            feet.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, dot, 1, 0, 0, 0, 0);
        }
    }

    private boolean fly() throws ReflectiveOperationException {
        Location body = dragon.getLocation();
        var headBox = FLIGHT.head(dragon).getBoundingBox();
        Location head = headBox.getCenter().toLocation(body.getWorld());
        Location feet = target.getLocation();
        var targetBox = target.getBoundingBox();
        Location torso = targetBox.getCenter().toLocation(body.getWorld());
        if (!charging) {
            if (!canReach(body, target) || !clearPath(head, torso)) return finish(false);
            Vector forward = horizontalDirection(torso.toVector().subtract(body.toVector()));
            if (forward == null) return finish(false);
            dragon.setPhase(EnderDragon.Phase.CHARGE_PLAYER);
            if (dragon.getPhase() != EnderDragon.Phase.CHARGE_PLAYER) return finish(false);
            charging = true;
            FLIGHT.aim(dragon, torso.clone().add(forward.multiply(6)));
            body.getWorld().playSound(body, Sound.ENTITY_ENDER_DRAGON_GROWL, 3, 1.15f);
            lastHead = head;
            return false;
        }
        if (dragon.getPhase() != EnderDragon.Phase.CHARGE_PLAYER) return finish(held != null);
        if (held != null) return lift(head, feet);
        if (age - skill.warningTicks() > CHARGE_LIMIT) return finish(false);
        Vector approach = head.toVector().subtract(lastHead.toVector());
        Vector toTarget = torso.toVector().subtract(lastHead.toVector());
        lastHead = head;
        if (approach.lengthSquared() < .01 || approach.dot(toTarget) <= 0
            || !headBox.expand(Math.min(MAX_BITE_MARGIN, skill.radius())).overlaps(targetBox)
            || !clearPath(head, torso)) return false;

        Vector forward = horizontalDirection(approach);
        if (forward == null) forward = horizontalDirection(head.toVector().subtract(body.toVector()));
        if (forward == null) return finish(false);
        double climbY = Math.min(body.getWorld().getMaxHeight() - 3, body.getY() + height + 12);
        Location climbTarget = body.clone().add(forward.multiply(32));
        climbTarget.setY(climbY);
        FLIGHT.aim(dragon, climbTarget);
        held = feet;
        holdOffset = feet.toVector().subtract(head.toVector());
        grabY = feet.getY();
        head.getWorld().spawnParticle(Particle.CRIT, head, 16, .35, .3, .35, .15);
        head.getWorld().playSound(head, Sound.ENTITY_PHANTOM_BITE, 1.4f, .75f);
        target.sendActionBar(language.message("sky-bite-grabbed", NamedTextColor.RED));
        return false;
    }

    private boolean lift(Location head, Location feet) {
        if (++heldTicks > CARRY_LIMIT || feet.distanceSquared(held) > 9 || head.distanceSquared(lastHead) > 16) return finish(true);
        lastHead = head;
        Location passenger = head.clone().add(holdOffset);
        passenger.setDirection(feet.getDirection());
        if (!clearPath(feet.clone().add(0, .9, 0), passenger.clone().add(0, .9, 0))
            || !DragonParticles.loaded(passenger.clone().add(0, 1.8, 0)) || target.collidesAt(passenger)
            || !target.teleport(passenger)) return finish(true);
        target.setVelocity(new Vector());
        target.setFallDistance(0);
        held = passenger;
        if (heldTicks % 10 == 0) DragonSkills.hurt(target, dragon, damage * .5);
        if (held.getY() - grabY >= height - .001) return finish(true);
        return false;
    }

    private static Vector horizontalDirection(Vector direction) {
        direction.setY(0);
        return direction.lengthSquared() < .0001 ? null : direction.normalize();
    }

    private static boolean clearPath(Location from, Location to) {
        if (!DragonParticles.loaded(from) || !DragonParticles.loaded(to)) return false;
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        return distance < .001 || from.getWorld().rayTraceBlocks(from, direction.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    void cancel() {
        finish(held != null);
    }

    private boolean finish(boolean notify) {
        if (charging && dragon.isValid() && !dragon.isDead() && dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER) {
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
        }
        charging = false;
        if (notify && held != null && target.isOnline() && !target.isDead()) {
            target.sendActionBar(language.message("sky-bite-drop", NamedTextColor.GOLD));
            target.playSound(target.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, .8f, 1.25f);
        }
        return true;
    }

    private record FlightAccess(Method handle, Field headPart, Method bukkitPart, Method phaseManager,
                                Method currentPhase, Method setTarget, Constructor<?> position) {
        static FlightAccess load() {
            try {
                Class<?> craft = Class.forName("org.bukkit.craftbukkit.entity.CraftEnderDragon");
                Class<?> nms = Class.forName("net.minecraft.world.entity.boss.enderdragon.EnderDragon");
                Class<?> charge = Class.forName("net.minecraft.world.entity.boss.enderdragon.phases.DragonChargePlayerPhase");
                Class<?> vector = Class.forName("net.minecraft.world.phys.Vec3");
                Field head = nms.getField("head");
                Method manager = nms.getMethod("getPhaseManager");
                return new FlightAccess(craft.getMethod("getHandle"), head, head.getType().getMethod("getBukkitEntity"), manager,
                    manager.getReturnType().getMethod("getCurrentPhase"), charge.getMethod("setTarget", vector),
                    vector.getConstructor(double.class, double.class, double.class));
            } catch (ReflectiveOperationException | LinkageError exception) {
                Bukkit.getLogger().log(Level.WARNING, "ALSuperDragon Sky Bite disabled: native dragon charge API unavailable", exception);
                return null;
            }
        }

        EnderDragonPart head(EnderDragon dragon) throws ReflectiveOperationException {
            return (EnderDragonPart) bukkitPart.invoke(headPart.get(handle.invoke(dragon)));
        }

        void aim(EnderDragon dragon, Location target) throws ReflectiveOperationException {
            Object phase = currentPhase.invoke(phaseManager.invoke(handle.invoke(dragon)));
            setTarget.invoke(phase, position.newInstance(target.getX(), target.getY(), target.getZ()));
        }
    }
}
