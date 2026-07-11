/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link AiToolPermissionStore}: tool-wide vs action-scoped override resolution,
/// path-glob override resolution/precedence (Part D), the {@link AiToolPermissionStore#matchesGlob}
/// primitive, disk persistence round-tripping, and (post SAFE/ASK/YOLO merge — see
/// {@link org.jackhuang.hmcl.ai.AiApprovalMode}'s own doc) {@link AiToolPermissionStore.OverrideMode#apply}'s
/// rule that a non-negotiable BLOCK can never be relaxed by an override.
public final class AiToolPermissionStoreTest {

    private Path dir;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("hmcl-ai-permission-store-");
        file = dir.resolve("ai-tool-permissions.json");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dir != null) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void defaultsToFollowGlobalForAnUnknownTool() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL, store.getOverride("write"));
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL, store.getOverride("write", "anything"));
    }

    @Test
    void toolWideOverrideAppliesToEveryAction() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setOverride("instance", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, store.getOverride("instance"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, store.getOverride("instance", "list"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, store.getOverride("instance", "delete"));
        // A different tool is unaffected.
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL, store.getOverride("nbt"));
    }

    @Test
    void actionScopedOverrideWinsOverToolWideForThatActionOnly() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setOverride("instance", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.setOverride("instance", "delete", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);

        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, store.getOverride("instance", "delete"));
        // Every other action still falls back to the tool-wide override.
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, store.getOverride("instance", "list"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, store.getOverride("instance"));
    }

    @Test
    void actionScopedFollowGlobalFallsBackToToolWideNotToGlobalDirectly() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setOverride("instance", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.setOverride("instance", "delete", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);
        // Removing the action-scoped override (by setting FOLLOW_GLOBAL) falls back to the
        // tool-wide override, which is still ALWAYS_ALLOW — not straight to FOLLOW_GLOBAL.
        store.setOverride("instance", "delete", AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL);
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, store.getOverride("instance", "delete"));
    }

    @Test
    void saveThenLoadRoundTripsToolAndActionOverrides() throws IOException {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setOverride("write", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);
        store.setOverride("instance", "delete", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.save();

        AiToolPermissionStore reloaded = new AiToolPermissionStore(file);
        reloaded.load();
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, reloaded.getOverride("write"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, reloaded.getOverride("instance", "delete"));
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL, reloaded.getOverride("instance", "list"));
    }

    @Test
    void loadOnMissingFileLeavesStoreEmpty() throws IOException {
        AiToolPermissionStore store = new AiToolPermissionStore(dir.resolve("does-not-exist.json"));
        store.load();
        assertTrue(store.getOverrides().isEmpty());
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL, store.getOverride("write"));
    }

    @Test
    void legacySafeAndAskIdsBothResolveToAlwaysAskAndLegacyYoloResolvesToAlwaysAllow() {
        // Settings files written before the SAFE/ASK/YOLO merge persisted one of those three ids —
        // fromId must keep loading them without an explicit migration step.
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, AiToolPermissionStore.OverrideMode.fromId("safe"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, AiToolPermissionStore.OverrideMode.fromId("ask"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, AiToolPermissionStore.OverrideMode.fromId("yolo"));
    }

    // ---- OverrideMode#apply: an override can relax/tighten but never touch a BLOCK ----

    @Test
    void alwaysAllowForcesAllowForAnAskDecision() {
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW.apply(
                        AiExecutionPolicy.Decision.ASK, ToolPermission.DANGEROUS_WRITE));
    }

    @Test
    void alwaysAskForcesAskForAnAllowDecisionOnWritePermissionsOnly() {
        assertEquals(AiExecutionPolicy.Decision.ASK,
                AiToolPermissionStore.OverrideMode.ALWAYS_ASK.apply(
                        AiExecutionPolicy.Decision.ALLOW, ToolPermission.CONTROLLED_WRITE));
        assertEquals(AiExecutionPolicy.Decision.ASK,
                AiToolPermissionStore.OverrideMode.ALWAYS_ASK.apply(
                        AiExecutionPolicy.Decision.ALLOW, ToolPermission.DANGEROUS_WRITE));
        // Mirrors the historical Safe/Ask modes, which never gated READ_ONLY/EXTERNAL_NETWORK
        // either — an ALWAYS_ASK override must not start gating those now.
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                AiToolPermissionStore.OverrideMode.ALWAYS_ASK.apply(
                        AiExecutionPolicy.Decision.ALLOW, ToolPermission.READ_ONLY));
    }

    @Test
    void followGlobalPassesTheBaseDecisionThroughUnchanged() {
        for (AiExecutionPolicy.Decision base : AiExecutionPolicy.Decision.values()) {
            assertEquals(base, AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL.apply(base, ToolPermission.DANGEROUS_WRITE));
        }
    }

    @Test
    void noOverrideCanEverRelaxABlock() {
        // A BLOCK always means a non-negotiable safety gate already fired (Plan Mode, or a
        // DANGEROUS_WRITE call on a possibly-unattended turn) — see AiExecutionPolicy's class doc.
        // Neither override value may touch it, including the otherwise-maximally-permissive
        // ALWAYS_ALLOW — this is exactly what stops a "remembered yes" for one tool from
        // reopening the unattended-dangerous-command safety hole.
        for (ToolPermission permission : ToolPermission.values()) {
            assertEquals(AiExecutionPolicy.Decision.BLOCK,
                    AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW.apply(AiExecutionPolicy.Decision.BLOCK, permission));
            assertEquals(AiExecutionPolicy.Decision.BLOCK,
                    AiToolPermissionStore.OverrideMode.ALWAYS_ASK.apply(AiExecutionPolicy.Decision.BLOCK, permission));
            assertEquals(AiExecutionPolicy.Decision.BLOCK,
                    AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL.apply(AiExecutionPolicy.Decision.BLOCK, permission));
        }
    }

    // ---- §3.7: cross-instance mtime self-sync on read ----

    @Test
    void getOverridePicksUpATighteningWrittenByAnotherInstanceOnDisk() throws IOException {
        // The security-critical direction: the chat page's live store must NOT keep auto-allowing a
        // tool the settings page (a SEPARATE store over the same file) just moved back to ALWAYS_ASK.
        AiToolPermissionStore writer = new AiToolPermissionStore(file);
        writer.setOverride("write", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        writer.save();

        AiToolPermissionStore reader = new AiToolPermissionStore(file);
        reader.load();
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW, reader.getOverride("write"));

        // Another instance tightens the same tool on disk.
        writer.setOverride("write", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);
        writer.save();
        // Force a distinct mtime so the change is observable even on coarse-granularity clocks.
        bumpMtime(file);

        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, reader.getOverride("write"),
                "a tightening written by another instance must be re-synced on the next read");
    }

    @Test
    void getOverrideWithPathPicksUpANewPathRuleWrittenByAnotherInstance() throws IOException {
        AiToolPermissionStore reader = new AiToolPermissionStore(file);
        reader.load();
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL,
                reader.getOverride("write", null, "saves/world1/level.dat"));

        AiToolPermissionStore writer = new AiToolPermissionStore(file);
        writer.load();
        writer.setPathOverride("write", "saves/**", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);
        writer.save();
        bumpMtime(file);

        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK,
                reader.getOverride("write", null, "saves/world1/level.dat"));
        assertEquals(1, reader.getPathOverrides("write").size());
    }

    private static void bumpMtime(Path f) throws IOException {
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
    }

    // ---- Part D: path-glob overrides ----

    @Test
    void matchesGlobHandlesStarDoubleStarAndQuestionMark() {
        assertTrue(AiToolPermissionStore.matchesGlob("mods/*.jar", "mods/fabric-api.jar"));
        assertFalse(AiToolPermissionStore.matchesGlob("mods/*.jar", "mods/sub/fabric-api.jar"));
        assertTrue(AiToolPermissionStore.matchesGlob("mods/**", "mods/sub/fabric-api.jar"));
        assertTrue(AiToolPermissionStore.matchesGlob("mods/**", "mods/fabric-api.jar"));
        assertTrue(AiToolPermissionStore.matchesGlob("saves/**/level.dat", "saves/world1/level.dat"));
        assertTrue(AiToolPermissionStore.matchesGlob("save?/level.dat", "save1/level.dat"));
        // '?' matches EXACTLY one non-separator character — two extra characters must not match.
        assertFalse(AiToolPermissionStore.matchesGlob("save?/level.dat", "save12/level.dat"));
        assertFalse(AiToolPermissionStore.matchesGlob("mods/**", "saves/world1/level.dat"));
    }

    @Test
    void matchesGlobNormalizesWindowsAndPosixSeparators() {
        assertTrue(AiToolPermissionStore.matchesGlob("mods/**", "mods\\sub\\fabric-api.jar"));
        assertTrue(AiToolPermissionStore.matchesGlob("mods\\**", "mods/sub/fabric-api.jar"));
    }

    @Test
    void pathOverrideRuleWinsOverActionAndToolWideOverrides() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setOverride("write", AiToolPermissionStore.OverrideMode.ALWAYS_ASK); // tool-wide default
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.setPathOverride("write", "saves/**", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);

        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW,
                store.getOverride("write", null, "mods/fabric-api.jar"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK,
                store.getOverride("write", null, "saves/world1/level.dat"));
        // A path that matches no rule falls back to the ordinary (tool-wide) resolution.
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK,
                store.getOverride("write", null, "config/options.txt"));
        // No path supplied at all -> skips straight to the ordinary resolution too.
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, store.getOverride("write", null, null));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK, store.getOverride("write", null, "  "));
    }

    @Test
    void pathOverrideRulesAreScopedPerTool() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        // A different tool with no rules of its own is unaffected.
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL,
                store.getOverride("edit", null, "mods/fabric-api.jar"));
    }

    @Test
    void removePathOverrideDropsOnlyThatRule() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.setPathOverride("write", "saves/**", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);
        store.removePathOverride("write", "mods/**");

        assertEquals(1, store.getPathOverrides("write").size());
        assertEquals(AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL,
                store.getOverride("write", null, "mods/fabric-api.jar"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK,
                store.getOverride("write", null, "saves/world1/level.dat"));
    }

    @Test
    void setPathOverrideFollowGlobalRemovesTheRule() {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.FOLLOW_GLOBAL);
        assertTrue(store.getPathOverrides("write").isEmpty());
    }

    @Test
    void pathOverridesRoundTripThroughSaveAndLoad() throws IOException {
        AiToolPermissionStore store = new AiToolPermissionStore(file);
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);
        store.setPathOverride("write", "saves/**", AiToolPermissionStore.OverrideMode.ALWAYS_ASK);
        store.save();

        AiToolPermissionStore reloaded = new AiToolPermissionStore(file);
        reloaded.load();
        assertEquals(2, reloaded.getPathOverrides("write").size());
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW,
                reloaded.getOverride("write", null, "mods/fabric-api.jar"));
        assertEquals(AiToolPermissionStore.OverrideMode.ALWAYS_ASK,
                reloaded.getOverride("write", null, "saves/world1/level.dat"));
    }
}
