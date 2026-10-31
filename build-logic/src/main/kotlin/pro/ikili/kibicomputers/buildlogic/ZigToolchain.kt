package pro.ikili.kibicomputers.buildlogic

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * 使う Zig の配布物。`gradle/zig.properties` で固定したバージョンと、実行中の OS・CPU に合うアーカイブを指す。
 *
 * @property version Zig のバージョン（例: `0.17.0`）
 * @property platform 配布物の名前に使う `<arch>-<os>`（例: `x86_64-windows`）
 * @property sha256 アーカイブの SHA-256（16進の小文字）
 */
data class ZigDistribution(
    val version: String,
    val platform: String,
    val sha256: String,
) {
    private val isWindows: Boolean get() = platform.endsWith("-windows")

    /** 公式配布のアーカイブの URL。 */
    val url: String
        get() = "https://ziglang.org/download/$version/zig-$platform-$version.${if (isWindows) "zip" else "tar.xz"}"

    /** 展開したディレクトリの中の実行ファイルの名前。 */
    val executableName: String get() = if (isWindows) "zig.exe" else "zig"

    companion object {
        /** [file]（`gradle/zig.properties`）を読み、実行中のホストに合う配布物を返す。 */
        fun load(file: File): ZigDistribution {
            val props = Properties()
            file.reader(Charsets.UTF_8).use(props::load)
            val version = props.getProperty("version")
                ?: throw GradleException("$file に version がありません")
            val platform = hostPlatform()
            val sha256 = props.getProperty("sha256.$platform")
                ?: throw GradleException("$file に $platform 用の Zig の SHA-256（sha256.$platform）がありません")
            return ZigDistribution(version, platform, sha256.lowercase())
        }

        /** Zig の配布物の名前で使う `<arch>-<os>` を、実行中の JVM から決める。 */
        fun hostPlatform(): String {
            val osName = System.getProperty("os.name").lowercase()
            val os = when {
                osName.startsWith("windows") -> "windows"
                osName.startsWith("mac") || osName.startsWith("darwin") -> "macos"
                osName.startsWith("linux") -> "linux"
                else -> throw GradleException("Zig を取得できない OS です: $osName")
            }
            val arch = when (val a = System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw GradleException("Zig を取得できない CPU です: $a")
            }
            return "$arch-$os"
        }
    }
}

/**
 * Zig を公式配布から取得し、SHA-256 を確かめてから [ZigDistribution] ごとのディレクトリに展開する。
 *
 * 同じ Gradle ホームを複数のビルド（worktree など）が同時に使っても壊れないよう、
 * JVM 内では synchronized、プロセス間ではファイルロックで排他する。
 */
internal object ZigInstaller {
    private const val MARKER = ".kibicomputers-installed"
    private val jvmLock = Any()

    /**
     * [toolchainsDir] の下に [dist] を用意し、`zig` 実行ファイルを返す。用意済みならダウンロードしない。
     */
    fun ensure(toolchainsDir: File, dist: ZigDistribution, logger: Logger): File = synchronized(jvmLock) {
        val name = "zig-${dist.platform}-${dist.version}"
        val root = toolchainsDir.toPath().resolve("zig")
        val installDir = root.resolve(name)
        val executable = installDir.resolve(dist.executableName).toFile()
        if (isInstalled(installDir, dist)) return executable

        Files.createDirectories(root)
        FileChannel.open(root.resolve("$name.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                // ロックを待つ間に別のプロセスが入れ終えたかもしれない
                if (isInstalled(installDir, dist)) return executable

                val archive = root.resolve("$name.download")
                val staging = root.resolve("$name.staging")
                try {
                    logger.lifecycle("Zig ${dist.version} (${dist.platform}) を ${dist.url} から取得します")
                    download(dist, archive)
                    deleteRecursively(staging)
                    Files.createDirectories(staging)
                    if (dist.url.endsWith(".zip")) extractZip(archive, staging) else extractTarXz(archive, staging)
                    if (!Files.isRegularFile(staging.resolve(dist.executableName))) {
                        throw GradleException("展開した Zig に ${dist.executableName} がありません: ${dist.url}")
                    }
                    deleteRecursively(installDir)
                    Files.move(staging, installDir, StandardCopyOption.ATOMIC_MOVE)
                    Files.writeString(installDir.resolve(MARKER), dist.sha256)
                } finally {
                    Files.deleteIfExists(archive)
                    deleteRecursively(staging)
                }
            }
        }
        executable
    }

    private fun isInstalled(installDir: Path, dist: ZigDistribution): Boolean {
        val marker = installDir.resolve(MARKER)
        return Files.isRegularFile(marker) &&
            Files.readString(marker).trim() == dist.sha256 &&
            Files.isRegularFile(installDir.resolve(dist.executableName))
    }

    private fun download(dist: ZigDistribution, target: Path) {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create(dist.url))
            .header("User-Agent", "kibicomputers-build")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw GradleException("Zig の取得に失敗しました（HTTP ${response.statusCode()}）: ${dist.url}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(response.body(), digest).use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != dist.sha256) {
            throw GradleException(
                "Zig のアーカイブの SHA-256 が合いません: ${dist.url}\n  期待: ${dist.sha256}\n  実際: $actual",
            )
        }
    }

    /** 先頭のディレクトリ（`zig-<platform>-<version>/`）を取り除いた相対パス。取り除くと空なら null。 */
    private fun stripFirst(entryName: String): String? =
        entryName.replace('\\', '/').substringAfter('/', "").trimEnd('/').ifEmpty { null }

    private fun resolveInside(dir: Path, relative: String): Path {
        val path = dir.resolve(relative).normalize()
        if (!path.startsWith(dir)) throw GradleException("アーカイブに不正なパスがあります: $relative")
        return path
    }

    private fun extractZip(archive: Path, dir: Path) {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = stripFirst(entry.name) ?: continue
                val path = resolveInside(dir, relative)
                if (entry.isDirectory) {
                    Files.createDirectories(path)
                } else {
                    writeFile(zip, path)
                }
            }
        }
    }

    private fun extractTarXz(archive: Path, dir: Path) {
        TarArchiveInputStream(XZInputStream(BufferedInputStream(Files.newInputStream(archive)))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val relative = stripFirst(entry.name) ?: continue
                val path = resolveInside(dir, relative)
                when {
                    entry.isDirectory -> Files.createDirectories(path)
                    entry.isSymbolicLink -> {
                        Files.createDirectories(path.parent)
                        Files.createSymbolicLink(path, Path.of(entry.linkName))
                    }
                    entry.isFile -> {
                        writeFile(tar, path)
                        if (entry.mode and "111".toInt(8) != 0) makeExecutable(path)
                    }
                }
            }
        }
    }

    private fun writeFile(input: InputStream, path: Path) {
        Files.createDirectories(path.parent)
        Files.newOutputStream(path).use { input.copyTo(it) }
    }

    private fun makeExecutable(path: Path) {
        runCatching {
            val perms = Files.getPosixFilePermissions(path) + setOf(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE,
            )
            Files.setPosixFilePermissions(path, perms)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.exists(path)) path.toFile().deleteRecursively()
    }
}
