package pro.ikili.kibicomputers.api.device

/**
 * デバイスのメソッドの表（#65）。名前 → 処理と、呼ぶスレッド（メインスレッド / 直接）。変わらない。[builder] で作る。
 *
 * ```java
 * DeviceMethods methods = DeviceMethods.builder()
 *     .mainThread("set-output", (ctx, args) -> { setOutput(args.getLong(0)); return new DeviceValue[0]; })
 *     .direct("get-name", (ctx, args) -> new DeviceValue[] { DeviceValue.of(name) })
 *     .build();
 * ```
 *
 * メソッドの名前は `[a-z][a-z0-9_-]*` で、64 バイトまで。
 */
public class DeviceMethods private constructor(private val entries: Map<String, Entry>) {
    /** メソッドの名前（登録した順） */
    public val names: List<String> = entries.keys.toList()

    /** 名前 [name] のメソッド。なければ `null`（内部用） */
    @JvmSynthetic
    internal fun find(name: String): Entry? = entries[name]

    /** 登録したメソッド1つ（内部用） */
    internal class Entry(val name: String, val handler: DeviceMethodHandler, val mainThread: Boolean)

    /** [DeviceMethods] を組み立てる。 */
    public class Builder internal constructor() {
        private val entries = LinkedHashMap<String, Entry>()

        /**
         * メインスレッドで呼ぶメソッドを足す（既定の推奨。ワールドに触ってよい）。呼び出しは次の tick の中で実行する
         *
         * @throws IllegalArgumentException 名前が規則に合わない・もう登録してある
         */
        public fun mainThread(name: String, handler: DeviceMethodHandler): Builder = add(name, handler, mainThread = true)

        /**
         * 呼んだプロセスのスレッドでその場で呼ぶメソッドを足す（速いが、スレッドの安全は作者が受け持つ。ワールドに触らない）
         *
         * @throws IllegalArgumentException 名前が規則に合わない・もう登録してある
         */
        public fun direct(name: String, handler: DeviceMethodHandler): Builder = add(name, handler, mainThread = false)

        private fun add(name: String, handler: DeviceMethodHandler, mainThread: Boolean): Builder = apply {
            require(isValidName(name)) { "メソッドの名前は [a-z][a-z0-9_-]* で $MAX_NAME_BYTES バイトまで: $name" }
            require(name !in entries) { "メソッド $name はもう登録してある" }
            entries[name] = Entry(name, handler, mainThread)
        }

        /** 表を作る */
        public fun build(): DeviceMethods = if (entries.isEmpty()) EMPTY else DeviceMethods(LinkedHashMap(entries))
    }

    override fun toString(): String = "DeviceMethods($names)"

    public companion object {
        /** メソッドの名前の最大のバイト数（内部用） */
        internal const val MAX_NAME_BYTES: Int = 64

        private val NAME = Regex("[a-z][a-z0-9_-]*")

        private val EMPTY = DeviceMethods(emptyMap())

        /** 名前が規則に合うか（内部用。ASCII だけなので、文字数がバイト数） */
        @JvmSynthetic
        internal fun isValidName(name: String): Boolean = name.length <= MAX_NAME_BYTES && NAME.matches(name)

        /** 組み立て始める */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** メソッドのない表 */
        @JvmStatic
        public fun empty(): DeviceMethods = EMPTY
    }
}
