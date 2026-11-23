package al.dragon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class LaserGuard {
    private static final int BLOCK_LIMIT = 60;
    private static final int SHIELD_COOLDOWN = 100;
    private final Map<UUID, Contact> contacts = new HashMap<>();

    boolean block(Player player, Location source, int tick) {
        UUID id = player.getUniqueId();
        if (!player.isBlocking() || player.hasCooldown(Material.SHIELD) || player.getActiveItem().getType() != Material.SHIELD) {
            contacts.remove(id);
            return false;
        }
        Location eyes = player.getEyeLocation();
        Vector facing = eyes.getDirection().setY(0);
        Vector toward = source.toVector().subtract(eyes.toVector()).setY(0);
        double dot = facing.dot(toward);
        if (toward.lengthSquared() >= 1e-6 && (dot <= 0 || dot * dot < .25 * facing.lengthSquared() * toward.lengthSquared())) {
            contacts.remove(id);
            return false;
        }
        Contact contact = contacts.get(id);
        int start = contact == null || tick - contact.last() > 2 ? tick : contact.start();
        if (tick - start + 1 >= BLOCK_LIMIT) {
            contacts.remove(id);
            player.clearActiveItem();
            player.setCooldown(Material.SHIELD, SHIELD_COOLDOWN);
            player.playSound(eyes, Sound.ITEM_SHIELD_BREAK, 1, 1);
        } else {
            contacts.put(id, new Contact(start, tick));
        }
        return true;
    }

    static double loadedLength(Location origin, Vector direction, double limit) {
        if (limit <= 0 || !DragonParticles.loaded(origin)) return 0;
        var world = origin.getWorld();
        double maxDistance = limit;
        if (direction.getY() > 1e-9) {
            maxDistance = Math.min(maxDistance, (world.getMaxHeight() - .001 - origin.getY()) / direction.getY());
        } else if (direction.getY() < -1e-9) {
            maxDistance = Math.min(maxDistance, (world.getMinHeight() + .001 - origin.getY()) / direction.getY());
        }
        if (maxDistance <= 0) return 0;
        Location probe = origin.clone();
        double distance = 0;
        while (distance < maxDistance) {
            int chunkX = probe.getBlockX() >> 4;
            int chunkZ = probe.getBlockZ() >> 4;
            double toX = Double.POSITIVE_INFINITY;
            double toZ = Double.POSITIVE_INFINITY;
            if (direction.getX() > 1e-9) {
                toX = ((chunkX + 1) * 16.0 - probe.getX()) / direction.getX();
            } else if (direction.getX() < -1e-9) {
                toX = (chunkX * 16.0 - probe.getX()) / direction.getX();
            }
            if (direction.getZ() > 1e-9) {
                toZ = ((chunkZ + 1) * 16.0 - probe.getZ()) / direction.getZ();
            } else if (direction.getZ() < -1e-9) {
                toZ = (chunkZ * 16.0 - probe.getZ()) / direction.getZ();
            }
            double boundaryDistance = Math.min(toX, toZ);
            if (!Double.isFinite(boundaryDistance) || boundaryDistance >= maxDistance - distance) return maxDistance;
            double advance = Math.max(0, boundaryDistance) + .01;
            if (advance >= maxDistance - distance) return maxDistance;
            probe.add(direction.getX() * advance, direction.getY() * advance, direction.getZ() * advance);
            distance += advance;
            if (!DragonParticles.loaded(probe)) return Math.max(0, distance - .01);
        }
        return maxDistance;
    }

    static boolean hasLineOfSight(Location source, Location target) {
        Vector direction = target.toVector().subtract(source.toVector());
        double distance = direction.length();
        if (distance < .001) return true;
        direction.multiply(1 / distance);
        return loadedLength(source, direction, distance) >= distance
            && source.getWorld().rayTraceBlocks(source, direction, distance, FluidCollisionMode.NEVER, true) == null;
    }

    private record Contact(int start, int last) {}
}
