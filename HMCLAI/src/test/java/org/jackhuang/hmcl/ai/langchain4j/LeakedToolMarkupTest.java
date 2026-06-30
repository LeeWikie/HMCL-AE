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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Some models (e.g. deepseek in streaming mode) leak their tool-call special tokens into the TEXT
/// content; {@link LangChain4jChatAdapter#stripLeakedToolMarkup} must drop that trailing markup so it
/// never reaches the chat UI / persisted history.
public final class LeakedToolMarkupTest {

    @Test
    void stripsTrailingDsmlToolCallBlock() {
        String leaked = "好的，开始操作！让我看看 HMCL 的账户配置文件结构。\n"
                + "<｜DSML｜tool_calls><｜DSML｜invoke name=\"glob\">"
                + "<｜DSML｜parameter name=\"pattern\" string=\"true\">.jar</｜DSML｜parameter>"
                + "</｜DSML｜invoke></｜DSML｜tool_calls>";
        assertEquals("好的，开始操作！让我看看 HMCL 的账户配置文件结构。",
                LangChain4jChatAdapter.stripLeakedToolMarkup(leaked));
    }

    @Test
    void leavesNormalTextUntouched() {
        String normal = "已为你安装 1.21.1 Fabric，可以启动了。代码示例：if (a < b) {...}";
        // The "< b" has a space after '<', not a vertical bar, so it must NOT be treated as markup.
        assertEquals(normal, LangChain4jChatAdapter.stripLeakedToolMarkup(normal));
    }

    @Test
    void handlesAsciiBarVariantAndEmpty() {
        assertEquals("看一下日志。", LangChain4jChatAdapter.stripLeakedToolMarkup("看一下日志。<|tool_calls>junk"));
        assertEquals("", LangChain4jChatAdapter.stripLeakedToolMarkup(""));
        assertEquals("", LangChain4jChatAdapter.stripLeakedToolMarkup("<｜tool_calls>only markup"));
    }
}
