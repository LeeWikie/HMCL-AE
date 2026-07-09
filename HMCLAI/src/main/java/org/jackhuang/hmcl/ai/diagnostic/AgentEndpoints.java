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
package org.jackhuang.hmcl.ai.diagnostic;

/// Single source of truth for HMCL-AE's service addresses.
///
/// Alpha/Beta: the domain is hardcoded here directly. The domain won't change within a year
/// (DNS is managed manually) — long enough to reach the stable release. The stable release will
/// replace this constant with a GitHub-hosted pointer fetch so the domain can rotate without a
/// client rebuild; when that happens, only this class changes.
public final class AgentEndpoints {

    private AgentEndpoints() {
    }

    /// Where the one-tap diagnostic (agent trace) upload is POSTed. Matches the HMCL-AE-Site
    /// server's `POST /api/feedback` contract (zip body, ≤10MB, returns {ok, id}).
    public static final String FEEDBACK_URL = "https://agentexperience.online/api/feedback";
}
