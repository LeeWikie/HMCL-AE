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
package org.jackhuang.hmcl.ai.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for [`ProxyAuthenticatorHolder`]: the safe no-op shape while nothing is pushed,
/// push/clear semantics, cross-thread visibility of the volatile field, and compatibility of
/// the no-op with `HttpClient.Builder.authenticator(...)` (which rejects `null`).
public final class ProxyAuthenticatorHolderTest {

    @AfterEach
    void clearHolder() {
        // The holder is global state — always restore the "nothing pushed" default.
        ProxyAuthenticatorHolder.set(null);
    }

    @Test
    void unsetHolderReturnsStableNoop() {
        Authenticator first = ProxyAuthenticatorHolder.getOrNoop();
        assertNotNull(first, "getOrNoop must never return null");
        assertSame(ProxyAuthenticatorHolder.NOOP, first, "unset holder must hand out the shared no-op");
        assertSame(first, ProxyAuthenticatorHolder.getOrNoop(), "no-op must be a stable singleton");
    }

    @Test
    void noopProvidesNoCredentialsAndThusNeverAuthenticates() {
        // Per the Authenticator contract, returning null from getPasswordAuthentication()
        // means "no credentials available" — the HTTP client leaves 401/407 responses to the
        // caller, exactly like a client with no authenticator, and no prompt can ever appear.
        ProxyAuthenticatorHolder.NoopAuthenticator noop =
                assertInstanceOf(ProxyAuthenticatorHolder.NoopAuthenticator.class,
                        ProxyAuthenticatorHolder.getOrNoop());
        assertNull(noop.getPasswordAuthentication());
    }

    @Test
    void setPushesTheExactInstance() {
        Authenticator pushed = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("proxy-user", "proxy-pass".toCharArray());
            }
        };
        ProxyAuthenticatorHolder.set(pushed);
        assertSame(pushed, ProxyAuthenticatorHolder.getOrNoop(),
                "the holder must hand back the very instance HMCL pushed");
    }

    @Test
    void setNullClearsBackToNoop() {
        ProxyAuthenticatorHolder.set(new Authenticator() {
        });
        ProxyAuthenticatorHolder.set(null);
        assertSame(ProxyAuthenticatorHolder.NOOP, ProxyAuthenticatorHolder.getOrNoop(),
                "clearing (proxy credentials removed) must fall back to the no-op");
    }

    @Test
    void pushedInstanceIsVisibleFromAnotherThread() throws InterruptedException {
        Authenticator pushed = new Authenticator() {
        };
        ProxyAuthenticatorHolder.set(pushed);
        AtomicReference<Authenticator> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(ProxyAuthenticatorHolder.getOrNoop()));
        reader.start();
        reader.join();
        assertSame(pushed, seen.get(), "volatile field must publish the instance to reader threads");
    }

    /// configure() must leave the builder authenticator-free while nothing is pushed: a JDK
    /// HttpClient carrying ANY authenticator (even a no-op) fails 401/407 responses that lack
    /// a WWW-Authenticate/Proxy-Authenticate header with IOException instead of surfacing
    /// them — and bare 401s are the norm for API-key-authenticated endpoints.
    @Test
    void configureWithoutPushLeavesClientAuthenticatorFree() {
        HttpClient.Builder builder = HttpClient.newBuilder();
        assertSame(builder, ProxyAuthenticatorHolder.configure(builder),
                "configure must return the same builder for chaining");
        assertTrue(builder.build().authenticator().isEmpty(),
                "no authenticator may be attached while nothing was pushed");
    }

    /// configure() must attach the exact pushed instance once credentials exist.
    @Test
    void configureAfterPushAttachesTheExactInstance() {
        Authenticator pushed = new Authenticator() {
        };
        ProxyAuthenticatorHolder.set(pushed);
        HttpClient client = ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()).build();
        assertSame(pushed, client.authenticator().orElseThrow(),
                "the very instance HMCL pushed must reach the JDK HttpClient");
    }

    @Test
    void noopIsAcceptedByHttpClientBuilder() {
        // HttpClient.Builder.authenticator(null) throws NPE — the whole point of getOrNoop()
        // is that call sites can chain it unconditionally. Building the client is enough to
        // exercise the contract; no request is sent.
        assertDoesNotThrow(() -> HttpClient.newBuilder()
                .authenticator(ProxyAuthenticatorHolder.getOrNoop())
                .build());
    }
}
