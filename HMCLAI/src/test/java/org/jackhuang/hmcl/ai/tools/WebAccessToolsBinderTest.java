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

import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Hot web-access toggle ("启用联网工具" — 立即生效, no restart): flipping the bound property
/// must register / fully unregister the web tools in the {@link ToolRegistry}, so the model's
/// tool list either contains them or does not — never "present but erroring".
public final class WebAccessToolsBinderTest {

    private static final class FakeTool implements Tool {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "fake " + name;
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }

    private static List<String> names(ToolRegistry registry) {
        return registry.list().stream().map(Tool::getName).toList();
    }

    @Test
    public void initialStateIsAppliedAtBindTime() {
        ToolRegistry registry = new ToolRegistry();
        SimpleBooleanProperty off = new SimpleBooleanProperty(false);
        WebAccessToolsBinder.bind(off, registry, new FakeTool("web_fetch"), new FakeTool("web_search"));
        assertTrue(names(registry).isEmpty(), "binding with the toggle OFF must not register the web tools");

        ToolRegistry registry2 = new ToolRegistry();
        SimpleBooleanProperty on = new SimpleBooleanProperty(true);
        WebAccessToolsBinder.bind(on, registry2, new FakeTool("web_fetch"), new FakeTool("web_search"));
        assertEquals(List.of("web_fetch", "web_search"), names(registry2),
                "binding with the toggle ON must register the web tools immediately");
    }

    @Test
    public void turningOffUnregistersEntirely_notMerelyDisables() {
        ToolRegistry registry = new ToolRegistry();
        SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);
        WebAccessToolsBinder.bind(enabled, registry, new FakeTool("web_fetch"), new FakeTool("web_search"));
        assertNotNull(registry.get("web_search"));
        assertNotNull(registry.get("web_fetch"));

        enabled.set(false);
        assertTrue(names(registry).isEmpty(), "OFF → the tools must vanish from the model-visible list");
        assertNull(registry.get("web_search"), "OFF → web_search must be undiscoverable, not just hidden");
        assertNull(registry.get("web_fetch"), "OFF → web_fetch must be undiscoverable, not just hidden");
        assertTrue(registry.listAll().isEmpty(),
                "OFF → unregistered for real (listAll empty), unlike disable() which keeps the tool around");
    }

    @Test
    public void turningBackOnReregistersWithoutRestart() {
        ToolRegistry registry = new ToolRegistry();
        SimpleBooleanProperty enabled = new SimpleBooleanProperty(false);
        WebAccessToolsBinder.bind(enabled, registry, new FakeTool("web_fetch"), new FakeTool("web_search"));

        enabled.set(true);
        assertEquals(List.of("web_fetch", "web_search"), names(registry));

        // A full off→on→off→on cycle stays consistent (idempotent register/unregister).
        enabled.set(false);
        enabled.set(true);
        assertEquals(List.of("web_fetch", "web_search"), names(registry));
        assertNotNull(registry.get("web_search"));
    }

    @Test
    public void otherToolsAreUntouchedByTheToggle() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("read"));
        SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);
        WebAccessToolsBinder.bind(enabled, registry, new FakeTool("web_fetch"), new FakeTool("web_search"));

        enabled.set(false);
        assertEquals(List.of("read"), names(registry), "unrelated tools must survive the web-access toggle");
    }
}
