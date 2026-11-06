package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * 使う MoonBit のツールチェーンの配布物（#32）。`gradle/moonbit.properties` で固定したバージョンと、
 * 実行中の OS・CPU に合うアーカイブを指す。
 *
 * 公式配布（`cli.moonbitlang.com`）は、版ごとの置き場所 `binaries/<版>/` と `cores/core-<版>` を持つ
 * （公式のインストーラーが `MOONBIT_INSTALL_VERSION` で使うもの）。ツールチェーンの SHA-256 は公式の `.sha256` を写し、
 * 標準ライブラリ（core）のアーカイブは公式の `.sha256` がないので、取得して計算した値を固定する。
 *
 * @property version MoonBit の版（`moonc` の版。例: `0.10.20+65efd5b93`）
 * @property platform 配布物の名前に使う `<os>-<arch>`（例: `windows-x86_64`）
 * @property sha256 ツールチェーンのアーカイブの SHA-256（16進の小文字）
 * @property coreSha256 標準ライブラリのアーカイブ（[coreUrl]）の SHA-256（16進の小文字）
 */
data class MoonBitDistribution(
    val version: String,
    val platform: String,
    val sha256: String,
    val coreSha256: String,
) {
    private val isWindows: Boolean get() = platform.startsWith("windows-")

    private val encodedVersion: String get() = URLEncoder.encode(version, Charsets.UTF_8)

    /** ツールチェーンのアーカイブの URL。 */
    val url: String
        get() = "$BASE/binaries/$encodedVersion/moonbit-$platform.${if (isWindows) "zip" else "tar.gz"}"

    /** 標準ライブラリ（core）のソースのアーカイブの URL。OS によらず tar.gz を使う（ハッシュを1つにするため）。 */
    val coreUrl: String get() = "$BASE/cores/core-$encodedVersion.tar.gz"

    /** `bin` の中の `moon` の実行ファイルの名前。 */
    val moonExecutable: String get() = if (isWindows) "moon.exe" else "moon"

    companion object {
        private const val BASE = "https://cli.moonbitlang.com"

        /** [file]（`gradle/moonbit.properties`）を読み、実行中のホストに合う配布物を返す。 */
        fun load(file: File): MoonBitDistribution {
            val props = Properties()
            file.reader(Charsets.UTF_8).use(props::load)
            val version = props.getProperty("version")
                ?: throw GradleException("$file に version がありません")
            val platform = hostPlatform()
            val sha256 = props.getProperty("sha256.$platform")
                ?: throw GradleException("$file に $platform 用の MoonBit の SHA-256（sha256.$platform）がありません")
            val core = props.getProperty("sha256.core")
                ?: throw GradleException("$file に標準ライブラリの SHA-256（sha256.core）がありません")
            return MoonBitDistribution(version, platform, sha256.lowercase(), core.lowercase())
        }

        /** MoonBit の配布物の名前で使う `<os>-<arch>` を、実行中の JVM から決める。 */
        fun hostPlatform(): String {
            val osName = System.getProperty("os.name").lowercase()
            val os = when {
                osName.startsWith("windows") -> "windows"
                osName.startsWith("mac") || osName.startsWith("darwin") -> "darwin"
                osName.startsWith("linux") -> "linux"
                else -> throw GradleException("MoonBit を取得できない OS です: $osName")
            }
            val arch = when (val a = System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw GradleException("MoonBit を取得できない CPU です: $a")
            }
            return "$os-$arch"
        }
    }
}

/**
 * MoonBit のツールチェーンを公式配布から取得し、SHA-256 を確かめてから展開する（#32）。
 *
 * 置き場所は `<toolchainsDir>/moonbit/moonbit-<platform>-<version>/`（`MOON_HOME` にする）。中身は公式のインストーラーと同じ形
 * （`bin/`・`include/`・`lib/core/`）で、展開した後に標準ライブラリを `moon bundle` でビルドしておく（インストーラーと同じ手順）。
 * 排他は [ZigInstaller] と同じく、JVM 内は synchronized、プロセス間はファイルロック。
 */
internal object MoonBitInstaller {
    private const val MARKER = ".kibicomputers-installed"
    private val jvmLock = Any()

