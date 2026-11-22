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
    private static final MinorSkill[] MINOR_ORDER = MinorSkill.values();
    private static final double RANGED_CAST_RANGE = 64;
    private static final NamespacedKey RUSH_REACHED = ALDragon.storedKey("rush_reached");
    private static final NamespacedKey RUSH_CASTS = ALDragon.storedKey("rush_casts");
    private static final NamespacedKey ROAR_REACHED = ALDragon.storedKey("roar_reached");
    private static final NamespacedKey ROAR_CASTS = ALDragon.storedKey("roar_casts");
    private static final NamespacedKey PODIUM_CRYSTAL_USED = ALDragon.storedKey("podium_crystal_used");
    private static final NamespacedKey PODIUM_CRYSTAL_REACHED = ALDragon.storedKey("podium_crystal_reached");
    private static final Particle.DustOptions WARNING = new Particle.DustOptions(Color.fromRGB(225, 86, 246), 1.2f);
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
        return fight != null && (fight.bite != null || fight.trackingLaser != null
            || fight.phase == CombatPhase.DIVE || fight.phase == CombatPhase.LASER);
    }

    boolean isUsingSkyBite(EnderDragon dragon) {
        Fight fight = fights.get(dragon.getUniqueId());
        return fight != null && fight.bite != null;
    }

    void tick(EnderDragon dragon, DragonBattle battle, Settings settings) {
        if (!settings.skills()) return;
        UUID id = dragon.getUniqueId();
        Fight existing = fights.get(id);
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
        Fight fight = fights.computeIfAbsent(id, ignored -> new Fight(dragon, settings, interval));
        fight.idle = false;
        fight.age += STEP;
        DragonParticles particles = new DragonParticles(viewers);
        int auraInterval = settings.electricAuraTicks();
        if (auraInterval > 0) {
            if (fight.age % auraInterval == STEP) particles.electricAura(dragon.getLocation(), fight.age);
        } else if (settings.trail() && fight.age % 3 == STEP) {
            particles.trail(dragon.getLocation().add(0, 2.5, 0));
        }
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
            if (fight.bite.tick(players)) {
                fight.bite = null;
                recover(fight, settings.rhythm().recovery());
            }
            return;
        }
        if (fight.trackingLaser != null) {
            if (fight.trackingLaser.tick(players, particles)) {
                fight.trackingLaser = null;
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
            if (fight.cast.tick(dragon, players, settings, fight.breathClouds)) {
                recover(fight, fight.cast.attack.isMajor() ? settings.majorRecoveryTicks() : settings.rhythm().recovery());
                fight.cast = null;
            }
            return;
        }
        if (fight.aiChargeUntil > 0) {
            if (dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER && fight.age < fight.aiChargeUntil) return;
            fight.endAiCharge();
            recover(fight, settings.rhythm().recovery());
        }
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
            if (rushPending) {
                fight.rush = new DragonRushCast(dragon, players, language, rush, tuning, rushCasts);
                data.set(RUSH_CASTS, PersistentDataType.INTEGER, rushCasts + 1);
                beginMajorCast(fight);
                return;
            }
            if (startDatapackPhase(fight, players, remaining)) {
                beginMajorCast(fight);
                fight.nextRegularCast = Math.max(fight.nextRegularCast, fight.age + interval);
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
            if (startMinorSkill(fight, players, remaining, true)) return;
        }
        boolean regularTried = false;
        if (fight.preferRegular && fight.age >= fight.nextRegularCast) {
            regularTried = true;
            if (tryRegularSkill(dragon, fight, players, remaining, interval, particles)) return;
        }
        if (startMinorSkill(fight, players, remaining, false)) {
            fight.preferRegular = true;
            return;
        }
        if (!regularTried && fight.age >= fight.nextRegularCast
            && tryRegularSkill(dragon, fight, players, remaining, interval, particles)) return;
        tryAiCharge(fight, players);
    }

    private boolean tryRegularSkill(EnderDragon dragon, Fight fight, List<Player> players, double remaining,
                                    int interval, DragonParticles particles) {
        var settings = fight.settings;
        var tuning = settings.tuning();
        if (settings.fireball() >= 0 && replaceVanillaFireball(fight, particles)) {
            beginRegularCast(fight, interval);
            return true;
        }
        List<Attack> options = new ArrayList<>(4);
        Location origin = dragon.getLocation().add(0, 2, 0);
        var nearby = players.stream().filter(player -> inRange(origin, player.getEyeLocation(), RANGED_CAST_RANGE)).toList();
        var waveTargets = nearby.stream().filter(player -> Math.abs(player.getLocation().getY() - origin.getY()) <= 8).toList();
        if (settings.shockwave() >= 0 && !waveTargets.isEmpty()) options.add(Attack.SHOCKWAVE);
        if (settings.dive() >= 0 && !nearby.isEmpty()) options.add(Attack.DIVE);
        boolean fireballReady = settings.fireball() >= 0 && remaining <= tuning.fireballHp();
        boolean laserReady = settings.laser() >= 0 && remaining <= tuning.laserHp();
        var visible = fireballReady || laserReady
            ? nearby.stream().filter(player -> clearCastPath(origin, player.getEyeLocation(), RANGED_CAST_RANGE)).toList()
            : List.<Player>of();
        if (!visible.isEmpty() && fireballReady) options.add(Attack.FIREBALL);
        if (!visible.isEmpty() && laserReady) options.add(Attack.LASER);
        if (options.isEmpty()) {
            fight.nextRegularCast = fight.age + 20;
            return false;
        }
        if (options.size() > 1) options.remove(fight.lastRegularAttack);
        beginRegularCast(fight, interval);
        var random = ThreadLocalRandom.current();
        Attack attack = options.contains(Attack.LASER) && !fight.usedLaser ? Attack.LASER
            : options.contains(Attack.FIREBALL) && !fight.usedFireball ? Attack.FIREBALL
            : remaining <= tuning.diveHp() && options.contains(Attack.DIVE) && !fight.usedDive ? Attack.DIVE
            : options.get(random.nextInt(options.size()));
        fight.lastRegularAttack = attack;
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
        return true;
    }

    private void tryAiCharge(Fight fight, List<Player> players) {
        int cooldown = fight.settings.aiChargeTicks();
        EnderDragon dragon = fight.dragon;
        if (cooldown == 0 || fight.age < fight.nextAiCheck || dragon.getPhase() != EnderDragon.Phase.CIRCLING
            || dragon.isInvulnerable()) return;
        fight.nextAiCheck = fight.age + 40;
        Location origin = dragon.getLocation().add(0, 2, 0);
        Player target = null;
        Location aim = null;
        double best = Double.POSITIVE_INFINITY;
        for (Player player : players) {
            Location spot = player.getLocation().add(0, 1, 0);
            double distance = spot.distanceSquared(origin);
            if (distance < 18 * 18 || distance > 56 * 56) continue;
            double priority = distance + (player.getUniqueId().equals(fight.lastAiTarget) ? 56 * 56 : 0);
            if (priority >= best || !clearCastPath(origin, spot, 56)) continue;
            best = priority;
            target = player;
            aim = spot;
        }
        if (target == null) return;
        Vector heading = aim.toVector().subtract(origin.toVector()).setY(0);
        if (heading.lengthSquared() < 4) return;
        aim.add(heading.normalize().multiply(4));
        fight.nextAiCheck = fight.age + cooldown;
        if (!DragonParticles.loaded(aim) || !SkyBite.tryNativeCharge(dragon, aim)) return;
        fight.aiChargeUntil = fight.age + 65;
        beginCast(fight);
        fight.lastAiTarget = target.getUniqueId();
        target.sendActionBar(language.message("ai-charge-warning", NamedTextColor.RED));
        dragon.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.5f, 1.25f);
    }

    private boolean startMinorSkill(Fight fight, List<Player> players, double health, boolean major) {
        var settings = fight.settings;
        var extra = settings.extra();
        MinorSkill selected = null;
        List<Player> targets = List.of();
        int earliest = Integer.MAX_VALUE;
        Location origin = fight.dragon.getLocation();
        Location beamOrigin = origin.clone().add(0, 2, 0);
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
                    case TRACKING_LASER -> players.stream().filter(player -> clearCastPath(beamOrigin,
                        player.getEyeLocation(), extra.trackingLaser().range())).toList();
                    case THUNDER_CHAIN -> thunderChainTargets(players, skill.radius() * .75);
                    default -> players.stream().filter(player -> DragonParticles.loaded(player.getLocation())).toList();
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
        Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        for (Player player : players) player.sendActionBar(language.message(selected.warning, NamedTextColor.LIGHT_PURPLE));
        fight.minorReady.put(selected, fight.age + selected.settings(extra).cooldownTicks());
        if (major) beginMajorCast(fight);
        else beginCast(fight);
        if (selected == MinorSkill.SKY_BITE) {
            fight.bite = new SkyBite(fight.dragon, target, language, extra, settings.tuning().damageScale());
        } else if (selected == MinorSkill.TRACKING_LASER) {
            fight.trackingLaser = new TrackingLaser(fight.dragon, target, language, extra.trackingLaser(), settings.tuning().damageScale());
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
        Player anchor = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        Location start = anchor.getLocation();
        if (!DragonParticles.loaded(start)) return List.of();
        double nearest = range * range;
        Player partner = null;
        for (Player player : players) {
            if (player == anchor) continue;
            Location spot = player.getLocation();
            double distance = spot.distanceSquared(start);
            if (distance <= nearest && DragonParticles.loaded(spot)) {
                partner = player;
                nearest = distance;
            }
        }
        return partner == null ? List.of() : List.of(anchor, partner);
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
        Location probe = from.clone();
        for (double distance = 1; distance < length; distance++) {
            if (!DragonParticles.loaded(probe.add(direction))) return false;
        }
        return from.getWorld().rayTraceBlocks(from, direction, length, FluidCollisionMode.NEVER, true) == null;
    }

    private boolean startDatapackPhase(Fight fight, List<Player> players, double health) {
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
        if (!fight.quarterPhase && health <= settings.tuning().laserHp() && settings.laser() >= 0) {
            fight.quarterPhase = true;
            fight.phase = CombatPhase.LASER;
            fight.phaseTick = 0;
            announce(players, "laser-phase", NamedTextColor.DARK_PURPLE);
            return true;
        }
        if (!fight.phaseDiveUsed && fight.age >= fight.nextPhaseDiveCheck && settings.dive() >= 0
            && health <= settings.tuning().diveHp()) {
            Location position = dragon.getLocation();
            if (players.stream().noneMatch(player -> horizontalDistanceSquared(player.getLocation(), position) <= 40 * 40)) {
                fight.nextPhaseDiveCheck = fight.age + 10;
                return false;
            }
            fight.phaseDiveUsed = true;
            fight.phase = CombatPhase.DIVE;
            fight.diveCount = 1;
            fight.phaseTick = -40;
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

    private boolean replaceVanillaFireball(Fight fight, DragonParticles particles) {
        Location origin = fight.dragon.getLocation();
        for (DragonFireball fireball : origin.getWorld().getNearbyEntitiesByType(DragonFireball.class, origin, 32)) {
            if (!fight.dragon.equals(fireball.getShooter()) || fireball.getTicksLived() > 2) continue;
            fireball.remove();
            launchUltra(fight, particles, origin.clone(), false, false);
            var maxHealth = fight.dragon.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && fight.dragon.getHealth() < maxHealth.getValue()) {
                fight.phase = CombatPhase.EXTRA_FIREBALL;
                fight.phaseTick = -1;
            }
            return true;
        }
        return false;
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
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
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
        Location start = spot.clone().subtract(0, 1, 0);
        int length = -1;
        Location impact = null;
        for (int step = 0; step < 128 && DragonParticles.loaded(spot); step++, spot.add(direction)) {
            length = step;
            if (step % 3 == 0) particles.laser(spot, direction);
            if (DragonParticles.stopsLaser(spot)) {
                impact = spot.clone();
                impact.setY(impact.getBlockY());
                Location cloudSpot = impact.clone().add(0, 1, 0);
                if (fight.breathClouds.size() < 6 && fight.age >= fight.nextLaserCloud
                    && ThreadLocalRandom.current().nextInt(10) == 0 && DragonParticles.loaded(cloudSpot)) {
                    fight.breathClouds.add(TrueEndingImpact.cloud(fight.dragon, cloudSpot, 400));
                    fight.nextLaserCloud = fight.age + 20;
                }
                particles.laserHit(spot);
                break;
            }
        }
        if (length >= 0) laserDamage(fight, particles, start, direction, length, impact);
    }

    private void laserDamage(Fight fight, DragonParticles particles, Location start, Vector direction, int length, Location impact) {
        Location end = start.clone().add(direction.clone().multiply(length));
        var box = org.bukkit.util.BoundingBox.of(start, end).expand(5);
        var world = start.getWorld();
        for (Entity candidate : world.getNearbyEntities(box)) {
            if (!(candidate instanceof LivingEntity target) || !TrueEndingImpact.target(target)) continue;
            Location position = target.getLocation();
            double projection = (position.getX() - start.getX()) * direction.getX()
                + (position.getY() - start.getY()) * direction.getY() + (position.getZ() - start.getZ()) * direction.getZ();
            int sample = (int) Math.round(Math.clamp(projection, 0, length));
            double dx = position.getX() - (start.getX() + direction.getX() * sample);
            double dy = position.getY() - (start.getY() + direction.getY() * sample);
            double dz = position.getZ() - (start.getZ() + direction.getZ() * sample);
            if (dx * dx + dy * dy + dz * dz > 2.25 && (impact == null || position.distanceSquared(impact) > 20.25)) continue;
            if (target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2) continue;
            particles.laserEntityHit(position);
            world.playSound(position, Sound.ENTITY_BLAZE_DEATH, 2, .6f);
            hurtEntity(target, fight.dragon, fight.settings.laser() * fight.settings.tuning().damageScale(), DamageType.DRAGON_BREATH);
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
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            fight.phase = CombatPhase.NONE;
            fight.phaseTick = 0;
        }
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
        private Attack lastRegularAttack;
        private boolean reinforcementsPending;
        private int nextAiCheck;
        private int nextLaserCloud;
        private int aiChargeUntil;
        private UUID lastAiTarget;
        private int nextRoar;
        private int nextCast;
        private boolean idle;
        private boolean podiumActive;
        private boolean usedFireball;
        private boolean usedLaser;
        private boolean usedDive;
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
        private Cast cast;
        private CombatPhase phase = CombatPhase.NONE;
        private int phaseTick;
        private boolean halfPhase;
        private boolean quarterPhase;
        private boolean phaseDiveUsed;
        private int diveCount;
        private DragonRushCast rush;
        private SkyBite bite;
        private TrackingLaser trackingLaser;
        private final Map<MinorSkill, Integer> minorReady = new EnumMap<>(MinorSkill.class);

        private final Settings settings;

        private Fight(EnderDragon dragon, Settings settings, int interval) {
            this.dragon = dragon;
            this.settings = settings;
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
            return revivalCasting || aiChargeUntil > 0 || podiumActive || rush != null || bite != null || trackingLaser != null || phase != CombatPhase.NONE || cast != null;
        }

        private void endAiCharge() {
            if (aiChargeUntil > 0 && dragon.isValid() && !dragon.isDead() && dragon.getPhase() == EnderDragon.Phase.CHARGE_PLAYER) {
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
            }
            aiChargeUntil = 0;
        }

        private void cleanup() {
            endAiCharge();
            if (revivalCasting) revival = null;
            revivalCasting = false;
            if (bite != null) bite.cancel();
            if ((phase == CombatPhase.DIVE || phase == CombatPhase.LASER) && dragon.isValid() && !dragon.isDead()
                && dragon.getPhase() != EnderDragon.Phase.DYING) {
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
            }
            if (rush != null) rush.cleanup();
            rush = null;
            bite = null;
            trackingLaser = null;
            cast = null;
            phase = CombatPhase.NONE;
            phaseTick = 0;
            laserPose = null;
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
                case VOID_RINGS -> voidRings(dragon, players, settings.extra().voidRings(), scale);
                case THUNDER, THUNDERSTORM -> {
                    if (lightning == null) lightning = new LightningCast(aim, attack == Attack.THUNDERSTORM);
                    yield lightning.tick(dragon, players, attack == Attack.THUNDER ? settings.extra().thunder()
                        : settings.extra().thunderstorm(), scale);
                }
                case THUNDER_CHAIN -> thunderChain(dragon, players, settings.extra().thunderChain(), scale);
                case RIFT_MARK -> riftMark(dragon, players, settings.extra().riftMark(), scale);
                case GRAVITY_WELL -> gravityWell(dragon, players, settings.extra().gravityWell(), scale);
                case END_CROSS -> endCross(dragon, players, settings.extra().endCross(), scale);
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
                    ring(aim, skill.radius(), 22, SAFE);
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

        private boolean endCross(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale) {
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
            }
            Vector perpendicular = new Vector(-crossDirection.getZ(), 0, crossDirection.getX());
            if (frame < (second ? secondWarning : warning)) {
                if (frame % 5 == STEP) drawCross(perpendicular, skill.radius(), WARNING);
                return false;
            }
            drawCross(perpendicular, skill.radius(), BEAM);
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
            for (int sign : new int[]{-1, 1}) {
                var offset = perpendicular.clone().multiply(sign * 1.15);
                var forward = crossDirection.clone().multiply(radius);
                line(aim.clone().add(offset).subtract(forward), aim.clone().add(offset).add(forward), 1.6, color);
                offset = crossDirection.clone().multiply(sign * 1.15);
                forward = perpendicular.clone().multiply(radius);
                line(aim.clone().add(offset).subtract(forward), aim.clone().add(offset).add(forward), 1.6, color);
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

        private boolean voidRings(EnderDragon dragon, List<Player> players, Settings.AreaSkill skill, double scale) {
            if (!DragonParticles.loaded(aim)) return true;
            int warning = skill.warningTicks();
            int secondWarning = Math.max(20, warning * 2 / 3);
            boolean outer = age > warning;
            int frame = outer ? age - warning : age;
            double split = skill.radius() * .48;
            double radius = outer ? skill.radius() : split;
            if (frame == STEP) {
                aim.getWorld().playSound(aim, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.3f, outer ? .8f : 1.2f);
            }
            if (frame < (outer ? secondWarning : warning)) {
                if (frame % 4 == STEP) {
                    ring(aim, radius, 32, WARNING);
                    ring(aim, outer ? split + (radius - split) * .5 : radius * .55, 20, WARNING);
                    if (outer) ring(aim, split, 24, SAFE);
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
                if (age % 5 == STEP) {
                    ring(aim, Math.min(2, maxRadius), 18, WARNING);
                    ring(aim, maxRadius, 28, WARNING);
                }
                return false;
            }
            double radius = Math.min(maxRadius, (age - warning) * 0.4);
            if (age == warning + STEP) aim.getWorld().playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
            if ((age - warning) % 3 == STEP || radius >= maxRadius) ring(aim, radius, 30, BEAM);
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
                if (age % 5 == STEP) ring(aim, radius, 24, WARNING);
                return false;
            }
            if (age < warning + 25) {
                double progress = (age - warning) / 25.0;
                Vector route = aim.toVector().subtract(origin.toVector());
                Location projectile = origin.clone().add(route.multiply(progress));
                if (age % 2 == STEP) aim.getWorld().spawnParticle(Particle.DRAGON_BREATH, projectile, 12, .35, .35, .35, .025, 1f);
                if (age % 5 == STEP) ring(aim, radius, 24, WARNING);
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
            if (age <= warning) {
                if (age % 4 == STEP) line(origin, clip(origin, aim), 3, WARNING);
                return false;
            }
            Location endpoint = clip(origin, aim);
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
