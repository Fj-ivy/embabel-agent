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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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

    @Test
    fun `podman runtime error is a container startup failure`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        assertTrue(
            engine.isContainerStartupFailure(
                exitCode = 125,
                stdout = "",
                stderr = "Error: preparing container for attach: OCI runtime error",
            )
        )
    }

    @Test
    fun `docker runtime error is a container startup failure`() {
        val engine = TestContainerEngine(
            root = System.getProperty("java.io.tmpdir"),
            workDir = "/work",
            containerCommand = "docker",
        )

        assertTrue(
            engine.isContainerStartupFailure(
                exitCode = 126,
                stdout = "",
                stderr = "docker: Error response from daemon: failed to create task",
            )
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `container runtime startup error returns Failure`() {
        val root = Files.createTempDirectory("container-startup-failure-test-")
        val runtime = root.resolve("fake-podman")
        val scripts = root.resolve("scripts").also { Files.createDirectories(it) }
        Files.writeString(
            runtime,
            """
                |#!/bin/sh
                |if [ "${'$'}1" = "version" ]; then
                |  exit 0
                |fi
                |echo "Error: preparing container for attach: OCI runtime error" >&2
                |exit 125
            """.trimMargin(),
        )
        assertTrue(runtime.toFile().setExecutable(true))
        Files.writeString(scripts.resolve("test.sh"), "echo should-not-run")

        try {
            val engine = TestContainerEngine(root.toString(), "/work", runtime.toString())
            val script = SkillScript("test", "test.sh", ScriptLanguage.BASH, root)

            val result = engine.execute(script)

            assertTrue(result is ScriptExecutionResult.Failure, "Expected Failure but got: $result")
            val failure = result as ScriptExecutionResult.Failure
            assertEquals(125, failure.exitCode)
            assertEquals("Podman failed to start the script", failure.error)
            assertTrue(failure.stderr!!.contains("OCI runtime error"))
            assertFalse(failure.timedOut)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing interpreter is a container startup failure`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        assertTrue(
            engine.isContainerStartupFailure(
                exitCode = 127,
                stdout = "",
                stderr = "--: 1: exec: python3: not found",
            )
        )
    }

    @Test
    fun `script non-zero exits remain successful executions`() {
        val engine = TestContainerEngine(System.getProperty("java.io.tmpdir"), "/work")

        for (exitCode in 125..127) {
            assertFalse(
                engine.isContainerStartupFailure(
                    exitCode = exitCode,
                    stdout = "",
                    stderr = "script reported an expected error",
                ),
                "Exit code $exitCode without a runtime diagnostic must remain a script result",
            )
        }
    }

    @Test
    fun `runtime check timeout destroys process and returns false`() {
        val process = mockk<Process>()
        every { process.waitFor(5, TimeUnit.SECONDS) } returns false
        every { process.destroyForcibly() } returns process

        val result = AbstractContainerSkillScriptExecutionEngine.processCompletesSuccessfully(process)

        assertFalse(result)
        verify(exactly = 1) { process.destroyForcibly() }
    }

    @Test
    fun `interrupted runtime check destroys process and restores interrupt`() {
        val process = mockk<Process>()
        every { process.waitFor(5, TimeUnit.SECONDS) } throws InterruptedException("test interruption")
        every { process.destroyForcibly() } returns process

        try {
            val result = AbstractContainerSkillScriptExecutionEngine.processCompletesSuccessfully(process)

            assertFalse(result)
            assertTrue(Thread.currentThread().isInterrupted)
            verify(exactly = 1) { process.destroyForcibly() }
        } finally {
            Thread.interrupted()
        }
    }

    private class TestContainerEngine(
        root: String,
        workDir: String,
        override val containerCommand: String = "podman",
    ) : AbstractContainerSkillScriptExecutionEngine(
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
        override val containerName = "Podman"
        override val tempDirPrefix = "test-container-"
        override val daemonErrorMessage = "unavailable"
        override val useWorkdir = false
    }
}
