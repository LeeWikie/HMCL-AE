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
package org.jackhuang.hmcl.ui.ai;

import org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// Wave-1 leftover (①a skipped `EmojiImages` on file ownership): the emoji download
/// `HttpClient` must pick up the username/password proxy authenticator pushed by
/// `ProxyManager` via [ProxyAuthenticatorHolder], otherwise every emoji download through an
/// authenticated proxy fails with 407. Wired through `ProxyAuthenticatorHolder.configure`,
/// so an authenticator is attached ONLY when one has actually been pushed.
public final class EmojiImagesProxyAuthTest {

    @AfterEach
    public void clearHolder() {
        ProxyAuthenticatorHolder.set(null);
    }

    @Test
    public void attachesThePushedProxyAuthenticator() {
        Authenticator pushed = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("user", "pass".toCharArray());
            }
        };
        ProxyAuthenticatorHolder.set(pushed);

        HttpClient client = EmojiImages.newHttpClient();

        assertTrue(client.authenticator().isPresent(), "pushed proxy authenticator must be attached");
        assertSame(pushed, client.authenticator().get());
    }

    @Test
    public void staysAuthenticatorFreeWhenNothingWasPushed() {
        ProxyAuthenticatorHolder.set(null);

        HttpClient client = EmojiImages.newHttpClient();

        // configure() semantics: no authenticator until credentials are actually pushed, so
        // challenge-header-less 401/407 responses still surface normally.
        assertTrue(client.authenticator().isEmpty());
    }

    @Test
    public void keepsTheOriginalClientConfiguration() {
        HttpClient client = EmojiImages.newHttpClient();

        assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
        assertEquals(Duration.ofSeconds(15), client.connectTimeout().orElse(null));
    }
}
