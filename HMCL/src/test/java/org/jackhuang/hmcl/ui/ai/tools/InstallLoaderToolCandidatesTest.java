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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.download.RemoteVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Candidate-enumeration coverage for [InstallLoaderTool]'s two "version does not match" failures
/// (patched to resolveVersion parity): an unknown Minecraft version and an unavailable loader
/// version now carry the actual values the model can retry with, wrapped in the unified failure
/// envelope (`Retryable:` + `Next:`), instead of a bare dead-end it can only blindly guess against.
///
/// Both envelope builders are pure functions over an already-loaded version collection, so they are
/// asserted directly (no network / no Profiles bootstrap). Every message is checked to be a
/// well-formed envelope via [ToolFailures#isWellFormedEnvelope].
public final class InstallLoaderToolCandidatesTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    // ---- unknown Minecraft version → list the newest releases ----

    @Test
    void unknownGameVersionEnvelopeCarriesRecentReleasesAndIsWellFormed() {
        List<RemoteVersion> loaded = List.of(
                game("1.21.1", RemoteVersion.Type.RELEASE, NOW),
                game("1.20.1", RemoteVersion.Type.RELEASE, NOW.minus(200, ChronoUnit.DAYS)),
                game("24w14a", RemoteVersion.Type.SNAPSHOT, NOW.minus(1, ChronoUnit.DAYS)));

        String msg = InstallLoaderTool.unknownGameVersionEnvelope("1.99.99", loaded);

        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.contains("Retryable:"), () -> msg);
        assertTrue(msg.contains("Next:"), () -> msg);
        assertTrue(msg.contains("1.99.99"), () -> "should echo the bad version: " + msg);
        // The real candidates the model can retry with must be enumerated.
        assertTrue(msg.contains("1.21.1"), () -> msg);
        assertTrue(msg.contains("1.20.1"), () -> msg);
        // Snapshots are not releases and must not be offered as candidates.
        assertFalse(msg.contains("24w14a"), () -> "snapshot leaked into release candidate list: " + msg);
    }

    @Test
    void unknownGameVersionEnvelopeStaysWellFormedWithNoLoadedVersions() {
        String msg = InstallLoaderTool.unknownGameVersionEnvelope("1.99.99", List.of());

        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        // The Next step must still offer a way out ("latest") when there are no listable candidates.
        assertTrue(msg.contains("latest"), () -> msg);
    }

    // ---- unavailable loader version → list the compatible loader versions ----

    @Test
    void loaderVersionUnavailableEnvelopeCarriesAvailableLoaderVersionsAndIsWellFormed() {
        List<RemoteVersion> available = List.of(
                loader("1.21.1", "0.16.9"),
                loader("1.21.1", "0.16.8"),
                loader("1.21.1", "0.16.7"));

        String msg = InstallLoaderTool.loaderVersionUnavailableEnvelope(
                "fabric", "0.1.0-nope", "1.21.1", available);

        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.contains("Retryable:"), () -> msg);
        assertTrue(msg.contains("Next:"), () -> msg);
        assertTrue(msg.contains("fabric"), () -> msg);
        assertTrue(msg.contains("0.1.0-nope"), () -> "should echo the bad loader version: " + msg);
        assertTrue(msg.contains("1.21.1"), () -> msg);
        // The compatible loader versions must be enumerated as candidates.
        assertTrue(msg.contains("0.16.9"), () -> msg);
        assertTrue(msg.contains("0.16.7"), () -> msg);
    }

    @Test
    void loaderVersionUnavailableEnvelopeStaysWellFormedWithNoCandidates() {
        String msg = InstallLoaderTool.loaderVersionUnavailableEnvelope(
                "forge", "99.9", "1.21.1", List.of());

        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
    }

    @Test
    void loaderVersionListIsCappedSoAHugeListCannotFloodTheMessage() {
        List<RemoteVersion> available = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            available.add(loader("1.21.1", String.format("v%02d", i)));
        }

        String msg = InstallLoaderTool.loaderVersionUnavailableEnvelope(
                "fabric", "bad", "1.21.1", available);

        assertTrue(ToolFailures.isWellFormedEnvelope(msg), () -> "not a well-formed envelope: " + msg);
        assertTrue(msg.contains("v01"), () -> msg);
        assertTrue(msg.contains("v15"), () -> msg);
        // Capped at 15 entries, so the 16th onward must not appear.
        assertFalse(msg.contains("v16"), () -> "candidate list was not capped: " + msg);
    }

    private static RemoteVersion game(String id, RemoteVersion.Type type, Instant date) {
        return new RemoteVersion("game", id, id, date, type, List.of("https://example.invalid/" + id));
    }

    private static RemoteVersion loader(String gameVersion, String selfVersion) {
        return new RemoteVersion("net.fabricmc:fabric-loader", gameVersion, selfVersion, null,
                List.of("https://example.invalid/" + selfVersion));
    }
}
