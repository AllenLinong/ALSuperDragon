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
    private static final double MOUTH_REACH = 1.6;
    private static final double BODY_REACH = 10;
    private static final double MAX_CARRY_SPEED = 2;
    private static final FlightAccess FLIGHT = FlightAccess.load();
    private final EnderDragon dragon;
    private final Player target;
    private final Language language;
    private final Settings.AreaSkill skill;
    private final double height;
    private final double damage;
    private int age;
    private int heldTicks;
    private Vector holdOffset;
    private Location lastHead;
    private Location lastBody;
    private boolean charging;
    private double grabY;
    private int nextChargeRestart;
    private Vector chargeDirection;
    private Location carryTarget;

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
            || dragon.getPhase() == EnderDragon.Phase.DYING || !dragon.getWorld().equals(target.getWorld())) return finish(holdOffset != null);
        if (++age <= skill.warningTicks()) {
            if (age % 4 == 1) markTarget();
            return false;
        }
        if (FLIGHT == null) return finish(false);
        try {
            return fly();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Bukkit.getLogger().log(Level.WARNING, "ALSuperDragon could not steer the dragon for Sky Bite", exception);
            return finish(holdOffset != null);
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
            if (!restartNativeCharge(dragon, torso.clone().add(forward.clone().multiply(6)))) return finish(false);
            charging = true;
            chargeDirection = forward;
            nextChargeRestart = age + 7;
            body.getWorld().playSound(body, Sound.ENTITY_ENDER_DRAGON_GROWL, 3, 1.15f);
            lastHead = head;
            lastBody = body;
            return false;
        }
        Vector updatedDirection = horizontalDirection(torso.toVector().subtract(body.toVector()));
        if (holdOffset == null && updatedDirection != null) chargeDirection = updatedDirection;
        Location chargeTarget = carryTarget == null
            ? torso.clone().add(chargeDirection.clone().multiply(6)) : carryTarget;
        if (dragon.getPhase() != EnderDragon.Phase.CHARGE_PLAYER || age >= nextChargeRestart) {
            if (!restartNativeCharge(dragon, chargeTarget)) return finish(holdOffset != null);
            nextChargeRestart = age + 7;
        }
        if (holdOffset != null) return lift(head, feet);
        if (age - skill.warningTicks() > CHARGE_LIMIT) return finish(false);
        Vector approach = head.toVector().subtract(lastHead.toVector());
        Vector toTarget = torso.toVector().subtract(lastHead.toVector());
        boolean bodyApproaching = body.distanceSquared(lastBody) >= .01
            && body.distanceSquared(torso) < lastBody.distanceSquared(torso);
        lastHead = head;
        lastBody = body;
        if (!bodyApproaching || body.distanceSquared(torso) > BODY_REACH * BODY_REACH
            || head.distanceSquared(torso) > MOUTH_REACH * MOUTH_REACH
            || approach.lengthSquared() < .01 || approach.dot(toTarget) <= 0
            || !headBox.expand(Math.min(MAX_BITE_MARGIN, skill.radius())).overlaps(targetBox)
            || !clearPath(head, torso)) return false;

        Vector forward = horizontalDirection(approach);
        if (forward == null) forward = horizontalDirection(head.toVector().subtract(body.toVector()));
        if (forward == null) return finish(false);
        double climbY = Math.min(body.getWorld().getMaxHeight() - 3, body.getY() + height + 12);
        carryTarget = body.clone().add(forward.multiply(32));
        carryTarget.setY(climbY);
        if (!restartNativeCharge(dragon, carryTarget)) return finish(false);
        nextChargeRestart = age + 7;
        holdOffset = feet.toVector().subtract(head.toVector());
        grabY = feet.getY();
        head.getWorld().spawnParticle(Particle.CRIT, head, 16, .35, .3, .35, .15);
        head.getWorld().playSound(head, Sound.ENTITY_PHANTOM_BITE, 1.4f, .75f);
        target.sendActionBar(language.message("sky-bite-grabbed", NamedTextColor.RED));
        return false;
    }

    private boolean lift(Location head, Location feet) {
        if (++heldTicks > CARRY_LIMIT || feet.getY() - grabY >= height - .001) return finish(true);
        Vector movement = head.toVector().subtract(lastHead.toVector());
        if (movement.lengthSquared() > 16) return finish(true);
        lastHead = head;
        Location anchor = head.clone().add(holdOffset);
        Vector correction = anchor.toVector().subtract(feet.toVector());
        if (correction.lengthSquared() > 9) return finish(true);
        Vector velocity = movement.add(correction.multiply(.55));
        if (velocity.lengthSquared() > MAX_CARRY_SPEED * MAX_CARRY_SPEED) velocity.normalize().multiply(MAX_CARRY_SPEED);
        Location next = feet.clone().add(velocity);
        if (!clearPath(feet.clone().add(0, .9, 0), next.clone().add(0, .9, 0))
            || !DragonParticles.loaded(next.clone().add(0, 1.8, 0)) || target.collidesAt(next)) return finish(true);
        target.setVelocity(velocity);
        target.setFallDistance(0);
        if (heldTicks % 10 == 0) DragonSkills.hurt(target, dragon, damage * .5);
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
        finish(holdOffset != null);
    }

    private boolean finish(boolean notify) {
        if (charging && dragon.isValid() && !dragon.isDead() && dragon.getPhase() != EnderDragon.Phase.DYING
            && (dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER || dragon.getPhase() == EnderDragon.Phase.HOVER)) {
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
        }
        charging = false;
        chargeDirection = null;
        carryTarget = null;
        if (notify && holdOffset != null && target.isOnline() && !target.isDead()) {
            if (lastHead.getWorld().equals(target.getWorld()) && target.getLocation().distanceSquared(lastHead) <= 16) {
                Vector velocity = target.getVelocity();
                if (velocity.getY() > 0) target.setVelocity(velocity.setY(0));
            }
            target.sendActionBar(language.message("sky-bite-drop", NamedTextColor.GOLD));
            target.playSound(target.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, .8f, 1.25f);
        }
        holdOffset = null;
        return true;
    }

    static boolean nativeChargeAvailable() {
        return FLIGHT != null;
    }

    static boolean hitFlightObstacle(EnderDragon dragon) {
        if (FLIGHT == null) return false;
        try {
            Object handle = FLIGHT.handle().invoke(dragon);
            return FLIGHT.inWall().getBoolean(handle) || FLIGHT.horizontalCollision().getBoolean(handle)
                || FLIGHT.verticalCollision().getBoolean(handle);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    static boolean tryNativeCharge(EnderDragon dragon, Location target) {
        return restartNativeCharge(dragon, target);
    }

    static boolean restartNativeCharge(EnderDragon dragon, Location target) {
        if (FLIGHT == null) return false;
        if (dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER) {
            dragon.setPhase(EnderDragon.Phase.HOVER);
            if (dragon.getPhase() != EnderDragon.Phase.HOVER) return false;
        }
        dragon.setPhase(EnderDragon.Phase.CHARGE_PLAYER);
        if (dragon.getPhase() != EnderDragon.Phase.CHARGE_PLAYER) return false;
        try {
            FLIGHT.aim(dragon, target);
            return true;
        } catch (ReflectiveOperationException exception) {
            dragon.setPhase(EnderDragon.Phase.HOVER);
            return false;
        }
    }

    private record FlightAccess(Method handle, Field headPart, Method bukkitPart, Method phaseManager,
                                Method currentPhase, Method setTarget, Constructor<?> position,
                                Field inWall, Field horizontalCollision, Field verticalCollision) {
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
                    vector.getConstructor(double.class, double.class, double.class), nms.getField("inWall"),
                    nms.getField("horizontalCollision"), nms.getField("verticalCollision"));
            } catch (ReflectiveOperationException | LinkageError exception) {
                Bukkit.getLogger().log(Level.WARNING, "ALSuperDragon native dragon charging unavailable (Sky Bite and proactive AI)", exception);
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
