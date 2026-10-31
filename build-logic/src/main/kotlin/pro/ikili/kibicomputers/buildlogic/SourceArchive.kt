package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * WASM にビルドする C のソース（Lua など）のアーカイブを取得し、SHA-256 を確かめて展開するタスク。
 *
 * アーカイブは [downloadsDir]（Gradle ホームの下。手元と CI で共有する）に SHA-256 ごとに置き、
 * 2回目からはダウンロードしない。展開先は [outputDir] で、アーカイブの先頭のディレクトリ
 * （`lua-5.4.9/` など）は取り除く。
 */
abstract class SourceArchive : DefaultTask() {
    /** アーカイブの URL（`.tar.gz`・`.tar.xz`・`.zip`）。 */
    @get:Input
    abstract val url: Property<String>

    /** アーカイブの SHA-256（16進）。 */
    @get:Input
    abstract val sha256: Property<String>

    /** 取得したアーカイブを置く場所。 */
    @get:Internal
    abstract val downloadsDir: DirectoryProperty

    /** 展開先。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val archive = fetch()
        val out = outputDir.get().asFile.toPath()
        Archives.deleteRecursively(out)
        Files.createDirectories(out)
        val name = url.get().substringAfterLast('/')
        when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> Archives.extractTarGz(archive, out)
            name.endsWith(".tar.xz") -> Archives.extractTarXz(archive, out)
            name.endsWith(".zip") -> Archives.extractZip(archive, out)
            else -> throw GradleException("展開できないアーカイブの形式です: ${url.get()}")
        }
    }

    /** アーカイブを [downloadsDir] に用意して返す。同じ Gradle ホームを使うほかのビルドとはファイルロックで排他する。 */
    private fun fetch(): java.nio.file.Path = synchronized(jvmLock) {
        val sha = sha256.get().lowercase()
        val name = url.get().substringAfterLast('/')
        val root = downloadsDir.get().asFile.toPath()
        val archive = root.resolve("$sha-$name")
        Files.createDirectories(root)
        FileChannel.open(root.resolve("$sha.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (Files.isRegularFile(archive) && Archives.sha256(archive) == sha) return archive
                val partial = root.resolve("$sha-$name.download")
                try {
                    logger.lifecycle("${url.get()} を取得します")
                    Archives.download(url.get(), sha, partial, name)
                    Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(partial)
                }
            }
        }
        archive
    }

    private companion object {
        val jvmLock = Any()
    }
}
