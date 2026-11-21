package al.dragon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.HeightMap;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.DragonBattle;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.util.Vector;

final class DragonSkills {
    private static final int STEP = 1;
    private static final int REGULAR_RECOVERY_TICKS = 40;
    private static final double RANGED_CAST_RANGE = 64;
    private static final NamespacedKey RUSH_REACHED = ALDragon.storedKey("rush_reached");
    private static final NamespacedKey RUSH_CASTS = ALDragon.storedKey("rush_casts");
    private static final NamespacedKey ROAR_REACHED = ALDragon.storedKey("roar_reached");
    private static final NamespacedKey ROAR_CASTS = ALDragon.storedKey("roar_casts");
    private static final NamespacedKey PODIUM_CRYSTAL_USED = ALDragon.storedKey("podium_crystal_used");
    private static final NamespacedKey PODIUM_CRYSTAL_REACHED = ALDragon.storedKey("podium_crystal_reached");
    private static final Particle.DustOptions WARNING = new Particle.DustOptions(Color.fromRGB(225, 86, 246), 1.2f);
    private static final Particle.DustOptions BEAM = new Particle.DustOptions(Color.fromRGB(184, 66, 255), 1.6f);
    private final Map<UUID, Fight> fights = new HashMap<>();
    private final Map<UUID, PodiumCrystal> podiumCrystals = new HashMap<>();
    private Settings settings;
    private Language language;
    private final NamespacedKey finaleKey;
    private final NamespacedKey crystalKey;

    DragonSkills(Settings settings, Language language) {
        this.settings = settings;
        this.language = language;
        finaleKey = ALDragon.storedKey("finale_played");
        crystalKey = ALDragon.storedKey("crystals_cleared");
    }

    void updateSettings(Settings updated, Language updatedLanguage) {
        if (!updated.skills()) {
            retain(Set.of());
            clearPodiumCrystals();
        } else if (!settings.music().equals(updated.music())) {
            for (Fight fight : fights.values()) fight.stopMusic();
        }
        settings = updated;
        language = updatedLanguage;
    }

