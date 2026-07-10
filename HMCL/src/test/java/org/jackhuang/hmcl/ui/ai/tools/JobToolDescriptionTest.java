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

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the `job(action="list")` hidden-acknowledge disclosure (T15 / prompt-contracts #7):
/// listing a finished job marks it acknowledged (see {@link ListJobsTool}), which suppresses its
/// automatic completion notification. The description must warn about this so the model does not
/// "just glance" at list and then silently lose a finished job's result.
public final class JobToolDescriptionTest {

    private final JobTool tool = new JobTool();

    @Test
    void descriptionDisclosesListAcknowledgeSideEffect() {
        String description = tool.getDescription();
        assertTrue(description.contains("acknowledged"),
                "description must state list marks finished jobs acknowledged: " + description);
        assertTrue(description.contains("will NOT fire again"),
                "description must warn the auto-notification will not fire again: " + description);
        assertTrue(description.contains("check(jobId)"),
                "description must direct the model to call check(jobId) to keep the result: " + description);
    }
}
