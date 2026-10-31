package pro.ikili.kibicomputers.buildlogic

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.GradleException
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** アーカイブの取得（SHA-256 の検証つき）と展開。Zig と、WASM にビルドする C のソース（Lua など）で使う。 */
internal object Archives {
    /** [url] を [target] に取得し、SHA-256 が [sha256] と合わなければ失敗する。[what] はエラーメッセージに使う名前。 */
    fun download(url: String, sha256: String, target: Path, what: String) {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "kibicomputers-build")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw GradleException("$what の取得に失敗しました（HTTP ${response.statusCode()}）: $url")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(response.body(), digest).use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.lowercase()) {
            Files.deleteIfExists(target)
            throw GradleException(
                "$what のアーカイブの SHA-256 が合いません: $url\n  期待: $sha256\n  実際: $actual",
            )
        }
    }

    /** [path] の SHA-256（16進の小文字）。 */
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path), digest).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 先頭のディレクトリ（`zig-<platform>-<version>/` など）を取り除いた相対パス。取り除くと空なら null。 */
    private fun stripFirst(entryName: String): String? =
        entryName.replace('\\', '/').substringAfter('/', "").trimEnd('/').ifEmpty { null }

    private fun resolveInside(dir: Path, relative: String): Path {
        val path = dir.resolve(relative).normalize()
        if (!path.startsWith(dir)) throw GradleException("アーカイブに不正なパスがあります: $relative")
        return path
    }

    /** zip を、先頭のディレクトリを取り除いて [dir] に展開する。 */
    fun extractZip(archive: Path, dir: Path) {
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

    /** .tar.xz を、先頭のディレクトリを取り除いて [dir] に展開する。 */
    fun extractTarXz(archive: Path, dir: Path) {
        extractTar(XZInputStream(BufferedInputStream(Files.newInputStream(archive))), dir)
    }

    /** .tar.gz を、先頭のディレクトリを取り除いて [dir] に展開する。 */
    fun extractTarGz(archive: Path, dir: Path) {
        extractTar(GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))), dir)
    }

    private fun extractTar(input: InputStream, dir: Path) {
        TarArchiveInputStream(input).use { tar ->
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

    fun deleteRecursively(path: Path) {
        if (Files.exists(path)) path.toFile().deleteRecursively()
    }
}
