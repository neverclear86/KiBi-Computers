package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import java.security.SecureRandom

/**
 * 時計（接続層 → カーネル。#44）。中身の使い方（WASI の clock、タイマー）は #64・#69。
 *
 * **どのスレッドから呼ばれてもよいように作る。** プロセスのスレッド（システムコールの中）から呼ばれるため、
 * ワールドを直接読まない。ゲーム内時間は、接続層が tick ごとに `volatile` のフィールドなどに写しておき、それを返す。
 */
@ApiStatus.Internal
public interface MachineClock {
    /** 実時間。UNIX エポックからのナノ秒 */
    public fun realtimeNanos(): Long

    /** 単調に増える時計（ナノ秒）。起点は決めない。差だけを使う */
    public fun monotonicNanos(): Long

    /** ゲーム内時間（ワールドの tick。`Level.getGameTime()` 相当）。ワールドがなければ 0 */
    public fun worldTicks(): Long

    public companion object {
        /** JVM の時計を使う。ゲーム内時間は常に 0（テストや、ワールドのない環境向け） */
        @JvmField
        public val SYSTEM: MachineClock = object : MachineClock {
            override fun realtimeNanos(): Long {
                val now = java.time.Instant.now()
                return now.epochSecond * 1_000_000_000L + now.nano
            }

            override fun monotonicNanos(): Long = System.nanoTime()

            override fun worldTicks(): Long = 0

            override fun toString(): String = "MachineClock.SYSTEM"
        }
    }
}

/**
 * 乱数の元（接続層 → カーネル。#44）。WASI の `random_get` などが使う（#69）。
 *
 * どのスレッドから呼ばれてもよいように作る。暗号に使える質を求める（プログラムが鍵を作るため）。
 */
@ApiStatus.Internal
public fun interface EntropySource {
    /** [bytes] を乱数で埋める */
    public fun fill(bytes: ByteArray)

    public companion object {
        /** [SecureRandom] で埋める */
        @JvmField
        public val SECURE: EntropySource = object : EntropySource {
            private val random = SecureRandom()

            override fun fill(bytes: ByteArray) {
                random.nextBytes(bytes)
            }

            override fun toString(): String = "EntropySource.SECURE"
        }
    }
}

/**
 * ディスクの実体（接続層 → カーネル。#44）。ディスクの識別子から、中身を置くホストのディレクトリを返す。
 * ディスクの FS（#52）とマウント（#56）が使う。識別子の決め方（アイテムに持たせる UUID など）は接続層が決める。
 *
 * どのスレッドから呼ばれてもよいように作る。ディレクトリを作るのはカーネルの側（#52）。
 */
@ApiStatus.Internal
public fun interface DiskResolver {
    /** [diskId] のディスクの中身を置くディレクトリ。知らない識別子なら `null` */
    public fun resolve(diskId: String): Path?

    public companion object {
        /** ディスクを1つも持たない */
        @JvmField
        public val NONE: DiskResolver = DiskResolver { null }
    }
}

/**
 * マシンが動く環境（接続層 → カーネル。#44）。[MachineScheduler.createMachine] に渡す。[builder] で作る。
 *
 * ここにあるものは、どれもプロセスのスレッドからも使われる。接続層の実装は、ワールドに直接触らない（スレッドが違うため）。
 * ワールドに触る必要があるときは、カーネルがメインスレッドで実行する口（tick の中のホスト呼び出し）を使う。
 *
 * ```java
 * MachineEnvironment env = MachineEnvironment.builder()
 *         .clock(clock)
 *         .disks(id -> disksDir.resolve(id))
 *         .storageDirectory(saveDir.resolve(machineId))
 *         .build();
 * ```
 */
@ApiStatus.Internal
public class MachineEnvironment private constructor(builder: Builder) {
    /** 時計 */
    public val clock: MachineClock = builder.clock

    /** 乱数の元 */
    public val entropy: EntropySource = builder.entropy

    /** ディスクの実体 */
    public val disks: DiskResolver = builder.disks

    /**
     * マシンの実行状態の保存先のディレクトリ（#81・#82・#84 が使う。中に置くファイルの形はそちらで決める）。
     * `null` なら保存しない（テストなど）。ディレクトリを作るのはカーネルの側
     */
    public val storageDirectory: Path? = builder.storageDirectory

    /** [MachineEnvironment] を組み立てる。 */
    public class Builder internal constructor() {
        internal var clock: MachineClock = MachineClock.SYSTEM
        internal var entropy: EntropySource = EntropySource.SECURE
        internal var disks: DiskResolver = DiskResolver.NONE
        internal var storageDirectory: Path? = null

        /** 時計（既定は [MachineClock.SYSTEM]） */
        public fun clock(value: MachineClock): Builder = apply { clock = value }

        /** 乱数の元（既定は [EntropySource.SECURE]） */
        public fun entropy(value: EntropySource): Builder = apply { entropy = value }

        /** ディスクの実体（既定は [DiskResolver.NONE]） */
        public fun disks(value: DiskResolver): Builder = apply { disks = value }

        /** 保存先のディレクトリ（既定は `null` = 保存しない） */
        public fun storageDirectory(value: Path?): Builder = apply { storageDirectory = value }

        /** 環境を作る。 */
        public fun build(): MachineEnvironment = MachineEnvironment(this)
    }

    public companion object {
        /** 既定の環境（JVM の時計、[SecureRandom]、ディスクなし、保存しない） */
        @JvmField
        public val DEFAULT: MachineEnvironment = Builder().build()

        /** 環境を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
