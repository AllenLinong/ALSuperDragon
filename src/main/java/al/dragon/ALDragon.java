package al.dragon;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ALDragon extends JavaPlugin implements Listener {
    private final NamespacedKey healthKey = storedKey("health_multiplier");
    private final NamespacedKey damageKey = storedKey("damage_multiplier");
    private final NamespacedKey lastDamageKey = storedKey("last_damage_at");
    private final NamespacedKey missesKey = storedKey("egg_misses");
    private final Set<UUID> eggDecisions = new HashSet<>();
    private final Map<UUID, PendingSummon> pendingSummons = new HashMap<>();
    private Settings settings;
    private DragonSkills skills;
    private Language language;

    private record PendingSummon(double multiplier, List<EnderCrystal> crystals, Player requester) {}

    static NamespacedKey storedKey(String key) {
        return NamespacedKey.fromString("aldragon:" + key);
    }

    @Override
    public void onEnable() {
        language = new Language(this);
        settings = Settings.load(getConfig());
        skills = new DragonSkills(settings, language);
        var command = Objects.requireNonNull(getCommand("alsuperdragon"));
        command.setExecutor(this::handleCommand);
        command.setTabCompleter(this::completeCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
    }

    private boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload", "summon", "resetpity" -> {
                if (!sender.hasPermission("alsuperdragon." + action)) {
                    sender.sendMessage(language.message("command-denied", NamedTextColor.RED));
                    return true;
                }
            }
            default -> { return false; }
        }
        return switch (action) {
            case "reload" -> args.length == 1 && reloadSettings(sender);
            case "summon" -> args.length == 2 && summonDragon(sender, args[1]);
            case "resetpity" -> args.length <= 2 && resetPity(sender, args.length == 2 ? args[1] : null);
            default -> false;
        };
    }

    private List<String> completeCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "summon", "resetpity").stream()
                .filter(action -> sender.hasPermission("alsuperdragon." + action))
                .filter(action -> !action.equals("summon") || sender instanceof Player player && isDragonWorld(player.getWorld()))
                .filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("resetpity") && sender.hasPermission("alsuperdragon.resetpity")) {
            return Bukkit.getWorlds().stream().filter(ALDragon::isDragonWorld).map(World::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
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
        double multiplier;
        try {
            multiplier = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            multiplier = Double.NaN;
        }
        if (!Double.isFinite(multiplier) || multiplier < .1 || multiplier > 5.12) {
            sender.sendMessage(language.message("summon-invalid-multiplier", NamedTextColor.RED));
            return true;
        }
        World world = player.getWorld();
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle.getEnderDragon() != null || !battle.hasBeenPreviouslyKilled()
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
        pendingSummons.put(world.getUID(), new PendingSummon(multiplier, crystals, player));
        sender.sendMessage(Component.text(language.text("summon-started")
            .replace("{health}", Double.toString(multiplier)).replace("{damage}", Double.toString(multiplier / 2)), NamedTextColor.GREEN));
        return true;
    }

    private boolean resetPity(CommandSender sender, String worldName) {
        World world = worldName != null ? Bukkit.getWorld(worldName) : sender instanceof Player player ? player.getWorld() : null;
        if (!isDragonWorld(world)) {
            sender.sendMessage(language.message("resetpity-world-required", NamedTextColor.RED));
            return true;
        }
        var data = world.getPersistentDataContainer();
        int previous = data.getOrDefault(missesKey, PersistentDataType.INTEGER, 0);
        data.set(missesKey, PersistentDataType.INTEGER, 0);
        sender.sendMessage(Component.text(language.text("resetpity-success").replace("{count}", Integer.toString(previous))
            .replace("{world}", world.getName()), NamedTextColor.GREEN));
        return true;
    }

    private boolean reloadSettings(CommandSender sender) {
        try {
            saveDefaultConfig();
            new YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
            reloadConfig();
            var updatedLanguage = new Language(this, true);
            var updatedSettings = Settings.load(getConfig());
            skills.updateSettings(updatedSettings, updatedLanguage);
            settings = updatedSettings;
            language = updatedLanguage;
            sender.sendMessage(language.message("reload-success", NamedTextColor.GREEN));
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            getLogger().log(Level.WARNING, "Could not reload ALSuperDragon configuration", exception);
            sender.sendMessage(language.message("reload-failed", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public void onDisable() {
        if (skills != null) {
            skills.retain(Set.of());
            skills.clearPodiumCrystals();
        }
    }

    private void tick() {
        Set<UUID> active = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle == null) continue;
            EnderDragon dragon = battle.getEnderDragon();
            if (dragon == null || DragonRushCast.isDecoy(dragon)) {
                if (battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE) {
                    PendingSummon pending = pendingSummons.remove(world.getUID());
                    if (pending != null) {
                        pending.crystals().forEach(EnderCrystal::remove);
                        if (pending.requester().isOnline()) {
                            pending.requester().sendMessage(language.message("summon-interrupted", NamedTextColor.RED));
                        }
                    }
                }
                continue;
            }
            boolean alive = !dragon.isDead() && dragon.getHealth() > 0 && dragon.getPhase() != EnderDragon.Phase.DYING;
            boolean enhanced = battle.hasBeenPreviouslyKilled() || settings.includeFirst();
            if (alive && enhanced) initialize(dragon);
            updateHealthTitle(dragon);
            if (!alive || !enhanced) continue;
            active.add(dragon.getUniqueId());
            skills.tick(dragon, battle);
        }
        skills.retain(active);
        skills.tickPodiumCrystals(active);
    }

    private void updateHealthTitle(EnderDragon dragon) {
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        int current = (int) Math.ceil(dragon.getHealth());
        int maximum = (int) Math.ceil(health.getValue());
        Component title = Component.text(language.text("boss-bar").replace("{current}", Integer.toString(current))
            .replace("{max}", Integer.toString(maximum)));
        if (!title.equals(dragon.customName())) dragon.customName(title);
    }

    private void initialize(EnderDragon dragon) {
        var data = dragon.getPersistentDataContainer();
        if (settings.idleCritical().enabled() && !data.has(lastDamageKey, PersistentDataType.LONG)) {
            data.set(lastDamageKey, PersistentDataType.LONG, System.currentTimeMillis());
        }
        if (data.has(healthKey, PersistentDataType.DOUBLE)) return;
        PendingSummon pending = pendingSummons.remove(dragon.getWorld().getUID());
        Double forced = pending == null ? null : pending.multiplier();
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        double actualMultiplier = 1;
        if (health != null) {
            double originalMax = health.getValue();
            double fraction = dragon.getHealth() / originalMax;
            health.setBaseValue(Math.clamp(health.getBaseValue() * (forced == null ? settings.health().roll() : forced), 1, 1024));
            actualMultiplier = health.getValue() / originalMax;
            dragon.setHealth(Math.min(health.getValue(), health.getValue() * fraction));
        }
        data.set(healthKey, PersistentDataType.DOUBLE, actualMultiplier);
        data.set(damageKey, PersistentDataType.DOUBLE, forced == null ? settings.damage().roll() : forced / 2);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        EnderDragon dragon = dragonSource(event.getDamageSource().getCausingEntity());
        if (dragon == null) dragon = dragonSource(event.getDamager());
        if (dragon == null) return;
        if (DragonRushCast.isDecoy(dragon) || (event.getEntity() instanceof Enderman
            && (event.getDamager() instanceof EnderDragon || event.getDamager() instanceof ComplexEntityPart)
            && skills.isUsingSkyBite(dragon))) {
            event.setCancelled(true);
            return;
        }
        double multiplier = multiplier(dragon, damageKey);
        if (multiplier != 1) event.setDamage(event.getDamage() * multiplier);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonIncomingDamage(EntityDamageEvent event) {
        EnderDragon dragon = enhancedDragonTarget(event.getEntity());
        if (dragon == null) return;
        if (event instanceof EntityDamageByEntityEvent attack) applyIdleCritical(attack, dragon);
        double percent = settings.maxDamagePerHitPercent();
        if (percent <= 0) return;
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        double limit = health.getValue() * (percent / 100);
        if (event.getDamage() > limit) event.setDamage(limit);
    }

    private void applyIdleCritical(EntityDamageByEntityEvent event, EnderDragon dragon) {
        var critical = settings.idleCritical();
        if (!critical.enabled() || event.getFinalDamage() <= 0) return;
        Entity attacker = event.getDamageSource().getCausingEntity();
        Entity damager = event.getDamager();
        if (!(attacker instanceof Player) && !(damager instanceof Player)
            && !(damager instanceof Projectile projectile && projectile.getShooter() instanceof Player)) return;
        Long lastDamage = dragon.getPersistentDataContainer().get(lastDamageKey, PersistentDataType.LONG);
        if (lastDamage != null && System.currentTimeMillis() - lastDamage >= critical.idleMillis()) {
            event.setDamage(event.getDamage() * critical.multiplier());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDamaged(EntityDamageEvent event) {
        if (!settings.idleCritical().enabled() || event.getFinalDamage() <= 0) return;
        EnderDragon dragon = enhancedDragonTarget(event.getEntity());
        if (dragon != null) dragon.getPersistentDataContainer().set(lastDamageKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    private EnderDragon enhancedDragonTarget(Entity target) {
        EnderDragon dragon = target instanceof EnderDragon main ? main
            : target instanceof ComplexEntityPart part && part.getParent() instanceof EnderDragon parent ? parent : null;
        if (dragon == null || DragonRushCast.isDecoy(dragon) || dragon.isDead() || dragon.isInvulnerable()
            || dragon.getPhase() == EnderDragon.Phase.DYING || dragon.getWorld().getEnvironment() != World.Environment.THE_END) return null;
        DragonBattle battle = dragon.getWorld().getEnderDragonBattle();
        return battle != null && dragon.equals(battle.getEnderDragon()) && (settings.includeFirst() || battle.hasBeenPreviouslyKilled())
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
        Bukkit.getScheduler().runTask(this, () -> {
            if (!dragon.isValid() || dragon.isDead()) return;
            DragonBattle battle = dragon.getWorld().getEnderDragonBattle();
            if (battle == null || !dragon.equals(battle.getEnderDragon())) return;
            if (battle.hasBeenPreviouslyKilled() || settings.includeFirst()) initialize(dragon);
            Location center = battle.getEndPortalLocation();
            if (center == null) center = dragon.getPodium();
            if (center == null) center = dragon.getLocation();
            String health = BigDecimal.valueOf(multiplier(dragon, healthKey))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            showTitle(center, language.message("spawn-title", NamedTextColor.LIGHT_PURPLE),
                Component.text(language.text("spawn-subtitle").replace("{multiplier}", health), NamedTextColor.WHITE));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || dragon.isSilent()) return;
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
            double radius = settings.tuning().soundRadius();
            if (radius == 0) return;
            for (var player : origin.getNearbyPlayers(radius)) {
                if (player.getLocation().distanceSquared(origin) <= radius * radius) {
                    player.playSound(origin, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, (float) Math.max(1, (radius + 4) / 16), 1.0f);
                }
            }
        });
    }

    private void showTitle(Location center, Component title, Component subtitle) {
        double radius = settings.tuning().titleRadius();
        int ticks = settings.tuning().titleTicks();
        if (radius == 0 || ticks == 0) return;
        Title message = Title.title(title, subtitle, settings.tuning().titleTimes());
        for (var player : center.getNearbyPlayers(radius)) {
            if (player.getLocation().distanceSquared(center) <= radius * radius) player.showTitle(message);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEggRoll(DragonEggFormEvent event) {
        DragonBattle battle = event.getDragonBattle();
        if (!battle.hasBeenPreviouslyKilled()) return;
        var data = event.getBlock().getWorld().getPersistentDataContainer();
        int misses = data.getOrDefault(missesKey, PersistentDataType.INTEGER, 0);
        EnderDragon dragon = battle.getEnderDragon();
        boolean win = settings.wins(misses, multiplier(dragon, healthKey), multiplier(dragon, damageKey),
            ThreadLocalRandom.current().nextDouble(100));
        event.setCancelled(!win);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEggResult(DragonEggFormEvent event) {
        boolean repeat = event.getDragonBattle().hasBeenPreviouslyKilled();
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!eggDecisions.add(world.getUID())) return;
        boolean approved = !event.isCancelled() && event.getNewState().getType() == Material.DRAGON_EGG;
        Bukkit.getScheduler().runTask(this, () -> {
            eggDecisions.remove(world.getUID());
            boolean generated = approved && block.getType() == Material.DRAGON_EGG;
            showTitle(block.getLocation().toCenterLocation(), language.message("death-title", NamedTextColor.LIGHT_PURPLE),
                language.message(generated ? "egg-dropped" : "egg-missed", generated ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            if (!repeat) return;
            var data = world.getPersistentDataContainer();
            if (generated) {
                data.set(missesKey, PersistentDataType.INTEGER, 0);
            } else {
                int misses = data.getOrDefault(missesKey, PersistentDataType.INTEGER, 0);
                if (misses < Integer.MAX_VALUE) data.set(missesKey, PersistentDataType.INTEGER, misses + 1);
            }
        });
    }
}
