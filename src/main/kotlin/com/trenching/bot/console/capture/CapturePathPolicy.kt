package com.trenching.bot.console.capture

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class CapturePathPolicy(val root: Path) {
    fun initialize() { verify(root.toAbsolutePath().normalize()); Files.createDirectories(root) }
    fun resolve(relative: String): Path {
        val reference = Path.of(relative)
        if (reference.isAbsolute || relative.contains(':') || relative.contains('\\') || relative.split('/').any { it == ".." || it == "." || it.isBlank() || forbidden(it) }) invalid()
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = normalizedRoot.resolve(reference).normalize()
        if (!target.startsWith(normalizedRoot) || target == normalizedRoot) invalid()
        verify(target)
        return target
    }
    fun independent(paths: List<String>) {
        val resolved = paths.map(::resolve)
        resolved.forEachIndexed { index, path -> resolved.drop(index + 1).forEach { other -> if (path.startsWith(other) || other.startsWith(path)) invalid() } }
    }
    private fun verify(path: Path) {
        var current = path.root
        for (part in path) {
            current = current.resolve(part)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || attributes.isOther || current.toRealPath() != current.toAbsolutePath().normalize()) invalid()
        }
    }
    private fun forbidden(name: String): Boolean {
        val lower = name.lowercase()
        return lower == ".env" || lower.startsWith(".env.") || lower.endsWith(".env") || lower.contains("secret") || lower.contains("keypair") || lower.endsWith(".pem") || lower.endsWith(".key")
    }
    private fun invalid(): Nothing = throw CaptureRequestException(400, "INVALID_PATH", "Use distinct directories within the configured capture root without links or traversal")
}
