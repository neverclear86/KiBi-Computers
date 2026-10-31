package pro.ikili.kibicomputers.neoforge.gametest

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncTarget
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.display.ScreenUpdatePayload
import pro.ikili.kibicomputers.neoforge.display.ScreenWatchPayload
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.Collections
import java.util.IdentityHashMap

/** サーバーからクライアントへの画面の同期（#140）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ScreenSyncGameTests {
    private val POS = BlockPos(1, 1, 1)

    /** 偽物のプレイヤーには本物のパケットを送れないので、送ったものを受け取る「クライアント」に渡す。 */
    private val clients: MutableMap<ServerPlayer, FakeClient> = Collections.synchronizedMap(IdentityHashMap())

    private val CAPTURE = ScreenSync.Transport { player, payload ->
        val client = clients[player]
        if (client != null) client.receive(payload) else ScreenSync.NETWORK.send(player, payload)
    }

    /** 受け取ったフラグメントを、クライアントの画面のつもりのバッファに再生する。 */
    private class FakeClient(tier: ScreenTier) : ScreenSyncTarget {
        override val text: TextBuffer = tier.createTextBuffer()
        private var px: PixelBuffer? = null
        private val tierRef = tier
        val receiver = ScreenSyncReceiver()
        var fragments = 0
        var lastDimension: net.minecraft.resources.ResourceKey<Level>? = null

        override fun pixels(): PixelBuffer? =
            if (!tierRef.supportsPixels) null else px ?: tierRef.createPixelBuffer().also { px = it }

        override fun pixelsIfCreated(): PixelBuffer? = px

        fun receive(payload: ScreenUpdatePayload) {
            fragments++
            lastDimension = payload.dimension
            receiver.receive(payload.data, this)
        }

        fun matches(be: ScreenBlockEntity): Boolean {
            if (!be.text.contentEquals(text)) return false
            val server = if (be.hasPixels) be.pixels else null
            val client = px
            return when {
                server != null -> server.contentEquals(client ?: PixelBuffer(server.width, server.height))
                client != null -> client.contentEquals(PixelBuffer(client.width, client.height))
                else -> true
            }
        }
    }

    // 偽物のプレイヤーを画面の近くに置き、そのチャンクを読み込んでいることにする
    private fun watcherNear(helper: GameTestHelper, tier: ScreenTier): Pair<ServerPlayer, FakeClient> {
        ScreenSync.transport = CAPTURE
        val player = helper.makeMockServerPlayerInLevel()
        val at = helper.absoluteVec(POS.above().center)
        player.teleportTo(at.x, at.y, at.z)
        helper.level.chunkSource.move(player)
        val client = FakeClient(tier)
        clients[player] = client
        return player to client
    }

    private fun cleanUp(helper: GameTestHelper, player: ServerPlayer) {
        clients.remove(player)
        helper.level.server.playerList.remove(player)
    }

    /** 見はじめるとキーフレームが届き、以後の描画（書き込み・スクロール・ピクセル）も届いて、サーバーと同じ画面になる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun watcherReceivesKeyframeThenCommands(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.PIXEL).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val (player, client) = watcherNear(helper, ScreenTier.PIXEL)
        val abs = helper.absolutePos(POS)
        be.text.write(0, 0, "before watching", 0x00FF00, 0)
        var sent = 0
        helper.startSequence()
            .thenExecute {
                ScreenSync.watch(player, ScreenWatchPayload(abs, true))
                helper.assertTrue(be.watcherCount == 1, "the player should be watching but count was ${be.watcherCount}")
            }
            // tick の終わりにキーフレームが届く
            .thenWaitUntil { helper.assertTrue(client.fragments > 0, "a keyframe should have been sent") }
            .thenExecute {
                helper.assertTrue(client.lastDimension == helper.level.dimension(), "payload should carry the dimension")
                helper.assertTrue(client.matches(be), "client should match the server after the keyframe")
                sent = client.fragments
                be.text.write(0, 1, "こんにちは", 0xFFFFFF, 0x000080)
                be.text.copy(0, 1, be.text.width, be.text.height - 1, 0, -1)
                be.text.fill(0, be.text.height - 1, be.text.width, 1, TextBuffer.BLANK, 0xFFFFFF, 0)
                be.pixels!!.fill(10, 10, 30, 20, 0xFF8800)
                be.pixels!!.set(0, 0, 0x00FFFF)
            }
            .thenWaitUntil { helper.assertTrue(client.fragments > sent, "the drawing should have been sent") }
            .thenExecute {
                helper.assertTrue(client.matches(be), "client should match the server after drawing")
                ScreenSync.watch(player, ScreenWatchPayload(abs, false))
                helper.assertTrue(be.watcherCount == 0, "the player should have stopped watching")
                sent = client.fragments
                be.text.write(0, 3, "unseen", 0xFFFFFF, 0)
            }
            .thenIdle(3)
            .thenExecute {
                helper.assertTrue(client.fragments == sent, "nothing should be sent without watchers")
                cleanUp(helper, player)
            }
            .thenSucceed()
    }

    /** 見ている人がいない画面には何も送らない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun unwatchedScreenSendsNothing(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val (player, client) = watcherNear(helper, ScreenTier.COLOR)
        be.text.write(0, 0, "nobody is watching", 0xFFFFFF, 0)
        helper.runAfterDelay(3) {
            helper.assertTrue(client.fragments == 0, "nothing should be sent to a player who is not watching")
            helper.assertTrue(be.watcherCount == 0, "nobody should be watching")
            cleanUp(helper, player)
            helper.succeed()
        }
    }

    /** 画面が壊されたら、見ていた人への送信も止まる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun removedScreenStopsSyncing(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val (player, _) = watcherNear(helper, ScreenTier.MONOCHROME)
        ScreenSync.watch(player, ScreenWatchPayload(helper.absolutePos(POS), true))
        helper.assertTrue(be.watcherCount == 1, "the player should be watching")
        helper.setBlock(POS, Blocks.AIR)
        helper.assertTrue(be.watcherCount == 0, "watchers should be dropped when the screen is removed")
        cleanUp(helper, player)
        helper.succeed()
    }

    /** 同期のパケットは、ディメンション・位置・バイト列をそのまま運ぶ。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun payloadsRoundTrip(helper: GameTestHelper) {
        val update = ScreenUpdatePayload(Level.NETHER, BlockPos(1, -2, 3), byteArrayOf(1, 2, 3, -1))
        val buf = Unpooled.buffer()
        ScreenUpdatePayload.STREAM_CODEC.encode(buf, update)
        val decoded = ScreenUpdatePayload.STREAM_CODEC.decode(buf)
        helper.assertTrue(
            decoded.dimension == update.dimension && decoded.pos == update.pos && decoded.data.contentEquals(update.data),
            "update payload should round trip",
        )
        val watch = ScreenWatchPayload(BlockPos(-5, 70, 9), true)
        val wbuf = Unpooled.buffer()
        ScreenWatchPayload.STREAM_CODEC.encode(wbuf, watch)
        helper.assertTrue(ScreenWatchPayload.STREAM_CODEC.decode(wbuf) == watch, "watch payload should round trip")
        helper.succeed()
    }
}
