package pro.ikili.kibicomputers.core.kernel

/** 起動の失敗の種類（[MachineHost.onBootFailed]。#49） */
public enum class BootFailure {
    /** 起動部品が挿さっていない */
    NO_FIRMWARE,

    /** 起動ディスクの候補がない、またはどれも開けない */
    NO_BOOT_DISK,

    /** 開けたディスクのどれにも [BootConfig.INIT_PATH] がない */
    NO_INIT,

    /** [BootConfig.INIT_PATH] を実行できない（普通のファイルでない、大きすぎる、WASM として読めない、動かせない形） */
    INIT_NOT_EXECUTABLE,

    /** init のプロセスを作れなかった（上限など） */
    INIT_START_FAILED,
}
