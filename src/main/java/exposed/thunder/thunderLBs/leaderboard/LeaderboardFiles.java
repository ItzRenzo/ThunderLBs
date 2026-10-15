package exposed.thunder.thunderLBs.leaderboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LeaderboardFiles {
    public static final int MAX_ID_LENGTH = 64;
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]{1," + MAX_ID_LENGTH + "}");

    private LeaderboardFiles() {
    }

    public static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidId(String id) {
        return VALID_ID.matcher(normalizeId(id)).matches();
    }

    public static File configFile(File folder, String id) {
        return resolveInside(folder, normalizeAndValidate(id) + ".yml");
    }

    public static File timestampedFile(File folder, String id) {
        return resolveInside(folder, normalizeAndValidate(id) + "--" + System.currentTimeMillis() + ".yml");
    }

    public static void atomicSave(YamlConfiguration yaml, File target) throws IOException {
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Path parent = targetPath.getParent();
        if (parent == null) {
            throw new IOException("Cannot determine parent directory for " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getName() + ".", ".tmp");
        try {
            yaml.save(temporary.toFile());
            moveReplacing(temporary, targetPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection existing = parent.getConfigurationSection(key);
        return existing == null ? parent.createSection(key) : existing;
    }

    public static void copy(File source, File target) throws IOException {
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Files.createDirectories(targetPath.getParent());
        Files.copy(source.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    public static void move(File source, File target) throws IOException {
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Files.createDirectories(targetPath.getParent());
        moveReplacing(source.toPath(), targetPath);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeAndValidate(String id) {
        String normalized = normalizeId(id);
        if (!VALID_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Leaderboard IDs may only contain a-z, 0-9, _ and - (maximum "
                    + MAX_ID_LENGTH + " characters).");
        }
        return normalized;
    }

    private static File resolveInside(File folder, String fileName) {
        Path root = folder.toPath().toAbsolutePath().normalize();
        Path candidate = root.resolve(fileName).normalize();
        if (!candidate.getParent().equals(root)) {
            throw new IllegalArgumentException("Leaderboard file must remain inside " + folder);
        }
        return candidate.toFile();
    }
}
