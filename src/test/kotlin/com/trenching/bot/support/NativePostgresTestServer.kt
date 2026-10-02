package com.trenching.bot.support

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NativePostgresTestServer private constructor(
    private val binaries: Path,
    private val directory: Path,
    val port: Int,
) : AutoCloseable {
    val jdbcUrl: String get() = "jdbc:postgresql://127.0.0.1:$port/postgres"

    override fun close() {
        command(binaries, directory.resolve("stop.log"), "pg_ctl", "-D", directory.resolve("data").toString(),
            "-m", "fast", "-w", "-t", "20", "stop")
    }

    companion object {
        const val USERNAME = "trenching_test"
        const val PASSWORD = ""

        fun start(binaryDirectory: String): NativePostgresTestServer {
            val binaries = Path.of(binaryDirectory).toAbsolutePath().normalize()
            val root = Path.of("build", "native-postgres-tests").toAbsolutePath().normalize()
            Files.createDirectories(root)
            val directory = Files.createTempDirectory(root, "cluster-")
            val version = directory.resolve("version.log")
            command(binaries, version, "postgres", "--version")
            require(Files.readString(version).contains("PostgreSQL) 17.")) { "Native test runtime must be PostgreSQL 17" }
            command(binaries, directory.resolve("init.log"), "initdb", "-D", directory.resolve("data").toString(),
                "--username=$USERNAME", "--auth-local=trust", "--auth-host=trust", "--encoding=UTF8", "--locale=C")
            val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            command(binaries, directory.resolve("start.log"), "pg_ctl", "-D", directory.resolve("data").toString(),
                "-l", directory.resolve("postgres.log").toString(), "-o", "-h 127.0.0.1 -p $port",
                "-w", "-t", "20", "start")
            return NativePostgresTestServer(binaries, directory, port)
        }

        private fun command(binaries: Path, log: Path, name: String, vararg arguments: String) {
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            val executable = binaries.resolve(name + suffix)
            require(Files.isRegularFile(executable)) { "Missing PostgreSQL test binary: $name" }
            val process = ProcessBuilder(listOf(executable.toString()) + arguments)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("PostgreSQL test command timed out: $name; diagnostics=$log")
            }
            check(process.exitValue() == 0) { "PostgreSQL test command failed: $name; diagnostics=$log" }
        }
    }
}
