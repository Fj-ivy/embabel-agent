/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.skills.script

import com.embabel.agent.tools.file.FileTools
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class AbstractContainerSkillScriptExecutionEngineTest {

    @Test
    fun `podman workdir is passed as a literal positional argument`() {
        val root = Files.createTempDirectory("container-command-test-")
        val workDir = "/data/\$(id -u)/a\"b"
        val engine = TestContainerEngine(root.toString(), workDir)

        try {
            val command = engine.buildContainerCommand(
                command = listOf("python3", "/script/test.py"),
                scriptDir = root.resolve("script"),
                inputDir = root.resolve("input"),
                outputDir = root.resolve("output"),
                containerInstanceName = "test-container",
            )

            assertEquals("test-container", command[command.indexOf("--name") + 1])
            assertEquals("mkdir -p \"\$1\" && cd \"\$1\" && shift && exec \"\$@\"", command[command.indexOf("-c") + 1])
            assertTrue(command.contains(workDir))
            assertFalse(command[command.indexOf("-c") + 1].contains(workDir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class TestContainerEngine(root: String, workDir: String) : AbstractContainerSkillScriptExecutionEngine(
        image = "test-image",
        timeout = 1.seconds,
        supportedLanguages = ScriptLanguage.entries.toSet(),
        networkEnabled = false,
        memoryLimit = null,
        cpuLimit = null,
        environment = emptyMap(),
        workDir = workDir,
        user = null,
        fileTools = FileTools.readWrite(root),
    ) {
        override val containerCommand = "podman"
        override val containerName = "Podman"
        override val tempDirPrefix = "test-container-"
        override val daemonErrorMessage = "unavailable"
        override val useWorkdir = false
    }
}
