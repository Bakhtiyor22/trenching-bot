package com.trenching.bot.support

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The directory links tests make to show that capture code refuses, or never follows, a link. */
object DirectoryLinks {
    /**
     * Creates [link] to the existing directory [target]: a symbolic link where the account may create one, otherwise on Windows
     * a directory junction. Windows grants symbolic links only with Developer Mode or administrator rights, but any account can
     * make a junction, so a junction is the link a capture directory is most likely to meet there. Java reads a junction as a
     * directory that is also "other" (a reparse point), never as a symbolic link. Skips the test when neither link can be made.
     */
    fun create(link: Path, target: Path): Path {
        val refused = try { return Files.createSymbolicLink(link, target) } catch (failure: IOException) { failure } catch (failure: UnsupportedOperationException) { failure }
        assumeTrue(System.getProperty("os.name").startsWith("Windows")) { "symbolic links are unavailable: $refused" }
        val junction = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        val output = junction.inputStream.use { String(it.readAllBytes()).trim() }
        assumeTrue(junction.waitFor() == 0 && Files.isSameFile(link, target)) { "directory junctions are unavailable: $output" }
        return link
    }
}
