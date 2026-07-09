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
package org.jackhuang.hmcl.setting;

import org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.PasswordAuthentication;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that [ProxyManager#init()] pushes the proxy [Authenticator] to the AI module's
/// [ProxyAuthenticatorHolder] and that the settings invalidation listener keeps the holder in
/// sync — `java.net.http.HttpClient` does not consult `Authenticator.setDefault(...)`, so
/// without this push every AI-side request through an authenticated proxy fails with 407.
///
/// The test swaps in a throwaway [LauncherSettings] via reflection (same technique as the
/// `ProfileFixture` helper in `org.jackhuang.hmcl.ui.ai.tools`), because `ProxyManager` reads
/// `SettingsManager.settings()` statically. Globals mutated by `init()` are routed back to
/// harmless values in the `finally` block.
public final class ProxyManagerAuthenticatorPushTest {

    /// Asks the authenticator the way `java.net.http.HttpClient` does: via the public final
    /// `requestPasswordAuthenticationInstance`, which populates the instance's request fields
    /// (notably the requestor type) before delegating to `getPasswordAuthentication()`.
    private static PasswordAuthentication challenge(Authenticator authenticator,
                                                    Authenticator.RequestorType type) {
        return authenticator.requestPasswordAuthenticationInstance(
                "proxy.example.com", null, 18080, "http", "proxy authentication", "basic",
                null, type);
    }

    @Test
    public void initPushesAuthenticatorAndListenerKeepsHolderInSync() throws Exception {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettingsField.setAccessible(true);
        Object previousSettings = launcherSettingsField.get(null);

        LauncherSettings settings = new LauncherSettings();
        launcherSettingsField.set(null, settings);
        try {
            settings.proxyTypeProperty().set(ProxyType.HTTP);
            settings.proxyHostProperty().set("127.0.0.1");
            settings.proxyPortProperty().set(18080);
            settings.hasProxyAuthProperty().set(true);
            settings.proxyUserProperty().set("proxy-user");
            settings.proxyPasswordProperty().set("proxy-pass");

            ProxyManager.init();

            // init() must hand the AI side a real authenticator that answers PROXY challenges
            // with the configured credentials when invoked the way the JDK HttpClient does.
            Authenticator pushed = ProxyAuthenticatorHolder.getOrNoop();
            PasswordAuthentication proxyAuth = challenge(pushed, Authenticator.RequestorType.PROXY);
            assertNotNull(proxyAuth, "PROXY challenge must yield the configured credentials");
            assertEquals("proxy-user", proxyAuth.getUserName());
            assertArrayEquals("proxy-pass".toCharArray(), proxyAuth.getPassword());

            // Origin-server (401) challenges must NOT leak the proxy credentials.
            assertNull(challenge(pushed, Authenticator.RequestorType.SERVER),
                    "SERVER challenges must not be answered with proxy credentials");

            // Turning proxy auth off fires the invalidation listener, which must clear the
            // holder back to its no-op ("no credentials available") shape.
            settings.hasProxyAuthProperty().set(false);
            assertNull(challenge(ProxyAuthenticatorHolder.getOrNoop(), Authenticator.RequestorType.PROXY),
                    "clearing credentials must propagate to the AI-side holder");

            // Turning it back on re-pushes fresh credentials through the same listener.
            settings.hasProxyAuthProperty().set(true);
            PasswordAuthentication repushed =
                    challenge(ProxyAuthenticatorHolder.getOrNoop(), Authenticator.RequestorType.PROXY);
            assertNotNull(repushed, "re-enabling credentials must re-push to the holder");
            assertEquals("proxy-user", repushed.getUserName());
        } finally {
            // Route the process-wide selector/authenticator installed by init() back to the
            // SYSTEM defaults (the listeners registered on our throwaway settings fire once
            // more and reset ProxyManager's volatile state), clear the AI-side holder, and
            // restore whatever settings instance was loaded before.
            settings.proxyTypeProperty().set(ProxyType.SYSTEM);
            ProxyAuthenticatorHolder.set(null);
            launcherSettingsField.set(null, previousSettings);
        }
    }
}
