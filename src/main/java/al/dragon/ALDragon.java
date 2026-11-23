package al.dragon;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import io.papermc.paper.event.block.DragonEggFormEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ALDragon extends JavaPlugin implements Listener {
    private static final int DRAGON_REGISTRATION_RETRIES = 4;
    private static final int DRAGON_RETRY_DELAY = 5;
    private static final int REWARD_COMMANDS_PER_TICK = 4;
    private static final long REWARD_TICK_BUDGET_NANOS = 2_000_000;
    private static final Map<String, String> SUMMON_ALIASES = Map.of(
        "随机", "random", "巡界龙", "scout", "巡界", "scout", "雷霆龙", "storm", "雷霆", "storm",
        "深渊龙", "abyss", "深渊", "abyss", "终末龙", "sovereign", "终末", "sovereign");
    private static final List<String> SUMMON_OPTIONS = List.of("random", "scout", "storm", "abyss", "sovereign", "随机", "巡界龙", "雷霆龙", "深渊龙", "终末龙");
    private final NamespacedKey profileKey = storedKey("dragon_profile");
    private final NamespacedKey scoutStreakKey = storedKey("random_scout_streak");
    private final NamespacedKey damageKey = storedKey("damage_multiplier");
    private final NamespacedKey lastDamageKey = storedKey("last_damage_at");
    private final NamespacedKey idleDamageKey = storedKey("idle_reset_damage_at");
    private final Set<UUID> eggDecisions = new HashSet<>();
    private final Map<UUID, PendingSummon> pendingSummons = new HashMap<>();
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private List<World> watchedWorlds = List.of();
    private final Set<UUID> activeDragons = new HashSet<>();
    private final ArrayDeque<Runnable> rewardCommands = new ArrayDeque<>();
    private BukkitTask battleTask;
    private BukkitTask rewardTask;
    private BukkitTask rewardSaveTask;
    private RewardLedger rewardLedger;
    private RewardLedger.Limits rewardLimits;
    private DragonProfiles profiles;
    private DragonSkills skills;
    private Language language;

    private record PendingSummon(DragonProfiles.Profile profile, List<EnderCrystal> crystals, UUID requester) {}

    private static final class Encounter {
        private final UUID dragon;
        private final DragonProfiles.Profile profile;
        private final DragonRewards rewards = new DragonRewards();
        private long idleDamageAt;
        private int lastCurrent = -1;
        private int lastMax = -1;
        private Language lastLanguage;
        private boolean rewarded;

        private Encounter(UUID dragon, DragonProfiles.Profile profile) {
            this.dragon = dragon;
            this.profile = profile;
        }
    }

    static NamespacedKey storedKey(String key) {
        return NamespacedKey.fromString("aldragon:" + key);
    }

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("ALEnhancedEnderDragon") != null) {
            getLogger().warning("Detected ALEnhancedEnderDragon. Both plugins can control the same dragon. Legacy Sky Bite can teleport the dragon and player, and legacy tracking lasers can fire away from the exit portal. Move the old plugin jar out of plugins and restart the server to use ALSuperDragon alone.");
        }
        try {
            loadSettings();
            rewardLedger = new RewardLedger(getDataFolder().toPath().resolve("reward-counts.tsv"));
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            throw new IllegalStateException("Could not load ALSuperDragon configuration", exception);
        }
        skills = new DragonSkills(language);
        var command = Objects.requireNonNull(getCommand("alsuperdragon"));
        command.setExecutor(this::handleCommand);
        command.setTabCompleter(this::completeCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getWorlds().forEach(this::watchExistingDragon);
    }

    private boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload", "summon" -> {
                if (!sender.hasPermission("alsuperdragon." + action)) {
                    sender.sendMessage(language.message("command-denied", NamedTextColor.RED));
                    return true;
                }
            }
            default -> { return false; }
        }
        return switch (action) {
            case "reload" -> args.length == 1 && reloadSettings(sender);
            case "summon" -> args.length <= 2 && summonDragon(sender, args.length == 2 ? args[1] : null);
            default -> false;
        };
    }

    private List<String> completeCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "summon").stream()
                .filter(action -> sender.hasPermission("alsuperdragon." + action))
                .filter(action -> !action.equals("summon") || sender instanceof Player player && isDragonWorld(player.getWorld()))
                .filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("summon") && sender.hasPermission("alsuperdragon.summon")
            && sender instanceof Player player && isDragonWorld(player.getWorld())) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return SUMMON_OPTIONS.stream().filter(id -> id.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private static boolean isDragonWorld(World world) {
        return world != null && world.getEnvironment() == World.Environment.THE_END && world.getEnderDragonBattle() != null;
    }

    private boolean summonDragon(CommandSender sender, String value) {
        if (!(sender instanceof Player player) || !isDragonWorld(player.getWorld())) {
            sender.sendMessage(language.message("summon-end-only", NamedTextColor.RED));
            return true;
        }
        String selection = value == null ? "random" : value.toLowerCase(Locale.ROOT);
        String profileId = SUMMON_ALIASES.getOrDefault(selection, selection);
        DragonProfiles.Profile forced = profiles.get(profileId);
        if (forced == null && !profileId.equals("random")) {
            sender.sendMessage(language.message("summon-invalid-profile", NamedTextColor.RED));
            return true;
        }
        World world = player.getWorld();
        DragonBattle battle = world.getEnderDragonBattle();
        if (pendingSummons.containsKey(world.getUID()) || battle.getEnderDragon() != null || !battle.hasBeenPreviouslyKilled()
            || battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            sender.sendMessage(language.message("summon-unavailable", NamedTextColor.RED));
            return true;
        }
        Location portal = battle.getEndPortalLocation();
        if (portal == null) {
            battle.generateEndPortal(true);
            portal = battle.getEndPortalLocation();
        }
        if (portal == null) {
            sender.sendMessage(language.message("summon-portal-missing", NamedTextColor.RED));
            return true;
        }
        List<EnderCrystal> crystals = new ArrayList<>(4);
        boolean started = false;
        try {
            for (BlockFace side : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                Location spot = portal.getBlock().getRelative(side, 3).getLocation().add(.5, 1, .5);
                EnderCrystal crystal = world.spawn(spot, EnderCrystal.class, spawned -> spawned.setShowingBottom(false));
                crystals.add(crystal);
                if (!crystal.isValid()) break;
            }
            started = crystals.size() == 4 && crystals.stream().allMatch(EnderCrystal::isValid)
                && battle.initiateRespawn(crystals);
        } catch (RuntimeException exception) {
            getLogger().log(Level.WARNING, "Could not prepare dragon respawn in " + world.getName(), exception);
        } finally {
            if (!started) crystals.forEach(EnderCrystal::remove);
        }
        if (!started) {
            sender.sendMessage(language.message("summon-start-failed", NamedTextColor.RED));
            return true;
        }
        pendingSummons.put(world.getUID(), new PendingSummon(forced, crystals, player.getUniqueId()));
        watchWorld(world);
        sender.sendMessage(forced == null ? language.message("summon-random-started", NamedTextColor.GREEN)
            : Component.text(language.text("summon-specific-started").replace("{dragon}", dragonName(forced)), NamedTextColor.GREEN));
        return true;
    }

    private void loadSettings() throws IOException, InvalidConfigurationException {
        saveDefaultConfig();
        var config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        if (!config.isSet("dragons") && config.isSet("config-version")) {
            try (var reader = new InputStreamReader(Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)) {
                var defaults = new YamlConfiguration();
                defaults.load(reader);
                config.setDefaults(defaults);
            }
            getLogger().warning("Legacy config.yml: using default dragon chances. Back it up and regenerate it to customize chances.");
        }
        var updatedProfiles = DragonProfiles.load(this, config);
        var updatedLanguage = new Language(this, config);
        File rewardsFile = new File(getDataFolder(), "rewards.yml");
        if (!rewardsFile.exists()) saveResource("rewards.yml", false);
        var rewardsConfig = new YamlConfiguration();
        rewardsConfig.load(rewardsFile);
        var updatedLimits = RewardLedger.Limits.load(rewardsConfig);
        profiles = updatedProfiles;
        language = updatedLanguage;
        rewardLimits = updatedLimits;
        if (skills != null) skills.updateLanguage(language);
    }

    private boolean reloadSettings(CommandSender sender) {
        try {
            loadSettings();
            sender.sendMessage(language.message("reload-success", NamedTextColor.GREEN));
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            getLogger().log(Level.WARNING, "Could not reload ALSuperDragon configuration", exception);
            sender.sendMessage(language.message("reload-failed", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public void onDisable() {
        stopBattleTask();
        stopRewardTask();
        if (rewardSaveTask != null) rewardSaveTask.cancel();
        rewardSaveTask = null;
        if (rewardLedger != null) saveRewardCounts(rewardLedger, rewardLedger.snapshot());
        if (!rewardCommands.isEmpty()) {
            getLogger().warning("Stopping with " + rewardCommands.size() + " unprocessed dragon reward commands; they will not be retried.");
            rewardCommands.clear();
        }
        watchedWorlds = List.of();
        if (skills != null) {
            skills.retain(Set.of());
            skills.clearPodiumCrystals();
        }
    }

    private void tick() {
        activeDragons.clear();
        List<World> snapshot = watchedWorlds;
        for (World world : snapshot) {
            if (snapshot != watchedWorlds && !watchedWorlds.contains(world)) continue;
            UUID worldId = world.getUID();
            DragonBattle battle = world.getEnderDragonBattle();
            EnderDragon dragon = battle == null ? null : battle.getEnderDragon();
            if (dragon == null || DragonRushCast.isDecoy(dragon)) {
                PendingSummon pending = pendingSummons.get(worldId);
                if (pending != null && battle != null && battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) continue;
                unwatchWorld(world);
                if (pending == null) continue;
                pendingSummons.remove(worldId);
                pending.crystals().forEach(EnderCrystal::remove);
                Player requester = Bukkit.getPlayer(pending.requester());
                if (requester != null) requester.sendMessage(language.message("summon-interrupted", NamedTextColor.RED));
                continue;
            }
            boolean alive = !dragon.isDead() && dragon.getHealth() > 0 && dragon.getPhase() != EnderDragon.Phase.DYING;
            if (!alive) continue;
            Encounter encounter = initialize(dragon);
            if (encounter.idleDamageAt > 0) resetIdleEncounter(dragon, encounter, System.currentTimeMillis());
            updateHealthTitle(dragon, encounter);
            activeDragons.add(dragon.getUniqueId());
            DragonParticles.tickBody(dragon, encounter.profile);
            skills.tick(dragon, battle, encounter.profile);
        }
        skills.retain(activeDragons);
        skills.tickPodiumCrystals(activeDragons);
        if (watchedWorlds.isEmpty()) stopBattleTask();
    }

    private void watchExistingDragon(World world) {
        if (world.getEnvironment() != World.Environment.THE_END) return;
        DragonBattle battle = world.getEnderDragonBattle();
        EnderDragon dragon = battle == null ? null : battle.getEnderDragon();
        if (dragon != null && !DragonRushCast.isDecoy(dragon)) watchWorld(world);
    }

    private void watchWorld(World world) {
        if (watchedWorlds.contains(world)) return;
        var watched = new ArrayList<>(watchedWorlds);
        watched.add(world);
        watchedWorlds = List.copyOf(watched);
        if (battleTask == null) battleTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
    }

    private void unwatchWorld(World world) {
        if (watchedWorlds.contains(world)) watchedWorlds = watchedWorlds.stream().filter(watched -> !watched.equals(world)).toList();
    }

    private void stopBattleTask() {
        if (battleTask == null) return;
        battleTask.cancel();
        battleTask = null;
    }

    int smallPartyMoneyCap() {
        return rewardLimits.smallPartyMoneyCap();
    }

    boolean rewardAvailable(RewardLedger.Key key) {
        return rewardLedger.available(key, rewardLimits);
    }

    boolean reserveReward(RewardLedger.Key key) {
        return rewardLedger.reserve(key, rewardLimits);
    }

    void finishReward(RewardLedger.Key key, boolean accepted) {
        if (!rewardLedger.finish(key, accepted) || rewardSaveTask != null) return;
        rewardSaveTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            rewardSaveTask = null;
            RewardLedger ledger = rewardLedger;
            var snapshot = ledger.snapshot();
            if (snapshot != null) Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveRewardCounts(ledger, snapshot));
        }, 20);
    }

    private void saveRewardCounts(RewardLedger ledger, RewardLedger.Snapshot snapshot) {
        if (snapshot == null) return;
        try {
            ledger.save(snapshot);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save dragon reward counts", exception);
        }
    }

    void enqueueReward(Runnable command) {
        rewardCommands.addLast(command);
        if (rewardTask == null) rewardTask = Bukkit.getScheduler().runTaskTimer(this, this::drainRewards, 1, 1);
    }

    private void drainRewards() {
        long start = System.nanoTime();
        for (int count = 0; count < REWARD_COMMANDS_PER_TICK; count++) {
            Runnable command = rewardCommands.pollFirst();
            if (command == null) break;
            try {
                command.run();
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Could not process a queued dragon reward", exception);
            }
            if (System.nanoTime() - start >= REWARD_TICK_BUDGET_NANOS) break;
        }
        if (rewardCommands.isEmpty()) stopRewardTask();
    }

    private void stopRewardTask() {
        if (rewardTask == null) return;
        rewardTask.cancel();
        rewardTask = null;
    }

    private void updateHealthTitle(EnderDragon dragon, Encounter encounter) {
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        int current = (int) Math.ceil(dragon.getHealth());
        int maximum = (int) Math.ceil(health.getValue());
        if (current == encounter.lastCurrent && maximum == encounter.lastMax && language == encounter.lastLanguage) return;
        encounter.lastCurrent = current;
        encounter.lastMax = maximum;
        encounter.lastLanguage = language;
        dragon.customName(Component.text(language.text("profile-boss-bar").replace("{current}", Integer.toString(current))
            .replace("{max}", Integer.toString(maximum)).replace("{dragon}", dragonName(encounter.profile))));
    }

    static boolean idleResetDue(long damageAt, long timeout, long now) {
        return damageAt > 0 && timeout > 0 && now - damageAt >= timeout;
    }

    private void resetIdleEncounter(EnderDragon dragon, Encounter encounter, long now) {
        if (!idleResetDue(encounter.idleDamageAt, encounter.profile.combat().idleResetMillis(), now)) return;
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        if (dragon.getHealth() < health.getValue()) dragon.setHealth(health.getValue());
        encounter.rewards.clearHits();
        encounter.idleDamageAt = 0;
        dragon.getPersistentDataContainer().remove(idleDamageKey);
    }

    private String dragonName(DragonProfiles.Profile profile) {
        return language.text("dragon-" + profile.id());
    }

    private Encounter initialize(EnderDragon dragon) {
        UUID world = dragon.getWorld().getUID();
        Encounter existing = encounters.get(world);
        if (existing != null && existing.dragon.equals(dragon.getUniqueId())) return existing;
        var data = dragon.getPersistentDataContainer();
        PendingSummon pending = pendingSummons.remove(world);
        DragonProfiles.Profile profile = profiles.get(data.get(profileKey, PersistentDataType.STRING));
        if (profile == null) {
            if (pending != null && pending.profile() != null) {
                profile = pending.profile();
            } else {
                var worldData = dragon.getWorld().getPersistentDataContainer();
                int scoutStreak = worldData.getOrDefault(scoutStreakKey, PersistentDataType.INTEGER, 0);
                profile = profiles.select(ThreadLocalRandom.current().nextDouble(), scoutStreak);
                int nextStreak = profiles.nextScoutStreak(profile.id(), scoutStreak);
                if (nextStreak == 0) worldData.remove(scoutStreakKey);
                else worldData.set(scoutStreakKey, PersistentDataType.INTEGER, nextStreak);
            }
            AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                double fraction = dragon.getHealth() / health.getValue();
                health.setBaseValue(profile.combat().health());
                dragon.setHealth(Math.min(health.getValue(), health.getValue() * fraction));
            }
            data.set(profileKey, PersistentDataType.STRING, profile.id());
            data.set(damageKey, PersistentDataType.DOUBLE, profile.combat().damage());
        }
        if (profile.combat().idleCritical().enabled() && !data.has(lastDamageKey, PersistentDataType.LONG)) {
            data.set(lastDamageKey, PersistentDataType.LONG, System.currentTimeMillis());
        }
        Encounter encounter = new Encounter(dragon.getUniqueId(), profile);
        if (profile.combat().idleResetMillis() > 0) {
            encounter.idleDamageAt = data.getOrDefault(idleDamageKey, PersistentDataType.LONG, 0L);
            if (encounter.idleDamageAt == 0) {
                AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
                if (health != null && dragon.getHealth() < health.getValue()) {
                    encounter.idleDamageAt = System.currentTimeMillis();
                    data.set(idleDamageKey, PersistentDataType.LONG, encounter.idleDamageAt);
                }
            }
        }
        encounters.put(world, encounter);
        return encounter;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        EnderDragon dragon = dragonSource(event.getDamageSource().getCausingEntity());
        if (dragon == null) dragon = dragonSource(event.getDamager());
        if (dragon == null) return;
        if (DragonRushCast.isDecoy(dragon) || (skills.isChargeStunned(dragon) && isDragonContact(event, dragon))
            || (event.getEntity() instanceof Enderman
            && (event.getDamager() instanceof EnderDragon || event.getDamager() instanceof ComplexEntityPart)
            && skills.isUsingSkyBite(dragon))) {
            event.setCancelled(true);
            return;
        }
        double multiplier = multiplier(dragon, damageKey);
        if (multiplier != 1) event.setDamage(event.getDamage() * multiplier);
    }

    private static boolean isDragonContact(EntityDamageByEntityEvent event, EnderDragon dragon) {
        Entity direct = event.getDamageSource().getDirectEntity();
        return (dragon.equals(direct) || (direct instanceof ComplexEntityPart part && dragon.equals(part.getParent())))
            && event.getDamageSource().getDamageType().equals(org.bukkit.damage.DamageType.MOB_ATTACK);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonPhaseChange(EnderDragonChangePhaseEvent event) {
        if (!skills.allowsPhaseChange(event.getEntity(), event.getNewPhase())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaDragonFireball(com.destroystokyo.paper.event.entity.EnderDragonShootFireballEvent event) {
        skills.replaceVanillaFireball(event);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChargeShieldBlock(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.isBlocking()
            || player.getActiveItem().getType() != Material.SHIELD) return;
        EnderDragon dragon = dragonSource(event.getDamageSource().getDirectEntity());
        var blocking = EntityDamageEvent.DamageModifier.BLOCKING;
        if (dragon != null && isDragonContact(event, dragon) && event.isApplicable(blocking) && event.getDamage(blocking) < 0) {
            skills.blockAiCharge(dragon, DragonSkills.ChargeBlock.SHIELD);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonIncomingDamage(EntityDamageEvent event) {
        EnderDragon dragon = enhancedDragonTarget(event.getEntity());
        if (dragon == null) return;
        Settings combat = initialize(dragon).profile.combat();
        if (event instanceof EntityDamageByEntityEvent attack) applyIdleCritical(attack, dragon, combat.idleCritical());
        double percent = combat.maxDamagePerHitPercent();
        if (percent <= 0) return;
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        double limit = health.getValue() * (percent / 100);
        if (event.getDamage() > limit) event.setDamage(limit);
    }

    private void applyIdleCritical(EntityDamageByEntityEvent event, EnderDragon dragon, Settings.IdleCritical critical) {
        if (!critical.enabled() || event.getFinalDamage() <= 0 || attacker(event) == null) return;
        Long lastDamage = dragon.getPersistentDataContainer().get(lastDamageKey, PersistentDataType.LONG);
        if (lastDamage != null && System.currentTimeMillis() - lastDamage >= critical.idleMillis()) {
            event.setDamage(event.getDamage() * critical.multiplier());
        }
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamageSource().getCausingEntity() instanceof Player player) return player;
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDamaged(EntityDamageEvent event) {
        double damage = event.getFinalDamage();
        if (!Double.isFinite(damage) || damage <= 0) return;
        EnderDragon dragon = enhancedDragonTarget(event.getEntity());
        if (dragon == null) return;
        Encounter encounter = initialize(dragon);
        long now = System.currentTimeMillis();
        if (encounter.profile.combat().idleResetMillis() > 0) {
            resetIdleEncounter(dragon, encounter, now);
            encounter.idleDamageAt = now;
            dragon.getPersistentDataContainer().set(idleDamageKey, PersistentDataType.LONG, now);
        }
        if (encounter.profile.combat().idleCritical().enabled()) {
            dragon.getPersistentDataContainer().set(lastDamageKey, PersistentDataType.LONG, now);
        }
        if (event instanceof EntityDamageByEntityEvent attack) {
            Player player = attacker(attack);
            if (player != null) encounter.rewards.record(player, Math.min(dragon.getHealth(), damage));
        }
    }

    private EnderDragon enhancedDragonTarget(Entity target) {
        EnderDragon dragon = target instanceof EnderDragon main ? main
            : target instanceof ComplexEntityPart part && part.getParent() instanceof EnderDragon parent ? parent : null;
        if (dragon == null || DragonRushCast.isDecoy(dragon) || dragon.isDead() || dragon.getHealth() <= 0 || dragon.isInvulnerable()
            || dragon.getPhase() == EnderDragon.Phase.DYING || dragon.getWorld().getEnvironment() != World.Environment.THE_END) return null;
        DragonBattle battle = dragon.getWorld().getEnderDragonBattle();
        return battle != null && dragon.equals(battle.getEnderDragon())
            ? dragon : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPodiumCrystalDamage(EntityDamageEvent event) {
        PodiumCrystal crystal = skills.findPodiumCrystal(event.getEntity());
        if (crystal == null) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent attack
            && (attack.getDamageSource().getCausingEntity() instanceof Player || attack.getDamager() instanceof Player
                || (attack.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player))) {
            crystal.defuse();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPodiumCrystalPrime(ExplosionPrimeEvent event) {
        if (skills.findPodiumCrystal(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPodiumCrystalIgnite(BlockIgniteEvent event) {
        if (skills.findPodiumCrystal(event.getIgnitingEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecoyExplosion(EntityExplodeEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && !event.blockList().isEmpty()) {
            skills.blockAiCharge(dragon, DragonSkills.ChargeBlock.WALL);
        }
        if (isProtectedDragon(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecoyBlockChange(EntityChangeBlockEvent event) {
        if (isProtectedDragon(event.getEntity())) event.setCancelled(true);
    }

    private boolean isProtectedDragon(Entity entity) {
        return entity instanceof EnderDragon dragon && (DragonRushCast.isDecoy(dragon)
            || (skills != null && skills.controlsDragonMovement(dragon)));
    }

    private static EnderDragon dragonSource(Entity source) {
        if (source instanceof EnderDragon dragon) return dragon;
        if (source instanceof ComplexEntityPart part && part.getParent() instanceof EnderDragon dragon) return dragon;
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof EnderDragon dragon) return dragon;
        if (source instanceof AreaEffectCloud cloud && cloud.getSource() instanceof EnderDragon dragon) return dragon;
        return null;
    }

    private static double multiplier(EnderDragon dragon, NamespacedKey key) {
        return dragon == null ? 1 : dragon.getPersistentDataContainer().getOrDefault(key, PersistentDataType.DOUBLE, 1.0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || DragonRushCast.isDecoy(dragon)
            || dragon.getWorld().getEnvironment() != World.Environment.THE_END) return;
        queueDragonRegistration(dragon, dragon.getWorld(), true, DRAGON_REGISTRATION_RETRIES, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) return;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof EnderDragon dragon && !DragonRushCast.isDecoy(dragon)) {
                queueDragonRegistration(dragon, world, false, DRAGON_REGISTRATION_RETRIES, 1);
            }
        }
    }

    private void queueDragonRegistration(EnderDragon dragon, World world, boolean announce, int retries, long delay) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (dragon.isDead() || dragon.getWorld() != world || Bukkit.getWorld(world.getUID()) != world) return;
            DragonBattle battle = world.getEnderDragonBattle();
            if (!dragon.isValid() || battle == null || !dragon.equals(battle.getEnderDragon())) {
                if (retries > 0) queueDragonRegistration(dragon, world, announce, retries - 1, DRAGON_RETRY_DELAY);
                return;
            }
            watchWorld(world);
            if (!announce) return;
            Encounter encounter = initialize(dragon);
            Location center = battle.getEndPortalLocation();
            if (center == null) center = dragon.getPodium();
            if (center != null && encounter.profile.id().equals("storm")) {
                Settings combat = encounter.profile.combat();
                double damage = combat.skills() ? combat.entranceStormDamage() * combat.tuning().damageScale() : 0;
                LightningCast.playEntrance(this, dragon, center, damage);
            }
            if (center == null) center = dragon.getLocation();
            showTitle(center, encounter.profile.combat().tuning(), language.message("spawn-title", NamedTextColor.LIGHT_PURPLE),
                Component.text(language.text("profile-spawn-subtitle").replace("{dragon}", dragonName(encounter.profile))
                    .replace("{max}", Integer.toString((int) Math.ceil(encounter.profile.combat().health()))), NamedTextColor.WHITE));
        }, delay);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || dragon.isSilent()) return;
        Encounter encounter = encounters.get(dragon.getWorld().getUID());
        if (encounter == null || !encounter.dragon.equals(dragon.getUniqueId())) return;
        boolean playSound = event.shouldPlayDeathSound();
        Location origin = dragon.getLocation();
        event.setShouldPlayDeathSound(false);
        dragon.setSilent(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (event.isCancelled()) {
                dragon.setSilent(false);
                return;
            }
            if (!playSound) return;
            double radius = encounter.profile.combat().tuning().soundRadius();
            if (radius == 0) return;
            for (var player : origin.getNearbyPlayers(radius)) {
                if (player.getLocation().distanceSquared(origin) <= radius * radius) {
                    player.playSound(origin, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, (float) Math.max(1, (radius + 4) / 16), 1.0f);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonRemoved(EntityRemoveEvent event) {
        if (event.getCause() != EntityRemoveEvent.Cause.DEATH || !(event.getEntity() instanceof EnderDragon dragon)) return;
        Encounter encounter = encounters.get(dragon.getWorld().getUID());
        if (encounter == null || !encounter.dragon.equals(dragon.getUniqueId()) || encounter.rewarded) return;
        encounter.rewarded = true;
        Bukkit.getScheduler().runTask(this, () -> encounter.rewards.distribute(this, language, encounter.profile));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        watchExistingDragon(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) return;
        unwatchWorld(world);
        encounters.remove(world.getUID());
        pendingSummons.remove(world.getUID());
    }

    private void showTitle(Location center, Settings.Tuning tuning, Component title, Component subtitle) {
        double radius = tuning.titleRadius();
        if (radius == 0 || tuning.titleTicks() == 0) return;
        Title message = Title.title(title, subtitle, tuning.titleTimes());
        for (var player : center.getNearbyPlayers(radius)) {
            if (player.getLocation().distanceSquared(center) <= radius * radius) player.showTitle(message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggRoll(DragonEggFormEvent event) {
        if (!event.getDragonBattle().hasBeenPreviouslyKilled()) return;
        Encounter encounter = eggEncounter(event);
        if (encounter != null && ThreadLocalRandom.current().nextDouble(100) >= encounter.profile.eggChance()) event.setCancelled(true);
    }

    private Encounter eggEncounter(DragonEggFormEvent event) {
        Encounter encounter = encounters.get(event.getBlock().getWorld().getUID());
        EnderDragon dragon = event.getDragonBattle().getEnderDragon();
        return dragon == null || encounter != null && encounter.dragon.equals(dragon.getUniqueId()) ? encounter : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEggResult(DragonEggFormEvent event) {
        Encounter encounter = eggEncounter(event);
        if (encounter == null) return;
        Block block = event.getBlock();
        UUID world = block.getWorld().getUID();
        if (!eggDecisions.add(world)) return;
        boolean approved = !event.isCancelled() && event.getNewState().getType() == Material.DRAGON_EGG;
        Bukkit.getScheduler().runTask(this, () -> {
            eggDecisions.remove(world);
            boolean generated = approved && block.getType() == Material.DRAGON_EGG;
            showTitle(block.getLocation().toCenterLocation(), encounter.profile.combat().tuning(),
                language.message("death-title", NamedTextColor.LIGHT_PURPLE),
                language.message(generated ? "egg-dropped" : "egg-missed", generated ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        });
    }
}
