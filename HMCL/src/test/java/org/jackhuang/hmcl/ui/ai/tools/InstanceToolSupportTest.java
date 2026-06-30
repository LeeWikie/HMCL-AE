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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
