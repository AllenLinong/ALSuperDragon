package al.dragon;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

final class RewardLedger {
    enum Kind { MONEY, DICE }

    record Limits(int money, int dice, int smallPartyMoneyCap) {
        static Limits load(ConfigurationSection config) {
            return new Limits(read(config, "money-max-times", 5), read(config, "dice-max-times", 3),
                read(config, "small-party-money-cap", 300));
        }

        private static int read(ConfigurationSection config, String key, int fallback) {
            Object value = config.get("limits." + key, fallback);
            if (value instanceof Integer limit && limit >= -1) return limit;
            throw new IllegalArgumentException("rewards.yml: limits." + key + " must be an integer >= -1");
        }

        int forKind(Kind kind) {
            return kind == Kind.MONEY ? money : dice;
        }
    }

    record Key(UUID player, Kind kind) {}
    record Snapshot(long revision, Map<Key, Integer> counts) {}

    private final Path file;
    private final Map<Key, Integer> counts = new HashMap<>();
    private final Map<Key, Integer> pending = new HashMap<>();
    private long revision;
    private volatile long savedRevision;

    RewardLedger(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            int lineNumber = 0;
            for (String line; (line = reader.readLine()) != null;) {
                lineNumber++;
                String[] columns = line.split("\t", -1);
                try {
                    if (columns.length != 3) throw new IllegalArgumentException();
                    var key = new Key(UUID.fromString(columns[0]), Kind.valueOf(columns[1]));
                    int count = Integer.parseInt(columns[2]);
                    if (count <= 0 || counts.putIfAbsent(key, count) != null) throw new IllegalArgumentException();
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid reward counter at " + file + ":" + lineNumber, exception);
                }
            }
        }
    }

    boolean available(Key key, Limits limits) {
        int limit = limits.forKind(key.kind());
        return limit < 0 || (long) counts.getOrDefault(key, 0) + pending.getOrDefault(key, 0) < limit;
    }

    boolean reserve(Key key, Limits limits) {
        if (!available(key, limits)) return false;
        pending.merge(key, 1, Integer::sum);
        return true;
    }

    boolean finish(Key key, boolean accepted) {
        Integer reserved = pending.get(key);
        if (reserved == null) return false;
        if (reserved == 1) pending.remove(key);
        else pending.put(key, reserved - 1);
        if (!accepted) return false;
        int previous = counts.getOrDefault(key, 0);
        if (previous == Integer.MAX_VALUE) return false;
        counts.put(key, previous + 1);
        revision++;
        return true;
    }

    Snapshot snapshot() {
        return revision > savedRevision ? new Snapshot(revision, Map.copyOf(counts)) : null;
    }

    synchronized void save(Snapshot snapshot) throws IOException {
        if (snapshot.revision() <= savedRevision) return;
        Path temp = Files.createTempFile(file.getParent(), "reward-counts-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp)) {
                for (var entry : snapshot.counts().entrySet()) {
                    Key key = entry.getKey();
                    writer.write(key.player() + "\t" + key.kind() + "\t" + entry.getValue());
                    writer.newLine();
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            savedRevision = snapshot.revision();
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
