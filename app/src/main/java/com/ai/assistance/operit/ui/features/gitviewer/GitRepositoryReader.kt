package com.ai.assistance.operit.ui.features.gitviewer

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.Inflater

/** 一个提交在界面上需要的字段。 */
data class GitCommitInfo(
    val hash: String,
    val shortHash: String,
    val author: String,
    val timestampSeconds: Long,
    val subject: String,
    val body: String,
) {
    fun formattedTime(zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(
            Instant.ofEpochSecond(timestampSeconds)
        )
}

/** 仓库概览：当前分支、全部分支、提交历史。 */
data class GitRepositorySnapshot(
    val rootPath: String,
    val headRef: String,
    val currentBranch: String?,
    val branches: List<String>,
    val commits: List<GitCommitInfo>,
)

/**
 * 只读的 .git 解析器。
 *
 * 为什么不调 git 命令：设备的 Android 环境里没有保证存在的 git 可执行文件，
 * 写"有 git 就用、没有就退化成别的"正是工程规范里禁止的兜底逻辑。
 * 这里直接按 Git 的对象格式读：refs 是纯文本，对象是 zlib 压缩的
 * "<type> <size>\0<content>"。只读查看器需要的分支与提交历史都能这么拿到。
 *
 * 不支持：packfile 里的对象（只在全部对象已打包的仓库出现）、worktree 状态、diff。
 * 这些是后续工作，不在本页声称支持。
 */
object GitRepositoryReader {

    fun open(rootPath: String): Result<GitRepositorySnapshot> = runCatching {
        val root = File(rootPath)
        require(root.exists()) { "路径不存在：$rootPath" }
        val gitDir = resolveGitDir(root) ?: error("这里不是 Git 仓库（找不到 .git）：$rootPath")
        val headText = File(gitDir, "HEAD").takeIf { it.isFile }?.readText()?.trim()
            ?: error("HEAD 缺失，仓库可能已损坏")

        val headRef =
            if (headText.startsWith("ref:")) headText.removePrefix("ref:").trim() else headText
        val currentBranch =
            if (headRef.startsWith("refs/heads/")) headRef.removePrefix("refs/heads/") else null
        val headHash = resolveRef(gitDir, headRef)

        val branches = readBranches(gitDir)
        val commits =
            if (headHash == null) {
                emptyList()
            } else {
                walkHistory(gitDir, headHash, limit = 40)
            }

        GitRepositorySnapshot(
            rootPath = root.absolutePath,
            headRef = headRef,
            currentBranch = currentBranch,
            branches = branches,
            commits = commits,
        )
    }

    private fun resolveGitDir(root: File): File? {
        val dotGit = File(root, ".git")
        if (dotGit.isDirectory) return dotGit
        if (dotGit.isFile) {
            // worktree / submodule：.git 是一个指向真实目录的文本文件
            val line = dotGit.readText().trim()
            if (line.startsWith("gitdir:")) {
                val target = line.removePrefix("gitdir:").trim()
                val resolved = File(target).let { if (it.isAbsolute) it else File(root, target) }
                if (resolved.isDirectory) return resolved
            }
        }
        // 允许直接传 .git 目录
        return if (root.name == ".git" && root.isDirectory) root else null
    }

    private fun resolveRef(gitDir: File, ref: String): String? {
        val loose = File(gitDir, ref)
        if (loose.isFile) {
            val value = loose.readText().trim()
            return value.takeIf { it.length >= 40 }
        }
        val packed = File(gitDir, "packed-refs")
        if (packed.isFile) {
            // 用显式循环而不是 forEachLine：非局部 return 在 lambda 里是被禁止的
            for (line in packed.readLines()) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) continue
                val parts = line.trim().split(' ')
                if (parts.size == 2 && parts[1] == ref) return parts[0]
            }
        }
        return null
    }

    private fun readBranches(gitDir: File): List<String> {
        val names = linkedSetOf<String>()
        File(gitDir, "refs/heads").listFiles()?.forEach { names.add(it.name) }
        val packed = File(gitDir, "packed-refs")
        if (packed.isFile) {
            packed.forEachLine { line ->
                if (line.startsWith("refs/heads/")) {
                    names.add(line.trim().substringAfter("refs/heads/"))
                }
            }
        }
        return names.sorted()
    }

    private fun walkHistory(gitDir: File, startHash: String, limit: Int): List<GitCommitInfo> {
        val result = ArrayList<GitCommitInfo>(limit)
        var next: String? = startHash
        while (next != null && result.size < limit) {
            val commit = readCommit(gitDir, next) ?: break
            result.add(commit.info)
            next = commit.parentHash
        }
        return result
    }

    private fun readCommit(gitDir: File, hash: String): ParsedCommit? {
        val raw = readObject(gitDir, hash) ?: return null
        val separator = raw.indexOf('\u0000')
        if (separator < 0) return null
        val header = raw.substring(0, separator)
        if (!header.startsWith("commit")) return null
        val body = raw.substring(separator + 1)

        var parent: String? = null
        var author = ""
        var timestamp = 0L
        val messageLines = ArrayList<String>()
        var inMessage = false

        body.split('\n').forEach { line ->
            when {
                inMessage -> messageLines.add(line)
                line.isEmpty() -> inMessage = true
                line.startsWith("parent ") -> parent = line.removePrefix("parent ").trim()
                line.startsWith("author ") -> {
                    val value = line.removePrefix("author ").trim()
                    val lastSpace = value.lastIndexOf(' ')
                    if (lastSpace > 0) {
                        val identity = value.substring(0, lastSpace)
                        val timePart = value.substring(lastSpace + 1)
                        author = identity.substringBefore(" <").ifBlank { identity }
                        timestamp = timePart.toLongOrNull() ?: 0L
                    }
                }
            }
        }

        val subject = messageLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val rest = messageLines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()

        return ParsedCommit(
            info =
                GitCommitInfo(
                    hash = hash,
                    shortHash = hash.take(7),
                    author = author,
                    timestampSeconds = timestamp,
                    subject = subject,
                    body = rest,
                ),
            parentHash = parent,
        )
    }

    private data class ParsedCommit(val info: GitCommitInfo, val parentHash: String?)

    /** 读取并解析一个 loose object。packfile 里的对象这里读不到。 */
    private fun readObject(gitDir: File, hash: String): String? {
        val path = File(gitDir, "objects/" + hash.substring(0, 2) + "/" + hash.substring(2))
        if (!path.isFile) return null
        val compressed = path.readBytes()
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val buffer = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream()
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0 && inflater.needsInput()) break
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }
}
