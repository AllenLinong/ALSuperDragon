package al.dragon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

final class DragonRewards {
    record Policy(int total, String moneyCommand, boolean dice, List<String> prizeCommands) {
        static Policy load(ConfigurationSection config, String file) {
            Object raw = config.get("rewards.money.total", 0);
            if (!(raw instanceof Integer total) || total < 0) {
                throw new IllegalArgumentException(file + ": rewards.money.total must be a non-negative integer");
            }
            String money = config.getString("rewards.money.command", "").strip();
            if (total > 0 && (!money.contains("{amount}") || !money.contains("{player}") && !money.contains("{uuid}"))) {
                throw new IllegalArgumentException(file + ": money command needs {amount} and {player} or {uuid}");
            }
            List<String> prizes = config.getStringList("rewards.dice.commands").stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
            boolean dice = config.getBoolean("rewards.dice.enabled", false);
            if (dice && (prizes.isEmpty() || prizes.stream().noneMatch(command -> command.contains("{player}") || command.contains("{uuid}")))) {
                throw new IllegalArgumentException(file + ": configure a dice prize command with {player} or {uuid}");
            }
            return new Policy(total, money, dice, prizes);
        }
    }

    record Hit(UUID id, String name, BigDecimal damage) {}
    private record Fraction(int index, BigDecimal remainder) {}

    private final Map<UUID, Hit> hits = new HashMap<>();

    void clearHits() {
        hits.clear();
    }

    void record(Player player, double damage) {
        if (!Double.isFinite(damage) || damage <= 0) return;
        BigDecimal credited = BigDecimal.valueOf(damage);
        hits.compute(player.getUniqueId(), (id, previous) -> new Hit(id, player.getName(),
            previous == null ? credited : previous.damage().add(credited)));
    }

    void distribute(ALDragon plugin, Language language, DragonProfiles.Profile profile) {
        if (hits.isEmpty()) return;
        List<Hit> ranking = hits.values().stream().sorted(Comparator.comparing(Hit::damage).reversed().thenComparing(Hit::id)).toList();
        clearHits();
        List<BigDecimal> damage = ranking.stream().map(Hit::damage).toList();
        BigDecimal sum = damage.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Policy policy = profile.rewards();
        int[] amounts = split(policy.total(), damage, sum);
        List<Hit> eligible = new ArrayList<>();
        for (int index = 0; index < ranking.size(); index++) {
            Hit hit = ranking.get(index);
            Player player = Bukkit.getPlayer(hit.id());
            if (policy.dice() && player != null && qualifies(damage.get(index), sum)
                && plugin.rewardAvailable(new RewardLedger.Key(hit.id(), RewardLedger.Kind.DICE))) eligible.add(hit);
            int amount = amounts[index];
            if (amount == 0) continue;
            var moneyKey = new RewardLedger.Key(hit.id(), RewardLedger.Kind.MONEY);
            if (!plugin.reserveReward(moneyKey)) continue;
            int rank = index + 1;
            plugin.enqueueReward(() -> {
                boolean accepted = dispatch(plugin, policy.moneyCommand(), hit, profile.id(), amount, 0);
                plugin.finishReward(moneyKey, accepted);
                if (!accepted) return;
                Player recipient = Bukkit.getPlayer(hit.id());
                if (recipient != null) recipient.sendMessage(Component.text(language.text("money-reward")
                    .replace("{rank}", Integer.toString(rank)).replace("{amount}", Integer.toString(amount)), NamedTextColor.GREEN));
            });
        }
        if (eligible.isEmpty()) return;
        List<Hit> tied = eligible;
        int highest;
        do {
            highest = 0;
            List<Hit> round = new ArrayList<>();
            for (Hit hit : tied) {
                int roll = ThreadLocalRandom.current().nextInt(1, 101);
                if (roll > highest) {
                    highest = roll;
                    round.clear();
                }
                if (roll == highest) round.add(hit);
                Player player = Bukkit.getPlayer(hit.id());
                if (player != null) player.sendMessage(Component.text(language.text("dice-roll")
                    .replace("{roll}", Integer.toString(roll)), NamedTextColor.LIGHT_PURPLE));
            }
            tied = round;
        } while (tied.size() > 1);
        Hit winner = tied.getFirst();
        var diceKey = new RewardLedger.Key(winner.id(), RewardLedger.Kind.DICE);
        if (!plugin.reserveReward(diceKey)) return;
        int winningRoll = highest;
        var result = new PrizeResult(policy.prizeCommands().size());
        for (String command : policy.prizeCommands()) plugin.enqueueReward(() -> {
            boolean accepted = dispatch(plugin, command, winner, profile.id(), 0, winningRoll);
            if (accepted && !result.counted) {
                result.counted = true;
                plugin.finishReward(diceKey, true);
            }
            result.success &= accepted;
            if (--result.remaining != 0) return;
            if (!result.counted) plugin.finishReward(diceKey, false);
            if (result.success) announceWinner(language, ranking, winner, winningRoll);
        });
    }

    private static void announceWinner(Language language, List<Hit> ranking, Hit winner, int roll) {
        Component message = Component.text(language.text("dice-winner").replace("{player}", winner.name())
            .replace("{roll}", Integer.toString(roll)), NamedTextColor.GOLD);
        for (Hit hit : ranking) {
            Player player = Bukkit.getPlayer(hit.id());
            if (player != null) player.sendMessage(message);
        }
    }

    private static final class PrizeResult {
        private int remaining;
        private boolean success = true;
        private boolean counted;

        private PrizeResult(int remaining) {
            this.remaining = remaining;
        }
    }

    static boolean qualifies(BigDecimal damage, BigDecimal total) {
        return damage.movePointRight(1).compareTo(total) > 0;
    }

    static int[] split(int pool, List<BigDecimal> damage, BigDecimal total) {
        int[] amounts = new int[damage.size()];
        if (pool == 0 || total.signum() == 0) return amounts;
        int remaining = pool;
        List<Fraction> fractions = new ArrayList<>(damage.size());
        for (int index = 0; index < damage.size(); index++) {
            BigDecimal[] share = damage.get(index).multiply(BigDecimal.valueOf(pool)).divideAndRemainder(total);
            amounts[index] = share[0].intValueExact();
            remaining -= amounts[index];
            fractions.add(new Fraction(index, share[1]));
        }
        fractions.sort(Comparator.comparing(Fraction::remainder).reversed().thenComparingInt(Fraction::index));
        for (int index = 0; index < remaining; index++) amounts[fractions.get(index).index()]++;
        return amounts;
    }

    private static boolean dispatch(ALDragon plugin, String template, Hit hit, String profile, int amount, int roll) {
        String command = template.replace("{player}", hit.name()).replace("{uuid}", hit.id().toString())
            .replace("{dragon}", profile).replace("{amount}", Integer.toString(amount)).replace("{roll}", Integer.toString(roll));
        try {
            if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command)) return true;
            plugin.getLogger().warning("Could not dispatch dragon reward command: " + command);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed dragon reward command: " + command, exception);
        }
        return false;
    }
}
