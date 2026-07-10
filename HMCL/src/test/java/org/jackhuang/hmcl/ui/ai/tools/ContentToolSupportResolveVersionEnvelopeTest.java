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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Envelope-format + candidate-enumeration coverage for the {@code mods_install} resolver
/// (sub-batch ④, items T2/T3). Complements {@link ContentToolSupportResolveVersionTest} (which
/// covers the happy-path picking) by pinning down the two failure-quality fixes:
///
/// - **T2 (#7/#8/#9)**: the three "no matching version" {@code throw}s now carry the data the
///   model was looking for — the actual version ids / supported game versions / published
///   loaders — wrapped in the unified failure envelope (`Retryable:` + `Next:`), instead of a
///   bare "no match" the model can only blindly retry.
/// - **T3**: when the caller passes no {@code loader}, a MOD install no longer silently
///   auto-picks the newest file across EVERY loader; it defaults to the CURRENTLY SELECTED
///   instance's own mod loaders (mirroring the native `DownloadPage`'s implicit protection).
///
/// The instance-loader lookup is exercised through {@link ContentToolSupport#instanceLoaderOverride}
/// so these tests need no real modded instance on disk. Every failure message is asserted to be a
/// well-formed envelope via {@link ToolFailures#isWellFormedEnvelope} (the module-crossing,
/// JUnit-free predicate).
public final class ContentToolSupportResolveVersionEnvelopeTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private static final RemoteAddon.Version FABRIC_1_21_1 = version(
            "fabric-1.21.1", "2.0.0", NOW, List.of("1.21.1"), List.of(ModLoaderType.FABRIC));
    private static final RemoteAddon.Version NEOFORGE_1_20_1 = version(
            "neoforge-1.20.1", "1.5.0", NOW.minus(60, ChronoUnit.DAYS),
            List.of("1.20.1"), List.of(ModLoaderType.NEO_FORGE));
    private static final RemoteAddon.Version FORGE_1_20_1 = version(
            "forge-1.20.1", "1.0.0", NOW.minus(200, ChronoUnit.DAYS),
            List.of("1.20.1"), List.of(ModLoaderType.FORGE));

    private final RemoteAddonRepository repo = fakeRepository(
            List.of(FABRIC_1_21_1, NEOFORGE_1_20_1, FORGE_1_20_1));

    @BeforeEach
    void isolateInstanceLoaders() {
        // Default to "instance loaders can't be determined" so the T2 tests (which fail before the
        // loader-default path) and the baseline never touch real Profiles static state. T3 tests
        // override this explicitly with a concrete loader set.
        ContentToolSupport.instanceLoaderOverride = () -> Set.of();
    }

    @AfterEach
    void resetInstanceLoaders() {
        ContentToolSupport.instanceLoaderOverride = null;
    }

    // ---- T2 · #7: unknown versionId carries the real version candidates ----
    @Test
    void versionIdMismatchCarriesCandidateVersionsInAWellFormedEnvelope() {
        IOException e = assertThrows(IOException.class, () ->
                ContentToolSupport.resolveVersion(repo, "demo-mod", null, "9.9.9-does-not-exist", null));
        String msg = e.getMessage();
        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.contains("Retryable:"), () -> msg);
        assertTrue(msg.contains("Next:"), () -> msg);
        // The candidate versions the model can retry with must be enumerated.
        assertTrue(msg.contains("fabric-1.21.1"), () -> msg);
        assertTrue(msg.contains("neoforge-1.20.1"), () -> msg);
    }

    // ---- T2 · #8: unsupported gameVersion carries the supported MC versions ----
    @Test
    void gameVersionMismatchCarriesSupportedGameVersionsInAWellFormedEnvelope() {
        IOException e = assertThrows(IOException.class, () ->
                ContentToolSupport.resolveVersion(repo, "demo-mod", "1.7.10", null, null));
        String msg = e.getMessage();
        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.contains("1.20.1"), () -> msg);
        assertTrue(msg.contains("1.21.1"), () -> msg);
    }

    // ---- T2 · #9: unsupported explicit loader carries the published loaders ----
    @Test
    void explicitLoaderMismatchCarriesAvailableLoadersInAWellFormedEnvelope() {
        IOException e = assertThrows(IOException.class, () ->
                ContentToolSupport.resolveVersion(repo, "demo-mod", null, null, ModLoaderType.QUILT));
        String msg = e.getMessage();
        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("quilt"), () -> msg);
        assertTrue(msg.contains("FABRIC"), () -> msg);
        assertTrue(msg.contains("NEO_FORGE"), () -> msg);
    }

    // ---- T3: no loader → default to the selected instance's loader, not "newest across all" ----
    @Test
    void missingLoaderDefaultsToTheSelectedInstanceLoaderInsteadOfNewestAcrossAll() throws Exception {
        // Selected instance is NeoForge 1.20.1; installing without an explicit loader must pick the
        // NeoForge build, NOT the globally newest Fabric one.
        ContentToolSupport.instanceLoaderOverride = () -> Set.of(ModLoaderType.NEO_FORGE);
        RemoteAddon.Version picked = ContentToolSupport.resolveVersion(repo, "demo-mod", null, null, null);
        assertEquals("neoforge-1.20.1", picked.name());
        assertNotEquals("fabric-1.21.1", picked.name());
    }

    @Test
    void missingLoaderFailsWithEnvelopeNamingInstanceAndPublishedLoadersWhenNoneMatch() {
        // Selected instance is Quilt; the project ships no Quilt build → fail with an envelope that
        // names both the inferred instance loader and what the project actually publishes.
        ContentToolSupport.instanceLoaderOverride = () -> Set.of(ModLoaderType.QUILT);
        IOException e = assertThrows(IOException.class, () ->
                ContentToolSupport.resolveVersion(repo, "demo-mod", null, null, null));
        String msg = e.getMessage();
        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("instance"), () -> msg);
        assertTrue(msg.contains("QUILT"), () -> msg);
        assertTrue(msg.contains("FABRIC"), () -> msg);
    }

    @Test
    void missingLoaderWithUndeterminableInstanceKeepsHistoricalNewestAutoPick() throws Exception {
        // When the instance's loaders can't be determined (empty set), fall back to the historical
        // no-filter behaviour rather than blocking the install.
        ContentToolSupport.instanceLoaderOverride = () -> Set.of();
        RemoteAddon.Version picked = ContentToolSupport.resolveVersion(repo, "demo-mod", null, null, null);
        assertEquals("fabric-1.21.1", picked.name());
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
