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

import javafx.beans.property.ObjectProperty;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectories;
import org.jackhuang.hmcl.setting.GameDirectoryID;
import org.jackhuang.hmcl.setting.GameSettingsPresets;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.SettingFileAccess;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.PortablePath;
import org.jackhuang.hmcl.util.i18n.LocalizedText;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/// Test-only fixture that stands up a real, throwaway [Profiles] singleton backed by a single
/// local [Profile] pointing at a fresh temp directory on disk — so AI-tool tests can exercise
/// `Profiles.getSelectedProfile()` / `Profiles.getSelectedInstance()` and real
/// [HMCLGameRepository] file operations (the same static entry points the tools under test call)
/// without a full HMCL application bootstrap.
///
/// This mirrors the reflection-based static-state-swap technique used by
/// `org.jackhuang.hmcl.setting.GameDirectoriesTest`'s private `ProfileEnvironment` helper, just
/// generalized for use from a different package: every target field is private or
/// package-private to `org.jackhuang.hmcl.setting`, so `setAccessible` is required regardless.
///
/// Usage:
/// ```
/// try (ProfileFixture fx = new ProfileFixture()) {
///     fx.createInstance("MyInstance");
///     // Profiles.getSelectedProfile() / getSelectedInstance() now resolve to this fixture.
/// }
/// ```
final class ProfileFixture implements AutoCloseable {

    private final Path tempDir;
    private final Profile profile;

    private final Field localGameDirectoriesField;
    private final Field userGameDirectoriesField;
    private final Field launcherSettingsField;
    private final Field gameSettingsPresetsField;
    private final Field localGameDirectoriesAccessField;
    private final Field userGameDirectoriesAccessField;
    private final Field initializedField;

    private final ObjectProperty<Profile> selectedProfileProp;
    private final ObservableList<Profile> mergedProfiles;

    private final Object previousLocalGameDirectories;
    private final Object previousUserGameDirectories;
    private final Object previousLauncherSettings;
    private final Object previousGameSettingsPresets;
    private final SettingFileAccess previousLocalAccess;
    private final SettingFileAccess previousUserAccess;
    private final boolean previousInitialized;
    private final Profile previousSelectedProfile;
    private final List<Profile> previousMergedProfiles;

