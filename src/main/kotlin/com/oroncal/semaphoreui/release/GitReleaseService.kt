package com.oroncal.semaphoreui.release

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Path

object GitReleaseService {
    fun resolveRepositoryRoot(moduleDir: Path): Path {
        val output = runGit(moduleDir, "rev-parse", "--show-toplevel")
        return Path.of(output.stdout)
    }

    fun resolvePushTarget(repoRoot: Path): GitPushTarget {
        val currentBranch = runGit(repoRoot, "branch", "--show-current").stdout
        if (currentBranch.isBlank()) {
            throw ReleaseException("Could not determine the current Git branch. Releases cannot be pushed from detached HEAD.")
        }

        val remoteName = runGit(
            repoRoot,
            "config",
            "--get",
            "branch.$currentBranch.remote",
            failOnNonZero = false,
        ).stdout
        val remoteBranchRef = runGit(
            repoRoot,
            "config",
            "--get",
            "branch.$currentBranch.merge",
            failOnNonZero = false,
        ).stdout
        if (remoteName.isBlank() || remoteBranchRef.isBlank()) {
            throw ReleaseException(
                "Current branch '$currentBranch' does not have an upstream configured. Configure a remote tracking branch before releasing.",
            )
        }

        return GitPushTarget(
            localBranchName = currentBranch,
            remoteName = remoteName,
            remoteBranchRef = remoteBranchRef,
        )
    }

    fun hasUncommittedChanges(repoRoot: Path): Boolean {
        val output = runGit(repoRoot, "status", "--porcelain")
        return output.stdout.isNotBlank()
    }

    fun ensureValidTagName(repoRoot: Path, tagName: String) {
        runGit(repoRoot, "check-ref-format", "refs/tags/$tagName")
    }

    fun ensureTagDoesNotExist(repoRoot: Path, tagName: String) {
        val output = runGit(repoRoot, "rev-parse", "-q", "--verify", "refs/tags/$tagName", failOnNonZero = false)
        if (output.exitCode == 0) {
            throw ReleaseException("Tag '$tagName' already exists.")
        }
    }

    fun createCommitAndTag(
        repoRoot: Path,
        relativePomPath: String,
        commitMessage: String,
        tagName: String,
        releasedVersion: String,
    ) {
        runGit(repoRoot, "add", "--", relativePomPath)
        val commitOutput = runGit(
            repoRoot,
            "commit",
            "--only",
            "-m",
            commitMessage,
            "--",
            relativePomPath,
        )

        try {
            runGit(repoRoot, "tag", tagName)
        } catch (throwable: Throwable) {
            throw ReleaseException(
                "The release commit for version $releasedVersion was created, but tag '$tagName' could not be created.",
                throwable,
            )
        }

        if (commitOutput.stdout.isBlank()) {
            throw ReleaseException("Git created the commit without standard output; inspect the repository before continuing.")
        }
    }

    fun pushCommitAndTag(
        repoRoot: Path,
        pushTarget: GitPushTarget,
        tagName: String,
        releasedVersion: String,
    ) {
        try {
            runGit(
                repoRoot,
                "push",
                pushTarget.remoteName,
                "HEAD:${pushTarget.remoteBranchRef}",
            )
        } catch (throwable: Throwable) {
            throw ReleaseException(
                "The release commit and tag for version $releasedVersion were created locally, but the branch push to '${pushTarget.remoteName}' failed.",
                throwable,
            )
        }

        try {
            runGit(
                repoRoot,
                "push",
                pushTarget.remoteName,
                "refs/tags/$tagName",
            )
        } catch (throwable: Throwable) {
            throw ReleaseException(
                "The release commit was pushed, but tag '$tagName' could not be pushed to remote '${pushTarget.remoteName}'.",
                throwable,
            )
        }
    }

    fun relativePath(repoRoot: Path, file: Path): String {
        val relative = FileUtil.getRelativePath(repoRoot.toString(), file.toString(), '/')
        return relative ?: throw ReleaseException("Could not calculate the pom.xml path relative to the Git repository root.")
    }

    private fun runGit(
        workingDir: Path,
        vararg parameters: String,
        failOnNonZero: Boolean = true,
    ): GitCommandResult {
        val commandLine = GeneralCommandLine("git")
            .withWorkDirectory(workingDir.toFile())
            .withParameters(parameters.toList())
        val output = try {
            CapturingProcessHandler(commandLine).runProcess(60_000)
        } catch (exception: ExecutionException) {
            throw ReleaseException("Could not execute Git. Verify that it is available on this system.", exception)
        }

        if (failOnNonZero && output.exitCode != 0) {
            val details = output.stderr.trim().ifBlank { output.stdout.trim() }
            val suffix = if (details.isBlank()) "" else ": $details"
            throw ReleaseException("Git command failed: git ${parameters.joinToString(" ")}$suffix")
        }

        return GitCommandResult(
            exitCode = output.exitCode,
            stdout = output.stdout.trim(),
            stderr = output.stderr.trim(),
        )
    }
}

data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class GitPushTarget(
    val localBranchName: String,
    val remoteName: String,
    val remoteBranchRef: String,
)
