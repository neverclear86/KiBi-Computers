package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.ChatVisiblity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * OS の既定の言語（#661、ADR 0661）の GameTest: 電源ボタンで起動したプレイヤーの言語を、起動の設定（init の `LANG`）とケースの NBT に入れる。
 * 止める操作・プレイヤーなし・FakePlayer では前回の値を使い、復元（冷えた起動）も前回の値で起動する
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object BootLanguageGameTests {
    private val CASE = BlockPos(1, 1, 1)

    /** 言語 [language] のクライアントのプレイヤー（ワールドには入れない。`clientInformation()` だけを使う） */
    private fun player(helper: GameTestHelper, language: String): ServerPlayer = ServerPlayer(
        helper.level.server,
        helper.level,
        GameProfile(UUID.randomUUID(), "lang-$language"),
        ClientInformation(language, 2, ChatVisiblity.FULL, true, 0, HumanoidArm.RIGHT, false, true),
    )

    // FakePlayer の clientInformation() は既定（en_us）。保存した言語を英語で上書きしない
    private fun fakePlayer(helper: GameTestHelper): FakePlayer = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "[kibi-lang-fake]"))

    private fun place(helper: GameTestHelper): CaseBlockEntity {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(CASE)
        be.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return be
    }

    private fun bootedLanguage(be: CaseBlockEntity): String? = be.computer.machine?.lastBootConfig?.language

    /** 起動したプレイヤーの言語を使い、止める操作・プレイヤーなし・FakePlayer では前回の値のまま */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun powerButtonUsesThePressingPlayersLanguage(helper: GameTestHelper) {
        val be = place(helper)
        helper.assertTrue(be.bootLanguage == null, "a new case has no language")
        // GUI の電源ボタン（CaseMenu）から押したプレイヤーの言語
        val ja = player(helper, "ja_jp")
        val menu = CaseMenu.server(0, ja.inventory, be)
        helper.assertTrue(menu.clickMenuButton(ja, CaseMenu.POWER_BUTTON), "power button should be handled")
        helper.assertTrue(be.computer.status == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        helper.assertTrue(be.bootLanguage == "ja_JP.UTF-8", "the case should remember ja_JP.UTF-8 but was ${be.bootLanguage}")
        helper.assertTrue(bootedLanguage(be) == "ja_JP.UTF-8", "boot config language was ${bootedLanguage(be)}")

        // 止める操作では言語を変えない
        helper.assertTrue(be.pressPowerButton(player(helper, "en_us")) == ComputerStatus.OFF, "second press should turn it off")
        helper.assertTrue(be.bootLanguage == "ja_JP.UTF-8", "turning off should not change the language: ${be.bootLanguage}")

        // プレイヤーなし・FakePlayer・形の合わない言語は前回の値
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run without a player")
        helper.assertTrue(bootedLanguage(be) == "ja_JP.UTF-8", "no player should reuse the language: ${bootedLanguage(be)}")
        be.pressPowerButton()
        helper.assertTrue(be.pressPowerButton(fakePlayer(helper)) == ComputerStatus.RUNNING, "case should run for a fake player")
        helper.assertTrue(be.bootLanguage == "ja_JP.UTF-8" && bootedLanguage(be) == "ja_JP.UTF-8", "a fake player should not change it")
        be.pressPowerButton()
        helper.assertTrue(be.pressPowerButton(player(helper, "../bad=")) == ComputerStatus.RUNNING, "case should run for a bad language")
        helper.assertTrue(bootedLanguage(be) == "ja_JP.UTF-8", "a malformed language should be ignored: ${bootedLanguage(be)}")
        be.pressPowerButton()

        // 別のプレイヤーが起動すると、その人の言語になる。同じ値なら setChanged しない
        helper.assertTrue(be.pressPowerButton(player(helper, "zh_tw")) == ComputerStatus.RUNNING, "case should run")
        helper.assertTrue(be.bootLanguage == "zh_TW.UTF-8" && bootedLanguage(be) == "zh_TW.UTF-8", "language was ${be.bootLanguage}")
        be.pressPowerButton()
        // 電源の状態（PoweredOn）の変化でも setChanged するので、プレイヤーなしで起動したときと同じ回数かで見る
        val beforeNoPlayer = be.setChangedCalls
        be.pressPowerButton()
        val noPlayerCalls = be.setChangedCalls - beforeNoPlayer
        be.pressPowerButton()
        val beforeSame = be.setChangedCalls
        be.pressPowerButton(player(helper, "zh_tw"))
        helper.assertTrue(
            be.setChangedCalls - beforeSame == noPlayerCalls,
            "the same language should not mark the case dirty again: ${be.setChangedCalls - beforeSame} != $noPlayerCalls",
        )
        be.pressPowerButton()
        val beforeChange = be.setChangedCalls
        be.pressPowerButton(player(helper, "en_us"))
        helper.assertTrue(
            be.setChangedCalls - beforeChange == noPlayerCalls + 1 && be.bootLanguage == "en_US.UTF-8",
            "a changed language should mark the case dirty once: ${be.setChangedCalls - beforeChange} != ${noPlayerCalls + 1}",
        )
        be.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /** NBT の往復: 言語があれば文字列タグに書き、読み込みで検査する（形の合わないもの・タグなしは言語なし） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun bootLanguageRoundTripsThroughNbt(helper: GameTestHelper) {
        val registries = helper.level.registryAccess()
        val be = place(helper)
        helper.assertTrue(!be.saveWithoutMetadata(registries).contains(CaseBlockEntity.BOOT_LANGUAGE_TAG), "no language, no tag")
        be.pressPowerButton(player(helper, "ja_jp"))
        be.pressPowerButton()
        val tag = be.saveWithoutMetadata(registries)
        helper.assertTrue(tag.getString(CaseBlockEntity.BOOT_LANGUAGE_TAG) == "ja_JP.UTF-8", "tag should hold the POSIX form: $tag")

        be.loadWithComponents(tag, registries)
        helper.assertTrue(be.bootLanguage == "ja_JP.UTF-8", "the language should be read back: ${be.bootLanguage}")
        for (bad in listOf("ja_jp", "LANG=x", "", "ja_JP.UTF-8\u0000")) {
            val copy = tag.copy().also { it.putString(CaseBlockEntity.BOOT_LANGUAGE_TAG, bad) }
            be.loadWithComponents(copy, registries)
            helper.assertTrue(be.bootLanguage == null, "a malformed tag '$bad' should be dropped: ${be.bootLanguage}")
        }
        be.loadWithComponents(tag.copy().also { it.putInt(CaseBlockEntity.BOOT_LANGUAGE_TAG, 1) }, registries)
        helper.assertTrue(be.bootLanguage == null, "a non-string tag should be dropped")
        val old = tag.copy().also { it.remove(CaseBlockEntity.BOOT_LANGUAGE_TAG) }
        be.loadWithComponents(old, registries)
        helper.assertTrue(be.bootLanguage == null, "an old save without the tag has no language")
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /** 置き直して読ませた（チャンクの読み込みと同じ）電源の入ったケースは、保存した言語で起動する（ここでは世代がないので冷えた起動） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 2_400)
    fun restoreBootsWithTheSavedLanguage(helper: GameTestHelper) {
        val registries = helper.level.registryAccess()
        val first = place(helper)
        helper.assertTrue(first.pressPowerButton(player(helper, "ja_jp")) == ComputerStatus.RUNNING, "case should run")
        val tag: CompoundTag = first.saveWithFullMetadata(registries)
        helper.assertTrue(tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "a running case should save PoweredOn")
        first.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val copy = helper.getBlockEntity<CaseBlockEntity>(CASE)
        copy.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        copy.loadWithComponents(tag, registries)
        helper.assertTrue(copy.bootLanguage == "ja_JP.UTF-8", "the reloaded case should keep the language")
        helper.succeedWhen {
            val machine = copy.computer.machine
            helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${copy.computer}")
            helper.assertTrue(bootedLanguage(copy) == "ja_JP.UTF-8", "restore should boot with the saved language: ${bootedLanguage(copy)}")
            copy.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
}
