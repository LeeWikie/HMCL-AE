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
package org.jackhuang.hmcl.ai.kb;

/// Where the knowledge base gets its documents and, crucially, WHO embeds the query.
///
/// This distinction is load-bearing: the shipped Minecraft KB's dense index was built with one
/// specific embedding model, so a query embedded by a different model lands in an incompatible
/// vector space and retrieval silently returns garbage. Keeping the two modes explicit is what
/// lets the embedding-model picker be honest rather than decorative — see the RAG integration plan.
public enum KbSourceMode {
    /// Retrieval runs on the remote FastAPI server, which owns BOTH the index and the query
    /// embedding — so there is no vector-space mismatch and no local embedding model is required.
    /// This is the default and the fully-wired path for the shipped Minecraft knowledge base.
    REMOTE_HTTP,

    /// Retrieval runs in-process against a local index; the configured embedding model embeds the
    /// query and MUST be the same model that built the index (enforced by a dimension check).
    LOCAL_INDEX
}
