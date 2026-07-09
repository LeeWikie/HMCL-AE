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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

/// Holds the proxy [`Authenticator`] that the HMCL side pushes down for the AI module's
/// `java.net.http.HttpClient` instances.
///
/// Why this exists: `java.net.http.HttpClient` does NOT consult
/// `Authenticator.setDefault(...)` (and `Authenticator` exposes no public `getDefault()` on
/// Java 17), so a username/password proxy configured in HMCL's settings fails every AI-side
/// request with 407 unless the authenticator is handed over explicitly. The dependency
/// direction HMCL → HMCLAI is the declared, legal one, so `ProxyManager.init()` and its
/// invalidation listener push the authenticator here (wiring is done by the proxy-linkage
/// batch; this class deliberately ships unwired), and each AI-side
/// `HttpClient.newBuilder()` chain adds `.authenticator(ProxyAuthenticatorHolder.getOrNoop())`.
///
/// How call sites should wire it: via [`#configure`], which attaches the authenticator ONLY
/// when one has actually been pushed. Attaching any authenticator — even a no-op — is NOT
/// behavior-neutral: the JDK `AuthenticationFilter` then insists on parsing a
/// `WWW-Authenticate`/`Proxy-Authenticate` challenge header on every 401/407 and throws
/// `IOException("WWW-Authenticate header missing")` when the server omits it, which most
/// API-key-authenticated LLM/search endpoints do on a plain 401. Without an authenticator the
/// same 401 simply surfaces to the caller. (That residual quirk still applies to users who DO
/// configure an authenticated proxy — unavoidable, and strictly better than the guaranteed 407.)
///
/// Shape of the "absent" value for the legacy [`#getOrNoop`] accessor:
/// [`java.net.http.HttpClient.Builder#authenticator`] throws `NullPointerException` for
/// `null`, so a non-null stand-in whose `getPasswordAuthentication()` returns `null` ("no
/// credentials available" per the [`Authenticator`] contract) is returned instead. It never
/// answers challenges and can never prompt — but see the caveat above: prefer [`#configure`].
///
/// Note: once a real authenticator is set, the `HttpClient` will use it for BOTH proxy (407)
/// and origin-server (401) challenges — same as HMCL's global `Authenticator.setDefault`
/// behavior for `HttpURLConnection`-based code, so no behavioral asymmetry is introduced.
///
/// Thread safety: a single `volatile` field; `set` happens on the settings thread while
/// readers are arbitrary worker threads.
@NotNullByDefault
public final class ProxyAuthenticatorHolder {

    private ProxyAuthenticatorHolder() {
    }

    /// The safe "no credentials" stand-in returned while nothing has been pushed.
    static final Authenticator NOOP = new NoopAuthenticator();

    @Nullable
    private static volatile Authenticator authenticator;

    /// Pushes the current proxy authenticator, or `null` when the user's proxy configuration
    /// has no credentials (clears back to the no-op).
    public static void set(@Nullable Authenticator value) {
        authenticator = value;
    }

    /// Returns the pushed authenticator, or the no-op stand-in (never `null`, so it can be
    /// chained unconditionally into `HttpClient.newBuilder().authenticator(...)`).
    /// Prefer [`#configure`], which leaves the client authenticator-free (and therefore
    /// tolerant of challenge-header-less 401s) until credentials are actually pushed.
    public static Authenticator getOrNoop() {
        Authenticator current = authenticator;
        return current != null ? current : NOOP;
    }

    /// Attaches the pushed proxy authenticator to `builder` — but only when HMCL has actually
    /// pushed one (i.e. the user configured a username/password proxy). Returns the same
    /// builder for chaining:
    ///
    /// ```java
    /// HttpClient client = ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()
    ///         .proxy(ProxySelector.getDefault())
    ///         .connectTimeout(...))
    ///         .build();
    /// ```
    ///
    /// This is the recommended wiring: an authenticator-free client surfaces bare 401/407
    /// responses to the caller, while a client with ANY authenticator attached (even a no-op)
    /// fails such responses with `IOException("WWW-Authenticate header missing")` when the
    /// server omits the challenge header — see the class docs.
    public static java.net.http.HttpClient.Builder configure(java.net.http.HttpClient.Builder builder) {
        Authenticator current = authenticator;
        if (current != null) {
            builder.authenticator(current);
        }
        return builder;
    }

    /// "No credentials available" per the [`Authenticator`] contract; equivalent to having no
    /// authenticator at all and can never prompt. The override widens visibility to `public`
    /// so tests can observe the contract directly.
    static final class NoopAuthenticator extends Authenticator {
        @Override
        @Nullable
        public PasswordAuthentication getPasswordAuthentication() {
            return null;
        }
    }
}
