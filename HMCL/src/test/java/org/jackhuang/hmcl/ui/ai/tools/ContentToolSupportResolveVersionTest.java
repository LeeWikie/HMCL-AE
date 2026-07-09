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

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression coverage for the `mods_install` "wrong loader / wrong game version" bug: the
/// `InstanceTool` facade used to advertise no `loader`/`gameVersion`/`version` parameters for the
/// `mods_install` action at all (they only existed for `create`/`download_java`), so the model had
/// no field to pass them through even when it knew the right values, and
/// {@link ContentToolSupport#resolveVersion} always fell back to auto-picking the single
/// most-recently-published file across EVERY loader and MC version a project ships — which for a
/// multi-loader/multi-version project is very often the WRONG file for the target instance.
///
/// These tests exercise `resolveVersion` directly (bypassing the network-bound Modrinth/CurseForge
/// repositories, which are exactly what makes the leaf `install_mod` tool untestable offline)
/// against a hand-built fake repository whose newest-published version deliberately does NOT
/// match the instance's actual loader/game version, so a regression back to "ignore loader/
/// gameVersion" would make these tests fail loudly instead of only surfacing as a live bug report.
public final class ContentToolSupportResolveVersionTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    /// The globally newest-published file — Fabric, 1.21.1. If `loader`/`gameVersion` are ever
    /// silently dropped again, auto-pick-with-no-filter returns exactly this file regardless of
    /// what the caller actually asked for — the exact failure mode this whole fix targets.
    private static final RemoteAddon.Version NEWEST_BUT_WRONG_LOADER = version(
            "fabric-1.21.1", "2.0.0", NOW, List.of("1.21.1"), List.of(ModLoaderType.FABRIC));

    /// An older file that is nonetheless the one that actually matches a NeoForge 1.20.1 instance.
    private static final RemoteAddon.Version OLDER_BUT_MATCHING = version(
            "neoforge-1.20.1", "1.5.0", NOW.minus(60, ChronoUnit.DAYS),
            List.of("1.20.1"), List.of(ModLoaderType.NEO_FORGE));

    /// A third, even-older distractor for yet another loader/version combo, so the filtering
    /// assertions below aren't trivially satisfied by "there happen to be only two candidates".
    private static final RemoteAddon.Version OLDEST_FORGE = version(
            "forge-1.20.1", "1.0.0", NOW.minus(200, ChronoUnit.DAYS),
            List.of("1.20.1"), List.of(ModLoaderType.FORGE));

    private final RemoteAddonRepository repo = fakeRepository(
            List.of(NEWEST_BUT_WRONG_LOADER, OLDER_BUT_MATCHING, OLDEST_FORGE));

    @Test
    void autoPickWithNoFiltersReturnsTheGloballyNewestFileRegardlessOfLoader() throws Exception {
        // Baseline: this is the exact mechanism that made the bug possible — with no loader/
        // gameVersion supplied, resolveVersion has nothing to narrow on and falls back to "most
        // recently published across everything", which is why the facade MUST forward those params.
        RemoteAddon.Version picked = ContentToolSupport.resolveVersion(repo, "demo-mod", null, null, null);
        assertEquals("fabric-1.21.1", picked.name());
    }

    @Test
    void explicitLoaderAndGameVersionResolveToTheMatchingVersionNotTheNewestOne() throws Exception {
        // The fix under test: an explicit loader+gameVersion (exactly what mods_install now
        // accepts and forwards unchanged to the leaf install_mod tool) steers the pick to the file
        // that actually matches the target instance, even though it is NOT the newest-published one.
        RemoteAddon.Version picked = ContentToolSupport.resolveVersion(
                repo, "demo-mod", "1.20.1", null, ModLoaderType.NEO_FORGE);
        assertEquals("neoforge-1.20.1", picked.name());
        assertNotEquals("fabric-1.21.1", picked.name());
    }

    @Test
    void explicitVersionLocksOntoOneExactBuildEvenWhenLoaderFilterWouldPickAnother() throws Exception {
        // A locked `version` (the third new mods_install parameter) wins outright over loader/
        // gameVersion auto-pick — matches the SKILL.md guidance to "pass version too when you need
        // one exact, locked build".
        RemoteAddon.Version picked = ContentToolSupport.resolveVersion(
                repo, "demo-mod", "1.20.1", "1.0.0", ModLoaderType.NEO_FORGE);
        assertEquals("forge-1.20.1", picked.name());
    }

    @Test
    void loaderFilterWithNoMatchingVersionFailsInsteadOfSilentlyPickingAWrongLoader() {
        Exception e = assertThrows(IOException.class, () ->
                ContentToolSupport.resolveVersion(repo, "demo-mod", "1.20.1", null, ModLoaderType.QUILT));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("quilt"),
                "expected a message naming the unmatched loader, got: " + e.getMessage());
    }

    private static RemoteAddon.Version version(String name, String versionNumber, Instant datePublished,
                                                List<String> gameVersions, List<ModLoaderType> loaders) {
        RemoteAddon.File file = new RemoteAddon.File(Collections.emptyMap(),
                "https://example.invalid/" + name + ".jar", name + ".jar");
        return new RemoteAddon.Version(null, "demo-mod", name, versionNumber, "", datePublished,
                RemoteAddon.VersionType.Release, file, Collections.emptyList(), gameVersions, loaders);
    }

    private static RemoteAddonRepository fakeRepository(List<RemoteAddon.Version> versions) {
        RemoteAddon.IMod data = new RemoteAddon.IMod() {
            @Override
            public List<RemoteAddon> loadDependencies(RemoteAddonRepository modRepository, DownloadProvider downloadProvider) {
                return Collections.emptyList();
            }

            @Override
            public Stream<RemoteAddon.Version> loadVersions(RemoteAddonRepository modRepository, DownloadProvider downloadProvider) {
                return versions.stream();
            }
        };
        RemoteAddon addon = new RemoteAddon("demo-mod", "tester", "Demo Mod", "a fake mod for tests",
                Collections.emptyList(), "", "", data, RemoteAddonRepository.Type.MOD);

        return new RemoteAddonRepository() {
            @Override
            public Type getType() {
                return Type.MOD;
            }

            @Override
            public SearchResult search(DownloadProvider downloadProvider, String gameVersion, Category category,
                                        int pageOffset, int pageSize, String searchFilter, SortType sortType,
                                        SortOrder sortOrder) {
                throw new UnsupportedOperationException("not used by resolveVersion");
            }

            @Override
            public Optional<RemoteAddon.Version> getRemoteVersionByLocalFile(Path file) {
                throw new UnsupportedOperationException("not used by resolveVersion");
            }

            @Override
            public RemoteAddon getModById(DownloadProvider downloadProvider, String id) {
                return addon;
            }

            @Override
            public RemoteAddon.File getModFile(String modId, String fileId) {
                throw new UnsupportedOperationException("not used by resolveVersion");
            }

            @Override
            public Stream<RemoteAddon.Version> getRemoteVersionsById(DownloadProvider downloadProvider, String id) {
                throw new UnsupportedOperationException("not used by resolveVersion");
            }

            @Override
            public Stream<Category> getCategories() {
                throw new UnsupportedOperationException("not used by resolveVersion");
            }
        };
    }
}
