package pro.ikili.kibicomputers.neoforge.gametest

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.ChatVisiblity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncTarget
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.ScreenWatchers
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenResyncPayload
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.display.ScreenUpdatePayload
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.Collections
import java.util.IdentityHashMap

/** サーバーからクライアントへの画面の同期（#140、#359）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ScreenSyncGameTests {
    private val POS = BlockPos(1, 1, 1)

    private const val START = ScreenWatchers.DEFAULT_START_DISTANCE
    private const val STOP = ScreenWatchers.DEFAULT_START_DISTANCE + ScreenWatchers.DEFAULT_HYSTERESIS

    /** 偽物のプレイヤーには本物のパケットを送れないので、送ったものを受け取る「クライアント」に渡す。 */
    private val clients: MutableMap<ServerPlayer, FakeClient> = Collections.synchronizedMap(IdentityHashMap())

    private val CAPTURE = ScreenSync.Transport { player, payload ->
        val client = clients[player]
        if (client != null) client.receive(payload) else ScreenSync.NETWORK.send(player, payload)
    }

    /**
     * 受け取ったフラグメントを、クライアントの画面のつもりのバッファに再生する。
     * 誰が見るかはサーバーが距離で決めるので、ほかのテストの画面の分も届きうる。[pos] の画面の分だけを受け取る。
     */
    private class FakeClient(private val tier: ScreenTier, val pos: BlockPos) : ScreenSyncTarget {
        override val text: TextBuffer = tier.createTextBuffer()
        private var px: PixelBuffer? = null
        val receiver = ScreenSyncReceiver()
        var fragments = 0
        var keyframes = 0
        private var atMessageStart = true
        var lastDimension: ResourceKey<Level>? = null

        override fun pixels(): PixelBuffer? =
            if (!tier.supportsPixels) null else px ?: tier.createPixelBuffer().also { px = it }

        override fun pixelsIfCreated(): PixelBuffer? = px

        fun receive(payload: ScreenUpdatePayload) {
            if (payload.pos != pos) return
            fragments++
            lastDimension = payload.dimension
            val data = payload.data
            // メッセージの最初のフラグメントの2バイト目が種類（1 がキーフレーム）。1バイト目は「最後のフラグメントか」
            if (atMessageStart && data.size > 1 && data[1].toInt() == 1) keyframes++
            atMessageStart = data[0].toInt() == 1
            receiver.receive(data, this)
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

    /**
     * 偽物のプレイヤーは接続が tick されないので、チャンクが「送る順番待ち」のまま残る（その間は画面を送らない）。
     * クライアントが受け取ったことにして、[at] のチャンクが届くまで送る。
     */
    fun deliverChunks(player: ServerPlayer, at: BlockPos) {
        val sender = player.connection.chunkSender
        val chunk = ChunkPos(at).toLong()
        var n = 0
        while (sender.isPending(chunk) && n++ < 256) {
            sender.onChunkBatchReceivedByClient(64f)
            sender.sendNextChunks(player)
        }
    }

    // 偽物のプレイヤーを、画面の中心から x 方向に [distance] ブロック離れたところ（目の高さを画面の中心にそろえる）に置く
    private fun moveTo(helper: GameTestHelper, player: ServerPlayer, distance: Double) {
        val center = helper.absoluteVec(POS.center)
        player.teleportTo(center.x + distance, center.y - player.eyeHeight, center.z)
        helper.level.chunkSource.move(player)
        deliverChunks(player, helper.absolutePos(POS))
    }

    private fun watcherAt(
        helper: GameTestHelper,
        tier: ScreenTier,
        distance: Double,
        viewDistance: Int = 2,
    ): Pair<ServerPlayer, FakeClient> {
        ScreenSync.transport = CAPTURE
        val player = helper.makeMockServerPlayerInLevel()
        if (viewDistance != 2) {
            player.updateOptions(
                ClientInformation("en_us", viewDistance, ChatVisiblity.FULL, true, 0, HumanoidArm.RIGHT, false, true),
            )
        }
        val client = FakeClient(tier, helper.absolutePos(POS))
        clients[player] = client
        moveTo(helper, player, distance)
        return player to client
    }

    private fun cleanUp(helper: GameTestHelper, player: ServerPlayer) {
        clients.remove(player)
        helper.level.server.playerList.remove(player)
    }

    /** 近くにいるとサーバーが見ている人に入れてキーフレームを送り、以後の描画も届く。遠ざかると止まる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun nearbyPlayerReceivesKeyframeThenCommands(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.PIXEL).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        be.text.write(0, 0, "before watching", 0x00FF00, 0)
        val (player, client) = watcherAt(helper, ScreenTier.PIXEL, 2.0)
        var sent = 0
        helper.startSequence()
            // 見はじめる人を探す tick の終わりにキーフレームが届く
            .thenWaitUntil { helper.assertTrue(client.fragments > 0, "a keyframe should have been sent") }
            .thenExecute {
                helper.assertTrue(be.isWatchedBy(player), "the server should count the nearby player as watching")
                helper.assertTrue(client.keyframes == 1, "exactly one keyframe should be sent but was ${client.keyframes}")
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
                helper.assertTrue(client.keyframes == 1, "small drawing should not resend the whole screen")
                moveTo(helper, player, STOP + 8.0)
            }
            .thenIdle(1)
            .thenExecute {
                helper.assertTrue(!be.isWatchedBy(player), "the player should have stopped watching")
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

    /** 見はじめる距離とやめる距離の間では、見ていなければ見はじめず、見ていればやめない。遠い画面は送らない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun farScreenIsNotSentAndWatchingHasHysteresis(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        be.text.write(0, 0, "far away", 0xFFFFFF, 0)
        // GameTest のサーバーと偽物のプレイヤーは追跡するチャンクの範囲が狭い（2 チャンク）ので、見はじめる距離まで広げる
        val viewDistance = helper.level.server.playerList.viewDistance
        helper.level.chunkSource.setViewDistance(8)
        val between = (START + STOP) / 2
        val (player, client) = watcherAt(helper, ScreenTier.COLOR, between, viewDistance = 8)
        val scan = ScreenWatchers.DEFAULT_SCAN_INTERVAL_TICKS + 2
        helper.startSequence()
            .thenIdle(scan)
            .thenExecute {
                helper.assertTrue(client.fragments == 0, "a screen farther than the start distance should not be sent")
                helper.assertTrue(!be.isWatchedBy(player), "the far player should not be watching")
                // 見ていない人のお願いは通らない
                ScreenSync.requestResync(player, ScreenResyncPayload(helper.absolutePos(POS)))
            }
            .thenIdle(1)
            .thenExecute {
                helper.assertTrue(client.fragments == 0, "a resync request from a non-watcher should be ignored")
                moveTo(helper, player, START - 4.0)
            }
            .thenWaitUntil { helper.assertTrue(be.isWatchedBy(player), "the player should start watching when close") }
            .thenIdle(1)
            .thenExecute {
                helper.assertTrue(client.keyframes == 1, "one keyframe should be sent when starting to watch")
                moveTo(helper, player, between)
            }
            .thenIdle(scan)
            .thenExecute {
                helper.assertTrue(be.isWatchedBy(player), "the player should keep watching between the two distances")
                moveTo(helper, player, STOP + 16.0)
            }
            .thenIdle(1)
            .thenExecute {
                helper.assertTrue(!be.isWatchedBy(player), "the player should stop watching beyond the stop distance")
                helper.assertTrue(client.keyframes == 1, "moving around should not resend the whole screen")
                cleanUp(helper, player)
                helper.level.chunkSource.setViewDistance(viewDistance)
            }
            .thenSucceed()
    }

    /** 見ている人が送り直しのお願いを連打しても、画面全体は一定の間隔に1回までしか送らない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun resyncSpamDoesNotMultiplyKeyframes(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.PIXEL).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        be.text.write(0, 0, "spam", 0xFFFFFF, 0)
        val (player, client) = watcherAt(helper, ScreenTier.PIXEL, 2.0)
        val abs = helper.absolutePos(POS)
        val interval = ScreenWatchers.DEFAULT_RESYNC_INTERVAL_TICKS
        var spamming = false
        helper.onEachTick {
            if (spamming) repeat(5) { ScreenSync.requestResync(player, ScreenResyncPayload(abs)) }
        }
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(client.keyframes == 1, "the first keyframe should arrive") }
            .thenExecute { spamming = true }
            .thenIdle(interval - 5)
            .thenExecute {
                helper.assertTrue(client.keyframes == 1, "spammed requests should not resend before the interval: ${client.keyframes}")
            }
            // 間隔が空いたら、待っていたお願いの分を1回だけ送る
            .thenWaitUntil { helper.assertTrue(client.keyframes == 2, "a deferred resync should be sent after the interval") }
            .thenExecute { spamming = false }
            .thenIdle(5)
            .thenExecute {
                helper.assertTrue(client.keyframes == 2, "only one keyframe per interval: ${client.keyframes}")
                helper.assertTrue(client.matches(be), "client should match the server")
                cleanUp(helper, player)
            }
            .thenSucceed()
    }

    /** 画面が壊されたら、見ていた人への送信も止まる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun removedScreenStopsSyncing(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val (player, _) = watcherAt(helper, ScreenTier.MONOCHROME, 2.0)
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(be.watcherCount == 1, "the player should be watching") }
            .thenExecute {
                helper.setBlock(POS, Blocks.AIR)
                helper.assertTrue(be.watcherCount == 0, "watchers should be dropped when the screen is removed")
                cleanUp(helper, player)
            }
            .thenSucceed()
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
        val resync = ScreenResyncPayload(BlockPos(-5, 70, 9))
        val rbuf = Unpooled.buffer()
        ScreenResyncPayload.STREAM_CODEC.encode(rbuf, resync)
        helper.assertTrue(ScreenResyncPayload.STREAM_CODEC.decode(rbuf) == resync, "resync payload should round trip")
        helper.succeed()
    }
}