    /** [toolchainsDir] の下に [dist] を用意し、`MOON_HOME` にするディレクトリを返す。用意済みならダウンロードしない。 */
    fun ensure(toolchainsDir: File, dist: MoonBitDistribution, execOperations: ExecOperations, logger: Logger): File = synchronized(jvmLock) {
        val name = "moonbit-${dist.platform}-${dist.version.replace('+', '_')}"
        val root = toolchainsDir.toPath().resolve("moonbit")
        val installDir = root.resolve(name)
        if (isInstalled(installDir, dist)) return installDir.toFile()

        Files.createDirectories(root)
        FileChannel.open(root.resolve("$name.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (isInstalled(installDir, dist)) return installDir.toFile()

                val archive = root.resolve("$name.download")
                val coreArchive = root.resolve("$name.core.download")
                val staging = root.resolve("$name.staging")
                try {
                    logger.lifecycle("MoonBit ${dist.version} (${dist.platform}) を ${dist.url} から取得します")
                    Archives.download(dist.url, dist.sha256, archive, "MoonBit")
                    Archives.download(dist.coreUrl, dist.coreSha256, coreArchive, "MoonBit の標準ライブラリ")
                    Archives.deleteRecursively(staging)
                    Files.createDirectories(staging)
                    // ツールチェーンのアーカイブは先頭のディレクトリを持たない（bin/・include/・lib/ が直下）
                    if (dist.url.endsWith(".zip")) {
                        Archives.extractZip(archive, staging, stripFirst = false)
                    } else {
                        Archives.extractTarGz(archive, staging, stripFirst = false)
                    }
                    val bin = staging.resolve("bin")
                    if (!Files.isRegularFile(bin.resolve(dist.moonExecutable))) {
                        throw GradleException("展開した MoonBit に bin/${dist.moonExecutable} がありません: ${dist.url}")
                    }
                    // アーカイブの bin の中身は実行の権限を持たない。公式のインストーラーと同じく、全部に付ける
                    Files.walk(bin).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach(Archives::makeExecutable) }
                    // 標準ライブラリのアーカイブは core/ を先頭に持つ。lib/core に置く
                    val core = staging.resolve("lib/core")
                    Archives.deleteRecursively(core)
                    Files.createDirectories(core)
                    Archives.extractTarGz(coreArchive, core)

                    Archives.deleteRecursively(installDir)
                    Files.move(staging, installDir, StandardCopyOption.ATOMIC_MOVE)
                    // ビルドの結果が置き場所のパスを覚えることがあるので、移した後でビルドする。失敗したら印を書かない（次に入れ直す）
                    bundleCore(installDir, dist, execOperations)
                    Files.writeString(installDir.resolve(MARKER), "${dist.sha256} ${dist.coreSha256}")
                } finally {
                    Files.deleteIfExists(archive)
                    Files.deleteIfExists(coreArchive)
                    Archives.deleteRecursively(staging)
                }
            }
        }
        installDir.toFile()
    }

    /** 公式のインストーラーと同じく、標準ライブラリを全バックエンド向けにビルドしておく（`moon -C lib/core bundle`）。 */
    private fun bundleCore(home: Path, dist: MoonBitDistribution, execOperations: ExecOperations) {
        val bin = home.resolve("bin").toFile()
        execOperations.exec {
            executable = bin.resolve(dist.moonExecutable).absolutePath
            args("-C", home.resolve("lib/core").toFile().absolutePath, "bundle", "--warn-list", "-a", "--all")
            environment(moonEnvironment(home.toFile()))
        }
    }

    /** `moon` を動かすときの環境変数（`MOON_HOME` と、`bin` を先頭にした `PATH`）。 */
    fun moonEnvironment(home: File): Map<String, String> {
        val bin = home.resolve("bin").absolutePath
        val path = System.getenv("PATH")?.let { bin + File.pathSeparator + it } ?: bin
        return mapOf("MOON_HOME" to home.absolutePath, "PATH" to path)
    }

    private fun isInstalled(installDir: Path, dist: MoonBitDistribution): Boolean {
        val marker = installDir.resolve(MARKER)
        return Files.isRegularFile(marker) &&
            Files.readString(marker).trim() == "${dist.sha256} ${dist.coreSha256}" &&
            Files.isRegularFile(installDir.resolve("bin").resolve(dist.moonExecutable))
    }
}