    void retain(Set<UUID> active) {
        fights.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().cleanup();
            return true;
        });
    }

    void tickPodiumCrystals(Set<UUID> active) {
        podiumCrystals.entrySet().removeIf(entry -> entry.getValue().tick(active.contains(entry.getKey())));
    }

    void clearPodiumCrystals() {
        podiumCrystals.values().forEach(PodiumCrystal::cleanup);
        podiumCrystals.clear();
    }

    PodiumCrystal findPodiumCrystal(Entity entity) {
        if (!(entity instanceof EnderCrystal)) return null;
        for (PodiumCrystal crystal : podiumCrystals.values()) if (crystal.matches(entity)) return crystal;
        return null;
    }

    boolean controlsDragonMovement(EnderDragon dragon) {
        Fight fight = fights.get(dragon.getUniqueId());
        return fight != null && (fight.bite != null || fight.trackingLaser != null
            || fight.phase == CombatPhase.DIVE || fight.phase == CombatPhase.LASER);
    }

    boolean isUsingSkyBite(EnderDragon dragon) {
        Fight fight = fights.get(dragon.getUniqueId());
        return fight != null && fight.bite != null;
    }

    void tick(EnderDragon dragon, DragonBattle battle) {
        if (!settings.skills()) return;
        var maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        double remaining = maxHealth == null ? 1 : dragon.getHealth() / maxHealth.getValue();
        var rush = settings.rush();
        var data = dragon.getPersistentDataContainer();
        var charge = settings.podiumCharge();
        if (charge.enabled() && remaining <= charge.health() && !data.has(PODIUM_CRYSTAL_USED)
            && !data.has(PODIUM_CRYSTAL_REACHED)) data.set(PODIUM_CRYSTAL_REACHED, PersistentDataType.BYTE, (byte) 1);
        boolean crystalPending = charge.enabled() && data.has(PODIUM_CRYSTAL_REACHED) && !data.has(PODIUM_CRYSTAL_USED);
        int rushReached = data.getOrDefault(RUSH_REACHED, PersistentDataType.INTEGER, 0);
        int rushCasts = data.getOrDefault(RUSH_CASTS, PersistentDataType.INTEGER, 0);
        if (rush.enabled()) {
            int reached = remaining <= 0.25 ? 3 : remaining <= 0.50 ? 2 : remaining <= 0.75 ? 1 : 0;
            if (reached > rushReached) {
                data.set(RUSH_REACHED, PersistentDataType.INTEGER, reached);
                rushReached = reached;
            }
        }
        boolean rushPending = rush.enabled() && rushCasts < rushReached;
        int roarReached = data.getOrDefault(ROAR_REACHED, PersistentDataType.INTEGER, 0);
        if (settings.extra().roarEnabled()) {
            int next = (roarReached / 2 + 1) * 2;
            while (next <= 8 && remaining <= (10 - next) / 10.0) next += 2;
            if (next - 2 > roarReached) {
                roarReached = next - 2;
                data.set(ROAR_REACHED, PersistentDataType.INTEGER, roarReached);
            }
        }
        UUID id = dragon.getUniqueId();
        Location podium = battle.getEndPortalLocation();
        if (podium == null) podium = dragon.getPodium();
        if (podium == null) return;
        Location center = podium;
        var tuning = settings.tuning();
        int interval = (int) Math.round(settings.intervalTicks() * tuning.cooldownScale());
        List<Player> players = new ArrayList<>();
        for (Player player : dragon.getWorld().getPlayers()) {
            if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && horizontalDistanceSquared(player.getLocation(), center) <= tuning.arenaRadius() * tuning.arenaRadius()) players.add(player);
        }
        if (players.isEmpty()) {
            Fight old = fights.remove(id);
            if (old != null) old.cleanup();
            return;
        }
        Fight fight = fights.computeIfAbsent(id, ignored -> new Fight(dragon, interval));
        fight.age += STEP;
        DragonParticles particles = new DragonParticles(dragon.getWorld().getPlayers());
        if (settings.trail()) particles.trail(dragon.getLocation().add(0, 2.5, 0));
        fight.fireballs.removeIf(shot -> shot.tick(fight, particles));
        fight.mirrorDragons.removeIf(mirror -> mirror.tick(fight, particles));
        fight.waves.removeIf(wave -> wave.tick(dragon, particles));
        if (!settings.music().isBlank() && fight.age % 20 == STEP) {
            for (Player player : players) {
                if (fight.musicListeners.add(player)) player.playSound(player.getLocation(), settings.music(), SoundCategory.MUSIC, 1, 1);
            }
            fight.musicListeners.removeIf(player -> {
                if (players.contains(player)) return false;
                if (player.isOnline()) player.stopSound(settings.music(), SoundCategory.MUSIC);
                return true;
            });
        }
        if ((settings.crystals() || settings.phantoms() > 0) && fight.age % 5 == STEP) checkCrystals(dragon, battle, fight, players, center);
        if (fight.age % 20 == STEP) {
            fight.expirePhantoms();
            fight.breathClouds.removeIf(cloud -> cloud.isDead() || cloud.getTicksLived() > cloud.getDuration() + cloud.getWaitTime());
        }
        if (fight.crystalReaction > 0 && --fight.crystalReaction == 25) {
            Location spot = dragon.getLocation().add(0, 1.5, 0);
            particles.phaseBurst(spot);
            spot.getWorld().playSound(spot, Sound.ENTITY_ENDER_DRAGON_GROWL, 4, .85f);
        }
        var crystal = podiumCrystals.get(id);
        if (crystal != null && crystal.isResolving()) {
            fight.podiumActive = true;
            return;
        }
        if (fight.podiumActive) {
            fight.podiumActive = false;
            recover(fight);
        }
        if (fight.age < fight.nextMajor) return;
        if (fight.rush != null) {
            if (fight.rush.tick(players)) {
                fight.rush = null;
                recover(fight);
            }
            return;
        }
        if (fight.bite != null) {
            if (fight.bite.tick(players)) {
                fight.bite = null;
                recoverRegular(fight);
            }
            return;
        }
        if (fight.trackingLaser != null) {
            if (fight.trackingLaser.tick(players, particles)) {
                fight.trackingLaser.finish();
                fight.trackingLaser = null;
                recoverRegular(fight);
            }
            return;
        }
        if (fight.phase != CombatPhase.NONE) {
            CombatPhase previous = fight.phase;
            runDatapackPhase(dragon, fight, center, particles);
            if (fight.phase == CombatPhase.NONE) {
                if (previous == CombatPhase.EXTRA_FIREBALL) recoverRegular(fight);
                else recover(fight);
            }
            return;
        }
        if (fight.cast != null && fight.cast.tick(dragon, players, settings, fight.breathClouds)) {
            boolean finale = fight.cast.attack == Attack.FINALE;
            fight.cast = null;
            if (finale) {
                recover(fight);
                return;
            }
            recoverRegular(fight);
        }
        fight.cooldown = Math.max(0, fight.cooldown - STEP);
        if (fight.cast != null) return;

        if (crystalPending && dragon.getWorld().isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            Location eggSpot = center.getBlock().getLocation().add(.5, 4, .5);
            if (battle.getEndPortalLocation() == null) {
                eggSpot.setY(dragon.getWorld().getHighestBlockYAt(eggSpot, HeightMap.MOTION_BLOCKING) + 1);
            }
            var chargeCrystal = new PodiumCrystal(eggSpot, charge, language);
            if (chargeCrystal.isValid()) {
                data.set(PODIUM_CRYSTAL_USED, PersistentDataType.BYTE, (byte) 1);
                podiumCrystals.put(id, chargeCrystal);
                fight.podiumActive = true;
                announce(players, "podium-wave-warning", NamedTextColor.RED);
                if (tuning.titleTicks() > 0) {
                    String seconds = Integer.toString((charge.fuseTicks() + 19) / 20);
                    Title warning = Title.title(language.message("podium-wave-title", NamedTextColor.RED),
                        Component.text(language.text("podium-wave-subtitle").replace("{seconds}", seconds), NamedTextColor.WHITE),
                        tuning.titleTimes());
                    for (Player player : players) player.showTitle(warning);
                }
                return;
            }
        }
        if (rushPending) {
            fight.rush = new DragonRushCast(dragon, players, language, rush, tuning, rushCasts);
            data.set(RUSH_CASTS, PersistentDataType.INTEGER, rushCasts + 1);
            fight.cooldown = interval;
            return;
        }
        if (remaining <= tuning.finaleHp() && settings.finale() >= 0 && !dragon.getPersistentDataContainer().has(finaleKey)) {
            dragon.getPersistentDataContainer().set(finaleKey, PersistentDataType.BYTE, (byte) 1);
            for (Player player : players) player.sendActionBar(language.message("finale-warning", NamedTextColor.GOLD));
            dragon.setPhase(EnderDragon.Phase.FLY_TO_PORTAL);
            fight.cast = new Cast(Attack.FINALE, dragon.getLocation(), center.clone().add(0.5, 1, 0.5));
            fight.cooldown = interval;
            return;
        }
        if (startDatapackPhase(dragon, fight, players)) return;
        if (fight.age < fight.nextRegular) return;
        int roarCasts = data.getOrDefault(ROAR_CASTS, PersistentDataType.INTEGER, 0);
        int nextRoarMark = (roarCasts / 2 + 1) * 2;
        if (settings.extra().roarEnabled() && nextRoarMark <= roarReached && fight.age >= fight.nextRoar) {
            roar(dragon.getLocation(), players, settings.extra().roarRange());
            data.set(ROAR_CASTS, PersistentDataType.INTEGER, nextRoarMark);
            fight.nextRoar = fight.age + Math.max(20, tuning.titleTicks() + tuning.titleFadeTicks());
            recoverRegular(fight);
            return;
        }
        if (settings.fireball() >= 0) {
            replaceVanillaFireballs(fight, particles);
            if (fight.phase != CombatPhase.NONE) return;
        }
        if (startMinorSkill(fight, players, remaining)) return;
        if (fight.cooldown > 0) return;
        List<Attack> options = new ArrayList<>(4);
        Location origin = dragon.getLocation().add(0, 2, 0);
        var nearby = players.stream().filter(player -> inRange(origin, player.getEyeLocation(), RANGED_CAST_RANGE)).toList();
        var waveTargets = nearby.stream().filter(player -> Math.abs(player.getLocation().getY() - origin.getY()) <= 8).toList();
        if (settings.shockwave() >= 0 && !waveTargets.isEmpty()) options.add(Attack.SHOCKWAVE);
        if (settings.dive() >= 0 && !nearby.isEmpty()) options.add(Attack.DIVE);
        var visible = nearby.stream().filter(player -> clearCastPath(origin, player.getEyeLocation(), RANGED_CAST_RANGE)).toList();
        if (!visible.isEmpty() && remaining <= tuning.fireballHp() && settings.fireball() >= 0) options.add(Attack.FIREBALL);
        if (!visible.isEmpty() && remaining <= tuning.laserHp() && settings.laser() >= 0) options.add(Attack.LASER);
        if (options.isEmpty()) {
            fight.cooldown = 20;
            return;
        }
        fight.cooldown = interval;
        var random = ThreadLocalRandom.current();
        Attack attack = options.contains(Attack.LASER) && !fight.usedLaser ? Attack.LASER
            : options.contains(Attack.FIREBALL) && !fight.usedFireball ? Attack.FIREBALL
            : remaining <= tuning.diveHp() && options.contains(Attack.DIVE) && !fight.usedDive ? Attack.DIVE
            : options.get(random.nextInt(options.size()));
        if (attack == Attack.LASER) fight.usedLaser = true;
        if (attack == Attack.FIREBALL) fight.usedFireball = true;
        if (attack == Attack.DIVE && remaining <= tuning.diveHp()) fight.usedDive = true;
        var targets = switch (attack) {
            case FIREBALL, LASER -> visible;
            case SHOCKWAVE -> waveTargets;
            default -> nearby;
        };
        Player target = targets.get(random.nextInt(targets.size()));
        Location aim = attack == Attack.LASER ? target.getEyeLocation() : target.getLocation().add(0, 0.15, 0);
        Vector aimVector = aim.toVector().subtract(origin.toVector());
        if (attack == Attack.LASER && aimVector.lengthSquared() >= 1.0e-6) {
            aim = aim.add(aimVector.normalize().multiply(4));
        }
        if (attack == Attack.FIREBALL || attack == Attack.LASER) aim = clip(origin, aim);
        fight.cast = new Cast(attack, origin, aim);
        if (attack == Attack.DIVE) fight.cast.dives = remaining <= tuning.diveHp() ? tuning.diveCount() : 1;
        aim.getWorld().playSound(aim, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 1.35f);
    }

    private boolean startMinorSkill(Fight fight, List<Player> players, double health) {
        if (fight.age < fight.nextMinor) return false;
        var extra = settings.extra();
        MinorSkill selected = null;
        List<Player> targets = List.of();
        int earliest = Integer.MAX_VALUE;
        Location origin = fight.dragon.getLocation();
        Location beamOrigin = origin.clone().add(0, 2, 0);
        var phase = fight.dragon.getPhase();
        boolean biteAllowed = !fight.dragon.isInvulnerable()
            && (phase == EnderDragon.Phase.CIRCLING || phase == EnderDragon.Phase.STRAFING);
        for (MinorSkill minor : MinorSkill.values()) {
            if (minor == MinorSkill.SKY_BITE && !biteAllowed) continue;
            var skill = minor.settings(extra);
            int readyAt = fight.minorReady.getOrDefault(minor, 0);
            if (skill.available(health) && readyAt <= fight.age && readyAt < earliest) {
                var candidates = switch (minor) {
                    case SKY_BITE -> players.stream().filter(player -> SkyBite.canReach(origin, player)).toList();
                    case TRACKING_LASER -> players.stream().filter(player -> clearCastPath(beamOrigin,
                        player.getEyeLocation(), extra.trackingLaser().range())).toList();
                    default -> players.stream().filter(player -> DragonParticles.loaded(player.getLocation())).toList();
                };
                if (candidates.isEmpty()) {
                    fight.minorReady.put(minor, fight.age + 20);
                    continue;
                }
                selected = minor;
                targets = candidates;
                earliest = readyAt;
            }
        }
        if (selected == null) return false;
        Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        for (Player player : players) player.sendActionBar(language.message(selected.warning, NamedTextColor.LIGHT_PURPLE));
        fight.minorReady.put(selected, fight.age + selected.settings(extra).cooldownTicks());
        fight.nextMinor = fight.age + 100;
        if (selected == MinorSkill.SKY_BITE) {
            fight.bite = new SkyBite(fight.dragon, target, language, extra, settings.tuning().damageScale());
        } else if (selected == MinorSkill.TRACKING_LASER) {
            fight.trackingLaser = new TrackingLaser(fight.dragon, target, language, extra.trackingLaser(), settings.tuning().damageScale());
        } else {
            fight.cast = new Cast(selected.attack, origin, target.getLocation().add(0, .15, 0));
        }
        return true;
    }

    private void roar(Location origin, List<Player> players, double range) {
        double rangeSquared = range * range;
        var endermen = origin.getWorld().getNearbyEntitiesByType(Enderman.class, origin, range, range, range,
            enderman -> enderman.isValid() && !enderman.isDead() && enderman.hasAI()
                && enderman.getLocation().distanceSquared(origin) <= rangeSquared);
        var title = Title.title(language.message("ender-roar-title", NamedTextColor.DARK_PURPLE),
            language.message("ender-roar-subtitle", NamedTextColor.WHITE), settings.tuning().titleTimes());
        for (Player player : players) {
            if (settings.tuning().titleTicks() > 0) player.showTitle(title);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 1, .75f);
        }
        new DragonParticles(players).phaseBurst(origin.clone().add(0, 2, 0));

        int[] assigned = new int[players.size()];
        List<Location> locations = players.stream().map(Player::getLocation).toList();
        for (Enderman enderman : endermen) {
            Location position = enderman.getLocation();
            int target = 0;
            for (int index = 1; index < players.size(); index++) {
                if (assigned[index] < assigned[target] || (assigned[index] == assigned[target]
                    && position.distanceSquared(locations.get(index)) < position.distanceSquared(locations.get(target)))) target = index;
            }
            enderman.setTarget(players.get(target));
            if (players.get(target).equals(enderman.getTarget())) assigned[target]++;
        }
    }

    private void checkCrystals(EnderDragon dragon, DragonBattle battle, Fight fight, List<Player> players, Location center) {
        if (fight.crystals == 0) return;
        int count = battle.getHealingCrystals().size();
        int previous = fight.crystals;
        fight.crystals = count;
        if (count > 0) {
            if (settings.crystals() && previous > count) for (Player player : players) {
                player.sendActionBar(Component.text(language.text("crystals-left").replace("{count}", Integer.toString(count)), NamedTextColor.LIGHT_PURPLE));
            }
            return;
        }
        var data = dragon.getPersistentDataContainer();
        if (data.has(crystalKey)) return;
        data.set(crystalKey, PersistentDataType.BYTE, (byte) 1);
        if (settings.crystals()) {
            for (Player player : players) player.sendActionBar(language.message("crystals-cleared", NamedTextColor.GOLD));
            dragon.getWorld().playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 3, 0.75f);
            fight.crystalReaction = 45;
        }
        for (int index = 0; index < Math.min(settings.phantoms(), players.size() + 2); index++) {
            Player target = players.get(index % players.size());
            Location spot = target.getLocation().add(index - 1, 10, 0);
            Phantom phantom = dragon.getWorld().spawn(spot, Phantom.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, entity -> {
                entity.setSize(2);
                entity.setPersistent(false);
                entity.setRemoveWhenFarAway(false);
            });
            if (phantom.isValid()) {
                phantom.setTarget(target);
                fight.phantoms.add(phantom);
            }
        }
        if (!fight.phantoms.isEmpty()) announce(players, "phantom-reinforcements", NamedTextColor.LIGHT_PURPLE);
        fight.phantomsExpire = fight.age + settings.tuning().phantomSeconds() * 20;
    }

    private void recover(Fight fight) {
        fight.nextMajor = fight.age + settings.majorRecoveryTicks();
    }

    private static void recoverRegular(Fight fight) {
        fight.nextRegular = fight.age + REGULAR_RECOVERY_TICKS;
    }

    private static boolean inRange(Location from, Location to, double range) {
        return DragonParticles.loaded(from) && DragonParticles.loaded(to) && from.distanceSquared(to) <= range * range;
    }

    static boolean clearCastPath(Location from, Location to, double range) {
        if (!inRange(from, to, range)) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < .01) return true;
        Vector direction = delta.multiply(1 / length);
        Location probe = from.clone();
        for (double distance = 1; distance < length; distance++) {
            if (!DragonParticles.loaded(probe.add(direction))) return false;
        }
        return from.getWorld().rayTraceBlocks(from, direction, length, FluidCollisionMode.NEVER, true) == null;
    }

    private boolean startDatapackPhase(EnderDragon dragon, Fight fight, List<Player> players) {
        if (fight.phase != CombatPhase.NONE) return false;
        var maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return false;
        double health = dragon.getHealth() / maxHealth.getValue();
        if (!fight.halfPhase && health <= settings.tuning().fireballHp() && settings.fireball() >= 0) {
            fight.halfPhase = true;
            fight.phase = CombatPhase.ULTRA_FIREBALL;
            fight.phaseTick = 0;
            dragon.setInvulnerable(true);
            fight.savedAi = dragon.hasAI();
            fight.savedSilent = dragon.isSilent();
            dragon.setAI(false);
            dragon.setSilent(true);
            announce(players, "ultra-fireball-phase", NamedTextColor.LIGHT_PURPLE);
            dragon.getWorld().playSound(dragon.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 6, .6f);
            return true;
        }
        if (!fight.quarterPhase && health <= settings.tuning().laserHp() && settings.laser() >= 0) {
            fight.quarterPhase = true;
            fight.phase = CombatPhase.LASER;
            fight.phaseTick = 0;
            dragon.setInvulnerable(true);
            announce(players, "laser-phase", NamedTextColor.DARK_PURPLE);
            return true;
        }
        if (fight.halfPhase && settings.dive() >= 0 && fight.age >= fight.nextRegular && fight.age % 20 == STEP
            && ThreadLocalRandom.current().nextDouble() < .06
            && players.stream().anyMatch(player -> horizontalDistanceSquared(player.getLocation(), dragon.getLocation()) <= 40 * 40)) {
            fight.phase = CombatPhase.DIVE;
            fight.phaseTick = 0;
            fight.diveCount = health <= settings.tuning().diveHp() ? settings.tuning().diveCount() : 1;
            if (fight.diveCount > 1) fight.phaseTick = -40;
            announce(players, "dive-warning", NamedTextColor.GOLD);
            return true;
        }
        return false;
    }

    private void runDatapackPhase(EnderDragon dragon, Fight fight, Location center, DragonParticles particles) {
        fight.phaseTick += STEP;
        switch (fight.phase) {
            case ULTRA_FIREBALL -> ultraFireball(dragon, fight, particles);
            case EXTRA_FIREBALL -> extraFireball(fight, particles);
            case LASER -> laserPhase(dragon, fight, particles);
            case DIVE -> divePhase(dragon, fight, center, particles);
            case NONE -> { }
        }
    }

    private void ultraFireball(EnderDragon dragon, Fight fight, DragonParticles particles) {
        int tick = fight.phaseTick;
        if (tick == 20) {
            dragon.setAI(fight.savedAi);
            dragon.setSilent(fight.savedSilent);
            dragon.setInvulnerable(false);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            particles.phaseBurst(dragon.getLocation().add(0, 1.5, 0));
            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 7, .8f);
        }
        boolean mainShot = tick >= 40 && tick <= 120 && tick % 20 == 0;
        boolean offsetShot = tick >= 50 && tick <= 130 && tick % 20 == 10;
        if (mainShot || offsetShot) {
            Location origin = dragon.getLocation().add(0, 1.5, 0);
            launchUltra(fight, particles, origin, offsetShot, true);
        }
        if (tick >= 218) {
            dragon.setInvulnerable(false);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
        }
    }

    private void extraFireball(Fight fight, DragonParticles particles) {
        int tick = fight.phaseTick;
        if (tick == 8 || tick == 16 || tick == 24 || tick == 32) {
            launchUltra(fight, particles, fight.dragon.getLocation(), tick != 24, true);
        }
        if (tick >= (fight.quarterPhase ? 35 : 23)) {
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
        }
    }

    private void replaceVanillaFireballs(Fight fight, DragonParticles particles) {
        Location origin = fight.dragon.getLocation();
        for (DragonFireball fireball : origin.getWorld().getNearbyEntitiesByType(DragonFireball.class, origin, 32)) {
            if (!fight.dragon.equals(fireball.getShooter()) || fireball.getTicksLived() > 2) continue;
            fireball.remove();
            launchUltra(fight, particles, origin.clone(), false, false);
            var maxHealth = fight.dragon.getAttribute(Attribute.MAX_HEALTH);
            if (fight.phase == CombatPhase.NONE && fight.cast == null
                && maxHealth != null && fight.dragon.getHealth() < maxHealth.getValue()) {
                fight.phase = CombatPhase.EXTRA_FIREBALL;
                fight.phaseTick = -1;
            }
        }
    }

    private void launchUltra(Fight fight, DragonParticles particles, Location origin, boolean offsetShot, boolean immediate) {
        var targets = origin.getWorld().getPlayers().stream()
            .filter(player -> !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && player.getLocation().distanceSquared(origin) <= 128 * 128).toList();
        if (!targets.isEmpty()) {
            Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            Vector aim = target.getLocation().toVector().subtract(origin.toVector());
            if (aim.lengthSquared() > 1e-6) origin.setDirection(aim);
        }
        if (offsetShot) {
            origin.setYaw(origin.getYaw() + DragonParticles.offset());
            origin.setPitch(origin.getPitch() + DragonParticles.offset());
        }
        UltraFireball shot = new UltraFireball(origin);
        if (!immediate || !shot.tick(fight, particles)) fight.fireballs.add(shot);
        if (immediate) origin.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_SHOOT, 10, 1);
    }

    private void laserPhase(EnderDragon dragon, Fight fight, DragonParticles particles) {
        int tick = fight.phaseTick;
        if (tick <= 40) {
            if (tick == 1) dragon.setPhase(EnderDragon.Phase.FLY_TO_PORTAL);
            return;
        }
        if (tick == 41) {
            fight.laserPose = dragon.getLocation();
            dragon.setPhase(EnderDragon.Phase.HOVER);
        }
        if (tick >= 200 && tick <= 240 && tick % 10 == 0) {
            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 8, 1.6f);
            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 8, 1.1f);
        }
        if (tick == 202) dragon.getWorld().playSound(dragon.getLocation(), Sound.BLOCK_BELL_RESONATE, 8, 1.2f);
        Location pose = fight.laserPose;
        Location actual = dragon.getLocation();
        double rate = tick < 60 ? .5 : 1;
        double rise = actual.getBlockY() < 85 ? rate : actual.getBlockY() > 85 ? -rate : 0;
        Location lifted = actual.clone().add(0, rise, 0);
        if (Math.abs(rise) > .01 && DragonParticles.loaded(lifted)) dragon.teleport(lifted);
        if (tick <= 180 && dragon.getLocation().getBlockY() == 85) fight.phaseTick = 181;
        Vector position = dragon.getLocation().toVector();
        pose.setX(position.getX());
        pose.setY(position.getY());
        pose.setZ(position.getZ());
        if (tick >= 248 && tick <= 253) {
            Location flat = pose.clone();
            flat.setPitch(0);
            particles.laserCharge(flat.add(flat.getDirection().multiply(-5)));
        }
        if (tick >= 250 && tick <= 281) pose.setPitch(90);
        if (tick >= 251 && tick <= 575) {
            if (tick == 251) {
                dragon.setInvulnerable(false);
                dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 4, .5f);
            }
            dragon.setRotation(pose.getYaw(), pose.getPitch());
            sweepLaser(fight, particles, pose);
            double turn = tick < 281 ? 0 : tick <= 300 ? 4.5 : tick <= 460 ? 9
                : tick <= 520 ? 4.5 : tick <= 550 ? 2.25 : tick <= 560 ? 1.125 : 0;
            pose.setYaw(pose.getYaw() + (float) turn);
            double tilt = tick < 311 ? 0 : tick <= 320 ? -1 : tick <= 380 ? -.43 : tick <= 480 ? -.25 : 0;
            pose.setPitch(pose.getPitch() + (float) tilt);
        }
        if (tick >= 575) {
            dragon.setInvulnerable(false);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
        }
    }

    private void sweepLaser(Fight fight, DragonParticles particles, Location pose) {
        Location flat = pose.clone();
        flat.setPitch(0);
        Vector forward = flat.getDirection();
        Vector left = new Vector(forward.getZ(), 0, -forward.getX());
        Location spot = pose.clone().add(forward.multiply(-5)).add(left.multiply(-3));
        Location reverse = pose.clone();
        reverse.setYaw(reverse.getYaw() - 180);
        Vector direction = reverse.getDirection();
        for (int step = 0; step < 128 && DragonParticles.loaded(spot); step++, spot.add(direction)) {
            laserDamage(fight.dragon, particles, spot.clone().subtract(0, 1, 0), 1.5);
            particles.laser(spot, direction);
            if (DragonParticles.stopsLaser(spot)) {
                Location impact = spot.clone();
                impact.setY(impact.getBlockY() + 1);
                if (ThreadLocalRandom.current().nextInt(10) == 0 && DragonParticles.loaded(impact)) {
                    fight.breathClouds.add(TrueEndingImpact.cloud(fight.dragon, impact, 3600));
                }
                particles.laserHit(spot);
                laserDamage(fight.dragon, particles, impact.subtract(0, 1, 0), 4.5);
                break;
            }
        }
    }

    private void laserDamage(EnderDragon dragon, DragonParticles particles, Location spot, double radius) {
        for (LivingEntity target : TrueEndingImpact.targets(spot, radius)) {
            Location position = target.getLocation();
            particles.laserEntityHit(position);
            position.getWorld().playSound(position, Sound.ENTITY_BLAZE_DEATH, 2, .6f);
            position.getWorld().playSound(position, Sound.ITEM_TRIDENT_THUNDER, 2, 2);
            hurtEntity(target, dragon, settings.laser() * settings.tuning().damageScale(), DamageType.DRAGON_BREATH);
        }
    }

    private void divePhase(EnderDragon dragon, Fight fight, Location center, DragonParticles particles) {
        int tick = fight.phaseTick;
        if (fight.diveCount > 1 && (tick == 20 || tick == 50)) {
            Location origin = dragon.getLocation();
            double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
            origin.add(6 * Math.cos(angle), 0, 6 * Math.sin(angle));
            if (DragonParticles.loaded(origin)) {
                origin.setY(Math.min(origin.getWorld().getHighestBlockYAt(origin) + 129, origin.getWorld().getMaxHeight() - 1));
                fight.mirrorDragons.add(new MirrorDragon(origin));
            }
        }
        Location spot = dragon.getLocation();
        if (fight.diveCount > 1 && tick <= 80 && DragonParticles.loaded(center)) {
            Location warning = center.clone();
            warning.setY(warning.getWorld().getHighestBlockYAt(warning, org.bukkit.HeightMap.MOTION_BLOCKING) - 5);
            float pitch = (float) Math.min(72, Math.max(0, (tick + 1) * 1.8));
            particles.diveWarning(warning, (tick + 40) * 2, pitch);
        }
        if (tick == 0 || tick == 10 || tick == 20) spot.getWorld().playSound(spot, Sound.ENTITY_ENDER_DRAGON_GROWL, 5, 1);
        if (tick == 40 || tick == 44 || tick == 48 || tick == 540 || tick == 544 || tick == 548
            || tick == 570 || tick == 574 || tick == 578) spot.getWorld().playSound(spot, Sound.ENTITY_ENDER_DRAGON_FLAP, 5, 1);
        double rise = tick >= 1 && tick <= 30 ? (tick <= 10 || tick >= 21 ? .5 : 1)
            : tick >= 40 && tick < 200 ? (tick < 50 ? -.5 : tick < 60 ? -1 : -2)
            : tick > 200 && tick <= 210 ? .2 : tick >= 500 && tick <= 520 ? -1 : tick >= 560 && tick <= 600 ? .4 : 0;
        if (tick >= 500 && tick <= 600) dragon.setPhase(EnderDragon.Phase.LAND_ON_PORTAL);
        if (tick >= 521 && tick <= 599) {
            spot.setX(center.getBlockX());
            spot.setZ(center.getBlockZ());
        }
        if ((rise != 0 || (tick >= 521 && tick <= 599)) && DragonParticles.loaded(spot.add(0, rise, 0))) dragon.teleport(spot);
        if (tick >= 40 && tick <= 200) {
            Location current = dragon.getLocation();
            Location below = current.clone().subtract(0, 3, 0);
            if (tick == 200 || current.getBlockY() <= 32 || (DragonParticles.loaded(below) && !TrueEndingImpact.permeable(below))) {
                diveImpact(fight, particles, current);
                fight.phaseTick = fight.diveCount > 1 ? 499 : 200;
            }
        }
        if ((fight.diveCount <= 1 && tick == 210) || tick >= 800) {
            dragon.setInvulnerable(false);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
        }
    }

    private void diveImpact(Fight fight, DragonParticles particles, Location position) {
        particles.diveHit(position);
        position.getWorld().playSound(position, Sound.ENTITY_GENERIC_EXPLODE, 6, .8f);
        position.getWorld().playSound(position, Sound.ENTITY_WARDEN_SONIC_BOOM, 6, 1.3f);
        if (settings.shockwave() >= 0) fight.waves.add(new GroundWave(position.clone()));
        for (LivingEntity target : TrueEndingImpact.targets(position, 12)) {
            double distance = target.getLocation().distanceSquared(position);
            if (distance < .000001) continue;
            double damage = settings.dive() * settings.tuning().damageScale() * (distance <= 36 ? 1 : .8);
            hurtEntity(target, fight.dragon, damage, DamageType.DRAGON_BREATH);
        }
    }

    private void announce(List<Player> players, String key, NamedTextColor color) {
        Component message = language.message(key, color);
        for (Player player : players) player.sendMessage(message);
    }

    private static Location clip(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 0.01) return to;
        Vector direction = delta.multiply(1 / length);
        var hit = from.getWorld().rayTraceBlocks(from, direction, length, FluidCollisionMode.NEVER, true);
        return hit == null ? to : hit.getHitPosition().subtract(direction.multiply(0.1)).toLocation(from.getWorld());
    }

    private static double horizontalDistanceSquared(Location first, Location second) {
        double xDistance = first.getX() - second.getX();
        double zDistance = first.getZ() - second.getZ();
        return xDistance * xDistance + zDistance * zDistance;
    }

    private static void ring(Location center, double radius, int points, Particle.DustOptions dust) {
        for (int point = 0; point < points; point++) {
            double angle = 2 * Math.PI * point / points;
            Location position = center.clone().add(radius * Math.cos(angle), 0.15, radius * Math.sin(angle));
            center.getWorld().spawnParticle(Particle.DUST, position, 1, dust);
        }
    }

    private static void line(Location start, Location end, double spacing, Particle.DustOptions dust) {
        Vector delta = end.toVector().subtract(start.toVector());
        int segments = Math.clamp((int) Math.ceil(delta.length() / spacing), 1, 80);
        for (int point = 0; point <= segments; point++) {
            Location position = start.clone().add(delta.clone().multiply((double) point / segments));
            start.getWorld().spawnParticle(Particle.DUST, position, 1, dust);
        }
    }

    static void hurt(Player player, EnderDragon dragon, double damage) {
        hurtEntity(player, dragon, damage, DamageType.INDIRECT_MAGIC);
    }

    private static void hurtEntity(LivingEntity target, EnderDragon dragon, double damage, DamageType type) {
        if (damage > 0) {
            target.damage(damage, DamageSource.builder(type)
                .withCausingEntity(dragon).withDirectEntity(dragon).build());
        }
    }

    private enum Attack { SHOCKWAVE, FIREBALL, LASER, DIVE, FINALE, LEVITATION_FIELD, BREATH_ERUPTION, CRYSTAL_BURST }
    private enum MinorSkill {
        TRACKING_LASER(null, "tracking-laser-warning"), SKY_BITE(null, "sky-bite-warning"), LEVITATION(Attack.LEVITATION_FIELD, "levitation-field-warning"),
        BREATH(Attack.BREATH_ERUPTION, "breath-eruption-warning"), CRYSTAL(Attack.CRYSTAL_BURST, "crystal-burst-warning");

        private final Attack attack;
        private final String warning;

        MinorSkill(Attack attack, String warning) {
            this.attack = attack;
            this.warning = warning;
        }

        private Settings.AreaSkill settings(Settings.ExtraSkills extra) {
            return switch (this) {
                case TRACKING_LASER -> extra.trackingLaser().skill();
                case SKY_BITE -> extra.skyBite();
                case LEVITATION -> extra.levitation();
                case BREATH -> extra.breath();
                case CRYSTAL -> extra.crystal();
            };
        }
    }
    private enum CombatPhase { NONE, ULTRA_FIREBALL, EXTRA_FIREBALL, LASER, DIVE }

    private final class MirrorDragon {
        private final EnderDragon entity;
        private final Location position;
        private int age;

        private MirrorDragon(Location origin) {
            position = origin;
            entity = origin.getWorld().spawn(origin, EnderDragon.class, mirror -> {
                DragonRushCast.markDecoy(mirror);
                mirror.setAI(false);
                mirror.setSilent(true);
                mirror.setPersistent(false);
                mirror.setInvulnerable(true);
                mirror.setPhase(EnderDragon.Phase.HOVER);
            });
        }

        private boolean tick(Fight fight, DragonParticles particles) {
            if (!entity.isValid() || ++age >= 1000) return remove();
            particles.mirrorTrail(position.clone().add(0, 2.5, 0));
            position.subtract(0, 1.8, 0);
            Location impactCheck = position.clone().subtract(0, 3, 0);
            if (!DragonParticles.loaded(impactCheck)) return remove();
            if (age < 900 && !TrueEndingImpact.permeable(impactCheck)) {
                diveImpact(fight, particles, position);
            }
            if (!TrueEndingImpact.permeable(position.clone().subtract(0, 2, 0))) age = Math.max(age, 900);
            return !entity.teleport(position) && remove();
        }

        private boolean remove() {
            entity.remove();
            return true;
        }
    }

    private final class UltraFireball {
        private final Location spot;
        private final Vector direction;
        private int age;

        private UltraFireball(Location spot) {
            this.spot = spot;
            direction = spot.getDirection();
        }

        private boolean tick(Fight fight, DragonParticles particles) {
            age++;
            double speed = age <= 10 ? .25 : age <= 20 ? .5 : age <= 30 ? .9 : 1.3;
            spot.add(direction.getX() * speed, direction.getY() * speed, direction.getZ() * speed);
            if (!DragonParticles.loaded(spot)) return true;
            particles.fireball(spot);
            if (age >= 200) return true;
            Location below = spot.clone().subtract(0, 1, 0);
            boolean hitEntity = age >= 10 && !TrueEndingImpact.targets(below, 1.4).isEmpty();
            if (!hitEntity && TrueEndingImpact.permeable(spot)) return false;
            Location impact = TrueEndingImpact.fireballImpact(spot);
            particles.fireballHit(impact);
            impact.getWorld().playSound(impact, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 4, 1);
            fight.breathClouds.add(TrueEndingImpact.cloud(fight.dragon, impact, 1200));
            var targets = TrueEndingImpact.targets(impact, 3.5);
            double damage = settings.fireball() * settings.tuning().damageScale();
            for (LivingEntity target : targets) {
                if (target.getLocation().distanceSquared(impact) >= .000001) hurtEntity(target, fight.dragon, damage, DamageType.MOB_ATTACK);
            }
            for (LivingEntity target : targets) {
                if (target.getLocation().distanceSquared(impact) >= .000001) hurtEntity(target, fight.dragon, damage, DamageType.DRAGON_BREATH);
            }
            return true;
        }
    }

    private final class GroundWave {
        private final List<Location> rays = new ArrayList<>();
        private int age;

        private GroundWave(Location origin) {
            for (int index = 1; index <= 144; index++) {
                Location ray = origin.clone();
                ray.setY(origin.getBlockY() + .4);
                ray.setPitch(0);
                ray.setYaw(origin.getYaw() + index * 2.5f);
                rays.add(ray);
            }
        }

        private boolean tick(EnderDragon dragon, DragonParticles particles) {
            age++;
            rays.removeIf(ray -> !moveRay(ray, dragon, particles));
            return age >= 70 || rays.isEmpty();
        }

        private boolean moveRay(Location ray, EnderDragon dragon, DragonParticles particles) {
            Location below = ray.clone().subtract(0, 1, 0);
            if (!DragonParticles.loaded(ray) || !DragonParticles.loaded(below)) return false;
            if (TrueEndingImpact.permeable(below) || !TrueEndingImpact.permeable(ray)) {
                Location aligned = ray.clone();
                aligned.setY(aligned.getBlockY() + .2);
                ray.setY(TrueEndingImpact.fireballImpact(aligned).getY());
            }
            particles.groundWave(ray);
            for (LivingEntity target : TrueEndingImpact.targets(ray.clone().subtract(0, 1, 0), 1.4)) {
                hurtEntity(target, dragon, settings.shockwave() * settings.tuning().damageScale(), DamageType.DRAGON_BREATH);
            }
            ray.add(ray.getDirection().multiply(.5));
            if (age >= 70) particles.groundWaveEnd(ray);
            return true;
        }
    }

    private final class Fight {
        private final EnderDragon dragon;
        private int cooldown;
        private int nextRoar;
        private int nextMajor;
        private int nextRegular;
        private boolean podiumActive;
        private boolean usedFireball;
        private boolean usedLaser;
        private boolean usedDive;
        private int crystalReaction;
        private int age;
        private int crystals = -1;
        private int phantomsExpire;
        private final List<Phantom> phantoms = new ArrayList<>();
        private final Set<Player> musicListeners = new HashSet<>();
        private final List<UltraFireball> fireballs = new ArrayList<>();
        private final List<AreaEffectCloud> breathClouds = new ArrayList<>();
        private final List<MirrorDragon> mirrorDragons = new ArrayList<>();
        private final List<GroundWave> waves = new ArrayList<>();
        private Location laserPose;
        private Cast cast;
        private CombatPhase phase = CombatPhase.NONE;
        private int phaseTick;
        private boolean halfPhase;
        private boolean savedAi;
        private boolean savedSilent;
        private boolean quarterPhase;
        private int diveCount;
        private DragonRushCast rush;
        private SkyBite bite;
        private TrackingLaser trackingLaser;
        private final Map<MinorSkill, Integer> minorReady = new EnumMap<>(MinorSkill.class);
        private int nextMinor;

        private Fight(EnderDragon dragon, int cooldown) {
            this.dragon = dragon;
            this.cooldown = cooldown;
        }

        private void expirePhantoms() {
            if (age >= phantomsExpire) clearPhantoms();
            else phantoms.removeIf(phantom -> !phantom.isValid());
        }

        private void clearPhantoms() {
            for (Phantom phantom : phantoms) if (phantom.isValid()) phantom.remove();
            phantoms.clear();
        }

        private void cleanup() {
            if (rush != null) rush.cleanup();
            if (bite != null) bite.finish(false);
            if (trackingLaser != null) trackingLaser.finish();
            breathClouds.forEach(AreaEffectCloud::remove);
            breathClouds.clear();
            mirrorDragons.forEach(MirrorDragon::remove);
            mirrorDragons.clear();
            if (phase == CombatPhase.ULTRA_FIREBALL && phaseTick < 20 && dragon.isValid()) {
                dragon.setAI(savedAi);
                dragon.setSilent(savedSilent);
            }
            if (phase != CombatPhase.NONE && dragon.isValid() && dragon.getPhase() != EnderDragon.Phase.DYING) {
                dragon.setInvulnerable(false);
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
            }
            clearPhantoms();
            stopMusic();
        }

        private void stopMusic() {
            for (Player player : musicListeners) if (player.isOnline()) player.stopSound(settings.music(), SoundCategory.MUSIC);
            musicListeners.clear();
        }
    }

    private static final class Cast {
        private final Attack attack;
        private final Location origin;
        private final Location aim;
        private final Set<UUID> hit = new HashSet<>();
        private List<Location> areaSpots;
        private int age;
        private int dives = 1;
        private Location diveTarget;
        private Location diveOrigin;

        private Cast(Attack attack, Location origin, Location aim) {
            this.attack = attack;
            this.origin = origin;
            this.aim = aim;
        }

        private boolean tick(EnderDragon dragon, List<Player> players, Settings settings, List<AreaEffectCloud> clouds) {
            age += STEP;
            var tuning = settings.tuning();
            double scale = tuning.damageScale();
            return switch (attack) {
                case SHOCKWAVE -> shockwave(dragon, players, settings.shockwave() * scale, tuning.shockwaveRadius(), tuning.shockwaveWarning());
                case FIREBALL -> fireball(dragon, players, settings.fireball() * scale, tuning.fireballRadius(), tuning.fireballWarning());
                case LASER -> laser(dragon, players, settings.laser() * scale, tuning.laserRadius(), tuning.laserWarning());
                case DIVE -> dive(dragon, players, settings.dive() * scale, tuning.diveRadius(), tuning.diveWarning());
                case FINALE -> finale(dragon, players, settings.finale() * scale, tuning.finaleRadius(), tuning.finaleWarning());
                case LEVITATION_FIELD -> areaSkill(dragon, players, settings.extra().levitation(), scale, clouds);
                case BREATH_ERUPTION -> areaSkill(dragon, players, settings.extra().breath(), scale, clouds);
                case CRYSTAL_BURST -> areaSkill(dragon, players, settings.extra().crystal(), scale, clouds);
            };
        }

        private boolean areaSkill(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale, List<AreaEffectCloud> clouds) {
            if (!DragonParticles.loaded(aim)) return true;
            if (areaSpots == null) {
                areaSpots = new ArrayList<>(3);
                areaSpots.add(aim);
                if (attack == Attack.CRYSTAL_BURST) {
                    double offset = skill.radius() * 1.65;
                    for (double sign : new double[] {-1, 1}) {
                        Location flank = aim.clone().add(sign * offset, 0, 0);
                        if (!DragonParticles.loaded(flank)) continue;
                        flank = TrueEndingImpact.fireballImpact(flank);
                        if (Math.abs(flank.getY() - aim.getY()) <= 5) areaSpots.add(flank);
                    }
                }
            }
            var spots = areaSpots.stream().filter(DragonParticles::loaded).toList();
            if (age < skill.warningTicks()) {
                if (age % 4 == STEP) for (Location spot : spots) ring(spot, skill.radius(), 24, WARNING);
                return false;
            }
            var world = aim.getWorld();
            for (Location spot : spots) {
                ring(spot, skill.radius(), 32, BEAM);
                switch (attack) {
                    case LEVITATION_FIELD -> world.spawnParticle(Particle.END_ROD, spot, 36, 1.5, .3, 1.5, .16);
                    case BREATH_ERUPTION -> {
                        world.spawnParticle(Particle.DRAGON_BREATH, spot, 55, .7, .35, .7, .16, 1f);
                        clouds.add(TrueEndingImpact.cloud(dragon, spot, 160, (float) skill.radius()));
                    }
                    case CRYSTAL_BURST -> {
                        world.spawnParticle(Particle.EXPLOSION, spot, 6, .7, .7, .7, 0);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, spot, 45, .8, .7, .8, .12);
                        world.playSound(spot, Sound.BLOCK_GLASS_BREAK, 2, .8f);
                    }
                    default -> throw new IllegalStateException("Not an area skill: " + attack);
                }
            }
            for (Player player : players) {
                Location feet = player.getLocation();
                if (spots.stream().noneMatch(spot -> feet.distanceSquared(spot) <= skill.radius() * skill.radius())) continue;
                hurt(player, dragon, skill.damage() * scale);
                if (attack == Attack.LEVITATION_FIELD) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 60, 0));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0));
                }
            }
            return true;
        }

        private boolean dive(EnderDragon dragon, List<Player> players, double damage, double radius, int warning) {
            int stage = (age - STEP) / (warning + 15);
            int frame = (age - STEP) % (warning + 15);
            if (frame == 0) {
                diveTarget = stage == 0 ? aim : players.get(stage % players.size()).getLocation();
                diveOrigin = dragon.getLocation().add(0, 2, 0);
                diveTarget.getWorld().playSound(diveTarget, Sound.ENTITY_ENDER_DRAGON_FLAP, 2, 0.8f);
            }
            if (frame < warning) {
                ring(diveTarget, radius, 22, WARNING);
            } else if (frame < warning + 10) {
                Location position = diveOrigin.clone().add(diveTarget.toVector().subtract(diveOrigin.toVector()).multiply((frame - warning + 5) / 15.0));
                diveTarget.getWorld().spawnParticle(Particle.DRAGON_BREATH, position, 18, 0.65, 0.4, 0.65, 0.02, 1f);
            } else {
                ring(diveTarget, radius, 28, BEAM);
                diveTarget.getWorld().playSound(diveTarget, Sound.ENTITY_GENERIC_EXPLODE, 1, 0.8f);
                for (Player player : players) if (player.getLocation().distanceSquared(diveTarget) <= radius * radius) hurt(player, dragon, damage);
                return stage + 1 >= dives;
            }
            return false;
        }

        private boolean finale(EnderDragon dragon, List<Player> players, double damage, double maxRadius, int warning) {
            if (age < warning) {
                if (age % 10 == 0) ring(aim, maxRadius, 48, WARNING);
                if (age % 20 == 0) aim.getWorld().playSound(aim, Sound.ENTITY_WARDEN_HEARTBEAT, 3, 0.8f);
                return false;
            }
            if (age == warning) aim.getWorld().playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 3, 0.65f);
            double radius = Math.min(maxRadius, 1 + (age - warning) * 0.5);
            ring(aim, radius, 42, BEAM);
            for (Player player : players) {
                Location position = player.getLocation();
                if (Math.abs(position.getY() - aim.getY()) <= 4 && horizontalDistanceSquared(position, aim) <= radius * radius
                    && hit.add(player.getUniqueId())) hurt(player, dragon, damage);
            }
            return radius >= maxRadius;
        }

        private boolean shockwave(EnderDragon dragon, List<Player> players, double damage, double maxRadius, int warning) {
            if (age <= warning) {
                ring(aim, Math.min(2, maxRadius), 18, WARNING);
                return false;
            }
            double radius = Math.min(maxRadius, (age - warning) * 0.4);
            if (age == warning + STEP) aim.getWorld().playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
            ring(aim, radius, 36, BEAM);
            for (Player player : players) {
                Location position = player.getLocation();
                double distance = Math.sqrt(horizontalDistanceSquared(position, aim));
                if (Math.abs(position.getY() - aim.getY()) <= 1.5 && distance >= Math.max(0, radius - 2.6)
                    && distance <= Math.min(maxRadius, radius + 0.6) && hit.add(player.getUniqueId())) hurt(player, dragon, damage);
            }
            return radius >= maxRadius;
        }

        private boolean fireball(EnderDragon dragon, List<Player> players, double damage, double radius, int warning) {
            if (age <= warning) {
                ring(aim, radius, 24, WARNING);
                return false;
            }
            if (age < warning + 25) {
                double progress = (age - warning) / 25.0;
                Vector route = aim.toVector().subtract(origin.toVector());
                Location projectile = origin.clone().add(route.multiply(progress));
                aim.getWorld().spawnParticle(Particle.DRAGON_BREATH, projectile, 24, 0.45, 0.45, 0.45, 0.025, 1f);
                ring(aim, radius, 24, WARNING);
                return false;
            }
            aim.getWorld().spawnParticle(Particle.DRAGON_BREATH, aim, 100, 1.5, 0.7, 1.5, 0.04, 1f);
            aim.getWorld().playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 1, 1.1f);
            for (Player player : players) {
                if (player.getLocation().distanceSquared(aim) <= radius * radius) hurt(player, dragon, damage);
            }
            return true;
        }

        private boolean laser(EnderDragon dragon, List<Player> players, double damage, double radius, int warning) {
            Location endpoint = clip(origin, aim);
            if (age <= warning) {
                line(origin, endpoint, 3, WARNING);
                return false;
            }
            line(origin, endpoint, 0.65, BEAM);
            origin.getWorld().playSound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);
            Vector segment = endpoint.toVector().subtract(origin.toVector());
            double lengthSquared = segment.lengthSquared();
            if (lengthSquared < 0.01) return true;
            for (Player player : players) {
                Vector toPlayer = player.getLocation().add(0, 0.9, 0).toVector().subtract(origin.toVector());
                double projection = toPlayer.dot(segment) / lengthSquared;
                if (projection >= 0 && projection <= 1
                    && toPlayer.subtract(segment.clone().multiply(projection)).lengthSquared() <= radius * radius) {
                    hurt(player, dragon, damage);
                }
            }
            return true;
        }
    }
}
