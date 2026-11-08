package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.core.kernel.Machine

/** テストで使う画面の address */
internal const val SCREEN_ADDRESS: String = "00000000-0000-4000-8000-000000000001"

/** テストで使うキーボードの address */
internal const val KEYBOARD_ADDRESS: String = "00000000-0000-4000-8000-000000000002"

/** テストで使うスピーカーの address */
internal const val SPEAKER_ADDRESS: String = "00000000-0000-4000-8000-000000000003"

/** 画面 [screen] と、あればキーボード [keyboard] を、このマシンにつなぐ（結線と同じ。メインスレッドから呼ぶ） */
internal fun Machine.attachConsole(screen: ScreenDevice, keyboard: KeyboardDevice? = null) {
    devices.attach(SCREEN_ADDRESS, screen)
    if (keyboard != null) devices.attach(KEYBOARD_ADDRESS, keyboard)
}