    ProfileFixture() throws IOException, ReflectiveOperationException {
        // Profiles.init() unconditionally subscribes a RefreshedVersionsEvent listener that calls
        // FXUtils.runInFX(...) — which calls Platform.runLater(...) whenever it isn't already
        // running ON the FX Application Thread. That fires synchronously the moment ANY caller
        // (e.g. createInstance()'s repository().refreshVersions()) triggers a version refresh, so
        // without the toolkit started first, Platform.runLater throws "Toolkit not initialized"
        // even for tests that otherwise have nothing to do with JavaFX. JavaFXLauncher's static
        // initializer starts it exactly once and tolerates an already-started/unavailable toolkit,
        // so triggering it here makes every ProfileFixture-backed test safe regardless of whether
        // that particular test itself needs JavaFX.
        org.jackhuang.hmcl.JavaFXLauncher.start();

        tempDir = Files.createTempDirectory("hmcl-ai-tool-test-");

        GameDirectoryID id = GameDirectoryID.generate();
        profile = new Profile(id, LocalizedText.plain("Test"), PortablePath.fromPath(tempDir));

        GameDirectories localDirectories = new GameDirectories();
        localDirectories.getGameDirectories().add(profile);
        setUserFile(localDirectories, false);
        GameDirectories userDirectories = new GameDirectories();
        setUserFile(userDirectories, true);

        localGameDirectoriesField = field(SettingsManager.class, "localGameDirectories");
        userGameDirectoriesField = field(SettingsManager.class, "userGameDirectories");
        launcherSettingsField = field(SettingsManager.class, "launcherSettings");
        gameSettingsPresetsField = field(SettingsManager.class, "gameSettingsPresets");
        localGameDirectoriesAccessField = field(SettingsManager.class, "localGameDirectoriesAccess");
        userGameDirectoriesAccessField = field(SettingsManager.class, "userGameDirectoriesAccess");
        initializedField = field(Profiles.class, "initialized");
        Field selectedProfileField = field(Profiles.class, "selectedProfile");
        Field mergedProfilesField = field(Profiles.class, "mergedProfiles");

        previousLocalGameDirectories = localGameDirectoriesField.get(null);
        previousUserGameDirectories = userGameDirectoriesField.get(null);
        previousLauncherSettings = launcherSettingsField.get(null);
        previousGameSettingsPresets = gameSettingsPresetsField.get(null);
        previousLocalAccess = (SettingFileAccess) localGameDirectoriesAccessField.get(null);
        previousUserAccess = (SettingFileAccess) userGameDirectoriesAccessField.get(null);
        previousInitialized = initializedField.getBoolean(null);

        @SuppressWarnings("unchecked")
        ObjectProperty<Profile> selectedProfileProp = (ObjectProperty<Profile>) selectedProfileField.get(null);
        this.selectedProfileProp = selectedProfileProp;
        previousSelectedProfile = selectedProfileProp.get();

        @SuppressWarnings("unchecked")
        ObservableList<Profile> mergedProfiles = (ObservableList<Profile>) mergedProfilesField.get(null);
        this.mergedProfiles = mergedProfiles;
        previousMergedProfiles = List.copyOf(mergedProfiles);

        localGameDirectoriesField.set(null, localDirectories);
        userGameDirectoriesField.set(null, userDirectories);
        launcherSettingsField.set(null, new LauncherSettings());
        gameSettingsPresetsField.set(null, new GameSettingsPresets());
        localGameDirectoriesAccessField.set(null, SettingFileAccess.READ_WRITE);
        userGameDirectoriesAccessField.set(null, SettingFileAccess.READ_WRITE);
        initializedField.setBoolean(null, false);
        mergedProfiles.clear();

        Profiles.init();
    }

    /// The single profile this fixture selected.
    Profile profile() {
        return profile;
    }

    /// The profile's game repository (same instance `Profiles.getSelectedProfile().getRepository()`
    /// would return while this fixture is active).
    HMCLGameRepository repository() {
        return profile.getRepository();
    }

    /// The profile's base (game) directory — a fresh temp directory owned by this fixture.
    Path baseDir() {
        return tempDir;
    }

    /// Writes a minimal `versions/<id>/<id>.json`, refreshes the repository so `hasVersion(id)`
    /// becomes true, and selects it as the current instance. Returns the instance id (for chaining).
    String createInstance(String instanceId) throws IOException {
        Path versionDir = tempDir.resolve("versions").resolve(instanceId);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve(instanceId + ".json"), "{\"id\":\"" + instanceId + "\"}");
        repository().refreshVersions();
        Profiles.setSelectedInstance(profile, instanceId);
        return instanceId;
    }

    private static void setUserFile(GameDirectories directories, boolean userFile) throws ReflectiveOperationException {
        Field f = field(GameDirectories.class, "userFile");
        f.set(directories, userFile);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    public void close() throws ReflectiveOperationException, IOException {
        try {
            if (previousSelectedProfile != null) {
                selectedProfileProp.set(previousSelectedProfile);
            }
            mergedProfiles.setAll(previousMergedProfiles);
            localGameDirectoriesField.set(null, previousLocalGameDirectories);
            userGameDirectoriesField.set(null, previousUserGameDirectories);
            launcherSettingsField.set(null, previousLauncherSettings);
            gameSettingsPresetsField.set(null, previousGameSettingsPresets);
            localGameDirectoriesAccessField.set(null, previousLocalAccess);
            userGameDirectoriesAccessField.set(null, previousUserAccess);
            initializedField.setBoolean(null, previousInitialized);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    Files.delete(file);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                try {
                    Files.delete(dir);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
