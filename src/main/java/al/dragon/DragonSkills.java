package al.dragon;

import java.util.ArrayList;
import java.util.Collections;
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
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.DragonFireball;
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
    private static final MinorSkill[] MINOR_ORDER = MinorSkill.values();
    private static final double RANGED_CAST_RANGE = 64;
    private static final int CHARGE_WARNING_TICKS = 28;
    private static final int NATIVE_CHARGE_REFRESH_TICKS = 7;
    private static final int CHARGE_RETRY_TICKS = 40;
    private static final int CHARGE_STUN_TICKS = 100;
    private static final double CHARGE_MAX_RANGE = 56;
    private static final int LASER_FLIGHT_TIMEOUT = 120;
    private static final int LASER_RETRY_TICKS = 100;
    private static final NamespacedKey RUSH_REACHED = ALDragon.storedKey("rush_reached");
    private static final NamespacedKey RUSH_CASTS = ALDragon.storedKey("rush_casts");
    private static final NamespacedKey ROAR_REACHED = ALDragon.storedKey("roar_reached");
    private static final NamespacedKey ROAR_CASTS = ALDragon.storedKey("roar_casts");
    private static final NamespacedKey PODIUM_CRYSTAL_USED = ALDragon.storedKey("podium_crystal_used");
    private static final NamespacedKey PODIUM_CRYSTAL_REACHED = ALDragon.storedKey("podium_crystal_reached");
    private static final Particle.DustOptions WARNING = new Particle.DustOptions(Color.fromRGB(225, 86, 246), 1.2f);
    private static final Particle.DustOptions FIREBALL_WARNING = new Particle.DustOptions(Color.fromRGB(255, 173, 53), 1.3f);
    private static final Particle.DustOptions DIVE_WARNING = new Particle.DustOptions(Color.fromRGB(255, 84, 67), 1.3f);
    private static final Particle.DustOptions CHARGE_WARNING = new Particle.DustOptions(Color.fromRGB(255, 45, 45), 2f);
    private static final Particle.DustOptions CROSS_WARNING = new Particle.DustOptions(Color.fromRGB(255, 75, 65), 1.65f);
    private static final Particle.DustOptions CROSS_IMPACT = new Particle.DustOptions(Color.fromRGB(255, 202, 112), 1.8f);
    private static final Particle.DustOptions PULL_WARNING = new Particle.DustOptions(Color.fromRGB(184, 114, 255), 1.45f);
    private static final Particle.DustOptions LASER_WARNING = new Particle.DustOptions(Color.fromRGB(92, 226, 255), 1.15f);
    private static final Particle.DustOptions LASER_LOCKED = new Particle.DustOptions(Color.fromRGB(202, 253, 255), 1.5f);
    static final Particle.DustOptions THUNDER_WARNING = new Particle.DustOptions(Color.fromRGB(255, 220, 82), 1.1f);
    private static final Particle.DustOptions CHAIN_BLUE = new Particle.DustOptions(Color.fromRGB(62, 162, 255), 1.1f);
    private static final Particle.DustOptions CHAIN_WHITE = new Particle.DustOptions(Color.fromRGB(224, 247, 255), .9f);
    static final Particle.DustOptions SAFE = new Particle.DustOptions(Color.fromRGB(80, 224, 224), 1f);
    private static final Particle.DustOptions BEAM = new Particle.DustOptions(Color.fromRGB(184, 66, 255), 1.6f);
    private final Map<UUID, Fight> fights = new HashMap<>();
    private final Map<UUID, PodiumCrystal> podiumCrystals = new HashMap<>();
    private Language language;
    private final NamespacedKey finaleKey;
    private final NamespacedKey crystalKey;

    DragonSkills(Language language) {
        this.language = language;
        finaleKey = ALDragon.storedKey("finale_played");
        crystalKey = ALDragon.storedKey("crystals_cleared");
    }

    void updateLanguage(Language updatedLanguage) {
        language = updatedLanguage;
    }

    void retain(Set<UUID> active) {
        fights.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().cleanup();
            if (entry.getValue().revival != null) entry.getValue().revival.cleanup();
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
        return fight != null && (fight.bite != null || fight.trackingLaser != null || fight.laserApproach != null
            || fight.chargeWarning != null || fight.aiChargeUntil > 0 || fight.chargeStunUntil > 0
            || fight.phase == CombatPhase.DIVE || fight.phase == CombatPhase.LASER
            || fight.cast != null && fight.cast.flightControlled);
    }

    boolean isChargeStunned(EnderDragon dragon) {
        Fight fight = fights.get(dragon.getUniqueId());
        return fight != null && (fight.chargeStunUntil > 0 || fight.chargeBlocked != null);
    }

    boolean allowsPhaseChange(EnderDragon dragon, EnderDragon.Phase requested) {
        Fight fight = fights.get(dragon.getUniqueId());
        if (fight == null || !fight.controlsFlightPhase() || fight.pluginPhaseChange) return true;
        return requested == EnderDragon.Phase.DYING;
    }

    boolean replaceVanillaFireball(com.destroystokyo.paper.event.entity.EnderDragonShootFireballEvent event) {
        EnderDragon dragon = event.getEntity();
        Fight fight = fights.get(dragon.getUniqueId());
        if (fight == null || fight.idle || fight.settings.fireball() < 0 || dragon.isInvulnerable()
            || dragon.getPhase() == EnderDragon.Phase.DYING) return false;
        event.setCancelled(true);
        launchUltra(fight, new DragonParticles(dragon.getWorld().getPlayers()), dragon.getLocation().add(0, 1.5, 0), false, false);
        beginRegularCast(fight, (int) Math.round(fight.settings.intervalTicks() * fight.settings.tuning().cooldownScale()));
        return true;
    }

    void blockAiCharge(EnderDragon dragon, ChargeBlock reason) {
        Fight fight = fights.get(dragon.getUniqueId());
        if (fight != null && fight.aiChargeUntil > fight.age && fight.chargeBlocked == null
            && dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER) {
            fight.chargeBlocked = reason;
        }
    }

    enum ChargeBlock { SHIELD, WALL }

    boolean isUsingSkyBite(EnderDragon dragon) {
        Fight fight = fights.get(dragon.getUniqueId());
        return fight != null && fight.bite != null;
    }

    void tick(EnderDragon dragon, DragonBattle battle, DragonProfiles.Profile profile) {
        Settings settings = profile.combat();
        if (!settings.skills()) return;
        UUID id = dragon.getUniqueId();
        Fight existing = fights.get(id);
        if (existing != null && tickChargeStun(existing)) return;
        if ((existing == null || existing.idle) && dragon.getTicksLived() % 10 != 0) return;
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
        Location podium = battle.getEndPortalLocation();
        boolean podiumKnown = podium != null;
        if (podium == null) podium = dragon.getPodium();
        if (podium == null) return;
        Location center = podium;
        var tuning = settings.tuning();
        int interval = (int) Math.round(settings.intervalTicks() * tuning.cooldownScale());
        List<Player> players = new ArrayList<>();
        var viewers = dragon.getWorld().getPlayers();
        for (Player player : viewers) {
            if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && horizontalDistanceSquared(player.getLocation(), center) <= tuning.arenaRadius() * tuning.arenaRadius()) players.add(player);
        }
        if (players.isEmpty()) {
            Fight idle = existing;
            if (idle != null) {
                idle.age += idle.idle ? 10 : STEP;
                if (!idle.idle) {
                    if (idle.hasActiveCast()) recover(idle, Math.max(settings.majorRecoveryTicks(), settings.rhythm().recovery()));
                    idle.cleanup();
                    idle.idle = true;
                }
            }
            return;
        }
        Fight fight = fights.computeIfAbsent(id, ignored -> new Fight(dragon, profile, interval));
        fight.idle = false;
        fight.age += STEP;
        DragonParticles particles = new DragonParticles(viewers);
        fight.fireballs.removeIf(shot -> shot.tick(fight, particles));
        fight.mirrorDragons.removeIf(mirror -> mirror.tick(fight, particles));
        fight.waves.removeIf(wave -> wave.tick(dragon, particles, settings));
        if (!settings.music().isBlank() && fight.age % 20 == STEP) {
            Set<Player> present = new HashSet<>(players);
            for (Player player : players) {
                if (fight.musicListeners.add(player)) player.playSound(player.getLocation(), settings.music(), SoundCategory.MUSIC, 1, 1);
            }
            fight.musicListeners.removeIf(player -> {
                if (present.contains(player)) return false;
                if (player.isOnline()) player.stopSound(settings.music(), SoundCategory.MUSIC);
                return true;
            });
        }
        if ((settings.crystals() || settings.phantoms() > 0) && fight.age % 20 == STEP) checkCrystals(dragon, battle, fight, players, center);
        if (fight.age % 20 == STEP) {
            if (fight.revival != null) fight.revival.tickHealing(dragon, settings.crystalRevival());
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
            fight.cancelAiChargeWarning();
            fight.podiumActive = true;
            return;
        }
        if (fight.podiumActive) {
            fight.podiumActive = false;
            recover(fight, settings.majorRecoveryTicks());
        }
        if (fight.rush != null) {
            if (fight.rush.tick(players)) {
                fight.rush = null;
                recover(fight, settings.majorRecoveryTicks());
            }
            return;
        }
        if (fight.bite != null) {
            fight.pluginPhaseChange = true;
            try {
                if (fight.bite.tick(players)) {
                    fight.bite = null;
                    recover(fight, settings.rhythm().recovery());
                }
            } finally {
                fight.pluginPhaseChange = false;
            }
            return;
        }
        if (fight.laserApproach != null) {
            tickLaserApproach(fight, players);
            return;
        }
        if (fight.trackingLaser != null) {
            if (!holdLaserStation(fight, fight.laserPose) || fight.trackingLaser.tick(players, particles)) {
                fight.releaseLaserFlight();
                fight.trackingLaser = null;
                fight.laserPose = null;
                recover(fight, settings.rhythm().recovery());
            }
            return;
        }
        if (fight.phase != CombatPhase.NONE) {
            CombatPhase previous = fight.phase;
            runDatapackPhase(dragon, fight, center, particles);
            if (fight.phase == CombatPhase.NONE) {
                recover(fight, previous == CombatPhase.EXTRA_FIREBALL ? settings.rhythm().recovery() : settings.majorRecoveryTicks());
            }
            return;
        }
        if (fight.revivalCasting) {
            if (fight.revival.tickCast(dragon, settings.crystalRevival(), players, language)) {
                fight.revivalCasting = false;
                recover(fight, settings.majorRecoveryTicks());
            }
            return;
        }
        if (fight.cast != null) {
            if (fight.cast.attack == Attack.LASER) {
                if (dragon.getPhase() != EnderDragon.Phase.HOVER) fight.requestPhase(EnderDragon.Phase.HOVER);
                if (dragon.getVelocity().lengthSquared() > .01) dragon.setVelocity(new Vector());
            }
            if (fight.cast.tick(fight, players, settings, fight.breathClouds, language)) {
                if (fight.cast.attack == Attack.DIVE) fight.lastAttackTarget = fight.cast.regularTarget;
                fight.releaseCastFlight();
                recover(fight, fight.cast.attack.isMajor() ? settings.majorRecoveryTicks() : settings.rhythm().recovery());
                fight.cast = null;
            }
            return;
        }
        if (fight.aiChargeUntil > 0) {
            if (dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER && fight.age < fight.aiChargeUntil) {
                if (fight.age >= fight.nextAiChargeReaim && fight.aiChargeTarget != null) {
                    if (fight.maintainNativeCharge(fight.aiChargeTarget)) {
                        fight.nextAiChargeReaim = fight.age + NATIVE_CHARGE_REFRESH_TICKS;
                    } else {
                        fight.endAiCharge();
                        recover(fight, settings.rhythm().recovery());
                    }
                }
                if (fight.aiChargeUntil > 0) return;
            } else {
                fight.endAiCharge();
                recover(fight, settings.rhythm().recovery());
            }
        }
        if (fight.chargeWarning != null && tickAiChargeWarning(fight, players)) return;
        if (fight.age < fight.nextCast) return;

        if (fight.age >= fight.nextMajorCast) {
            if (remaining <= tuning.finaleHp() && settings.finale() >= 0 && !dragon.getPersistentDataContainer().has(finaleKey)) {
                dragon.getPersistentDataContainer().set(finaleKey, PersistentDataType.BYTE, (byte) 1);
                for (Player player : players) player.sendActionBar(language.message("finale-warning", NamedTextColor.GOLD));
                dragon.setPhase(EnderDragon.Phase.FLY_TO_PORTAL);
                fight.cast = new Cast(Attack.FINALE, dragon.getLocation(), center.clone().add(0.5, 1, 0.5));
                beginMajorCast(fight);
                return;
            }
            if (crystalPending && fight.age >= fight.nextPodiumCheck
                && dragon.getWorld().isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
                fight.nextPodiumCheck = fight.age + 20;
                Location eggSpot = center.getBlock().getLocation().add(.5, 4, .5);
                if (!podiumKnown) {
                    eggSpot.setY(dragon.getWorld().getHighestBlockYAt(eggSpot, HeightMap.MOTION_BLOCKING) + 1);
                }
                var chargeCrystal = new PodiumCrystal(eggSpot, charge, language);
                if (chargeCrystal.isValid()) {
                    data.set(PODIUM_CRYSTAL_USED, PersistentDataType.BYTE, (byte) 1);
                    podiumCrystals.put(id, chargeCrystal);
                    fight.podiumActive = true;
                    beginMajorCast(fight);
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
            if (startDatapackPhase(fight, players, remaining, center)) {
                if (fight.laserApproach == null) beginMajorCast(fight);
                return;
            }
            if (rushPending) {
                fight.rush = new DragonRushCast(dragon, players, language, rush, tuning, rushCasts);
                data.set(RUSH_CASTS, PersistentDataType.INTEGER, rushCasts + 1);
                beginMajorCast(fight);
                return;
            }
            int roarCasts = data.getOrDefault(ROAR_CASTS, PersistentDataType.INTEGER, 0);
            int nextRoarMark = (roarCasts / 2 + 1) * 2;
            if (settings.extra().roarEnabled() && nextRoarMark <= roarReached && fight.age >= fight.nextRoar) {
                roar(dragon.getLocation(), players, settings.extra().roarRange(), tuning);
                data.set(ROAR_CASTS, PersistentDataType.INTEGER, nextRoarMark);
                fight.nextRoar = fight.age + Math.max(settings.rhythm().roarCooldown(), tuning.titleTicks() + tuning.titleFadeTicks());
                beginMajorCast(fight);
                recover(fight, settings.majorRecoveryTicks());
                return;
            }
            if (fight.age >= fight.nextRevivalCheck && CrystalRevival.available(dragon, settings.crystalRevival(), remaining)) {
                fight.nextRevivalCheck = fight.age + settings.crystalRevival().cast().cooldownTicks();
                var revival = CrystalRevival.choose(dragon.getWorld());
                if (revival != null) {
                    fight.revival = revival;
                    fight.revivalCasting = true;
                    beginMajorCast(fight);
                    announce(players, "crystal-revival-warning", NamedTextColor.AQUA);
                    return;
                }
            }
            if (fight.reinforcementsPending) {
                fight.reinforcementsPending = false;
                data.set(crystalKey, PersistentDataType.BYTE, (byte) 1);
                spawnReinforcements(dragon, fight, players);
                beginMajorCast(fight);
                recover(fight, settings.majorRecoveryTicks());
                return;
            }
            if (startMinorSkill(fight, players, remaining, true, center)) return;
        }
        boolean regularTried = false;
        if (fight.preferRegular && fight.age >= fight.nextRegularCast) {
            regularTried = true;
            if (tryRegularSkill(dragon, fight, players, remaining, interval)) return;
        }
        if (startMinorSkill(fight, players, remaining, false, center)) {
            fight.preferRegular = true;
            return;
        }
        if (!regularTried && fight.age >= fight.nextRegularCast
            && tryRegularSkill(dragon, fight, players, remaining, interval)) return;
        tryAiCharge(fight, players);
    }

    private boolean tryRegularSkill(EnderDragon dragon, Fight fight, List<Player> players, double remaining,
                                    int interval) {
        var settings = fight.settings;
        var tuning = settings.tuning();
        Location origin = dragon.getLocation().add(0, 2, 0);
        List<Player> nearby = new ArrayList<>();
        for (Player player : players) {
            if (inRange(origin, player.getEyeLocation(), RANGED_CAST_RANGE)) nearby.add(player);
        }
        boolean fireballReady = settings.fireball() >= 0
            && (remaining <= tuning.fireballHp() || (settings.shockwave() < 0 && settings.dive() < 0));
        boolean laserReady = settings.laser() >= 0 && remaining <= tuning.laserHp();
        Attack attack = null;
        Player target = null;
        var rotation = fight.regularStyle.rotation;
        int checked = 0;
        for (int offset = 0; !nearby.isEmpty() && offset < rotation.size(); offset++) {
            int index = (fight.regularCursor + offset) % rotation.size();
            Attack candidate = rotation.get(index);
            int bit = 1 << candidate.ordinal();
            if ((checked & bit) != 0) continue;
            checked |= bit;
            target = switch (candidate) {
                case FIREBALL -> fireballReady ? rangedTarget(fight, nearby, origin, candidate) : null;
                case LASER -> laserReady ? rangedTarget(fight, nearby, origin, candidate) : null;
                case SHOCKWAVE -> {
                    if (settings.shockwave() < 0) yield null;
                    if (fight.regularStyle == RegularStyle.SCOUT) yield scoutWaveTarget(nearby, origin);
                    var targets = nearby.stream()
                        .filter(player -> Math.abs(player.getLocation().getY() - origin.getY()) <= 8).toList();
                    yield targets.isEmpty() ? null : fight.regularStyle.target(targets, origin, fight.lastAttackTarget);
                }
                case DIVE -> {
                    var diveTargets = nearby.stream()
                        .filter(player -> clearCastPath(origin, player.getEyeLocation(), RANGED_CAST_RANGE)).toList();
                    yield settings.dive() < 0 || !SkyBite.nativeChargeAvailable() || diveTargets.isEmpty() ? null
                        : fight.regularStyle.target(diveTargets, origin, fight.lastAttackTarget);
                }
                default -> throw new IllegalStateException("Unexpected regular attack: " + candidate);
            };
            if (target != null) {
                attack = candidate;
                fight.regularCursor = (index + 1) % rotation.size();
                break;
            }
        }
        if (target == null) {
            fight.nextRegularCast = fight.age + 20;
            return false;
        }
        beginRegularCast(fight, interval);
        fight.lastAttackTarget = target.getUniqueId();
        Location aim = target.getLocation().add(0, attack == Attack.LASER ? .9 : .15, 0);
        if (attack == Attack.DIVE && fight.regularStyle == RegularStyle.STORM) aim = stormDiveAim(origin, aim, target);
        if (attack == Attack.LASER) {
            Vector aimVector = aim.toVector().subtract(origin.toVector());
            if (aimVector.lengthSquared() >= 1.0e-6) aim.add(aimVector.normalize().multiply(4));
        }
        if (attack == Attack.FIREBALL || attack == Attack.LASER) aim = clip(origin, aim);
        fight.cast = new Cast(attack, origin, aim);
        fight.cast.regularStyle = fight.regularStyle;
        fight.cast.regularTarget = target.getUniqueId();
        if (attack == Attack.LASER) {
            fight.cast.flightControlled = true;
            fight.requestPhase(EnderDragon.Phase.HOVER);
            dragon.setVelocity(new Vector());
        }
        if (attack == Attack.DIVE) fight.cast.dives = remaining <= tuning.diveHp() ? tuning.diveCount() : 1;
        String warning = switch (attack) {
            case SHOCKWAVE -> fight.regularStyle == RegularStyle.SCOUT ? "scout-gap-warning" : "regular-shockwave-warning";
            case FIREBALL -> "regular-fireball-warning";
            case DIVE -> fight.regularStyle == RegularStyle.STORM ? "storm-breath-strike-warning"
                : fight.cast.dives > 1 ? "breath-strike-combo-warning" : "breath-strike-warning";
            case LASER -> fight.regularStyle == RegularStyle.ABYSS ? "abyss-focus-warning" : "regular-laser-warning";
            default -> throw new IllegalStateException("Unexpected regular attack: " + attack);
        };
        target.sendActionBar(language.message(warning, NamedTextColor.GOLD));
        aim.getWorld().playSound(aim, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 1.35f);
        return true;
    }

    private static Player scoutWaveTarget(List<Player> nearby, Location origin) {
        Player target = null;
        double closest = Double.POSITIVE_INFINITY;
        for (Player player : nearby) {
            Location feet = player.getLocation();
            if (Math.abs(feet.getY() - origin.getY()) > 8) continue;
            double distance = horizontalDistanceSquared(feet, origin);
            if (distance < closest) {
                closest = distance;
                target = player;
            }
        }
        return target;
    }

    private static Player rangedTarget(Fight fight, List<Player> nearby, Location origin, Attack attack) {
        if (fight.regularStyle == RegularStyle.SCOUT) {
            Player closest = null;
            double best = Double.POSITIVE_INFINITY;
            for (Player player : nearby) {
                Location feet = player.getLocation();
                double distance = feet.distanceSquared(origin);
                if (distance >= best) continue;
                Location point = attack == Attack.FIREBALL ? player.getEyeLocation() : feet.add(0, .9, 0);
                if (clearCastPath(origin, point, RANGED_CAST_RANGE)) {
                    closest = player;
                    best = distance;
                }
            }
            return closest;
        }
        boolean switchTarget = fight.regularStyle == RegularStyle.STORM || fight.regularStyle == RegularStyle.SOVEREIGN;
        Player previous = null;
        var random = ThreadLocalRandom.current();
        for (int remaining = nearby.size(); remaining > 0; remaining--) {
            int index = random.nextInt(remaining);
            Player player = nearby.get(index);
            if (index != remaining - 1) Collections.swap(nearby, index, remaining - 1);
            if (switchTarget && player.getUniqueId().equals(fight.lastAttackTarget)) {
                previous = player;
                continue;
            }
            if (hasRegularCastPath(origin, player, attack)) return player;
        }
        return previous != null && hasRegularCastPath(origin, previous, attack) ? previous : null;
    }

    private static boolean hasRegularCastPath(Location origin, Player player, Attack attack) {
        Location point = attack == Attack.FIREBALL ? player.getEyeLocation() : player.getLocation().add(0, .9, 0);
        return clearCastPath(origin, point, RANGED_CAST_RANGE);
    }

    private static Location stormDiveAim(Location origin, Location aim, Player target) {
        Vector lead = target.getVelocity().setY(0).multiply(7);
        if (lead.lengthSquared() > 9) lead.normalize().multiply(3);
        Location predicted = aim.clone().add(lead);
        return inRange(origin, predicted, RANGED_CAST_RANGE) ? predicted : aim;
    }

    private void tryAiCharge(Fight fight, List<Player> players) {
        int cooldown = fight.settings.aiChargeTicks();
        EnderDragon dragon = fight.dragon;
        if (cooldown == 0 || !SkyBite.nativeChargeAvailable() || fight.age < fight.nextAiCheck || dragon.getPhase() != EnderDragon.Phase.CIRCLING
            || dragon.isInvulnerable()) return;
        fight.nextAiCheck = fight.age + CHARGE_RETRY_TICKS;
        Location origin = dragon.getLocation().add(0, 2, 0);
        Player target = null;
        Location aim = null;
        double best = Double.POSITIVE_INFINITY;
        for (Player player : players) {
            Location spot = player.getLocation().add(0, 1, 0);
            double distance = spot.distanceSquared(origin);
            if (distance < 18 * 18 || distance > CHARGE_MAX_RANGE * CHARGE_MAX_RANGE) continue;
            double priority = distance + (player.getUniqueId().equals(fight.lastAttackTarget) ? CHARGE_MAX_RANGE * CHARGE_MAX_RANGE : 0);
            if (priority >= best) continue;
            double dx = spot.getX() - origin.getX();
            double dz = spot.getZ() - origin.getZ();
            if (dx * dx + dz * dz < 4 || !clearCastPath(origin, spot, CHARGE_MAX_RANGE)) continue;
            Vector heading = new Vector(dx, 0, dz);
            Location endpoint = spot.clone().add(heading.normalize().multiply(4));
            if (!clearCastPath(origin, endpoint, CHARGE_MAX_RANGE + 4)) continue;
            best = priority;
            target = player;
            aim = endpoint;
        }
        if (target == null) return;
        fight.requestPhase(EnderDragon.Phase.HOVER);
        if (dragon.getPhase() != EnderDragon.Phase.HOVER) return;
        dragon.setVelocity(new Vector());
        float yaw = (float) (180 - Math.toDegrees(Math.atan2(aim.getX() - origin.getX(), aim.getZ() - origin.getZ())));
        dragon.setRotation(yaw, 0);
        var warning = new ChargeWarning(target.getUniqueId(), aim, fight.age + CHARGE_WARNING_TICKS);
        fight.chargeWarning = warning;
        drawAiChargeWarning(origin, warning.aim());
        target.sendActionBar(language.message("ai-charge-warning", NamedTextColor.RED));
        dragon.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.5f, 1.25f);
    }

    private boolean tickAiChargeWarning(Fight fight, List<Player> players) {
        ChargeWarning warning = fight.chargeWarning;
        EnderDragon dragon = fight.dragon;
        if (dragon.getPhase() != EnderDragon.Phase.HOVER || dragon.isInvulnerable()
            || players.stream().noneMatch(player -> player.getUniqueId().equals(warning.target()))) {
            fight.cancelAiChargeWarning();
            return false;
        }
        Location origin = dragon.getLocation().add(0, 2, 0);
        if (fight.age < warning.launchAt()) {
            if ((warning.launchAt() - fight.age) % 4 == 0) {
                drawAiChargeWarning(origin, warning.aim());
            }
            return true;
        }
        if (!clearCastPath(origin, warning.aim(), CHARGE_MAX_RANGE + 12)
            || !fight.maintainNativeCharge(warning.aim())) {
            fight.cancelAiChargeWarning();
            return false;
        }
        fight.chargeWarning = null;
        origin.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 3f, 1f);
        fight.nextAiCheck = fight.age + fight.settings.aiChargeTicks();
        fight.aiChargeUntil = fight.age + 65;
        fight.aiChargeTarget = warning.aim().clone();
        fight.nextAiChargeReaim = fight.age + NATIVE_CHARGE_REFRESH_TICKS;
        fight.lastAttackTarget = warning.target();
        beginCast(fight);
        return true;
    }

    private static void drawAiChargeWarning(Location origin, Location aim) {
        var audience = DragonParticles.warningViewers(aim, 64);
        warningLine(origin, aim, 1.25, CHARGE_WARNING, audience);
        warningRing(aim.clone().subtract(0, .7, 0), 1.5, 16, CHARGE_WARNING, audience);
    }

    private boolean tickChargeStun(Fight fight) {
        EnderDragon dragon = fight.dragon;
        if (fight.chargeStunUntil > 0) {
            if (fight.age + STEP >= fight.chargeStunUntil) {
                fight.endChargeStun();
                return false;
            }
            fight.age += STEP;
            if (dragon.getPhase() != EnderDragon.Phase.HOVER) fight.requestPhase(EnderDragon.Phase.HOVER);
            if (dragon.getVelocity().lengthSquared() > 0) dragon.setVelocity(new Vector());
            return true;
        }
        if (fight.aiChargeUntil <= fight.age) return false;
        ChargeBlock reason = fight.chargeBlocked;
        if (reason == null && SkyBite.hitFlightObstacle(dragon)) reason = ChargeBlock.WALL;
        if (reason == null) return false;
        fight.aiChargeUntil = 0;
        fight.aiChargeTarget = null;
        fight.nextAiChargeReaim = 0;
        fight.chargeBlocked = null;
        fight.requestPhase(EnderDragon.Phase.HOVER);
        if (dragon.getPhase() != EnderDragon.Phase.HOVER) return false;
        dragon.setVelocity(new Vector());
        fight.age += STEP;
        fight.chargeStunUntil = fight.age + CHARGE_STUN_TICKS;
        recover(fight, CHARGE_STUN_TICKS);
        Location spot = dragon.getLocation();
        spot.getWorld().playSound(spot, Sound.ENTITY_ENDER_DRAGON_HURT, 3f, .85f);
        announce(DragonParticles.warningViewers(spot, 64),
            reason == ChargeBlock.SHIELD ? "ai-charge-shield-stun" : "ai-charge-wall-stun", NamedTextColor.GOLD);
        return true;
    }

    private record ChargeWarning(UUID target, Location aim, int launchAt) {}

    private boolean startMinorSkill(Fight fight, List<Player> players, double health, boolean major, Location center) {
        var settings = fight.settings;
        var extra = settings.extra();
        MinorSkill selected = null;
        List<Player> targets = List.of();
        int earliest = Integer.MAX_VALUE;
        Location origin = fight.dragon.getLocation();
        List<Player> loadedTargets = null;
        var phase = fight.dragon.getPhase();
        boolean biteAllowed = !fight.dragon.isInvulnerable()
            && (phase == EnderDragon.Phase.CIRCLING || phase == EnderDragon.Phase.STRAFING);
        for (MinorSkill minor : MINOR_ORDER) {
            if ((minor.attack != null && minor.attack.isMajor()) != major) continue;
            if (minor == MinorSkill.SKY_BITE && !biteAllowed) continue;
            var skill = minor.settings(extra);
            int readyAt = fight.minorReady.getOrDefault(minor, 0);
            if (skill.available(health) && readyAt <= fight.age && readyAt < earliest) {
                var candidates = switch (minor) {
                    case SKY_BITE -> players.stream().filter(player -> SkyBite.canReach(origin, player)).toList();
                    case TRACKING_LASER -> {
                        Location beamOrigin = laserStation(center).add(0, 2, 0);
                        Player candidate = null;
                        for (Player player : players) {
                            if (inRange(beamOrigin, player.getEyeLocation(), extra.trackingLaser().range())) {
                                candidate = player;
                                break;
                            }
                        }
                        yield candidate == null ? List.<Player>of() : List.of(candidate);
                    }
                    case THUNDER_CHAIN -> thunderChainTargets(players, skill.radius() * .75);
                    default -> {
                        if (loadedTargets == null) {
                            loadedTargets = players.stream().filter(player -> DragonParticles.loaded(player.getLocation())).toList();
                        }
                        yield loadedTargets;
                    }
                };
                if (candidates.isEmpty()) {
                    fight.minorReady.put(minor, fight.age + (minor == MinorSkill.THUNDER_CHAIN ? 40 : 10));
                    continue;
                }
                selected = minor;
                targets = candidates;
                earliest = readyAt;
            }
        }
        if (selected == null) return false;
        if (selected == MinorSkill.TRACKING_LASER) {
            if (!startLaserApproach(fight, center, false)) {
                fight.minorReady.put(selected, fight.age + LASER_RETRY_TICKS);
                return false;
            }
            beginCast(fight);
            return true;
        }
        Player target = selected == MinorSkill.THUNDER_CHAIN ? targets.getFirst()
            : fight.regularStyle.target(targets, origin, fight.lastAttackTarget);
        fight.lastAttackTarget = target.getUniqueId();
        Component warning = language.message(selected.warning, NamedTextColor.LIGHT_PURPLE);
        for (Player player : players) player.sendActionBar(warning);
        fight.minorReady.put(selected, fight.age + selected.settings(extra).cooldownTicks());
        if (major) beginMajorCast(fight);
        else beginCast(fight);
        if (selected == MinorSkill.SKY_BITE) {
            fight.bite = new SkyBite(fight.dragon, target, language, extra, settings.tuning().damageScale());
        } else if (selected == MinorSkill.THUNDER_CHAIN) {
            fight.cast = new Cast(selected.attack, origin, target.getLocation());
            fight.cast.chainFirst = targets.get(0);
            fight.cast.chainSecond = targets.get(1);
            String message = language.text("thunder-chain-target").replace("{distance}", Double.toString(extra.thunderChain().radius()));
            for (Player linked : targets) {
                linked.sendActionBar(Component.text(message, NamedTextColor.RED));
                linked.playSound(linked.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1, 1.4f);
            }
        } else {
            Location aim = target.getLocation().add(0, .15, 0);
            if (selected == MinorSkill.VOID_RINGS || selected == MinorSkill.RIFT_MARK
                || selected == MinorSkill.GRAVITY_WELL || selected == MinorSkill.END_CROSS) {
                aim = TrueEndingImpact.fireballImpact(aim);
            }
            if (selected == MinorSkill.THUNDER) {
                target.sendActionBar(language.message("thunder-target", NamedTextColor.RED));
            }
            fight.cast = new Cast(selected.attack, origin, aim);
        }
        return true;
    }

    private static List<Player> thunderChainTargets(List<Player> players, double range) {
        if (players.size() < 2) return List.of();
        List<Player> candidates = new ArrayList<>();
        List<Location> positions = new ArrayList<>();
        for (Player player : players) {
            Location position = player.getLocation();
            if (!DragonParticles.loaded(position)) continue;
            candidates.add(player);
            positions.add(position);
        }
        int count = candidates.size();
        if (count < 2) return List.of();
        int anchor = ThreadLocalRandom.current().nextInt(count);
        double limit = range * range;
        for (int remaining = count - 1; remaining > 0; remaining--) {
            double nearest = limit;
            int partner = -1;
            Location anchorPosition = positions.get(anchor);
            int index = anchor;
            for (int checked = 0; checked < remaining; checked++) {
                if (++index == count) index = 0;
                double distance = anchorPosition.distanceSquared(positions.get(index));
                if (distance < nearest || (distance == nearest && index > partner)) {
                    nearest = distance;
                    partner = index;
                }
            }
            if (partner >= 0) return List.of(candidates.get(anchor), candidates.get(partner));
            if (++anchor == count) anchor = 0;
        }
        return List.of();
    }

    private void roar(Location origin, List<Player> players, double range, Settings.Tuning tuning) {
        double rangeSquared = range * range;
        var endermen = origin.getWorld().getNearbyEntitiesByType(Enderman.class, origin, range, range, range,
            enderman -> enderman.isValid() && !enderman.isDead() && enderman.hasAI()
                && enderman.getLocation().distanceSquared(origin) <= rangeSquared);
        var title = Title.title(language.message("ender-roar-title", NamedTextColor.DARK_PURPLE),
            language.message("ender-roar-subtitle", NamedTextColor.WHITE), tuning.titleTimes());
        for (Player player : players) {
            if (tuning.titleTicks() > 0) player.showTitle(title);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 1, .75f);
        }
        new DragonParticles(players).phaseBurst(origin.clone().add(0, 2, 0));

        int[] assigned = new int[players.size()];
        List<Location> locations = players.stream().map(Player::getLocation).toList();
        int retargetAttempts = 0;
        for (Enderman enderman : endermen) {
            if (retargetAttempts++ >= 24) break;
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
        var settings = fight.settings;
        if (fight.crystals == 0) return;
        int count = 0;
        for (EnderCrystal crystal : battle.getHealingCrystals()) if (!CrystalRevival.isRevived(crystal)) count++;
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
        if (settings.phantoms() > 0) fight.reinforcementsPending = true;
        else data.set(crystalKey, PersistentDataType.BYTE, (byte) 1);
        if (settings.crystals()) {
            for (Player player : players) player.sendActionBar(language.message("crystals-cleared", NamedTextColor.GOLD));
            dragon.getWorld().playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 3, 0.75f);
            fight.crystalReaction = 45;
        }
    }

    private void spawnReinforcements(EnderDragon dragon, Fight fight, List<Player> players) {
        var settings = fight.settings;
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

    private static void beginCast(Fight fight) {
        fight.nextCast = fight.age + fight.settings.rhythm().minimumGap();
    }

    private static void beginMajorCast(Fight fight) {
        beginCast(fight);
        fight.nextMajorCast = fight.age + fight.settings.rhythm().majorGap();
    }

    private static void beginRegularCast(Fight fight, int interval) {
        beginCast(fight);
        fight.nextRegularCast = fight.age + interval;
        fight.preferRegular = false;
    }

    private static void recover(Fight fight, int ticks) {
        fight.nextCast = Math.max(fight.nextCast, fight.age + ticks);
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
        if (LaserGuard.loadedLength(from, direction, length) + .001 < length) return false;
        return from.getWorld().rayTraceBlocks(from, direction, length, FluidCollisionMode.NEVER, true) == null;
    }

    private boolean startDatapackPhase(Fight fight, List<Player> players, double health, Location center) {
        var dragon = fight.dragon;
        var settings = fight.settings;
        if (!fight.halfPhase && health <= settings.tuning().fireballHp() && settings.fireball() >= 0) {
            fight.halfPhase = true;
            fight.phase = CombatPhase.ULTRA_FIREBALL;
            fight.phaseTick = 0;
            announce(players, "ultra-fireball-phase", NamedTextColor.LIGHT_PURPLE);
            dragon.getWorld().playSound(dragon.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 6, .6f);
            return true;
        }
        if (!fight.phaseDiveUsed && fight.age >= fight.nextPhaseDiveCheck && settings.dive() >= 0
            && health <= settings.tuning().diveHp() && SkyBite.nativeChargeAvailable() && !dragon.isInvulnerable()) {
            Location position = dragon.getLocation();
            fight.nextPhaseDiveCheck = fight.age + 20;
            for (Player player : players) {
                Location target = player.getLocation();
                if (!clearCastPath(position, target.clone().add(0, 2, 0), 64)) continue;
                Location landing = TrueEndingImpact.fireballImpact(target);
                if (position.getY() - landing.getY() < 7 || !clearCastPath(position, landing.clone().add(0, 2, 0), 64)) continue;
                fight.requestPhase(EnderDragon.Phase.HOVER);
                if (dragon.getPhase() != EnderDragon.Phase.HOVER) break;
                dragon.setVelocity(new Vector());
                fight.phaseDiveTarget = landing;
                fight.phase = CombatPhase.DIVE;
                fight.phaseTick = 0;
                announce(players, "dive-warning", NamedTextColor.GOLD);
                return true;
            }
        }
        if (fight.age >= fight.nextSweepAttempt && health <= settings.tuning().laserHp() && settings.laser() >= 0) {
            if (startLaserApproach(fight, center, true)) return true;
            fight.nextSweepAttempt = fight.age + LASER_RETRY_TICKS;
        }
        return false;
    }

    private void runDatapackPhase(EnderDragon dragon, Fight fight, Location center, DragonParticles particles) {
        fight.phaseTick += STEP;
        switch (fight.phase) {
            case ULTRA_FIREBALL -> ultraFireball(dragon, fight, particles);
            case EXTRA_FIREBALL -> extraFireball(fight, particles);
            case LASER -> laserPhase(dragon, fight, particles);
            case DIVE -> divePhase(dragon, fight, particles);
            case NONE -> { }
        }
    }

    private void ultraFireball(EnderDragon dragon, Fight fight, DragonParticles particles) {
        int tick = fight.phaseTick;
        if (tick == 20) {
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

    private static Location laserStation(Location center) {
        double height = Math.min(center.getWorld().getMaxHeight() - 8, center.getY() + 20);
        return new Location(center.getWorld(), center.getBlockX() + .5, height, center.getBlockZ() + .5);
    }

    private record LaserApproach(Location station, int deadline, boolean sweep, int nextRetry) {}

    private boolean startLaserApproach(Fight fight, Location center, boolean sweep) {
        EnderDragon dragon = fight.dragon;
        Location station = laserStation(center);
        if (!SkyBite.nativeChargeAvailable() || dragon.getPhase() == EnderDragon.Phase.DYING || dragon.isInvulnerable()
            || station.getY() - center.getY() < 8 || !DragonParticles.loaded(station)
            || !station.getBlock().isPassable()) return false;
        fight.laserApproach = new LaserApproach(station, fight.age + LASER_FLIGHT_TIMEOUT, sweep,
            fight.age + NATIVE_CHARGE_REFRESH_TICKS);
        if (!atLaserStation(dragon.getLocation(), station) && !fight.maintainNativeCharge(station)) {
            fight.releaseLaserFlight();
            fight.laserApproach = null;
            return false;
        }
        if (atLaserStation(dragon.getLocation(), station)) fight.requestPhase(EnderDragon.Phase.HOVER);
        return true;
    }

    private static boolean atLaserStation(Location position, Location station) {
        return horizontalDistanceSquared(position, station) <= 16 && Math.abs(position.getY() - station.getY()) <= 4;
    }

    private static boolean holdLaserStation(Fight fight, Location pose) {
        EnderDragon dragon = fight.dragon;
        if (dragon.isInvulnerable() || dragon.getPhase() == EnderDragon.Phase.DYING) return false;
        if (!atLaserStation(dragon.getLocation(), pose)) return false;
        if (dragon.getPhase() != EnderDragon.Phase.HOVER) fight.requestPhase(EnderDragon.Phase.HOVER);
        if (dragon.getPhase() != EnderDragon.Phase.HOVER) return false;
        if (dragon.getVelocity().lengthSquared() > .01) dragon.setVelocity(new Vector());
        return true;
    }

    private void tickLaserApproach(Fight fight, List<Player> players) {
        EnderDragon dragon = fight.dragon;
        LaserApproach approach = fight.laserApproach;
        Location actual = dragon.getLocation();
        if (dragon.getPhase() != EnderDragon.Phase.DYING && !dragon.isInvulnerable() && fight.age < approach.deadline()
            && DragonParticles.loaded(approach.station())) {
            if (atLaserStation(actual, approach.station())) {
                fight.requestPhase(EnderDragon.Phase.HOVER);
                if (dragon.getPhase() == EnderDragon.Phase.HOVER) {
                    dragon.setVelocity(new Vector());
                    if (approach.sweep()) {
                        fight.quarterPhase = true;
                        fight.nextSweepAttempt = fight.age + fight.settings.sweepCooldownTicks();
                        fight.phase = CombatPhase.LASER;
                        fight.phaseTick = 181;
                        fight.laserPose = actual;
                        fight.sweepGuard = new LaserGuard();
                        fight.laserApproach = null;
                        beginMajorCast(fight);
                        announce(players, "laser-phase", NamedTextColor.DARK_PURPLE);
                        return;
                    }
                    var beam = fight.settings.extra().trackingLaser();
                    Location origin = actual.clone().add(0, 2, 0);
                    var targets = players.stream().filter(player -> clearCastPath(origin, player.getEyeLocation(), beam.range())).toList();
                    if (!targets.isEmpty()) {
                        Player target = fight.regularStyle.target(targets, origin, fight.lastAttackTarget);
                        fight.lastAttackTarget = target.getUniqueId();
                        fight.minorReady.put(MinorSkill.TRACKING_LASER, fight.age + beam.skill().cooldownTicks());
                        beginCast(fight);
                        announce(players, MinorSkill.TRACKING_LASER.warning, NamedTextColor.LIGHT_PURPLE);
                        fight.laserPose = actual;
                        fight.trackingLaser = new TrackingLaser(dragon, target, language, beam, fight.settings.tuning().damageScale());
                        fight.laserApproach = null;
                        return;
                    }
                }
            } else {
                if (fight.age >= approach.nextRetry()) {
                    if (!fight.maintainNativeCharge(approach.station())) {
                        fight.releaseLaserFlight();
                        fight.laserApproach = null;
                        if (approach.sweep()) fight.nextSweepAttempt = fight.age + LASER_RETRY_TICKS;
                        else fight.minorReady.put(MinorSkill.TRACKING_LASER, fight.age + LASER_RETRY_TICKS);
                        recover(fight, fight.settings.rhythm().recovery());
                        return;
                    }
                    fight.laserApproach = new LaserApproach(approach.station(), approach.deadline(), approach.sweep(),
                        fight.age + NATIVE_CHARGE_REFRESH_TICKS);
                }
                return;
            }
        }
        fight.releaseLaserFlight();
        fight.laserApproach = null;
        if (approach.sweep()) fight.nextSweepAttempt = fight.age + LASER_RETRY_TICKS;
        else fight.minorReady.put(MinorSkill.TRACKING_LASER, fight.age + LASER_RETRY_TICKS);
        recover(fight, fight.settings.rhythm().recovery());
    }

    private void laserPhase(EnderDragon dragon, Fight fight, DragonParticles particles) {
        if (!holdLaserStation(fight, fight.laserPose)) {
            fight.releaseLaserFlight();
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
            fight.laserPose = null;
            fight.sweepGuard = null;
            return;
        }
        int tick = fight.phaseTick;
        if (tick >= 200 && tick <= 240 && tick % 10 == 0) {
            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 8, 1.6f);
            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 8, 1.1f);
        }
        if (tick == 202) dragon.getWorld().playSound(dragon.getLocation(), Sound.BLOCK_BELL_RESONATE, 8, 1.2f);
        Location pose = fight.laserPose;
        if (tick >= 248 && tick <= 253) {
            Location flat = pose.clone();
            flat.setPitch(0);
            particles.laserCharge(flat.add(flat.getDirection().multiply(-5)));
        }
        if (tick >= 250 && tick <= 281) pose.setPitch(90);
        if (tick >= 251 && tick <= 575) {
            if (tick == 251) {
                dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 4, .5f);
            }
            dragon.setRotation(pose.getYaw(), pose.getPitch());
            if (tick % 2 == 1) sweepLaser(fight, particles, pose);
            double turn = tick < 281 ? 0 : tick <= 300 ? 4.5 : tick <= 460 ? 9
                : tick <= 520 ? 4.5 : tick <= 550 ? 2.25 : tick <= 560 ? 1.125 : 0;
            pose.setYaw(pose.getYaw() + (float) turn);
            double tilt = tick < 311 ? 0 : tick <= 320 ? -1 : tick <= 380 ? -.43 : tick <= 480 ? -.25 : 0;
            pose.setPitch(pose.getPitch() + (float) tilt);
        }
        if (tick >= 575) {
            fight.releaseLaserFlight();
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
            fight.laserPose = null;
            fight.sweepGuard = null;
        }
    }

    private void sweepLaser(Fight fight, DragonParticles particles, Location pose) {
        Location flat = pose.clone();
        flat.setPitch(0);
        Vector forward = flat.getDirection();
        Vector left = new Vector(forward.getZ(), 0, -forward.getX());
        Location start = pose.clone().add(forward.multiply(-5)).add(left.multiply(-3));
        Location reverse = pose.clone();
        reverse.setYaw(reverse.getYaw() - 180);
        Vector direction = reverse.getDirection();
        double length = LaserGuard.loadedLength(start, direction, 128);
        if (length < .001) return;
        var block = start.getWorld().rayTraceBlocks(start, direction, length, FluidCollisionMode.NEVER, true);
        Location impact = null;
        if (block != null) {
            impact = block.getHitPosition().toLocation(start.getWorld());
            length = start.distance(impact);
            Location cloudSpot = impact.clone();
            cloudSpot.setY(block.getHitBlock().getY() + 1.0);
            if (block.getHitBlockFace() == org.bukkit.block.BlockFace.UP && fight.breathClouds.size() < 6 && fight.age >= fight.nextLaserCloud
                && ThreadLocalRandom.current().nextInt(10) == 0 && DragonParticles.loaded(cloudSpot)) {
                fight.breathClouds.add(TrueEndingImpact.cloud(fight.dragon, cloudSpot, 400));
                fight.nextLaserCloud = fight.age + 20;
            }
            particles.laserHit(impact);
        }
        Location spot = start.clone();
        for (int step = 0; step < length; step += 3) {
            particles.laser(spot, direction);
            spot.add(direction.getX() * 3, direction.getY() * 3, direction.getZ() * 3);
        }
        if (length >= .001) laserDamage(fight, particles, start, direction, length, impact);
    }

    private void laserDamage(Fight fight, DragonParticles particles, Location start, Vector direction, double length, Location impact) {
        Location end = start.clone().add(direction.clone().multiply(length));
        var box = org.bukkit.util.BoundingBox.of(start, end).expand(5);
        var world = start.getWorld();
        for (Entity candidate : world.getNearbyEntities(box)) {
            if (!(candidate instanceof LivingEntity target) || !TrueEndingImpact.target(target)) continue;
            Player player = target instanceof Player participant ? participant : null;
            if (player != null && player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
            boolean hurtReady = target.getNoDamageTicks() <= target.getMaximumNoDamageTicks() / 2;
            if (!hurtReady && (player == null || !player.isBlocking())) continue;
            Location position = target.getLocation();
            double projection = (position.getX() - start.getX()) * direction.getX()
                + (position.getY() - start.getY()) * direction.getY() + (position.getZ() - start.getZ()) * direction.getZ();
            double sample = Math.clamp(projection, 0, length);
            double dx = position.getX() - (start.getX() + direction.getX() * sample);
            double dy = position.getY() - (start.getY() + direction.getY() * sample);
            double dz = position.getZ() - (start.getZ() + direction.getZ() * sample);
            if (dx * dx + dy * dy + dz * dz > 2.25 && (impact == null || position.distanceSquared(impact) > 20.25)) continue;
            Location body = target.getBoundingBox().getCenter().toLocation(world);
            if (!LaserGuard.hasLineOfSight(start, body)) continue;
            if (player != null && fight.sweepGuard.block(player, start, fight.age)) continue;
            if (!hurtReady) continue;
            particles.laserEntityHit(position);
            world.playSound(position, Sound.ENTITY_BLAZE_DEATH, 2, .6f);
            hurtEntity(target, fight.dragon, fight.settings.laser() * fight.settings.tuning().damageScale(), DamageType.DRAGON_BREATH);
        }
    }

    private void divePhase(EnderDragon dragon, Fight fight, DragonParticles particles) {
        Location landing = fight.phaseDiveTarget;
        Location position = dragon.getLocation();
        int frame = fight.phaseTick;
        if (frame <= 30 && dragon.getPhase() == EnderDragon.Phase.HOVER && DragonParticles.loaded(landing)) {
            if (frame % 5 == 1) {
                var audience = DragonParticles.warningViewers(landing, 48);
                warningRing(landing, 12, 48, CROSS_WARNING, audience);
                warningRing(landing, 6, 24, CROSS_WARNING, audience);
            }
            if (frame < 30) return;
            Location destination = landing.clone().add(0, 2, 0);
            if (clearCastPath(position, destination, 64) && fight.maintainNativeCharge(destination)) {
                fight.phaseDiveNextReaim = fight.age + NATIVE_CHARGE_REFRESH_TICKS;
                position.getWorld().playSound(position, Sound.ENTITY_ENDER_DRAGON_FLAP, 5, 1);
                return;
            }
        } else if (frame > 30 && DragonParticles.loaded(landing)) {
            if (horizontalDistanceSquared(position, landing) <= 36 && Math.abs(position.getY() - landing.getY()) <= 7) {
                fight.phaseDiveUsed = true;
                diveImpact(fight, particles, landing);
            } else if (frame < 100 && !SkyBite.hitFlightObstacle(dragon)) {
                if (fight.age >= fight.phaseDiveNextReaim) {
                    if (!fight.maintainNativeCharge(landing.clone().add(0, 2, 0))) {
                        fight.releasePhaseDive();
                        fight.nextPhaseDiveCheck = fight.age + 100;
                        fight.phase = CombatPhase.NONE;
                        fight.phaseTick = 0;
                        return;
                    }
                    fight.phaseDiveNextReaim = fight.age + NATIVE_CHARGE_REFRESH_TICKS;
                }
                return;
            }
        }
        fight.releasePhaseDive();
        fight.nextPhaseDiveCheck = fight.age + 100;
        fight.phase = CombatPhase.NONE;
        fight.phaseTick = 0;
    }

    private void diveImpact(Fight fight, DragonParticles particles, Location position) {
        var settings = fight.settings;
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

    static double horizontalDistanceSquared(Location first, Location second) {
        double xDistance = first.getX() - second.getX();
        double zDistance = first.getZ() - second.getZ();
        return xDistance * xDistance + zDistance * zDistance;
    }

    static void ring(Location center, double radius, int points, Particle.DustOptions dust) {
        var world = center.getWorld();
        for (int point = 0; point < points; point++) {
            double angle = Math.TAU * point / points;
            world.spawnParticle(Particle.DUST, center.getX() + radius * Math.cos(angle), center.getY() + .15,
                center.getZ() + radius * Math.sin(angle), 1, 0, 0, 0, 0, dust);
        }
    }

    static void warningRing(Location center, double radius, int points, Particle.DustOptions dust, List<Player> audience) {
        if (audience.isEmpty()) return;
        for (int point = 0; point < points; point++) {
            double angle = Math.TAU * point / points;
            warningPoint(center, radius * Math.cos(angle), .15, radius * Math.sin(angle), dust, audience);
        }
    }

    private static void warningPoint(Location center, double offsetX, double offsetY, double offsetZ,
                                     Particle.DustOptions dust, List<Player> audience) {
        if (!audience.isEmpty()) center.getWorld().spawnParticle(Particle.DUST, audience, null,
            center.getX() + offsetX, center.getY() + offsetY, center.getZ() + offsetZ, 1, 0, 0, 0, 0, dust, true);
    }

    private static void warningLine(Location start, Location end, double spacing, Particle.DustOptions dust, List<Player> audience) {
        if (audience.isEmpty()) return;
        Vector delta = end.toVector().subtract(start.toVector());
        int segments = Math.clamp((int) Math.ceil(delta.length() / spacing), 1, 80);
        for (int point = 0; point <= segments; point++) {
            double fraction = (double) point / segments;
            warningPoint(start, delta.getX() * fraction, delta.getY() * fraction, delta.getZ() * fraction, dust, audience);
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

    private static void chainArc(Location start, Location end, double distance, double spacing, int frame, boolean warning) {
        int segments = Math.clamp((int) Math.ceil(distance / spacing), 1, 80);
        double deltaX = end.getX() - start.getX();
        double deltaY = end.getY() - start.getY();
        double deltaZ = end.getZ() - start.getZ();
        double horizontal = Math.hypot(deltaX, deltaZ);
        double sidewaysX = horizontal > .001 ? -deltaZ / horizontal : 1;
        double sidewaysZ = horizontal > .001 ? deltaX / horizontal : 0;
        double amplitude = Math.min(.42, distance * .12);
        var world = start.getWorld();
        for (int point = 0; point <= segments; point++) {
            double fraction = (double) point / segments;
            boolean even = ((point + frame) & 1) == 0;
            double offset = point == 0 || point == segments ? 0 : amplitude * (even ? 1 : -1);
            world.spawnParticle(Particle.DUST, start.getX() + deltaX * fraction + sidewaysX * offset,
                start.getY() + deltaY * fraction, start.getZ() + deltaZ * fraction + sidewaysZ * offset,
                1, 0, 0, 0, 0, warning ? THUNDER_WARNING : (even ? CHAIN_BLUE : CHAIN_WHITE));
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

    private enum Attack { SHOCKWAVE, FIREBALL, LASER, DIVE, FINALE, LEVITATION_FIELD, BREATH_ERUPTION, CRYSTAL_BURST, VOID_RINGS, THUNDER, THUNDERSTORM, THUNDER_CHAIN, RIFT_MARK, GRAVITY_WELL, END_CROSS;

        boolean isMajor() {
            return this == FINALE || this == THUNDERSTORM || this == END_CROSS;
        }
    }
    private enum RegularStyle {
        SCOUT(Attack.SHOCKWAVE, Attack.SHOCKWAVE, Attack.FIREBALL, Attack.DIVE, Attack.LASER),
        STORM(Attack.DIVE, Attack.SHOCKWAVE, Attack.FIREBALL, Attack.LASER),
        ABYSS(Attack.FIREBALL, Attack.LASER, Attack.SHOCKWAVE, Attack.DIVE),
        SOVEREIGN(Attack.SHOCKWAVE, Attack.DIVE, Attack.FIREBALL, Attack.DIVE, Attack.LASER);

        private final List<Attack> rotation;

        RegularStyle(Attack... rotation) {
            this.rotation = List.of(rotation);
        }

        private Player target(List<Player> candidates, Location origin, UUID previous) {
            if (this == SCOUT) {
                return candidates.stream().min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(origin))).orElseThrow();
            }
            if (this == STORM || this == SOVEREIGN) {
                var fresh = candidates.stream().filter(player -> !player.getUniqueId().equals(previous)).toList();
                if (!fresh.isEmpty()) candidates = fresh;
            }
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
    }

    private enum MinorSkill {
        THUNDERSTORM(Attack.THUNDERSTORM, "thunderstorm-warning"), THUNDER(Attack.THUNDER, "thunder-warning"),
        THUNDER_CHAIN(Attack.THUNDER_CHAIN, "thunder-chain-warning"),
        RIFT_MARK(Attack.RIFT_MARK, "rift-mark-warning"), GRAVITY_WELL(Attack.GRAVITY_WELL, "gravity-well-warning"),
        END_CROSS(Attack.END_CROSS, "end-cross-warning"),
        VOID_RINGS(Attack.VOID_RINGS, "void-rings-warning"),
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
                case VOID_RINGS -> extra.voidRings();
                case THUNDER -> extra.thunder();
                case THUNDERSTORM -> extra.thunderstorm();
                case THUNDER_CHAIN -> extra.thunderChain();
                case RIFT_MARK -> extra.riftMark();
                case GRAVITY_WELL -> extra.gravityWell();
                case END_CROSS -> extra.endCross();
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
            double damage = fight.settings.fireball() * fight.settings.tuning().damageScale();
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

        private boolean tick(EnderDragon dragon, DragonParticles particles, Settings settings) {
            age++;
            rays.removeIf(ray -> !moveRay(ray, dragon, particles, settings));
            return age >= 70 || rays.isEmpty();
        }

        private boolean moveRay(Location ray, EnderDragon dragon, DragonParticles particles, Settings settings) {
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
        private int nextRegularCast;
        private int nextMajorCast;
        private boolean preferRegular;
        private int regularCursor;
        private UUID lastAttackTarget;
        private final RegularStyle regularStyle;
        private boolean reinforcementsPending;
        private int nextAiCheck;
        private int nextAiChargeReaim;
        private int phaseDiveNextReaim;
        private int nextLaserCloud;
        private int aiChargeUntil;
        private int chargeStunUntil;
        private ChargeBlock chargeBlocked;
        private ChargeWarning chargeWarning;
        private int nextRoar;
        private int nextCast;
        private boolean idle;
        private boolean podiumActive;
        private int crystalReaction;
        private int nextRevivalCheck;
        private int nextPodiumCheck;
        private int nextPhaseDiveCheck;
        private boolean revivalCasting;
        private CrystalRevival revival;
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
        private Location aiChargeTarget;
        private LaserGuard sweepGuard;
        private LaserApproach laserApproach;
        private int nextSweepAttempt;
        private Cast cast;
        private CombatPhase phase = CombatPhase.NONE;
        private int phaseTick;
        private boolean halfPhase;
        private boolean quarterPhase;
        private boolean phaseDiveUsed;
        private Location phaseDiveTarget;
        private boolean pluginPhaseChange;
        private DragonRushCast rush;
        private SkyBite bite;
        private TrackingLaser trackingLaser;
        private final Map<MinorSkill, Integer> minorReady = new EnumMap<>(MinorSkill.class);

        private final Settings settings;

        private Fight(EnderDragon dragon, DragonProfiles.Profile profile, int interval) {
            this.dragon = dragon;
            this.settings = profile.combat();
            this.regularStyle = switch (profile.id()) {
                case "storm" -> RegularStyle.STORM;
                case "abyss" -> RegularStyle.ABYSS;
                case "sovereign" -> RegularStyle.SOVEREIGN;
                default -> RegularStyle.SCOUT;
            };
            nextRegularCast = interval;
            nextAiCheck = settings.aiChargeTicks();
        }

        private void expirePhantoms() {
            if (age >= phantomsExpire) clearPhantoms();
            else phantoms.removeIf(phantom -> !phantom.isValid());
        }

        private void clearPhantoms() {
            for (Phantom phantom : phantoms) if (phantom.isValid()) phantom.remove();
            phantoms.clear();
        }

        private boolean hasActiveCast() {
            return revivalCasting || chargeWarning != null || aiChargeUntil > 0 || chargeStunUntil > 0 || podiumActive || rush != null || bite != null || trackingLaser != null
                || laserApproach != null || phase != CombatPhase.NONE || cast != null;
        }

        private boolean controlsFlightPhase() {
            return !idle && (bite != null || chargeWarning != null || aiChargeUntil > 0 || chargeStunUntil > 0
                || laserApproach != null || trackingLaser != null || phase == CombatPhase.DIVE || phase == CombatPhase.LASER
                || cast != null && cast.flightControlled);
        }

        private void requestPhase(EnderDragon.Phase requested) {
            boolean previous = pluginPhaseChange;
            pluginPhaseChange = true;
            try {
                dragon.setPhase(requested);
            } finally {
                pluginPhaseChange = previous;
            }
        }

        private boolean maintainNativeCharge(Location target) {
            if (!dragon.isValid() || dragon.isDead() || dragon.getPhase() == EnderDragon.Phase.DYING
                || target == null || !dragon.getWorld().equals(target.getWorld())) return false;
            boolean previous = pluginPhaseChange;
            pluginPhaseChange = true;
            try {
                return SkyBite.restartNativeCharge(dragon, target);
            } finally {
                pluginPhaseChange = previous;
            }
        }

        private void releasePhaseDive() {
            if (dragon.isValid() && !dragon.isDead() && dragon.getPhase() != EnderDragon.Phase.DYING
                && (dragon.getPhase() == EnderDragon.Phase.HOVER || dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER)) {
                requestPhase(EnderDragon.Phase.CIRCLING);
            }
            phaseDiveTarget = null;
            phaseDiveNextReaim = 0;
        }

        private void releaseCastFlight() {
            if (cast == null || !cast.flightControlled) return;
            cast.flightControlled = false;
            if (dragon.isValid() && !dragon.isDead() && dragon.getPhase() != EnderDragon.Phase.DYING
                && (dragon.getPhase() == EnderDragon.Phase.HOVER || dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER)) {
                requestPhase(EnderDragon.Phase.CIRCLING);
            }
        }

        private void releaseLaserFlight() {
            if (!dragon.isValid() || dragon.isDead()) return;
            var current = dragon.getPhase();
            if (current == EnderDragon.Phase.HOVER || (laserApproach != null && current == EnderDragon.Phase.CHARGE_PLAYER)) {
                requestPhase(EnderDragon.Phase.CIRCLING);
            }
        }

        private void endAiCharge() {
            if (aiChargeUntil > 0 && dragon.isValid() && !dragon.isDead() && dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER) {
                requestPhase(EnderDragon.Phase.CIRCLING);
            }
            aiChargeUntil = 0;
            aiChargeTarget = null;
            nextAiChargeReaim = 0;
            chargeBlocked = null;
        }

        private void endChargeStun() {
            if (chargeStunUntil == 0) return;
            chargeStunUntil = 0;
            if (dragon.isValid() && !dragon.isDead() && dragon.getPhase() == EnderDragon.Phase.HOVER) {
                requestPhase(EnderDragon.Phase.CIRCLING);
            }
        }

        private void cancelAiChargeWarning() {
            if (chargeWarning == null) return;
            chargeWarning = null;
            if (dragon.isValid() && !dragon.isDead() && dragon.getPhase() == EnderDragon.Phase.HOVER) {
                requestPhase(EnderDragon.Phase.CIRCLING);
            }
        }

        private void cleanup() {
            cancelAiChargeWarning();
            endAiCharge();
            endChargeStun();
            if (revivalCasting) revival = null;
            revivalCasting = false;
            if (bite != null) {
                boolean previous = pluginPhaseChange;
                pluginPhaseChange = true;
                try {
                    bite.cancel();
                } finally {
                    pluginPhaseChange = previous;
                }
            }
            if (laserApproach != null || trackingLaser != null || phase == CombatPhase.LASER) releaseLaserFlight();
            laserApproach = null;
            if (phase == CombatPhase.DIVE) releasePhaseDive();
            if (cast != null) releaseCastFlight();
            if (rush != null) rush.cleanup();
            rush = null;
            bite = null;
            trackingLaser = null;
            cast = null;
            phase = CombatPhase.NONE;
            phaseTick = 0;
            laserPose = null;
            sweepGuard = null;
            fireballs.clear();
            waves.clear();
            crystalReaction = 0;
            breathClouds.forEach(AreaEffectCloud::remove);
            breathClouds.clear();
            mirrorDragons.forEach(MirrorDragon::remove);
            mirrorDragons.clear();
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
        private LightningCast lightning;
        private Player chainFirst;
        private Player chainSecond;
        private Vector crossDirection;
        private int age;
        private int dives = 1;
        private RegularStyle regularStyle;
        private UUID regularTarget;
        private Location focusedAim;
        private Location diveTarget;
        private Location diveOrigin;
        private Set<UUID> diveTargetsSeen;
        private int diveStage;
        private int diveStageStartedAt = 1;
        private int diveFlightDeadline;
        private int nextDiveReaim;
        private boolean flightControlled;

        private Cast(Attack attack, Location origin, Location aim) {
            this.attack = attack;
            this.origin = origin;
            this.aim = aim;
        }

        private boolean tick(Fight fight, List<Player> players, Settings settings, List<AreaEffectCloud> clouds, Language language) {
            age += STEP;
            EnderDragon dragon = fight.dragon;
            var tuning = settings.tuning();
            double scale = tuning.damageScale();
            return switch (attack) {
                case SHOCKWAVE -> shockwave(dragon, players, settings.shockwave() * scale, tuning.shockwaveRadius(), tuning.shockwaveWarning());
                case FIREBALL -> fireball(dragon, players, settings.fireball() * scale, tuning.fireballRadius(), tuning.fireballWarning());
                case LASER -> laser(dragon, players, settings.laser() * scale, tuning.laserRadius(), tuning.laserWarning());
                case DIVE -> dive(fight, players, settings.dive() * scale, tuning.diveRadius(), tuning.diveWarning(), language);
                case FINALE -> finale(dragon, players, settings.finale() * scale, tuning.finaleRadius(), tuning.finaleWarning());
                case LEVITATION_FIELD -> areaSkill(dragon, players, settings.extra().levitation(), scale, clouds);
                case BREATH_ERUPTION -> areaSkill(dragon, players, settings.extra().breath(), scale, clouds);
                case CRYSTAL_BURST -> areaSkill(dragon, players, settings.extra().crystal(), scale, clouds);
                case VOID_RINGS -> voidRings(dragon, players, settings.extra().voidRings(), scale, language);
                case THUNDER, THUNDERSTORM -> {
                    if (lightning == null) lightning = new LightningCast(aim, attack == Attack.THUNDERSTORM);
                    yield lightning.tick(dragon, players, attack == Attack.THUNDER ? settings.extra().thunder()
                        : settings.extra().thunderstorm(), scale);
                }
                case THUNDER_CHAIN -> thunderChain(dragon, players, settings.extra().thunderChain(), scale);
                case RIFT_MARK -> riftMark(dragon, players, settings.extra().riftMark(), scale);
                case GRAVITY_WELL -> gravityWell(dragon, players, settings.extra().gravityWell(), scale);
                case END_CROSS -> endCross(dragon, players, settings.extra().endCross(), scale, language);
            };
        }

        private boolean thunderChain(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale) {
            if (!players.contains(chainFirst) || !players.contains(chainSecond)) return true;
            Location first = chainFirst.getLocation().add(0, 1, 0);
            Location second = chainSecond.getLocation().add(0, 1, 0);
            double distanceSquared = first.distanceSquared(second);
            if (distanceSquared >= skill.radius() * skill.radius()) {
                first.getWorld().playSound(first, Sound.BLOCK_CHAIN_BREAK, .9f, 1.4f);
                second.getWorld().playSound(second, Sound.BLOCK_CHAIN_BREAK, .9f, 1.4f);
                return true;
            }
            if (age < skill.warningTicks()) {
                if (age % 5 == STEP) {
                    boolean warning = skill.warningTicks() - age <= Math.min(15, skill.warningTicks() / 2);
                    chainArc(first, second, Math.sqrt(distanceSquared), 1, age / 5, warning);
                }
                return false;
            }
            chainArc(first, second, Math.sqrt(distanceSquared), .65, age / 5, true);
            first.getWorld().playSound(first, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, .8f, 1.45f);
            hurt(chainFirst, dragon, skill.damage() * scale);
            hurt(chainSecond, dragon, skill.damage() * scale);
            return true;
        }

        private boolean riftMark(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale) {
            if (!DragonParticles.loaded(aim)) return true;
            if (age < skill.warningTicks()) {
                if (age == STEP) aim.getWorld().playSound(aim, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.1f, 1.1f);
                if (age % 5 == STEP) {
                    ring(aim, skill.radius(), 20, WARNING);
                    ring(aim, skill.radius() * (1 - .7 * age / skill.warningTicks()), 12, BEAM);
                }
                return false;
            }
            ring(aim, skill.radius(), 26, BEAM);
            aim.getWorld().spawnParticle(Particle.END_ROD, aim, 20, .8, .3, .8, .06);
            aim.getWorld().playSound(aim, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.2f, .7f);
            hitCircle(dragon, players, skill.radius(), skill.damage() * scale);
            return true;
        }

        private boolean gravityWell(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale) {
            if (!DragonParticles.loaded(aim)) return true;
            double blastRadius = skill.radius() * .48;
            if (age < skill.warningTicks()) {
                if (age == STEP) aim.getWorld().playSound(aim, Sound.BLOCK_BEACON_AMBIENT, 1.4f, .65f);
                if (age % 5 == STEP) {
                    ring(aim, skill.radius(), 22, PULL_WARNING);
                    ring(aim, blastRadius, 20, WARNING);
                    ring(aim, skill.radius() - (skill.radius() - blastRadius) * age / skill.warningTicks(), 14, BEAM);
                    for (Player player : players) {
                        Location feet = player.getLocation();
                        double distance = horizontalDistanceSquared(feet, aim);
                        if (Math.abs(feet.getY() - aim.getY()) > 2.5 || distance > skill.radius() * skill.radius() || distance < 1) continue;
                        Vector pull = new Vector(aim.getX() - feet.getX(), 0, aim.getZ() - feet.getZ()).multiply(.13 / Math.sqrt(distance));
                        Vector velocity = player.getVelocity();
                        if (velocity.dot(pull) < .035) player.setVelocity(velocity.add(pull));
                    }
                }
                return false;
            }
            ring(aim, blastRadius, 28, BEAM);
            aim.getWorld().spawnParticle(Particle.REVERSE_PORTAL, aim, 38, .8, .65, .8, .14);
            aim.getWorld().playSound(aim, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.15f, 1.45f);
            hitCircle(dragon, players, blastRadius, skill.damage() * scale);
            return true;
        }

        private boolean endCross(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale, Language language) {
            if (!DragonParticles.loaded(aim)) return true;
            int warning = skill.warningTicks();
            int secondWarning = Math.max(20, warning * 3 / 4);
            boolean second = age > warning;
            int frame = second ? age - warning : age;
            if (frame == STEP) {
                double angle = second ? Math.atan2(crossDirection.getZ(), crossDirection.getX()) + Math.PI / 4
                    : ThreadLocalRandom.current().nextDouble(Math.TAU);
                crossDirection = new Vector(Math.cos(angle), 0, Math.sin(angle));
                aim.getWorld().playSound(aim, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.4f, second ? 1.4f : .8f);
                if (second) warnNear(players, skill.radius() + 8, language, "end-cross-second-warning");
            }
            Vector perpendicular = new Vector(-crossDirection.getZ(), 0, crossDirection.getX());
            if (frame < (second ? secondWarning : warning)) {
                if (frame % 5 == STEP) drawCross(perpendicular, skill.radius(), CROSS_WARNING);
                return false;
            }
            drawCross(perpendicular, skill.radius(), CROSS_IMPACT);
            aim.getWorld().playSound(aim, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.4f, second ? 1.15f : .8f);
            for (Player player : players) {
                Location feet = player.getLocation();
                if (Math.abs(feet.getY() - aim.getY()) > 2.5) continue;
                double dx = feet.getX() - aim.getX(), dz = feet.getZ() - aim.getZ();
                double along = Math.abs(dx * crossDirection.getX() + dz * crossDirection.getZ());
                double across = Math.abs(dx * perpendicular.getX() + dz * perpendicular.getZ());
                if ((along <= skill.radius() && across <= 1.15) || (across <= skill.radius() && along <= 1.15)) {
                    hurt(player, dragon, skill.damage() * scale);
                }
            }
            return second;
        }

        private void drawCross(Vector perpendicular, double radius, Particle.DustOptions color) {
            var audience = DragonParticles.warningViewers(aim, radius + 24);
            if (audience.isEmpty()) return;
            Location base = aim.clone().add(0, .35, 0);
            for (int lane = -1; lane <= 1; lane++) {
                double width = lane * 1.15;
                Location first = base.clone().add(perpendicular.clone().multiply(width));
                Location second = base.clone().add(crossDirection.clone().multiply(width));
                Vector along = crossDirection.clone().multiply(radius);
                Vector across = perpendicular.clone().multiply(radius);
                double spacing = Math.max(lane == 0 ? 1.4 : 1, radius / 12);
                warningLine(first.clone().subtract(along), first.add(along), spacing, color, audience);
                warningLine(second.clone().subtract(across), second.add(across), spacing, color, audience);
            }
        }

        private void warnNear(List<Player> players, double radius, Language language, String key) {
            Component message = language.message(key, NamedTextColor.GOLD);
            for (Player player : players) {
                if (horizontalDistanceSquared(player.getLocation(), aim) <= radius * radius) player.sendActionBar(message);
            }
        }

        private void hitCircle(EnderDragon dragon, List<Player> players, double radius, double damage) {
            for (Player player : players) {
                Location feet = player.getLocation();
                if (Math.abs(feet.getY() - aim.getY()) <= 2 && horizontalDistanceSquared(feet, aim) <= radius * radius) {
                    hurt(player, dragon, damage);
                }
            }
        }

        private boolean voidRings(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale, Language language) {
            if (!DragonParticles.loaded(aim)) return true;
            int warning = skill.warningTicks();
            int secondWarning = Math.max(20, warning * 2 / 3);
            boolean outer = age > warning;
            int frame = outer ? age - warning : age;
            double split = skill.radius() * .48;
            double radius = outer ? skill.radius() : split;
            if (frame == STEP) {
                aim.getWorld().playSound(aim, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.3f, outer ? .8f : 1.2f);
                if (outer) warnNear(players, skill.radius() + 8, language, "void-rings-outer-warning");
            }
            if (frame < (outer ? secondWarning : warning)) {
                if (frame % 4 == STEP) {
                    var audience = DragonParticles.warningViewers(aim, radius + 24);
                    warningRing(aim, radius, 32, CROSS_WARNING, audience);
                    warningRing(aim, outer ? split + (radius - split) * .5 : radius * .55, 20, CROSS_WARNING, audience);
                    if (outer) warningRing(aim, split, 28, SAFE, audience);
                }
                return false;
            }
            ring(aim, radius, 40, BEAM);
            ring(aim, outer ? split + (radius - split) * .5 : radius * .55, 28, BEAM);
            aim.getWorld().playSound(aim, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.3f, outer ? .7f : 1.1f);
            for (Player player : players) {
                Location feet = player.getLocation();
                if (Math.abs(feet.getY() - aim.getY()) > 2) continue;
                double distance = horizontalDistanceSquared(feet, aim);
                if (distance <= radius * radius && (!outer || distance > split * split)) {
                    hurt(player, dragon, skill.damage() * scale);
                }
            }
            return outer;
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
                Location nearest = null;
                double distance = skill.radius() * skill.radius();
                for (Location spot : spots) {
                    double squared = feet.distanceSquared(spot);
                    if (squared > distance) continue;
                    nearest = spot;
                    distance = squared;
                }
                if (nearest == null) continue;
                if (attack == Attack.CRYSTAL_BURST) PodiumCrystal.resolveBlast(player, nearest);
                else hurt(player, dragon, skill.damage() * scale);
                if (attack == Attack.LEVITATION_FIELD) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 60, 0));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0));
                }
            }
            return true;
        }

        private boolean dive(Fight fight, List<Player> players, double damage, double radius, int warning, Language language) {
            EnderDragon dragon = fight.dragon;
            int stage = diveStage;
            int frame = age - diveStageStartedAt;
            boolean ringImpact = regularStyle == RegularStyle.SOVEREIGN && dives > 1 && stage + 1 == dives;
            double innerRadius = ringImpact ? radius * .45 : 0;
            if (frame == 0) {
                diveOrigin = dragon.getLocation().add(0, 2, 0);
                if (stage == 0) {
                    diveTarget = aim;
                    if (regularStyle == RegularStyle.SOVEREIGN && dives > 1) {
                        diveTargetsSeen = new HashSet<>();
                        diveTargetsSeen.add(regularTarget);
                    }
                } else {
                    Player target;
                    if (diveTargetsSeen != null) {
                        target = sovereignDiveTarget(players);
                        if (target != null) diveTargetsSeen.add(target.getUniqueId());
                    } else {
                        var targets = players.stream()
                            .filter(player -> inRange(diveOrigin, player.getEyeLocation(), RANGED_CAST_RANGE)
                                && clearCastPath(diveOrigin, player.getEyeLocation(), RANGED_CAST_RANGE)).toList();
                        target = targets.isEmpty() ? null : regularStyle.target(targets, diveOrigin, regularTarget);
                    }
                    if (target == null) return true;
                    regularTarget = target.getUniqueId();
                    diveTarget = target.getLocation();
                    if (regularStyle == RegularStyle.STORM) diveTarget = stormDiveAim(diveOrigin, diveTarget, target);
                    String cue = ringImpact ? "sovereign-ring-breath-strike-warning" : "breath-strike-stage-warning";
                    target.sendActionBar(Component.text(language.text(cue)
                        .replace("{stage}", Integer.toString(stage + 1)).replace("{total}", Integer.toString(dives)), NamedTextColor.GOLD));
                }
                flightControlled = true;
                diveFlightDeadline = 0;
                fight.requestPhase(EnderDragon.Phase.HOVER);
                if (dragon.getPhase() == EnderDragon.Phase.HOVER) dragon.setVelocity(new Vector());
                diveTarget.getWorld().playSound(diveTarget, Sound.ENTITY_ENDER_DRAGON_FLAP, 2, 0.8f);
            }
            if (frame < warning && frame % 4 == 0) {
                var audience = DragonParticles.warningViewers(diveTarget, 48);
                warningRing(diveTarget, radius, 28, DIVE_WARNING, audience);
                if (ringImpact) {
                    warningRing(diveTarget, innerRadius, 24, SAFE, audience);
                    for (int spoke = 0; spoke < 8; spoke++) {
                        double angle = spoke * Math.TAU / 8;
                        double middle = (innerRadius + radius) * .5;
                        warningPoint(diveTarget, middle * Math.cos(angle), .15, middle * Math.sin(angle), DIVE_WARNING, audience);
                    }
                } else {
                    warningPoint(diveTarget, 0, .45, 0, DIVE_WARNING, audience);
                }
            }
            if (frame < warning) return false;
            if (diveFlightDeadline == 0) {
                Location destination = diveTarget.clone().add(0, 2, 0);
                if (!clearCastPath(dragon.getLocation().add(0, 2, 0), destination, RANGED_CAST_RANGE)
                    || !fight.maintainNativeCharge(destination)) return true;
                nextDiveReaim = fight.age + NATIVE_CHARGE_REFRESH_TICKS;
                diveFlightDeadline = fight.age + 80;
                dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 5, 1);
                return false;
            }
            if (fight.age % 3 == STEP) {
                Location body = dragon.getLocation().add(0, 1, 0);
                body.getWorld().spawnParticle(Particle.DRAGON_BREATH, body, 6, .5, .45, .5, .02, 1f);
            }
            Location position = dragon.getLocation();
            if (horizontalDistanceSquared(position, diveTarget) <= 36 && Math.abs(position.getY() - diveTarget.getY()) <= 7) {
                if (diveStage + 1 >= dives) {
                    fight.releaseCastFlight();
                } else {
                    fight.requestPhase(EnderDragon.Phase.HOVER);
                    if (dragon.getVelocity().lengthSquared() > .01) dragon.setVelocity(new Vector());
                }
                var audience = DragonParticles.warningViewers(diveTarget, 48);
                warningRing(diveTarget, radius, 32, DIVE_WARNING, audience);
                if (ringImpact) warningRing(diveTarget, innerRadius, 24, SAFE, audience);
                diveTarget.getWorld().playSound(diveTarget, Sound.ENTITY_GENERIC_EXPLODE, 1, 0.8f);
                for (Player player : players) {
                    Location playerPosition = player.getLocation();
                    if (inImpactArea(playerPosition, diveTarget, radius, 2)
                        && horizontalDistanceSquared(playerPosition, diveTarget) >= innerRadius * innerRadius) {
                        hurt(player, dragon, damage);
                    }
                }
                diveStage++;
                if (diveStage >= dives) return true;
                diveStageStartedAt = age + 1;
                diveFlightDeadline = 0;
                return false;
            }
            if (fight.age >= diveFlightDeadline || (fight.age % 3 == STEP && SkyBite.hitFlightObstacle(dragon))) {
                fight.releaseCastFlight();
                return true;
            }
            if (fight.age >= nextDiveReaim) {
                if (!fight.maintainNativeCharge(diveTarget.clone().add(0, 2, 0))) {
                    fight.releaseCastFlight();
                    return true;
                }
                nextDiveReaim = fight.age + NATIVE_CHARGE_REFRESH_TICKS;
            }
            return false;
        }

        private Player sovereignDiveTarget(List<Player> players) {
            Player target = null;
            int bestPriority = -1;
            int matching = 0;
            var random = ThreadLocalRandom.current();
            for (Player player : players) {
                UUID id = player.getUniqueId();
                int priority = id.equals(regularTarget) ? 0 : diveTargetsSeen.contains(id) ? 1 : 2;
                if (priority < bestPriority || !inRange(diveOrigin, player.getEyeLocation(), RANGED_CAST_RANGE)
                    || !clearCastPath(diveOrigin, player.getEyeLocation(), RANGED_CAST_RANGE)) continue;
                if (priority > bestPriority) {
                    bestPriority = priority;
                    matching = 0;
                }
                if (++matching == 1 || random.nextInt(matching) == 0) target = player;
            }
            return target;
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
                if (age % 5 == STEP) {
                    var audience = DragonParticles.warningViewers(aim, 48);
                    shockwaveRing(Math.min(2, maxRadius), 16, WARNING, audience);
                    shockwaveRing(maxRadius, 36, WARNING, audience);
                }
                return false;
            }
            double radius = Math.min(maxRadius, (age - warning) * 0.4);
            if (age == warning + STEP) aim.getWorld().playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
            if ((age - warning) % 2 == STEP || radius >= maxRadius) {
                shockwaveRing(radius, 36, BEAM, DragonParticles.warningViewers(aim, 48));
            }
            double inner = Math.max(0, radius - .8);
            double outer = Math.min(maxRadius, radius + .4);
            double innerSquared = inner * inner;
            double outerSquared = outer * outer;
            for (Player player : players) {
                Location position = player.getLocation();
                if (Math.abs(position.getY() - aim.getY()) > 1.25) continue;
                double distanceSquared = horizontalDistanceSquared(position, aim);
                if (distanceSquared >= innerSquared && distanceSquared <= outerSquared
                    && !inShockwaveGap(position.getX() - aim.getX(), position.getZ() - aim.getZ())
                    && hit.add(player.getUniqueId())) hurt(player, dragon, damage);
            }
            return radius >= maxRadius;
        }

        private boolean inShockwaveGap(double offsetX, double offsetZ) {
            if (regularStyle != RegularStyle.SCOUT || offsetX * offsetX + offsetZ * offsetZ < .0001) return false;
            double gapAngle = Math.atan2(origin.getZ() - aim.getZ(), origin.getX() - aim.getX());
            return Math.cos(Math.atan2(offsetZ, offsetX) - gapAngle) >= .7071067811865476;
        }

        private void shockwaveRing(double radius, int points, Particle.DustOptions color, List<Player> audience) {
            if (audience.isEmpty()) return;
            if (regularStyle != RegularStyle.SCOUT) {
                warningRing(aim, radius, points, color, audience);
                return;
            }
            for (int point = 0; point < points; point++) {
                double angle = Math.TAU * point / points;
                double offsetX = Math.cos(angle), offsetZ = Math.sin(angle);
                if (!inShockwaveGap(offsetX, offsetZ)) warningPoint(aim, radius * offsetX, .15, radius * offsetZ, color, audience);
            }
            double gapAngle = Math.atan2(origin.getZ() - aim.getZ(), origin.getX() - aim.getX());
            for (int side = -1; side <= 1; side += 2) {
                double edge = gapAngle + side * Math.PI / 4;
                warningPoint(aim, radius * Math.cos(edge), .25, radius * Math.sin(edge), SAFE, audience);
                warningPoint(aim, radius * Math.cos(edge), .55, radius * Math.sin(edge), SAFE, audience);
            }
        }

        private boolean fireball(EnderDragon dragon, List<Player> players, double damage, double radius, int warning) {
            int travelTicks = 16;
            if (age % 4 == STEP) {
                var audience = DragonParticles.warningViewers(aim, 48);
                warningRing(aim, radius, 28, FIREBALL_WARNING, audience);
                double inner = Math.min(radius, .5);
                warningRing(aim, inner + (radius - inner) * age / (warning + travelTicks), 12, FIREBALL_WARNING, audience);
            }
            if (age <= warning) {
                return false;
            }
            if (age < warning + travelTicks) {
                double progress = (double) (age - warning) / travelTicks;
                Vector route = aim.toVector().subtract(origin.toVector());
                Location projectile = origin.clone().add(route.multiply(progress));
                if (age % 2 == STEP) aim.getWorld().spawnParticle(Particle.DRAGON_BREATH, projectile, 12, .35, .35, .35, .025, 1f);
                return false;
            }
            aim.getWorld().spawnParticle(Particle.DRAGON_BREATH, aim, 36, .9, .45, .9, .04, 1f);
            warningRing(aim, radius, 28, FIREBALL_WARNING, DragonParticles.warningViewers(aim, 48));
            aim.getWorld().playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 1, 1.1f);
            for (Player player : players) {
                Location position = player.getLocation();
                if (inImpactArea(position, aim, radius, 2.5)
                    && clearCastPath(aim.clone().add(0, .35, 0), position.add(0, .8, 0), radius + 4)) hurt(player, dragon, damage);
            }
            return true;
        }

        private boolean laser(EnderDragon dragon, List<Player> players, double damage, double radius, int warning) {
            boolean focus = regularStyle == RegularStyle.ABYSS;
            int lastTrackTick = warning / 3;
            boolean drawWarning = age % 4 == STEP;
            if (focus && age <= lastTrackTick && (drawWarning || age == lastTrackTick)) {
                for (Player player : players) {
                    if (!player.getUniqueId().equals(regularTarget)) continue;
                    Location body = player.getLocation().add(0, .9, 0);
                    Vector direction = body.toVector().subtract(origin.toVector());
                    if (direction.lengthSquared() > .01 && clearCastPath(origin, body, RANGED_CAST_RANGE)) {
                        focusedAim = body.add(direction.normalize().multiply(4));
                    }
                    break;
                }
            }
            boolean justLocked = focus && age == lastTrackTick + 1;
            if (justLocked) {
                origin.getWorld().playSound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2, 1.4f);
            }
            Location beamAim = focusedAim == null ? aim : focusedAim;
            if (age <= warning) {
                if (drawWarning || justLocked) {
                    Location endpoint = clip(origin, beamAim);
                    Vector segment = endpoint.toVector().subtract(origin.toVector());
                    var audience = DragonParticles.warningViewers(origin.clone().add(segment.clone().multiply(.5)), 64);
                    Particle.DustOptions color = focus && age > lastTrackTick ? LASER_LOCKED : LASER_WARNING;
                    warningLine(origin, endpoint, 2, color, audience);
                    Vector side = new Vector(-segment.getZ(), 0, segment.getX());
                    if (side.lengthSquared() > .01) {
                        side.normalize().multiply(radius);
                        warningLine(origin.clone().add(side), endpoint.clone().add(side), 3.5, color, audience);
                        warningLine(origin.clone().subtract(side), endpoint.clone().subtract(side), 3.5, color, audience);
                    }
                }
                return false;
            }
            Location endpoint = clip(origin, beamAim);
            line(origin, endpoint, 0.65, BEAM);
            origin.getWorld().playSound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);
            Vector segment = endpoint.toVector().subtract(origin.toVector());
            double lengthSquared = segment.lengthSquared();
            if (lengthSquared < 0.01) return true;
            for (Player player : players) {
                Location body = player.getLocation().add(0, .9, 0);
                Vector toPlayer = body.toVector().subtract(origin.toVector());
                double projection = toPlayer.dot(segment) / lengthSquared;
                if (projection >= 0 && projection <= 1
                    && toPlayer.subtract(segment.clone().multiply(projection)).lengthSquared() <= radius * radius
                    && clearCastPath(origin, body, RANGED_CAST_RANGE + 5)) {
                    hurt(player, dragon, damage);
                }
            }
            return true;
        }

        private static boolean inImpactArea(Location position, Location center, double radius, double height) {
            return Math.abs(position.getY() - center.getY()) <= height
                && horizontalDistanceSquared(position, center) <= radius * radius;
        }
    }
}
