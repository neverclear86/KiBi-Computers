package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * 使う wasi-sdk の配布物（#775）。`gradle/wasi-sdk.properties` で固定したバージョンと、実行中の OS・CPU に合うアーカイブを指す。
 *
 * wasi-sdk は clang・wasi-libc・`wasm-component-ld` をまとめたもので、`--target=wasm32-wasip2`・`wasm32-wasip3` で
 * ネイティブのツールチェーンのコンポーネントを作れる（リポジトリの Zig には `wasm32-wasip2` がない）。テストでだけ使い、MOD には入れない。
 *
 * @property version 版（例: `34.0`）
 * @property tag GitHub のリリースのタグ（例: `wasi-sdk-34`）
 * @property platform 配布物の名前に使う `<arch>-<os>`（例: `x86_64-windows`、`arm64-linux`）
 * @property sha256 アーカイブの SHA-256（16進の小文字）
 */
data class WasiSdkDistribution(
    val version: String,
    val tag: String,
    val platform: String,
    val sha256: String,
) {
    private val isWindows: Boolean get() = platform.endsWith("-windows")

    /** 配布物の名前（アーカイブの先頭のディレクトリと同じ）。 */
    val name: String get() = "wasi-sdk-$version-$platform"

    /** GitHub のリリースのアーカイブの URL。 */
    val url: String get() = "https://github.com/WebAssembly/wasi-sdk/releases/download/$tag/$name.tar.gz"

    /** 展開したディレクトリの中の clang の相対パス。 */
    val clangPath: String get() = if (isWindows) "bin/clang.exe" else "bin/clang"

    companion object {
        /** [file]（`gradle/wasi-sdk.properties`）を読み、実行中のホストに合う配布物を返す。 */
        fun load(file: File): WasiSdkDistribution {
            val props = Properties()
            file.reader(Charsets.UTF_8).use(props::load)
            val version = props.getProperty("version") ?: throw GradleException("$file に version がありません")
            val tag = props.getProperty("tag") ?: throw GradleException("$file に tag がありません")
            // wasi-sdk は aarch64 を arm64 と書く
            val platform = ZigDistribution.hostPlatform().replace("aarch64-", "arm64-")
            val sha256 = props.getProperty("sha256.$platform")
                ?: throw GradleException("$file に $platform 用の wasi-sdk の SHA-256（sha256.$platform）がありません")
            return WasiSdkDistribution(version, tag, platform, sha256.lowercase())
        }
    }
}

/**
 * wasi-sdk を GitHub のリリースから取得し、SHA-256 を確かめてから [WasiSdkDistribution] ごとのディレクトリに展開する。
 *
 * アーカイブは大きい（Windows は約 600 MB、展開すると約 1.5 GB）ので、C を `wasm32-wasip2`・`wasm32-wasip3` に
 * ビルドするのに要るもの（[keep]）だけを展開する。排他は [ZigInstaller] と同じ（JVM 内は synchronized、プロセス間はファイルロック）。
 */
internal object WasiSdkInstaller {
    private const val MARKER = ".kibicomputers-installed"
    private val jvmLock = Any()

    /** 展開する bin の中の実行ファイル（拡張子を除く）。clang は Linux・macOS では `clang-<版>` へのリンク、wasm-ld は `lld` へのリンク */
    private val binaries = Regex("""(clang|clang-\d+|wasm-ld|lld|wasm-component-ld)(\.exe)?""")

    /** 先頭のディレクトリを取り除いた相対パス [path] を展開するか。 */
    internal fun keep(path: String): Boolean {
        val parts = path.split('/')
        return when (parts[0]) {
            "bin" -> parts.size == 2 && (
                binaries.matches(parts[1]) || parts[1].endsWith(".cfg") ||
                    (parts[1].endsWith(".dll") && parts[1] != "liblldb.dll")
                )
            // clang のヘッダー（lib/clang/<版>/include）と、Linux・macOS で clang が使う共有ライブラリ
            "lib" -> true
            // sysroot は p2・p3 のものだけ（p1・スレッド・C++・実験用の sysroot は使わない）
            "share" -> parts.size >= 4 && parts[1] == "wasi-sysroot" && parts[2] in setOf("include", "lib") &&
                parts[3] in setOf("wasm32-wasip2", "wasm32-wasip3")
            else -> false
        }
    }

    /** [toolchainsDir] の下に [dist] を用意し、展開したディレクトリを返す。用意済みならダウンロードしない。 */
    fun ensure(toolchainsDir: File, dist: WasiSdkDistribution, logger: Logger): File = synchronized(jvmLock) {
        val root = toolchainsDir.toPath().resolve("wasi-sdk")
        val installDir = root.resolve(dist.name)
        if (isInstalled(installDir, dist)) return installDir.toFile()

        Files.createDirectories(root)
        FileChannel.open(root.resolve("${dist.name}.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (isInstalled(installDir, dist)) return installDir.toFile()

                val archive = root.resolve("${dist.name}.download")
                val staging = root.resolve("${dist.name}.staging")
                try {
                    logger.lifecycle("wasi-sdk ${dist.version} (${dist.platform}) を ${dist.url} から取得します")
                    Archives.download(dist.url, dist.sha256, archive, "wasi-sdk")
                    Archives.deleteRecursively(staging)
                    Files.createDirectories(staging)
                    Archives.extractTarGz(archive, staging, include = ::keep)
                    if (!Files.isRegularFile(staging.resolve(dist.clangPath))) {
                        throw GradleException("展開した wasi-sdk に ${dist.clangPath} がありません: ${dist.url}")
                    }
                    Archives.deleteRecursively(installDir)
                    Files.move(staging, installDir, StandardCopyOption.ATOMIC_MOVE)
                    Files.writeString(installDir.resolve(MARKER), dist.sha256)
                } finally {
                    Files.deleteIfExists(archive)
                    Archives.deleteRecursively(staging)
                }
            }
        }
        installDir.toFile()
    }

    private fun isInstalled(installDir: Path, dist: WasiSdkDistribution): Boolean {
        val marker = installDir.resolve(MARKER)
        return Files.isRegularFile(marker) &&
            Files.readString(marker).trim() == dist.sha256 &&
            Files.isRegularFile(installDir.resolve(dist.clangPath))
    }
}
