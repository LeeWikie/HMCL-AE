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
package org.jackhuang.hmcl.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.EditTool;
import org.jackhuang.hmcl.ai.tools.ReadLedger;
import org.jackhuang.hmcl.ai.tools.ToolConfirmHandler;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the small-file ASK→ALLOW downgrade (spec 条目1, product decision 2026-07-11): editing a
/// small file auto-backs-up and skips the confirmation; a large file still asks; a failed backup
/// falls back to ASK; an inherently-lossless action (mods_toggle) skips ASK with no backup; and a
/// CRITICAL delete is completely unaffected (still red-confirmed).
public final class AskBackupDowngradeTest {

    /// Auto policy with dangerous confirmation ENABLED — so a CONTROLLED_WRITE edit/remove is a
    /// ⚠️ASK (the tier this feature relaxes) and a DANGEROUS_WRITE delete is an ASK + red confirm.
    private static AiExecutionPolicy strictPolicy() {
        return new AiExecutionPolicy(AiApprovalMode.AUTO, true);
    }

    private static ToolExecutionRequest editReq(String path, String oldStr, String newStr) {
        return ToolExecutionRequest.builder().name("edit")
                .arguments("{\"path\":\"" + path + "\",\"old_string\":\"" + oldStr
                        + "\",\"new_string\":\"" + newStr + "\"}")
                .build();
    }

    /// Registers a real {@link EditTool} rooted at {@code root} with an isolated {@link ReadLedger}
    /// that already recorded a read of {@code file} (so the edit's read-precondition is satisfied).
    private static ToolRegistry registryWithEdit(Path root, Path file) throws IOException {
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        ToolRegistry r = new ToolRegistry();
        r.register(new EditTool(root, ledger));
        return r;
    }

    @Test
    void smallFileEditIsAutoBackedUpAndNotAsked(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter =
                new LangChain4jToolAdapter(registryWithEdit(root, file), strictPolicy(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(editReq("a.txt", "world", "there"));

        assertEquals(0, confirms.get(), "a small-file edit must NOT prompt once it can be auto-backed-up");
        assertEquals("hello there", Files.readString(file), "the edit must have applied");
        Path bak = root.resolve("a.txt.bak");
        assertTrue(Files.exists(bak), "a restore point must have been written");
        assertEquals("hello world", Files.readString(bak), "the backup must hold the pre-edit content");
        assertTrue(result.text().contains("已自动备份"), "the receipt must note the backup: " + result.text());
    }

    @Test
    void largeFileEditStillAsks(@TempDir Path root) throws IOException {
        Path file = root.resolve("big.txt");
        Files.writeString(file, "world " + "x".repeat(1_100_000)); // > 1 MiB
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter =
                new LangChain4jToolAdapter(registryWithEdit(root, file), strictPolicy(), handler, handler);

        adapter.execute(editReq("big.txt", "world", "earth"));

        assertEquals(1, confirms.get(), "an over-threshold file is too big to snapshot — it must still ask");
        assertFalse(Files.exists(root.resolve("big.txt.bak")), "no snapshot for an over-threshold file");
    }

    @Test
    void backupFailureFallsBackToAsk(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        // Block the .bak destination with a non-empty directory so the snapshot deterministically
        // fails — the downgrade must then fall back to the original ASK.
        Path blocker = root.resolve("a.txt.bak");
        Files.createDirectory(blocker);
        Files.writeString(blocker.resolve("keep"), "x");
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter =
                new LangChain4jToolAdapter(registryWithEdit(root, file), strictPolicy(), handler, handler);

        adapter.execute(editReq("a.txt", "world", "there"));

        assertEquals(1, confirms.get(), "a failed backup must fall back to asking");
    }

    // ---- Inherently-lossless actions (mods_toggle / clean_logs): ASK dropped with NO backup ----

    /// Minimal `instance` domain-tool stub: CONTROLLED_WRITE for mods_toggle (the lossless case) and
    /// DANGEROUS_WRITE for delete (the CRITICAL case). Deliberately does NOT implement
    /// BackupTargetResolver, proving the lossless downgrade needs no backup machinery.
    private static final class StubInstanceTool implements ToolSpec {
        @Override public String getName() { return "instance"; }
        @Override public String getDescription() { return "instance domain stub"; }
        @Override public ToolSource getSource() { return ToolSource.LOCAL; }
        @Override public boolean supportsStructuredSchema() { return true; }
        @Override public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"}}}";
        }
        @Override public ToolPermission getPermission(Map<String, Object> parameters) {
            Object a = parameters.get("action");
            String action = a != null ? a.toString().trim().toLowerCase(Locale.ROOT) : "";
            return "delete".equals(action) ? ToolPermission.DANGEROUS_WRITE : ToolPermission.CONTROLLED_WRITE;
        }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            Object a = parameters.get("action");
            return ToolResult.success("did:" + (a != null ? a : ""));
        }
    }

    private static ToolRegistry registryWithInstance() {
        ToolRegistry r = new ToolRegistry();
        r.register(new StubInstanceTool());
        return r;
    }

    @Test
    void losslessModsToggleIsNotAskedAndNotBackedUp() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter =
                new LangChain4jToolAdapter(registryWithInstance(), strictPolicy(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder().name("instance")
                .arguments("{\"action\":\"mods_toggle\",\"mod\":\"x.jar\",\"enable\":false}").build());

        assertEquals(0, confirms.get(), "mods_toggle is inherently reversible — no prompt");
        assertTrue(result.text().contains("did:mods_toggle"), "it must still run: " + result.text());
        assertFalse(result.text().contains("已自动备份"), "a lossless action takes no backup");
    }

    // ---- CRITICAL delete stays fully gated (both the ordinary ASK and the red confirm fire) ----

    @Test
    void criticalDeleteInstanceStillRedConfirms() {
        AtomicInteger normalConfirms = new AtomicInteger();
        AtomicInteger redConfirms = new AtomicInteger();
        ToolConfirmHandler normal = (name, summary) -> { normalConfirms.incrementAndGet(); return true; };
        ToolConfirmHandler red = (name, summary) -> { redConfirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter =
                new LangChain4jToolAdapter(registryWithInstance(), strictPolicy(), normal, red);

        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder().name("instance")
                .arguments("{\"action\":\"delete\",\"instance\":\"foo\"}").build());

        assertEquals(1, normalConfirms.get(), "a DANGEROUS_WRITE delete must still hit the ordinary confirm");
        assertEquals(1, redConfirms.get(), "a CRITICAL delete must still hit the red confirm — never downgraded");
        assertTrue(result.text().contains("did:delete"), "confirmed delete still runs: " + result.text());
    }
}
