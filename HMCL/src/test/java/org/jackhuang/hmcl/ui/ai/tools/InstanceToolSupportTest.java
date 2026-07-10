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
import org.jackhuang.hmcl.setting.Profiles;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the integer-parameter parsing fix: Gson decodes every JSON number as a Double, so an
/// int arg like {@code 4096} arrives as {@code 4096.0} (or the string "4096.0"). parseInt must
/// handle Number, plain integer strings, and trailing-".0" strings, and fall back to the default.
public final class InstanceToolSupportTest {

    @Test
    void parsesGsonDoubleAsInt() {
        Map<String, Object> args = new HashMap<>();
        args.put("maxMemoryMB", 4096.0); // how Gson hands an int arg to a tool
        assertEquals(4096, InstanceToolSupport.parseInt(args, "maxMemoryMB", -1));
    }

    @Test
    void parsesPlainIntegerAndTrailingDotZeroStrings() {
        assertEquals(4096, InstanceToolSupport.parseInt("4096", -1));
        assertEquals(4096, InstanceToolSupport.parseInt("4096.0", -1));
        assertEquals(17, InstanceToolSupport.parseInt(17.0, -1));
        assertEquals(8, InstanceToolSupport.parseInt(Integer.valueOf(8), -1));
    }

    @Test
    void fallsBackToDefaultForMissingOrUnparseable() {
        Map<String, Object> args = new HashMap<>();
        assertEquals(10, InstanceToolSupport.parseInt(args, "absent", 10));
        args.put("k", "not-a-number");
        assertEquals(10, InstanceToolSupport.parseInt(args, "k", 10));
        assertEquals(10, InstanceToolSupport.parseInt((Object) null, 10));
        assertEquals(10, InstanceToolSupport.parseInt("", 10));
    }

    /// Locks in `set_instance_jvm_args`'s "explicit empty string clears the setting, entirely
    /// absent means report-only" distinction — the reason this couldn't just reuse the
    /// blank-means-report check `set_memory` uses (memory has no meaningful "clear" value).
    @Test
    void presentOrQueryFallbackTreatsAbsentKeyDifferentlyFromExplicitEmptyValue() {
        Map<String, Object> args = new HashMap<>();
        assertNull(InstanceToolSupport.presentOrQueryFallback(args, "jvmArgs"),
                "neither 'jvmArgs' nor 'query' present at all must mean report-only");

        args.put("jvmArgs", "");
        assertEquals("", InstanceToolSupport.presentOrQueryFallback(args, "jvmArgs"),
                "an explicitly-empty 'jvmArgs' is a deliberate clear request, not 'absent'");
    }

    @Test
    void presentOrQueryFallbackHonoursQueryAliasOnlyWhenThePrimaryKeyIsAbsent() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "-XX:+UseG1GC");
        assertEquals("-XX:+UseG1GC", InstanceToolSupport.presentOrQueryFallback(args, "jvmArgs"),
                "the generic 'query' alias must be honoured when the primary key is absent");

        args.put("jvmArgs", "-XX:+UseZGC");
        assertEquals("-XX:+UseZGC", InstanceToolSupport.presentOrQueryFallback(args, "jvmArgs"),
                "an explicit 'jvmArgs' must win over the 'query' fallback, not the other way round");
    }

    /// Locks in the heap-size-flag detector that drives `set_instance_jvm_args`'s advisory note
    /// steering the model back to `set_memory` instead of duplicating `-Xmx`/`-Xms` here.
    @Test
    void mentionsHeapSizeFlagDetectsXmxAndXmsCaseInsensitively() {
        assertTrue(InstanceToolSupport.mentionsHeapSizeFlag("-Xmx4096m"));
        assertTrue(InstanceToolSupport.mentionsHeapSizeFlag("-XX:+UseG1GC -XMS2048M"));
        assertFalse(InstanceToolSupport.mentionsHeapSizeFlag("-XX:+UseG1GC -XX:MaxGCPauseMillis=200"));
        assertFalse(InstanceToolSupport.mentionsHeapSizeFlag(""));
    }

    // ---------------------------------------------------------------------
    // T4/T5: resolveInstance is the shared "instance not found / not selected" range — its failure
    // is the unified envelope carrying the real instance names.
    // ---------------------------------------------------------------------

    @Test
    void resolveInstanceNamedMissingReturnsCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            InstanceToolSupport.ResolvedInstance r =
                    InstanceToolSupport.resolveInstance(fx.repository(), Map.of("instance", "Nope"), false);

            assertNull(r.name());
            assertNotNull(r.failure());
            String err = r.failure().getError();
            assertTrue(ToolFailures.isWellFormedEnvelope(err), "not a well-formed envelope: " + err);
            assertTrue(err.contains("does not exist"), err);
            assertTrue(err.contains("Existing"), "must list the real instance names: " + err);
        }
    }

    @Test
    void resolveInstanceNoSelectionReturnsEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            InstanceToolSupport.ResolvedInstance r =
                    InstanceToolSupport.resolveInstance(fx.repository(), Map.of(), false);

            assertNull(r.name());
            assertNotNull(r.failure());
            String err = r.failure().getError();
            assertTrue(ToolFailures.isWellFormedEnvelope(err), "not a well-formed envelope: " + err);
            assertTrue(err.contains("No instance is selected"), err);
        }
    }

    /// T20/ST-1: the default target (no `instance` parameter) is resolved LIVE from the currently
    /// selected instance, so switching the selection mid-flight is reflected immediately — this is
    /// exactly what stops a mod from being installed into a stale, previously-selected instance.
    @Test
    void resolveInstanceDefaultTargetFollowsLiveSelectedInstanceSwitch() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("A");
            fx.createInstance("B"); // B becomes the selected instance

            InstanceToolSupport.ResolvedInstance rB =
                    InstanceToolSupport.resolveInstance(fx.repository(), Map.of(), false);
            assertNull(rB.failure(), () -> "unexpected failure: " + rB.failure().getError());
            assertEquals("B", rB.name());

            Profiles.setSelectedInstance(fx.profile(), "A");

            InstanceToolSupport.ResolvedInstance rA =
                    InstanceToolSupport.resolveInstance(fx.repository(), Map.of(), false);
            assertNull(rA.failure(), () -> "unexpected failure: " + rA.failure().getError());
            assertEquals("A", rA.name(), "the default target must follow the live selection, not a cached value");
        }
    }
}
