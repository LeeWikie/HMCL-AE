/*
 * Hello Minecraft! Launcher - Agent Experience
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.ai.tools;

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.io.SNBTCodec;
import org.glavo.nbt.tag.ByteTag;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.DoubleTag;
import org.glavo.nbt.tag.FloatTag;
import org.glavo.nbt.tag.IntTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.LongTag;
import org.glavo.nbt.tag.ParentTag;
import org.glavo.nbt.tag.ShortTag;
import org.glavo.nbt.tag.StringTag;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.tag.TagType;
import org.glavo.nbt.tag.ValueTag;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/// Shared helper for the NBT save-editing tool suite (read_nbt / get_nbt / set_nbt /
/// compute_offline_uuid / copy_player_data / transfer_inventory / read_world_info).
///
/// Centralises the safety-critical plumbing so every write tool behaves identically:
///   - strict path resolution that keeps every accessed file inside the target
///     instance's `saves` directory (rejects absolute paths and `..` escapes),
///   - gzip detection by magic bytes (`1f 8b`) so the file's compression is preserved
///     on write-back (standard `.dat` files are gzip; `servers.dat` is uncompressed),
///   - a mandatory timestamped backup (`<file>.bak-<yyyyMMdd-HHmmss>`) taken before any
///     mutation,
///   - atomic write-back via [`FileUtils#saveSafely`], executed while HOLDING the world's
///     `session.lock` (see [#backupAndWriteLocked]) so a game launched mid-write cannot race
///     the mutation, plus a post-write re-read validation ([#postWriteValidation]),
///   - SNBT (de)serialisation and offline-UUID computation.
///
/// This class is intentionally side-effect free except for the explicit `backup`/`write`
/// methods. It is stateless and therefore thread-safe.
@NotNullByDefault
final class NbtToolSupport {

    private NbtToolSupport() {
    }

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // ---------------------------------------------------------------------
    // Instance / profile resolution
    // ---------------------------------------------------------------------

    /// Returns the currently selected profile, throwing a friendly message if none.
    static Profile requireProfile() throws NbtToolException {
        try {
            return Profiles.getSelectedProfile();
        } catch (Throwable e) {
            throw new NbtToolException("No profile is currently selected: " + e.getMessage());
        }
    }

    /// Resolves the instance id from the `instance` parameter, falling back to the
    /// currently selected instance, and verifies it exists in the profile.
    static String requireInstance(Profile profile, Map<String, Object> parameters) throws NbtToolException {
        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                // T5: reuse the shared candidate list so the NBT domain fails identically to the
                // rest of the instance tools (unified envelope), not with a bare sentence.
                throw new NbtToolException(ToolFailures.failureEnvelope(
                        "No instance is selected and no 'instance' parameter was given",
                        ToolFailures.Retryable.YES,
                        "the tool needs to know which instance to act on",
                        "available instances: " + InstanceToolSupport.availableInstanceNames(profile.getRepository())
                                + ". Call instance(action=\"list\"), then retry with instance=<name>"));
            }
            instance = selected;
        }
        try {
            if (!profile.getRepository().hasVersion(instance)) {
                // T5: same unified "instance not found" envelope (candidate list included) that
                // InstanceToolSupport.resolveInstance produces for the non-NBT tools.
                throw new NbtToolException(ToolFailures.failureEnvelope(
                        "Instance '" + instance + "' does not exist in the selected profile",
                        ToolFailures.Retryable.YES,
                        "the name is wrong, not a missing instance — use the exact id",
                        "available instances: " + InstanceToolSupport.availableInstanceNames(profile.getRepository())
                                + ". Use instance(action=\"list\") to refresh, or omit 'instance' for the "
                                + "currently selected one"));
            }
        } catch (NbtToolException e) {
            throw e;
        } catch (Throwable e) {
            throw new NbtToolException("Failed to verify instance '" + instance + "': " + e.getMessage());
        }
        return instance;
    }

    /// Returns the `saves` directory of the given instance (absolute, normalised).
    static Path savesDir(Profile profile, String instance) throws NbtToolException {
        try {
            HMCLGameRepository repository = profile.getRepository();
            return repository.getRunDirectory(instance).resolve("saves").toAbsolutePath().normalize();
        } catch (Throwable e) {
            throw new NbtToolException("Failed to resolve the saves directory of '" + instance + "': " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Path safety
    // ---------------------------------------------------------------------

    /// Resolves a user-supplied relative path against the instance `saves` directory and
    /// guarantees the result stays inside it. Absolute paths and `..` escapes are rejected.
    static Path resolveInSaves(Path savesDir, String relative) throws NbtToolException {
        if (relative == null || relative.trim().isEmpty()) {
            throw new NbtToolException("Empty path.");
        }
        String cleaned = relative.trim().replace('\\', '/');
        Path candidate;
        try {
            candidate = Path.of(cleaned);
        } catch (Throwable e) {
            throw new NbtToolException("Invalid path '" + relative + "': " + e.getMessage());
        }
        if (candidate.isAbsolute()) {
            throw new NbtToolException("Absolute paths are not allowed; give a path relative to the instance saves/ directory.");
        }
        Path resolved = savesDir.resolve(candidate).normalize();
        if (!resolved.startsWith(savesDir)) {
            throw new NbtToolException("Path escapes the instance saves/ directory ('..' is not allowed): " + relative);
        }
        return resolved;
    }

    /// Resolves a world folder (single path segment, no separators) under `saves`.
    static Path resolveWorldDir(Path savesDir, String world) throws NbtToolException {
        if (world == null || world.trim().isEmpty()) {
            throw new NbtToolException("Missing 'world' parameter (the world folder name under saves/).");
        }
        Path dir = resolveInSaves(savesDir, world.trim());
        if (!Files.isDirectory(dir)) {
            // Same unified "world not found" envelope (real saves/ folder names) that
            // WorldToolSupport produces for the non-NBT world tools — mirrors how requireInstance
            // above shares InstanceToolSupport's "instance not found" candidate list.
            throw new NbtToolException(WorldToolSupport.worldNotFoundEnvelope(savesDir, world));
        }
        return dir;
    }

    /// Resolves the NBT file a read/get/set tool should act on: either the `path`
    /// parameter (relative to `saves/`) or the combination of `world` + `file`.
    static Path resolveTargetFile(Path savesDir, Map<String, Object> parameters) throws NbtToolException {
        Object pathObj = parameters.get("path");
        if (pathObj instanceof String && !((String) pathObj).trim().isEmpty()) {
            return resolveInSaves(savesDir, ((String) pathObj).trim());
        }
        Object worldObj = parameters.get("world");
        Object fileObj = parameters.get("file");
        if (worldObj instanceof String && !((String) worldObj).trim().isEmpty()
                && fileObj instanceof String && !((String) fileObj).trim().isEmpty()) {
            String w = ((String) worldObj).trim();
            String f = ((String) fileObj).trim();
            resolveWorldDir(savesDir, w); // validate the world exists
            return resolveInSaves(savesDir, w + "/" + f);
        }
        throw new NbtToolException("Provide either 'path' (relative to saves/) or both 'world' and 'file'.");
    }

    static @Nullable String str(Map<String, Object> parameters, String key) {
        Object v = parameters.get(key);
        return v instanceof String && !((String) v).trim().isEmpty() ? ((String) v).trim() : null;
    }

    // ---------------------------------------------------------------------
    // Read / detect
    // ---------------------------------------------------------------------

    /// Detects gzip compression by inspecting the first two magic bytes (`1f 8b`).
    static boolean isGzip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 0x1f && b1 == 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    /// Reads any NBT file (compression auto-detected by the codec).
    static Tag readTag(Path file) throws NbtToolException {
        if (!Files.isRegularFile(file)) {
            throw new NbtToolException("File not found: " + file);
        }
        try {
            return NBTCodec.of().readTag(file);
        } catch (Throwable e) {
            throw new NbtToolException("Failed to read NBT file '" + file + "': " + e.getMessage());
        }
    }

    /// Reads an NBT file whose root must be a compound.
    static CompoundTag readCompound(Path file) throws NbtToolException {
        Tag tag = readTag(file);
        if (!(tag instanceof CompoundTag)) {
            throw new NbtToolException("Malformed NBT (root is not a compound): " + file);
        }
        return (CompoundTag) tag;
    }

    // ---------------------------------------------------------------------
    // Backup / write
    // ---------------------------------------------------------------------

    /// Copies the target file to a timestamped sibling backup before it is modified.
    /// Returns the backup path, or `null` if the file does not yet exist (nothing to back up).
    static @Nullable Path backup(Path file) throws NbtToolException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Path bak = file.resolveSibling(file.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_STAMP));
        try {
            Files.copy(file, bak, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new NbtToolException("Failed to create backup of '" + file + "': " + e.getMessage());
        }
        return bak;
    }

    /// Outcome of [#backupAndWriteLocked]: the pre-write backup path (`null` when the file did
    /// not exist yet, so there was nothing to back up) and an optional post-write validation
    /// WARNING to append verbatim to the tool's SUCCESS receipt (`null` when the write read
    /// back clean).
    record WriteReceipt(@Nullable Path backup, @Nullable String warning) {
    }

    /// Internal carrier that smuggles an [NbtToolException] across the
    /// [GameResourceGuard.LockedWorldAction] boundary (which only permits [IOException]).
    private static final class WrappedNbtToolException extends IOException {
        WrappedNbtToolException(NbtToolException cause) {
            super(cause);
        }
    }

    /// The whole mutation pipeline of a write tool — `backup()` + `writeTag()` +
    /// [#postWriteValidation] — executed while HOLDING the world's `session.lock`
    /// [java.nio.channels.FileChannel] (via [GameResourceGuard#withWorldLock]), mirroring the
    /// native `WorldManageUIUtils.getSessionLockChannel()` editing-session discipline. This
    /// closes the TOCTOU window of the old check-then-write pattern: a game launched between
    /// the pre-check and the write can no longer acquire the world and race us.
    ///
    /// @param worldDir   the world directory whose `session.lock` guards {@code file}, or
    ///                   `null` when the file is not inside a world (e.g. directly under
    ///                   `saves/`) — the write then proceeds without a lock
    /// @param worldLabel the world name for rejection texts (`null` falls back to the
    ///                   directory name)
    /// @param file       the NBT file to back up and overwrite
    /// @param root       the tag to serialise
    /// @param gzip       whether to gzip-compress (preserve the original compression)
    /// @throws NbtToolException when the world is busy (message is the ready-to-return
    ///                          unified rejection envelope), or when backup/write itself fails
    static WriteReceipt backupAndWriteLocked(@Nullable Path worldDir, @Nullable String worldLabel,
                                             Path file, Tag root, boolean gzip) throws NbtToolException {
        if (worldDir == null) {
            return doBackupAndWrite(file, root, gzip);
        }
        String label = worldLabel != null ? worldLabel : String.valueOf(worldDir.getFileName());
        try {
            return GameResourceGuard.withWorldLock(worldDir, label, () -> {
                try {
                    return doBackupAndWrite(file, root, gzip);
                } catch (NbtToolException e) {
                    throw new WrappedNbtToolException(e);
                }
            });
        } catch (GameResourceGuard.WorldBusyException busy) {
            throw new NbtToolException(busy.getMessage());
        } catch (WrappedNbtToolException wrapped) {
            throw (NbtToolException) wrapped.getCause();
        } catch (IOException e) {
            throw new NbtToolException("Failed to acquire the session lock of world '" + label + "': "
                    + e.getMessage());
        }
    }

    private static WriteReceipt doBackupAndWrite(Path file, Tag root, boolean gzip) throws NbtToolException {
        @Nullable Path backupPath = backup(file);
        writeTag(file, root, gzip);
        return new WriteReceipt(backupPath, postWriteValidation(file, root));
    }

    /// Post-write validation for the NBT write tools (the NBT leg of the same "validate what
    /// you just wrote" contract `PostWriteValidator` applies to .json/.properties): re-reads
    /// the just-written file and checks it parses as NBT with the same root tag type that was
    /// written. Returns a `"WARNING: ..."` text for the SUCCESS receipt — the file HAS been
    /// written, so failing the call would lie about the on-disk state — or `null` when clean.
    static @Nullable String postWriteValidation(Path file, Tag written) {
        Tag reread;
        try {
            reread = readTag(file);
        } catch (NbtToolException e) {
            return "WARNING: post-write validation failed — the file could not be read back as valid NBT ("
                    + e.getMessage() + "). Do not assume the change took effect; the timestamped .bak backup "
                    + "taken before this write is intact — restore it if the file is corrupted.";
        }
        if (reread.getType() != written.getType()) {
            return "WARNING: post-write validation — the file read back with root tag type "
                    + reread.getType().name() + " but " + written.getType().name() + " was written. Verify with "
                    + "read_nbt; restore the pre-write .bak backup if the content is wrong.";
        }
        return null;
    }

    /// Atomically writes a tag back to disk, preserving the original compression format.
    static void writeTag(Path file, Tag tag, boolean gzip) throws NbtToolException {
        try {
            FileUtils.saveSafely(file, os -> {
                if (gzip) {
                    try (OutputStream gos = new GZIPOutputStream(os)) {
                        NBTCodec.of().writeTag(gos, tag);
                    }
                } else {
                    NBTCodec.of().writeTag(os, tag);
                }
            });
        } catch (IOException e) {
            throw new NbtToolException("Failed to write NBT file '" + file + "': " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // SNBT
    // ---------------------------------------------------------------------

    static String toSNBT(Tag tag) {
        return SNBTCodec.of().toString(tag);
    }

    static Tag parseSNBT(String text) throws NbtToolException {
        try {
            return SNBTCodec.of().readTag(text);
        } catch (Throwable e) {
            throw new NbtToolException("Failed to parse SNBT value: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Offline UUID
    // ---------------------------------------------------------------------

    /// Computes the offline-mode UUID for a username, exactly as the vanilla client does.
    /// The username is case-sensitive.
    static UUID computeOfflineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    /// Resolves a "player" argument that may be either a raw UUID or a username (the
    /// latter is converted to its offline UUID).
    static UUID resolvePlayerUuid(String uuidOrName) throws NbtToolException {
        if (uuidOrName == null || uuidOrName.trim().isEmpty()) {
            throw new NbtToolException("Empty player identifier.");
        }
        String s = uuidOrName.trim();
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return computeOfflineUuid(s);
        }
    }

    // ---------------------------------------------------------------------
    // Player data location
    // ---------------------------------------------------------------------

    /// A located player NBT compound together with how to write it back. Player data may
    /// live in `playerdata/<uuid>.dat`, the legacy `players/data/<uuid>.dat`, or — for a
    /// single-player world — inside `level.dat` at `Data.Player`.
    static final class PlayerHandle {
        final CompoundTag player;     // the player compound to read/modify
        final boolean gzip;           // compression of the backing file
        final Path backingFile;       // file to back up and write
        final @Nullable CompoundTag rootToWrite; // tag actually serialised (level root, or player itself)

        PlayerHandle(CompoundTag player, boolean gzip, Path backingFile, @Nullable CompoundTag rootToWrite) {
            this.player = player;
            this.gzip = gzip;
            this.backingFile = backingFile;
            this.rootToWrite = rootToWrite;
        }

        CompoundTag root() {
            return rootToWrite != null ? rootToWrite : player;
        }
    }

    static Path playerDataFile(Path worldDir, UUID uuid) {
        return worldDir.resolve("playerdata").resolve(uuid + ".dat");
    }

    /// Locates a player's NBT, searching `playerdata/`, the legacy `players/data/`, and
    /// finally `level.dat`'s `Data.Player` (single-player owner). Returns null if absent.
    static @Nullable PlayerHandle locatePlayer(Path worldDir, UUID uuid) throws NbtToolException {
        Path p1 = playerDataFile(worldDir, uuid);
        if (Files.isRegularFile(p1)) {
            return new PlayerHandle(readCompound(p1), isGzip(p1), p1, null);
        }
        Path p2 = worldDir.resolve("players").resolve("data").resolve(uuid + ".dat");
        if (Files.isRegularFile(p2)) {
            return new PlayerHandle(readCompound(p2), isGzip(p2), p2, null);
        }
        Path levelDat = worldDir.resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            CompoundTag level = readCompound(levelDat);
            if (level.get("Data") instanceof CompoundTag data && data.get("Player") instanceof CompoundTag player) {
                return new PlayerHandle(player, isGzip(levelDat), levelDat, level);
            }
        }
        return null;
    }

    /// True if the world directory is currently locked (the game is running on it).
    static boolean isWorldLocked(Path worldDir) {
        try {
            return new World(worldDir.toAbsolutePath().normalize()).isLocked();
        } catch (Throwable e) {
            // If we cannot even construct a World, fall back to checking session.lock directly is not
            // possible without a World; treat as not locked so the (already backed-up) write can proceed.
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // NBT path navigation (dotted path with [index] segments)
    // ---------------------------------------------------------------------

    /// One step of a parsed NBT path: either a compound key or a list/array index.
    static final class Step {
        final @Nullable String key;
        final int index;
        final boolean isIndex;

        private Step(@Nullable String key, int index, boolean isIndex) {
            this.key = key;
            this.index = index;
            this.isIndex = isIndex;
        }

        static Step key(String k) {
            return new Step(k, -1, false);
        }

        static Step index(int i) {
            return new Step(null, i, true);
        }

        @Override
        public String toString() {
            return isIndex ? "[" + index + "]" : key;
        }
    }

    /// Parses a path such as `Data.Player.Inventory[0].id` into ordered steps.
    static List<Step> parsePath(String path) throws NbtToolException {
        if (path == null || path.trim().isEmpty()) {
            throw new NbtToolException("Empty nbtPath.");
        }
        List<Step> steps = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        int i = 0;
        String s = path.trim();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.') {
                flushName(name, steps);
                i++;
            } else if (c == '[') {
                flushName(name, steps);
                int close = s.indexOf(']', i);
                if (close < 0) {
                    throw new NbtToolException("Unclosed '[' in nbtPath: " + path);
                }
                String num = s.substring(i + 1, close).trim();
                try {
                    steps.add(Step.index(Integer.parseInt(num)));
                } catch (NumberFormatException e) {
                    throw new NbtToolException("Invalid list index '" + num + "' in nbtPath: " + path);
                }
                i = close + 1;
            } else {
                name.append(c);
                i++;
            }
        }
        flushName(name, steps);
        if (steps.isEmpty()) {
            throw new NbtToolException("nbtPath resolves to no steps: " + path);
        }
        return steps;
    }

    private static void flushName(StringBuilder name, List<Step> steps) {
        if (name.length() > 0) {
            steps.add(Step.key(name.toString().trim()));
            name.setLength(0);
        }
    }

    /// Navigates from a root tag along the given steps. Returns null if any step is missing.
    static @Nullable Tag navigate(Tag root, List<Step> steps) {
        Tag current = root;
        for (Step step : steps) {
            if (current == null) {
                return null;
            }
            if (step.isIndex) {
                if (!(current instanceof ParentTag<?> parent)) {
                    return null;
                }
                if (step.index < 0 || step.index >= parent.size()) {
                    return null;
                }
                current = parent.getTag(step.index);
            } else {
                if (!(current instanceof CompoundTag compound)) {
                    return null;
                }
                current = compound.get(step.key);
            }
        }
        return current;
    }

    /// Navigates as far along {@code steps} as possible and returns the DEEPEST tag actually
    /// reached — the last resolvable node before navigation fell off. Unlike {@link #navigate},
    /// it never returns null for a partial path: it stops at the last node it could descend into,
    /// so [#describeChildren] can enumerate that node's keys/indices for the model (T10). For a
    /// fully-valid path it returns the same node as {@link #navigate}.
    static Tag navigateBestEffort(Tag root, List<Step> steps) {
        Tag current = root;
        for (Step step : steps) {
            Tag next;
            if (step.isIndex) {
                if (!(current instanceof ParentTag<?> parent) || step.index < 0 || step.index >= parent.size()) {
                    return current;
                }
                next = parent.getTag(step.index);
            } else {
                if (!(current instanceof CompoundTag compound)) {
                    return current;
                }
                next = compound.get(step.key);
            }
            if (next == null) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /// One-line, model-facing summary of a tag's immediate children — the data a caller hands the
    /// model when an NBT path misses (T10): the sibling keys of the deepest node it could reach,
    /// e.g. `"CompoundTag keys: XpLevel, XpTotal, foodLevel"` or `"ListTag length: 27 (valid
    /// indices 0..26)"`. A scalar/leaf node reports that it has nothing to descend into. The key
    /// list is capped so a huge compound can't flood the error text.
    static String describeChildren(@Nullable Tag tag) {
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>();
            for (Tag child : compound) {
                keys.add(child.getName());
            }
            if (keys.isEmpty()) {
                return "the deepest resolvable node is an empty CompoundTag (no keys)";
            }
            int shown = Math.min(keys.size(), 40);
            String joined = String.join(", ", keys.subList(0, shown));
            if (keys.size() > shown) {
                joined += ", … (" + keys.size() + " keys total)";
            }
            return "CompoundTag keys: " + joined;
        }
        if (tag instanceof ListTag<?> list) {
            return "ListTag length: " + list.size()
                    + (list.size() > 0 ? " (valid indices 0.." + (list.size() - 1) + ")" : " (empty)");
        }
        if (tag instanceof ParentTag<?> parent) {
            return parent.getType().name() + " length: " + parent.size()
                    + (parent.size() > 0 ? " (valid indices 0.." + (parent.size() - 1) + ")" : " (empty)");
        }
        if (tag instanceof ValueTag<?>) {
            return "the deepest resolvable node is a scalar " + tag.getType().name()
                    + " (it has no child keys/indices to descend into)";
        }
        return "the deepest resolvable node has no enumerable children";
    }

    /// Mutates a scalar value tag in place, parsing the raw text into the tag's own type.
    /// Booleans `true`/`false` are accepted for byte tags. Returns a human description of
    /// the new value.
    static String setScalar(ValueTag<?> tag, String raw) throws NbtToolException {
        try {
            if (tag instanceof ByteTag t) {
                byte v = "true".equalsIgnoreCase(raw) ? (byte) 1 : "false".equalsIgnoreCase(raw) ? (byte) 0 : Byte.parseByte(raw.trim());
                t.setValue(v);
            } else if (tag instanceof ShortTag t) {
                t.setValue(Short.parseShort(raw.trim()));
            } else if (tag instanceof IntTag t) {
                t.setValue(Integer.parseInt(raw.trim()));
            } else if (tag instanceof LongTag t) {
                t.setValue(Long.parseLong(raw.trim()));
            } else if (tag instanceof FloatTag t) {
                t.setValue(Float.parseFloat(raw.trim()));
            } else if (tag instanceof DoubleTag t) {
                t.setValue(Double.parseDouble(raw.trim()));
            } else if (tag instanceof StringTag t) {
                t.setValue(raw);
            } else {
                throw new NbtToolException("Unsupported scalar tag type: " + tag.getType().name());
            }
        } catch (NumberFormatException e) {
            throw new NbtToolException("Value '" + raw + "' is not a valid " + tag.getType().name() + ".");
        }
        return tag.getType().name() + " = " + tag.getAsString();
    }

    // ---------------------------------------------------------------------
    // Pretty printing
    // ---------------------------------------------------------------------

    /// Renders a tag as an indented tree, truncated at the given depth (negative = unlimited).
    static String renderTree(Tag tag, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        renderTree(sb, null, tag, 0, maxDepth);
        return sb.toString();
    }

    private static void renderTree(StringBuilder sb, @Nullable String name, Tag tag, int depth, int maxDepth) {
        sb.append("  ".repeat(depth));
        if (name != null) {
            sb.append(name).append(": ");
        }
        TagType<?> type = tag.getType();
        if (tag instanceof CompoundTag compound) {
            sb.append("{compound, ").append(compound.size()).append(" entries}");
            if (maxDepth >= 0 && depth >= maxDepth) {
                sb.append(" …");
                sb.append('\n');
                return;
            }
            sb.append('\n');
            for (Tag child : compound) {
                renderTree(sb, child.getName(), child, depth + 1, maxDepth);
            }
        } else if (tag instanceof ListTag<?> list) {
            sb.append("[list of ").append(list.getElementType().name()).append(", ").append(list.size()).append(" items]");
            if (maxDepth >= 0 && depth >= maxDepth) {
                sb.append(" …");
                sb.append('\n');
                return;
            }
            sb.append('\n');
            int idx = 0;
            for (Tag child : list) {
                renderTree(sb, "[" + (idx++) + "]", child, depth + 1, maxDepth);
            }
        } else if (tag instanceof ValueTag<?> value) {
            sb.append("(").append(type.name()).append(") ").append(value.getAsString()).append('\n');
        } else {
            // arrays etc.
            sb.append("(").append(type.name()).append(") ").append(tag).append('\n');
        }
    }

    // ---------------------------------------------------------------------
    // Exception
    // ---------------------------------------------------------------------

    /// Lightweight checked exception carrying a user-facing message; tools translate it
    /// into [`org.jackhuang.hmcl.ai.tools.ToolResult#failure`].
    static final class NbtToolException extends Exception {
        NbtToolException(String message) {
            super(message);
        }
    }
}
