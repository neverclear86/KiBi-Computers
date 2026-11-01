package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.machine.MachineReleaseReason
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines

/** 起動シーケンス（#49）の GameTest。init まで動かすのは、実際のディスク（#52・#55）ができてから。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object BootGameTests {
    private val POS = BlockPos(1, 1, 1)

    /** 起動の失敗と、コンソールの出力を記録する */
    private class Recorder(private val server: MinecraftServer) : MachineHost {
        @Volatile
        var failure: BootFailure? = null

        @Volatile
        var failedOnServerThread = false

        val console = StringBuffer()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console.append(String(bytes, Charsets.UTF_8))
        }

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            failedOnServerThread = server.isSameThread
            this.failure = failure
        }
    }

    private fun bootFails(helper: GameTestHelper, config: BootConfig, expected: BootFailure, message: String) {
        val manager = ServerMachines.current!!
        val key = MachineKey(helper.level.dimension(), helper.absolutePos(POS))
        val host = Recorder(helper.level.server)
        val machine = manager.attach(key, host, MachineConfig.DEFAULT)
        helper.assertTrue(machine.boot(config), "boot should be accepted")
        helper.succeedWhen {
            helper.assertTrue(host.failure == expected, "expected $expected, got ${host.failure}")
            helper.assertTrue(host.failedOnServerThread, "onBootFailed should run on the server thread")
            helper.assertTrue(host.console.contains(message), "console should show '$message': ${host.console}")
            helper.assertTrue(machine.state == MachineState.STOPPED, "machine should stay stopped")
            manager.release(key, machine, MachineReleaseReason.REMOVED)
        }
    }

    /** 起動部品がなければ、メインスレッドで NO_FIRMWARE が届き、画面にメッセージが出る */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun bootWithoutFirmwareFails(helper: GameTestHelper) {
        bootFails(helper, BootConfig.builder().firmwarePresent(false).addBootDisk("any").build(), BootFailure.NO_FIRMWARE, "kibi: no boot firmware")
    }

    /** 起動ディスクの候補が開けなければ NO_BOOT_DISK */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun bootWithoutDiskFails(helper: GameTestHelper) {
        bootFails(helper, BootConfig.builder().addBootDisk("missing-disk").build(), BootFailure.NO_BOOT_DISK, "kibi: no bootable disk")
    }
}
