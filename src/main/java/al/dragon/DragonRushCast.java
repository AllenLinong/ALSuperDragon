package al.dragon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

final class DragonRushCast {
    private static final int COUNT = 5;
    private static final int LAUNCH_GAP = 5;
    private static final int MAX_LIFETIME_TICKS = 30 * 20;
    private static final double ORBIT_RADIUS = 8;
    private static final double ORBIT_STEP = .085;
    private static final NamespacedKey DECOY = ALDragon.storedKey("rush_decoy");
    private final EnderDragon dragon;
    private final Settings.DragonRush settings;
    private final double damage;
    private final int round;
    private final List<Dive> dives = new ArrayList<>(COUNT);
    private int age;

    DragonRushCast(EnderDragon dragon, List<Player> players, Language language, Settings.DragonRush settings, Settings.Tuning tuning, int round) {
        this.dragon = dragon;
        this.settings = settings;
        this.damage = settings.damage() * tuning.damageScale();
        this.round = round;
        var warning = Title.title(Component.empty(), language.message("dragon-rush-warning", NamedTextColor.LIGHT_PURPLE), tuning.titleTimes());
        for (Player player : players) {
            if (tuning.titleTicks() > 0) player.showTitle(warning);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 1, .75f);
        }
    }

    static boolean isDecoy(Entity entity) {
        return entity instanceof EnderDragon && entity.getPersistentDataContainer().has(DECOY, PersistentDataType.BYTE);
    }

    static void markDecoy(EnderDragon entity) {
        entity.getPersistentDataContainer().set(DECOY, PersistentDataType.BYTE, (byte) 1);
    }

    boolean tick(List<Player> players) {
        age++;
        if (age < settings.warningTicks()) return false;
        if (age == settings.warningTicks()) {
            List<Player> targets = players.stream().sorted(Comparator.comparing(Player::getUniqueId)).toList();
            for (int index = 0; index < COUNT; index++) {
                Player anchor = targets.get(Math.floorMod(round * COUNT + index, targets.size()));
                double angle = 2 * Math.PI * index / COUNT;
                Location spot = anchor.getLocation().add(orbitOffset(angle)).add(0, 26, 0);
                spot.setY(Math.min(spot.getY(), spot.getWorld().getMaxHeight() - 2));
                if (!DragonParticles.loaded(spot)) {
                    spot.setX(anchor.getLocation().getX());
                    spot.setZ(anchor.getLocation().getZ());
                }
                if (DragonParticles.loaded(spot)) dives.add(new Dive(spot, angle, index * LAUNCH_GAP));
            }
        }
        dives.removeIf(dive -> dive.tick(players));
        return dives.isEmpty();
    }

    void cleanup() {
        for (Dive dive : dives) dive.remove();
        dives.clear();
    }

    private static Vector orbitOffset(double angle) {
        return new Vector(ORBIT_RADIUS * Math.cos(angle), 0, ORBIT_RADIUS * Math.sin(angle));
    }

    private final class Dive {
        private final Location spot;
        private final Location orbitCenter;
        private final int launchAfter;
        private final List<Part> parts = new ArrayList<>();
        private Player target;
        private EnderDragon decoy;
        private Vector heading;
        private double orbitAngle;
        private int ticks;
        private int diveTicks;

        private Dive(Location spot, double angle, int launchDelay) {
            this.spot = spot;
            orbitAngle = angle;
            orbitCenter = spot.clone().subtract(orbitOffset(angle));
            launchAfter = settings.orbitTicks() + launchDelay;
            heading = new Vector(-Math.sin(angle), 0, Math.cos(angle));
            if (settings.realDragons()) {
                decoy = spot.getWorld().spawn(spot.clone().setDirection(heading.clone().multiply(-1)), EnderDragon.class, entity -> {
                    markDecoy(entity);
                    entity.setAI(false);
                    entity.setPhase(EnderDragon.Phase.HOVER);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.setPersistent(false);
                });
                if (!decoy.isValid()) decoy = null;
            }
            if (decoy == null) buildModel();
        }

        private boolean tick(List<Player> players) {
            ticks++;
            if (ticks >= MAX_LIFETIME_TICKS || players.isEmpty() || !DragonParticles.loaded(spot)) return remove();
            if (ticks <= launchAfter) return orbit();
            if (++diveTicks > 70) return remove();
            boolean acquiringTarget = target == null || !players.contains(target);
            if (acquiringTarget) {
                target = players.stream().min(Comparator.comparingDouble((Player player) -> player.getLocation().distanceSquared(spot))
                    .thenComparing(Player::getUniqueId)).orElse(null);
                if (target == null) return remove();
            }
            Vector desired = target.getLocation().add(0, 1, 0).toVector().subtract(spot.toVector());
            if (desired.lengthSquared() > 1e-6) {
                if (acquiringTarget) heading = desired.normalize();
                else heading.multiply(.8).add(desired.normalize().multiply(.2)).normalize();
            }
            double speed = Math.min(1.65, .68 + diveTicks * .055);
            Location next = spot.clone().add(heading.clone().multiply(speed));
            if (!DragonParticles.loaded(next)) return remove();

            var block = spot.getWorld().rayTraceBlocks(spot, heading, speed, FluidCollisionMode.NEVER, true);
            Vector impact = block == null ? null : block.getHitPosition();
            double nearest = impact == null ? speed * speed : impact.distanceSquared(spot.toVector());
            for (Player player : players) {
                var hitBox = player.getBoundingBox().expand(.45);
                var hit = hitBox.rayTrace(spot.toVector(), heading, speed);
                Vector point = hitBox.contains(spot.toVector()) ? spot.toVector() : hit == null ? null : hit.getHitPosition();
                if (point != null && point.distanceSquared(spot.toVector()) <= nearest) {
                    impact = point;
                    nearest = point.distanceSquared(spot.toVector());
                }
            }
            if (impact != null) {
                explode(impact.toLocation(spot.getWorld()), players);
                return remove();
            }
            return move(next, diveTicks, 5);
        }

        private boolean orbit() {
            double nextAngle = orbitAngle + ORBIT_STEP;
            Location next = orbitCenter.clone().add(orbitOffset(nextAngle));
            if (!DragonParticles.loaded(next)) return false;
            Vector step = next.toVector().subtract(spot.toVector());
            double distance = step.length();
            if (distance < 1e-6) return false;
            Vector direction = step.multiply(1 / distance);
            if (spot.getWorld().rayTraceBlocks(spot, direction, distance, FluidCollisionMode.NEVER, true) != null) return false;
            orbitAngle = nextAngle;
            heading = direction;
            return move(next, ticks, 2);
        }

        private boolean move(Location next, int phaseTicks, int trailCount) {
            spot.setX(next.getX());
            spot.setY(next.getY());
            spot.setZ(next.getZ());
            if (decoy != null) {
                if (!decoy.isValid() || !decoy.teleport(spot.clone().setDirection(heading.clone().multiply(-1)))) return remove();
            } else {
                for (Part part : parts) if (!part.display.isValid() || !part.display.teleport(pose(part.offset))) return remove();
            }
            spot.getWorld().spawnParticle(Particle.DRAGON_BREATH, spot, trailCount, .24, .24, .24, .015, 1f);
            if (phaseTicks % 12 == 1) spot.getWorld().playSound(spot, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 1.2f, 1.25f);
            return false;
        }

        private void explode(Location impact, List<Player> players) {
            var world = impact.getWorld();
            world.spawnParticle(Particle.EXPLOSION, impact, 6, .65, .5, .65, .04);
            world.spawnParticle(Particle.DRAGON_BREATH, impact, 55, .65, .45, .65, .16, 1f);
            world.spawnParticle(Particle.ELECTRIC_SPARK, impact, 28, .55, .4, .55, .12);
            world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.7f, .8f);
            for (Player player : players) {
                if (player.getLocation().add(0, .9, 0).distanceSquared(impact) <= settings.radius() * settings.radius()) {
                    DragonSkills.hurt(player, dragon, damage);
                }
            }
        }

        private Location pose(Vector offset) {
            Vector right = new Vector(heading.getZ(), 0, -heading.getX());
            if (right.lengthSquared() < 1e-6) right.setX(1);
            right.normalize();
            Vector up = heading.clone().crossProduct(right).normalize();
            return spot.clone().add(right.multiply(offset.getX())).add(up.multiply(offset.getY()))
                .add(heading.clone().multiply(offset.getZ())).setDirection(heading);
        }

        private void buildModel() {
            Vector headOffset = new Vector(0, .1, .63);
            ItemDisplay head = spot.getWorld().spawn(pose(headOffset), ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(Material.DRAGON_HEAD));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                size(display, .85f, .85f, .85f, false);
            });
            parts.add(new Part(head, headOffset));
            block(Material.BLACK_CONCRETE, new Vector(), .58f, .48f, 1.05f);
            block(Material.BLACK_CONCRETE, new Vector(0, 0, -.82), .19f, .20f, .76f);
            for (int side : new int[]{-1, 1}) {
                block(Material.BLACK_CONCRETE, new Vector(side * .83, .08, -.05), 1.24f, .09f, .51f);
                block(Material.PURPLE_CONCRETE, new Vector(side * 1.30, .08, -.38), .44f, .07f, .51f);
            }
        }

        private void block(Material material, Vector offset, float width, float height, float length) {
            BlockDisplay display = spot.getWorld().spawn(pose(offset), BlockDisplay.class, part -> {
                part.setBlock(material.createBlockData());
                size(part, width, height, length, true);
            });
            parts.add(new Part(display, offset));
        }

        private boolean remove() {
            if (decoy != null) decoy.remove();
            for (Part part : parts) part.display.remove();
            return true;
        }
    }

    private static void size(Display display, float width, float height, float length, boolean centerBlock) {
        display.setPersistent(false);
        display.setTeleportDuration(1);
        var transform = display.getTransformation();
        transform.getScale().set(width, height, length);
        if (centerBlock) transform.getTranslation().set(-width / 2, -height / 2, -length / 2);
        display.setTransformation(transform);
    }

    private record Part(Display display, Vector offset) { }
}
