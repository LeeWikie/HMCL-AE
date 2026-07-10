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
import javafx.beans.property.ObjectPropertyBase;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.event.EventBus;
import org.jackhuang.hmcl.event.EventManager;
import org.jackhuang.hmcl.event.RefreshedVersionsEvent;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

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

    /// Maximum time to wait for [Profiles#init()]'s background version refresh(es) to settle.
    /// See the big comment in the constructor for why this wait exists at all.
    private static final Duration REFRESH_SETTLE_TIMEOUT = Duration.ofSeconds(10);

    /// How long the repository's [RefreshedVersionsEvent] stream must stay quiet before we
    /// consider every refresh Profiles.init() triggered to be done, rather than just the first one.
    private static final Duration REFRESH_QUIET_WINDOW = Duration.ofMillis(100);

    /// Poll granularity while waiting for [#REFRESH_QUIET_WINDOW] to elapse.
    private static final Duration REFRESH_POLL_INTERVAL = Duration.ofMillis(15);

    private final Path tempDir;
    private final Profile profile;

    private final Field localGameDirectoriesField;
    private final Field userGameDirectoriesField;
    private final Field launcherSettingsField;
    private final Field gameSettingsPresetsField;
    private final Field localGameDirectoriesAccessField;
    private final Field userGameDirectoriesAccessField;
    private final Field initializedField;

    /// Reflected `ObjectPropertyBase.helper` field of [Profiles]'s `selectedProfile` property --
    /// this is where JavaFX stores the property's registered listeners. Saved/restored so that
    /// this fixture's call to [Profiles#init()] (which unconditionally does
    /// `selectedProfile.addListener(...)`) never leaves a permanent listener behind on this
    /// shared static property; see the constructor's comment for why that matters.
    private final Field selectedProfileHelperField;
    private final Object previousSelectedProfileHelper;

    /// The [RefreshedVersionsEvent] channel's per-priority handler lists (reflected out of
    /// [EventManager]) and a snapshot of their contents before [Profiles#init()] runs, so the
    /// weak listener `Profiles.init()` registers on this shared static channel can be undone too.
    private final CopyOnWriteArrayList<?>[] refreshedVersionsHandlers;
    private final List<?>[] previousRefreshedVersionsHandlerSnapshots;

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

        // Profiles.init() (below) does two things that are harmless in the real app -- which only
        // ever calls it once per process -- but become a correctness hazard when a test harness
        // calls it once per test, as every ProfileFixture construction does:
        //
        //  1. It unconditionally does `selectedProfile.addListener(...)`, permanently attaching a
        //     new listener to this *static, shared* property, and unconditionally registers a new
        //     weak handler on the *static, shared* RefreshedVersionsEvent channel. Nothing ever
        //     removes either. After N ProfileFixture constructions in one test JVM, the *next*
        //     `selectedProfile.set(...)` call (a few lines into Profiles.init()) re-fires all N
        //     stale listeners plus the new one -- every one of which independently calls
        //     `newValue.getRepository().refreshVersionsAsync().start()` against this fixture's own,
        //     brand-new repository.
        //  2. `refreshVersionsAsync().start()` schedules `HMCLGameRepository#refreshVersionsImpl()`
        //     onto a background thread pool (Schedulers.defaultScheduler()), not the calling
        //     thread. refreshVersionsImpl() mutates this repository's plain (non-thread-safe)
        //     instanceGameSettings/loadedInstanceGameSettings maps.
        //
        // Put together, Profiles.init() can leave anywhere from one to N background threads racing
        // to refresh this fixture's repository. If the test's own code (createInstance(),
        // InstallLoaderTool.applyPostInstallDefaults(), ...) then calls repository().refreshVersions()
        // again on the calling thread before those stragglers finish, they race on the same maps --
        // the actual root cause of the historical "per-instance settings null"-style flakes seen in
        // tests that share this fixture. Whether that race is won or lost depends on unrelated
        // thread-scheduling noise, which is exactly why the failures looked random and combination-
        // dependent instead of always-on or always-off.
        //
        // Fixed in two parts, both scoped to this fixture:
        //  a) Undo the leak at its source: snapshot the two pieces of shared listener state
        //     Profiles.init() mutates, and restore them in close() -- exactly like every other
        //     piece of static state this class already saves/restores -- so this fixture never
        //     leaves more listeners behind than it found, and the "next" fixture construction is
        //     never racing against more than the one refresh *it* triggers.
        //  b) Make the wait itself deterministic: register for this repository's
        //     RefreshedVersionsEvent *before* calling Profiles.init(), then block in the
        //     constructor until no further completion has arrived for a short quiet window --
        //     instead of returning after the first event (which, before (a) is applied elsewhere,
        //     or for stragglers from other Profiles.init() callers such as GameDirectoriesTest's
        //     own helper, does not guarantee every triggered refresh is done) or, worse, not
        //     waiting at all.
        selectedProfileHelperField = field(ObjectPropertyBase.class, "helper");
        Object previousSelectedProfileHelper = selectedProfileHelperField.get(selectedProfileProp);
        this.previousSelectedProfileHelper = previousSelectedProfileHelper;

        EventManager<RefreshedVersionsEvent> refreshedVersionsChannel = EventBus.EVENT_BUS.channel(RefreshedVersionsEvent.class);
        Field allHandlersField = field(EventManager.class, "allHandlers");
        @SuppressWarnings("unchecked")
        CopyOnWriteArrayList<?>[] refreshedVersionsHandlers = (CopyOnWriteArrayList<?>[]) allHandlersField.get(refreshedVersionsChannel);
        this.refreshedVersionsHandlers = refreshedVersionsHandlers;
        List<?>[] previousRefreshedVersionsHandlerSnapshots = new List<?>[refreshedVersionsHandlers.length];
        for (int i = 0; i < refreshedVersionsHandlers.length; i++) {
            if (refreshedVersionsHandlers[i] != null) {
                previousRefreshedVersionsHandlerSnapshots[i] = List.copyOf(refreshedVersionsHandlers[i]);
            }
        }
        this.previousRefreshedVersionsHandlerSnapshots = previousRefreshedVersionsHandlerSnapshots;

        HMCLGameRepository repository = profile.getRepository();
        AtomicLong lastRefreshCompletionNanos = new AtomicLong(-1);
        refreshedVersionsChannel.register(event -> {
            if (event.getSource() == repository) {
                lastRefreshCompletionNanos.set(System.nanoTime());
            }
        });

        Profiles.init();

        awaitVersionsRefreshQuiescence(lastRefreshCompletionNanos);
    }

    /// Blocks until no [RefreshedVersionsEvent] completion has been observed for
    /// [#REFRESH_QUIET_WINDOW], so the constructor never returns while a Profiles.init()-triggered
    /// background refresh (see the constructor's comment) might still be touching this fixture's
    /// repository. Throws rather than silently returning early if nothing ever completes, since a
    /// hang here means Profiles.init() stopped triggering a refresh at all -- a bug worth failing
    /// loudly on, not masking.
    private static void awaitVersionsRefreshQuiescence(AtomicLong lastCompletionNanos) {
        long deadline = System.nanoTime() + REFRESH_SETTLE_TIMEOUT.toNanos();
        for (;;) {
            long last = lastCompletionNanos.get();
            long now = System.nanoTime();
            if (last >= 0 && now - last >= REFRESH_QUIET_WINDOW.toNanos()) {
                return;
            }
            if (now >= deadline) {
                throw new IllegalStateException("ProfileFixture: Profiles.init()'s background version "
                        + "refresh(es) never " + (last >= 0 ? "settled" : "completed even once")
                        + " within " + REFRESH_SETTLE_TIMEOUT + "; the background task executor may be stuck");
            }
            try {
                Thread.sleep(REFRESH_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Profiles.init()'s background version refresh(es)", e);
            }
        }
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
            // Undo Profiles.init()'s permanent listener registrations *first*, before restoring
            // the previous selected-profile value below -- otherwise that restoration would
            // re-fire (and therefore re-trigger a background refresh through) a listener this
            // fixture is about to discard anyway. See the constructor's comment for the full story.
            selectedProfileHelperField.set(selectedProfileProp, previousSelectedProfileHelper);
            for (int i = 0; i < refreshedVersionsHandlers.length; i++) {
                List<?> previous = previousRefreshedVersionsHandlerSnapshots[i];
                if (previous == null) {
                    refreshedVersionsHandlers[i] = null;
                } else {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    List rawHandlers = refreshedVersionsHandlers[i];
                    rawHandlers.clear();
                    //noinspection unchecked
                    rawHandlers.addAll(previous);
                }
            }

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
