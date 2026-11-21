package al.dragon;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

final class TrueEndingImpact {
    private static final Set<Material> PERMEABLE = permeableMaterials();

    private TrueEndingImpact() { }

    private static Set<Material> permeableMaterials() {
        Set<Material> materials = EnumSet.of(Material.SNOW, Material.AIR, Material.STRUCTURE_VOID,
            Material.CAVE_AIR, Material.VOID_AIR, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
            Material.LAVA, Material.VINE, Material.CAVE_VINES, Material.TALL_GRASS, Material.TORCH,
            Material.SOUL_TORCH, Material.WARPED_ROOTS, Material.CRIMSON_ROOTS, Material.FERN,
            Material.LARGE_FERN, Material.DEAD_BUSH, Material.BAMBOO, Material.IRON_BARS, Material.LEVER,
            Material.POWDER_SNOW, Material.MOSS_CARPET, Material.KELP, Material.KELP_PLANT,
            Material.TALL_SEAGRASS, Material.SEAGRASS, Material.BUBBLE_COLUMN, Material.END_PORTAL,
            Material.END_GATEWAY, Material.NETHER_PORTAL, Material.LIGHT);
        for (String name : List.of("grass", "replaceable_plants", "standing_signs", "flowers", "buttons",
            "saplings", "climbable", "banners", "tall_flowers", "carpets", "pressure_plates", "crops")) {
            Material material = Material.matchMaterial("minecraft:" + name);
            if (material != null && material.isBlock()) materials.add(material);
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(name), Material.class);
            if (tag != null) materials.addAll(tag.getValues());
        }
        return materials;
    }

    static boolean permeable(Location point) {
        return PERMEABLE.contains(point.getBlock().getType());
    }

    static boolean target(LivingEntity entity) {
        return !entity.isDead() && !(entity instanceof EnderDragon) && !(entity instanceof ArmorStand);
    }

    static List<LivingEntity> targets(Location center, double radius) {
        double squared = radius * radius;
        return center.getWorld().getNearbyLivingEntities(center, radius, radius, radius, TrueEndingImpact::target)
            .stream().filter(entity -> entity.getLocation().distanceSquared(center) <= squared).toList();
    }

    static Location fireballImpact(Location point) {
        Location position = point.clone();
        while (DragonParticles.loaded(position.clone().subtract(0, 1, 0)) && permeable(position.clone().subtract(0, 1, 0))) {
            position.subtract(0, 1, 0);
        }
        while (DragonParticles.loaded(position) && !permeable(position)) position.add(0, 1, 0);
        return DragonParticles.loaded(position.clone().subtract(0, 1, 0)) && DragonParticles.loaded(position) ? position : point.clone();
    }

    static AreaEffectCloud cloud(EnderDragon dragon, Location point, int durationTicks) {
        return cloud(dragon, point, durationTicks, 4);
    }

    static AreaEffectCloud cloud(EnderDragon dragon, Location point, int durationTicks, float radius) {
        Location origin = point.clone();
        origin.setY(origin.getBlockY() + .2);
        return origin.getWorld().spawn(origin, AreaEffectCloud.class, cloud -> {
            cloud.setPersistent(false);
            cloud.setSource(dragon);
            cloud.setOwnerUniqueId(dragon.getUniqueId());
            cloud.setParticle(Particle.DRAGON_BREATH, 1f);
            cloud.setWaitTime(20);
            cloud.setDuration(durationTicks);
            cloud.setReapplicationDelay(20);
            cloud.setRadius(radius);
            cloud.setRadiusPerTick(0);
            cloud.setRadiusOnUse(0);
            cloud.setDurationOnUse(0);
            cloud.addCustomEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 0), true);
        });
    }
}
