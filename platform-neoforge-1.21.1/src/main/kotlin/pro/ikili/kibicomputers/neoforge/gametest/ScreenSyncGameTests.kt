package pro.ikili.kibicomputers.neoforge.gametest

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ChunkTrackingView
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.ChatVisiblity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.portal.DimensionTransition
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.ChunkWatchEvent
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncSender
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
import java.util.concurrent.CompletableFuture

/** サーバーからクライアントへの画面の同期（#140、#359）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ScreenSyncGameTests {
    private val POS = BlockPos(1, 1, 1)

    private const val START = ScreenWatchers.DEFAULT_START_DISTANCE
    private const val STOP = ScreenWatchers.DEFAULT_START_DISTANCE + ScreenWatchers.DEFAULT_HYSTERESIS

    /** 偽物のプレイヤーには本物のパケットを送れないので、送ったものを受け取る「クライアント」に渡す。 */
    private val clients: MutableMap<ServerPlayer, FakeClient> = Collections.synchronizedMap(IdentityHashMap())

    /** 1人で複数の画面を受け取るテストの「クライアント」。[clients] より先に見る。 */
    private val multiClients: MutableMap<ServerPlayer, List<FakeClient>> = Collections.synchronizedMap(IdentityHashMap())

    private val CAPTURE = ScreenSync.Transport { player, payload ->
        val many = multiClients[player]
        val client = clients[player]
        when {
            many != null -> for (c in many) c.receive(payload)
            client != null -> client.receive(payload)
            else -> ScreenSync.NETWORK.send(player, payload)
        }
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

    /**
     * テストが終わったとき（成功・失敗・タイムアウト）に [block] を走らせる（#934）。シーケンスの中の後始末は、途中の段で
     * タイムアウトすると走らず、偽物のプレイヤー・チケット・止めた送信が残って、同じバッチのほかのテストを巻き込む
     */
    private fun whenTestEnds(helper: GameTestHelper, block: () -> Unit) {
        helper.testInfo.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = block()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = block()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
            },
        )
    }

    /**
     * 離れたチャンク [far] が送れる状態にならないときの、原因を絞るための様子（#934）。holder の段階、送れる状態か、
     * プレイヤーの順番待ち・追跡の範囲、サーバーの視距離、待ち始めてからの tick 数と壁時計の時間
     */
    private fun farChunkState(
        level: ServerLevel,
        far: ChunkPos,
        player: ServerPlayer?,
        startTick: Int,
        startNanos: Long,
    ): String {
        val map = level.chunkSource.chunkMap
        val holder = map.getVisibleChunkIfPresent(far.toLong())
        val parts = mutableListOf(
            "holder=${if (holder == null) "none" else holder.latestStatus?.toString() ?: "unloaded"}",
            "sendable=${map.getChunkToSend(far.toLong()) != null}",
        )
        if (player != null) {
            parts += "pending=${player.connection.chunkSender.isPending(far.toLong())}"
            parts += "tracked=${player.chunkTrackingView.contains(far)}"
            parts += "view=${player.chunkTrackingView}"
        }
        parts += "serverViewDistance=${level.server.playerList.viewDistance}"
        parts += "ticks=${level.server.tickCount - startTick}"
        parts += "ms=${(System.nanoTime() - startNanos) / 1_000_000}"
        return parts.joinToString(", ")
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

    /**
     * 見はじめる距離とやめる距離の間では、見ていなければ見はじめず、見ていればやめない。遠い画面は送らない。
     *
     * サーバーの視距離を広げると、同じバッチのすべての偽物のプレイヤーのチケットが広がり、チャンクの生成が詰まって
     * ほかのテストの待ちを延ばす（#934）。別のバッチで動かし、視距離はテストが終わったとき（失敗・タイムアウトでも）に戻す。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200, batch = "screen_sync_view_distance")
    fun farScreenIsNotSentAndWatchingHasHysteresis(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        be.text.write(0, 0, "far away", 0xFFFFFF, 0)
        // GameTest のサーバーと偽物のプレイヤーは追跡するチャンクの範囲が狭い（2 チャンク）ので、見はじめる距離まで広げる
        val viewDistance = helper.level.server.playerList.viewDistance
        helper.level.chunkSource.setViewDistance(8)
        whenTestEnds(helper) { helper.level.chunkSource.setViewDistance(viewDistance) }
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
            }
            .thenSucceed()
    }

    /**
     * 1人が、全画面を毎 tick 描き直す画面を2つ見ると、そのプレイヤーへの送信量はプレイヤーごとの予算に収まり、
     * 近い画面の方が多く届く。描くのをやめると、止められた画面にも画面全体が届いて追いつく（#352）。
     *
     * 描き続ける画面が、ほかのテストの偽物のプレイヤーの予算を使わないように、別のバッチで（ほかのテストと並べずに）動かす。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 600, batch = "screen_sync_budget")
    fun perPlayerBudgetLimitsBusyScreens(helper: GameTestHelper) {
        val nearPos = BlockPos(0, 1, 1)
        val farPos = BlockPos(2, 1, 1)
        helper.setBlock(nearPos, KiBiBlocks.SCREENS.getValue(ScreenTier.PIXEL).get())
        helper.setBlock(farPos, KiBiBlocks.SCREENS.getValue(ScreenTier.PIXEL).get())
        val near = helper.getBlockEntity<ScreenBlockEntity>(nearPos)
        val far = helper.getBlockEntity<ScreenBlockEntity>(farPos)
        ScreenSync.transport = CAPTURE
        val player = helper.makeMockServerPlayerInLevel()
        val nearClient = FakeClient(ScreenTier.PIXEL, helper.absolutePos(nearPos))
        val farClient = FakeClient(ScreenTier.PIXEL, helper.absolutePos(farPos))
        multiClients[player] = listOf(nearClient, farClient)
        // 近い画面の西に 2 ブロック（遠い画面までは 4 ブロック）
        val c = helper.absoluteVec(nearPos.center)
        player.teleportTo(c.x - 2.0, c.y - player.eyeHeight, c.z)
        helper.level.chunkSource.move(player)
        deliverChunks(player, helper.absolutePos(nearPos))
        deliverChunks(player, helper.absolutePos(farPos))

        val traffic = ScreenSync.traffic
        val w = ScreenTier.PIXEL.maxPixelWidth
        val h = ScreenTier.PIXEL.maxPixelHeight
        val image = IntArray(w * h)
        var frame = 0
        var bytesBefore = 0L
        var nearBefore = 0
        var farBefore = 0
        val ticks = 100
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(near.isWatchedBy(player) && far.isWatchedBy(player), "the player should watch both screens")
                helper.assertTrue(nearClient.keyframes > 0 && farClient.keyframes > 0, "both screens should be sent")
            }
            .thenExecute {
                bytesBefore = traffic.bytesSent(player)
                nearBefore = nearClient.fragments
                farBefore = farClient.fragments
            }
            .thenExecuteFor(ticks) {
                // 毎 tick 全画面の画像を描く（描画命令が画面全体の半分を超えるので、毎回キーフレームになる）
                frame++
                for (i in image.indices) image[i] = (i * 31 + frame * 977) and 0xFFFFFF
                near.pixels!!.setPixels(0, 0, w, h, image, 0, w)
                far.pixels!!.setPixels(0, 0, w, h, image, 0, w)
            }
            .thenExecute {
                val sent = traffic.bytesSent(player) - bytesBefore
                // 組み込みの既定ではなく、動いているサーバーの予算（コンフィグの値）と比べる
                val perSecond = traffic.playerBytesPerSecond.toLong()
                // 測った tick 分の予算 + 初めの残り（1秒分）+ 余裕（1 tick のずれ）
                val limit = perSecond * (ticks + 2) / ScreenSyncSender.TICKS_PER_SECOND + perSecond
                helper.assertTrue(sent <= limit, "sent $sent bytes in $ticks ticks, over the per-player budget ($limit)")
                helper.assertTrue(traffic.throttled(player) > 0, "some messages should be throttled")
                val n = nearClient.fragments - nearBefore
                val f = farClient.fragments - farBefore
                helper.assertTrue(n > f, "the near screen should be sent more often: near $n, far $f")
            }
            .thenWaitUntil {
                helper.assertTrue(
                    !near.isThrottled(player) && !far.isThrottled(player),
                    "throttled screens should get a keyframe once drawing stops",
                )
                helper.assertTrue(nearClient.matches(near), "the near client should catch up")
                helper.assertTrue(farClient.matches(far), "the far client should catch up")
            }
            .thenExecute {
                multiClients.remove(player)
                helper.level.server.playerList.remove(player)
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

    /** ログアウトすると、すべての画面の見ている人と、送信量の勘定から外れる（#382）。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun logoutForgetsPlayerOnAllScreens(helper: GameTestHelper) {
        val other = POS.west()
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        helper.setBlock(other, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        val a = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val b = helper.getBlockEntity<ScreenBlockEntity>(other)
        val (player, _) = watcherAt(helper, ScreenTier.MONOCHROME, 2.0)
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(a.isWatchedBy(player) && b.isWatchedBy(player), "the player should watch both screens")
                helper.assertTrue(ScreenSync.traffic.bytesSent(player) > 0, "sent bytes should be counted")
            }
            .thenExecute {
                // PlayerList.remove が PlayerLoggedOutEvent を出す
                cleanUp(helper, player)
                helper.assertTrue(!a.isWatchedBy(player) && !b.isWatchedBy(player), "logout should drop the player from all screens")
                helper.assertTrue(ScreenSync.traffic.bytesSent(player) == 0L, "logout should drop the player's traffic")
                helper.assertTrue(ScreenSync.traffic.keyframesSent(player) == 0L, "logout should drop the player's keyframes")
                // ログアウトのイベントの後に出る UnWatch で、記録が作り直されない（#655）
                helper.assertTrue(ScreenSync.sentChunkCount(player) == -1, "logout should drop the player's sent chunks")
            }
            .thenSucceed()
    }

    /** リスポーンで ServerPlayer が替わると、古いオブジェクトは見ている人と勘定から外れる（#382）。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun respawnForgetsOldPlayerObject(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val (player, client) = watcherAt(helper, ScreenTier.MONOCHROME, 2.0)
        var respawnedPlayer: ServerPlayer? = null
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(be.isWatchedBy(player), "the player should be watching")
                helper.assertTrue(ScreenSync.traffic.bytesSent(player) > 0, "sent bytes should be counted")
                helper.assertTrue(ScreenSync.sentChunkCount(player) > 0, "sent chunks should be recorded")
            }
            .thenExecute {
                val respawned = helper.level.server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
                clients[respawned] = client
                clients.remove(player)
                helper.assertTrue(respawned !== player, "respawning should create a new player object")
                helper.assertTrue(!be.isWatchedBy(player), "the old player object should not be watching")
                helper.assertTrue(ScreenSync.traffic.bytesSent(player) == 0L, "the old player object should not be counted")
                helper.assertTrue(ScreenSync.traffic.keyframesSent(player) == 0L, "the old player's keyframes should be dropped")
                helper.assertTrue(ScreenSync.sentChunkCount(player) == -1, "the old player's sent chunks should be dropped")
                respawnedPlayer = respawned
            }
            // 本物の接続では、リスポーンのパケットの処理で接続の player が替わるまでの Sent が古いオブジェクトに出る。
            // 偽物の接続では、送信枠や順番待ちの中身しだいで Sent が出ないこともあるので、イベントを直に出して確かめる。
            // 古いオブジェクトへの Sent で記録が作り直されない（#655）
            .thenExecute {
                val level = helper.level
                val chunk = level.getChunkAt(helper.absolutePos(POS))
                NeoForge.EVENT_BUS.post(ChunkWatchEvent.Sent(player, chunk, level))
                helper.assertTrue(ScreenSync.sentChunkCount(player) == -1, "a Sent for the old player object should be ignored")
                // 対照: 同じイベントを新しいオブジェクトに出すと記録される（イベントが処理に届いていることの確かめ）
                val respawned = respawnedPlayer!!
                NeoForge.EVENT_BUS.post(ChunkWatchEvent.Sent(respawned, chunk, level))
                helper.assertTrue(
                    ScreenSync.isChunkSent(level, respawned, chunk.pos),
                    "a Sent for the new player object should be recorded",
                )
            }
            // 偽物の接続のままの送信でも、古いオブジェクトの記録は作り直されない
            .thenIdle(2)
            .thenExecute {
                helper.assertTrue(ScreenSync.sentChunkCount(player) == -1, "a Sent for the old player object should be ignored")
                cleanUp(helper, respawnedPlayer!!)
            }
            .thenSucceed()
    }

    /**
     * ディメンションを移ると、元のディメンションの画面の見ている人から外れる。勘定は残る（#382）。
     * 送ったチャンクの記録は捨てられ、移った先の Sent で新しいレベルの記録になる（元のディメンションの同じ座標の
     * チャンクは送ったものと数えない。#655）。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 600)
    fun changingDimensionStopsWatching(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        val (player, _) = watcherAt(helper, ScreenTier.MONOCHROME, 2.0)
        val overworld = helper.level
        val screenChunk = ChunkPos(helper.absolutePos(POS))
        // ネザーの (0, 0) のチャンク。元のディメンションの同じ座標は、別のチャンクとして扱われる
        val netherChunk = ChunkPos(0, 0)
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(be.isWatchedBy(player), "the player should be watching")
                helper.assertTrue(ScreenSync.isChunkSent(overworld, player, screenChunk), "the screen's chunk should be recorded as sent")
            }
            .thenExecute {
                val nether = helper.level.server.getLevel(Level.NETHER)
                helper.assertTrue(nether != null, "the nether should exist on the test server")
                checkNotNull(nether)
                // ネザーの (0, 0) とその周り 1 チャンクを、この tick の中で作り切っておく。(0, 0) を送れる状態（ブロックが
                // tick する段）にするには周りも FULL になっている必要がある。移ってから生成させると、生成はワーカーの
                // スレッドで実時間をかけて進む一方、遅れを取り戻すサーバーは tick を続けて回すので、tick で数える
                // タイムアウトが先に来ることがある（CI で、生成の途中に 600 tick を 1 秒ほどで使い切った）
                for (dx in -1..1) {
                    for (dz in -1..1) {
                        nether.getChunk(netherChunk.x + dx, netherChunk.z + dz, ChunkStatus.FULL, true)
                    }
                }
                val sent = ScreenSync.traffic.bytesSent(player)
                val moved = player.changeDimension(
                    DimensionTransition(nether, Vec3(0.5, 64.0, 0.5), Vec3.ZERO, 0f, 0f, DimensionTransition.DO_NOTHING),
                )
                helper.assertTrue(moved === player, "a player keeps its object when changing dimension")
                helper.assertTrue(player.level() === nether, "the player should be in the nether")
                helper.assertTrue(!be.isWatchedBy(player), "changing dimension should stop watching at once")
                helper.assertTrue(ScreenSync.traffic.bytesSent(player) == sent, "the traffic of the same player should stay")
                // 元のディメンションで送ったチャンクの記録は、移った直後に消える
                helper.assertTrue(ScreenSync.sentChunkCount(player) == -1, "changing dimension should drop the sent chunks")
                helper.assertTrue(
                    !ScreenSync.isChunkSent(overworld, player, screenChunk),
                    "the old dimension's chunk should not count as sent after the move",
                )
            }
            .thenIdle(1)
            .thenExecute {
                helper.assertTrue(!be.isWatchedBy(player), "a player in another dimension should not start watching")
            }
            // 偽物の接続は tick されないので、ネザーのチャンクが届くまで、クライアントが受け取ったことにして送る
            .thenWaitUntil {
                val nether = helper.level.server.getLevel(Level.NETHER)!!
                val sender = player.connection.chunkSender
                sender.onChunkBatchReceivedByClient(64f)
                sender.sendNextChunks(player)
                helper.assertTrue(ScreenSync.isChunkSent(nether, player, netherChunk), "the nether chunk should be recorded as sent")
            }
            .thenExecute {
                val nether = helper.level.server.getLevel(Level.NETHER)!!
                helper.assertTrue(ScreenSync.sentChunkCount(player) > 0, "the new level's record should hold the sent chunks")
                helper.assertTrue(
                    !ScreenSync.isChunkSent(overworld, player, netherChunk),
                    "the same coordinates in the old dimension should not count as sent",
                )
                helper.assertTrue(
                    !ScreenSync.isChunkSent(overworld, player, screenChunk),
                    "the old dimension's screen chunk should still not count as sent",
                )
                helper.assertTrue(ScreenSync.isChunkSent(nether, player, netherChunk), "the nether chunk should still be recorded")
                helper.assertTrue(!be.isWatchedBy(player), "the screen in the old dimension should not be watched")
                cleanUp(helper, player)
            }
            .thenSucceed()
    }

    /** 送り直しのお願いは、遠い座標や読み込まれていない座標なら、ブロックエンティティを探す前に捨てる（#382）。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun resyncRequestsForFarOrUnloadedPositionsAreDroppedEarly(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get())
        val (player, _) = watcherAt(helper, ScreenTier.MONOCHROME, 2.0)
        val abs = helper.absolutePos(POS)
        fun outcome(pos: BlockPos) = ScreenSync.handleResync(player, ScreenResyncPayload(pos))
        helper.assertTrue(outcome(abs) == ScreenSync.ResyncOutcome.FORWARDED, "a nearby screen should get the request")
        helper.assertTrue(outcome(abs.east()) == ScreenSync.ResyncOutcome.NO_SCREEN, "a nearby non-screen should be dropped")
        val far = abs.offset((STOP + 32.0).toInt(), 0, 0)
        helper.assertTrue(outcome(far) == ScreenSync.ResyncOutcome.TOO_FAR, "a far position should be dropped first")
        // ワールドの高さの外は読み込まれていない。プレイヤーを近くに置いて、距離では捨てられないようにする
        val top = helper.level.maxBuildHeight
        player.teleportTo(abs.x + 0.5, top + 1.0, abs.z + 0.5)
        val above = BlockPos(abs.x, top + 2, abs.z)
        helper.assertTrue(outcome(above) == ScreenSync.ResyncOutcome.NOT_LOADED, "an unloaded position should be dropped")
        cleanUp(helper, player)
        helper.succeed()
    }

    /**
     * 追跡の範囲に入っていて送る順番待ちでもなくても、チャンクがまだ送れる状態（ブロックが tick する段）でなければ、
     * クライアントはチャンクを持っていないので、見られないものとする（#636）。
     * そうしないと、その間に送ったキーフレームをクライアントが捨て、サーバーは差分しか送らなくなる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenInChunkNotYetSentIsNotWatched(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get())
        val (player, _) = watcherAt(helper, ScreenTier.COLOR, 2.0)
        val level = helper.level
        val here = ChunkPos(helper.absolutePos(POS))
        // 届いたチャンクの画面は、見られる
        helper.assertTrue(ScreenSync.isChunkSent(level, player, here), "the test chunk should have been sent")
        val near = ScreenSync.Host(helper.getBlockEntity<ScreenBlockEntity>(POS)).distanceSquared(player)
        helper.assertTrue(near.isFinite(), "a screen in a sent chunk should be visible but was $near")
        // テストの場所から離れたチャンクを、同期の読み込み（一時的なチケット）で FULL まで読み込む。ブロックが tick する段には
        // ならないので、まだ送れない（画面のブロックエンティティはこの段で読み込まれる）
        val far = ChunkPos(here.x + 40, here.z + 40)
        level.getChunk(far.x, far.z)
        helper.assertTrue(level.chunkSource.chunkMap.getChunkToSend(far.toLong()) == null, "the far chunk should not be sendable yet")
        val farPos = far.getMiddleBlockPosition(helper.absolutePos(POS).y)
        level.setBlock(farPos, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState(), 3)
        try {
            val farScreen = level.getBlockEntity(farPos) as? ScreenBlockEntity
            helper.assertTrue(farScreen != null, "the far screen should have a block entity")
            // 追跡の範囲に入れる（送る順番待ちには入らない。本物のサーバーでも、送れる状態になるまでは入れない）
            player.setChunkTrackingView(ChunkTrackingView.of(here, 64))
            helper.assertTrue(player.chunkTrackingView.contains(far), "the far chunk should be in the tracking view")
            helper.assertTrue(!player.connection.chunkSender.isPending(far.toLong()), "the far chunk should not be pending")
            helper.assertTrue(!ScreenSync.isChunkSent(level, player, far), "the far chunk should not count as sent")
            val d = ScreenSync.Host(farScreen!!).distanceSquared(player)
            helper.assertTrue(d == Double.POSITIVE_INFINITY, "a screen in a chunk not yet sent should not be visible but was $d")
        } finally {
            level.setBlock(farPos, Blocks.AIR.defaultBlockState(), 3)
            cleanUp(helper, player)
        }
        helper.succeed()
    }

    private val SEND_TEST_TICKET: TicketType<ChunkPos> =
        TicketType.create("kibicomputers_screen_send_test", Comparator.comparingLong(ChunkPos::toLong))

    /**
     * [SEND_TEST_TICKET] の距離。段階は 33 − 距離なので、1 で中心がブロックの tick する段（32。送れる状態）になり、FULL になるのは
     * まわりの 3×3 だけで済む（2 だと 5×5 を生成する）
     */
    private const val SEND_TEST_TICKET_DISTANCE = 1

    /**
     * 届いた後のチャンクに、送る前の待ち（`ChunkMap.waitForLightBeforeSending` と同じ `addSendDependency`）が付いて
     * いま送れない状態（`getChunkToSend` が null）になっても、送った事実は変わらないので、見ている人から外れず、描画も届く（#655 のずれ1）。
     *
     * 待ちを、ほかのテストのプレイヤーが追跡しはじめるチャンクに付けると、その人には待ちが終わっても送られなくなる。
     * だれも使わない離れたチャンク（[FarChunks]）に付け、失敗しても必ず外す。
     *
     * 新しいチャンクの生成は実時間かかるので、tick の上限の中で待たず、先に同期で生成しておく（[FarChunks.generate]、#934）。
     * 後始末は、タイムアウトのときも走るように、テストが終わったときに走らせる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun sentChunkStaysWatchedWhileSendIsDelayed(helper: GameTestHelper) {
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）。プレイヤーのチケットで読み込む
        val far = FarChunks.reserve(helper)
        val farPos = far.getMiddleBlockPosition(helper.absolutePos(POS).y)
        ScreenSync.transport = CAPTURE
        val player = helper.makeMockServerPlayerInLevel()
        val client = FakeClient(ScreenTier.COLOR, farPos)
        clients[player] = client
        // 画面の東 2 ブロックに立つ（目の高さを画面の中心にそろえる）
        player.teleportTo(farPos.x + 2.5, farPos.y + 0.5 - player.eyeHeight, farPos.z + 0.5)
        level.chunkSource.move(player)
        val delay = CompletableFuture<Any?>()
        var screen: ScreenBlockEntity? = null
        var sent = 0
        var cleaned = false
        // 何度呼んでもよい（成功の段と、テストが終わったときの両方から呼ぶ）
        fun cleanUpAll() {
            if (cleaned) return
            cleaned = true
            delay.complete(null)
            if (screen != null) level.setBlock(farPos, Blocks.AIR.defaultBlockState(), 3)
            cleanUp(helper, player)
        }
        whenTestEnds(helper) { cleanUpAll() }
        // プレイヤーのチケットを付けた後に、送れる状態に要る 3×3 を生成しておく
        FarChunks.generate(level, far)
        val startTick = level.server.tickCount
        val startNanos = System.nanoTime()
        helper.startSequence()
            // プレイヤーのチケットで読み込まれ、送れる状態になったら届ける
            .thenWaitUntil {
                deliverChunks(player, farPos)
                if (!ScreenSync.isChunkSent(level, player, far)) {
                    helper.fail("the far chunk should be sent to the player (${farChunkState(level, far, player, startTick, startNanos)})")
                }
            }
            .thenExecute {
                // 送った後のチャンクに画面を置く（いちばんよくある置き方）
                level.setBlock(farPos, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState(), 3)
                screen = level.getBlockEntity(farPos) as? ScreenBlockEntity
                helper.assertTrue(screen != null, "the far screen should have a block entity")
                screen!!.text.write(0, 0, "delayed send", 0xFFFFFF, 0)
            }
            .thenWaitUntil {
                helper.assertTrue(screen!!.isWatchedBy(player), "the player should watch the screen")
                helper.assertTrue(client.keyframes == 1, "the first keyframe should arrive")
            }
            .thenExecute {
                val holder = level.chunkSource.chunkMap.getVisibleChunkIfPresent(far.toLong())
                helper.assertTrue(holder != null, "the far chunk should have a holder")
                holder!!.addSendDependency(delay)
                // 以前の推し量り（#654）なら、ここで「送っていない」とみなして見ている人から外していた
                helper.assertTrue(
                    level.chunkSource.chunkMap.getChunkToSend(far.toLong()) == null,
                    "the far chunk should not be sendable now",
                )
                helper.assertTrue(ScreenSync.isChunkSent(level, player, far), "a sent chunk should stay sent")
                sent = client.fragments
                screen!!.text.write(0, 1, "still watched", 0x00FF00, 0)
            }
            .thenExecuteFor(5) {
                helper.assertTrue(screen!!.isWatchedBy(player), "the player should keep watching while sending is delayed")
            }
            .thenExecute {
                helper.assertTrue(client.fragments > sent, "the drawing should be sent while sending is delayed")
                helper.assertTrue(client.keyframes == 1, "no keyframe should be resent: ${client.keyframes}")
                helper.assertTrue(client.matches(screen!!), "the client should match the server")
                cleanUpAll()
            }
            .thenSucceed()
    }

    /**
     * 送れる状態（`getChunkToSend` が null でない）で、追跡の範囲に入っていて順番待ちでもないが、まだ送っていないチャンクの画面は、
     * 見られないものとする（#655 のずれ2。以前の推し量りでは、送ったとみなしていた）。
     *
     * 新しいチャンクの生成は実時間かかるので、tick の上限の中で待たず、先に同期で生成しておく（[FarChunks.generate]、#934）。
     * 後始末は、タイムアウトのときも走るように、テストが終わったときに走らせる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun sendableButUnsentChunkIsNotWatched(helper: GameTestHelper) {
        val level = helper.level
        val cache = level.chunkSource
        // だれも読み込んでいない離れたチャンク（#747）。テスト用のチケットで読み込む
        val far = FarChunks.reserve(helper)
        val farPos = far.getMiddleBlockPosition(helper.absolutePos(POS).y)
        ScreenSync.transport = CAPTURE
        val player = helper.makeMockServerPlayerInLevel()
        var placed = false
        var cleaned = false
        // 何度呼んでもよい（成功の段と、テストが終わったときの両方から呼ぶ）
        fun cleanUpAll() {
            if (cleaned) return
            cleaned = true
            if (placed) level.setBlock(farPos, Blocks.AIR.defaultBlockState(), 3)
            cache.removeRegionTicket(SEND_TEST_TICKET, far, SEND_TEST_TICKET_DISTANCE, far)
            cleanUp(helper, player)
        }
        // 中心のチャンクがブロックの tick する段（送れる状態）になるチケット。まわりの 3×3 が FULL になる
        cache.addRegionTicket(SEND_TEST_TICKET, far, SEND_TEST_TICKET_DISTANCE, far)
        whenTestEnds(helper) { cleanUpAll() }
        // チケットを付けた後に、送れる状態に要る 3×3 を生成しておく
        FarChunks.generate(level, far)
        val startTick = level.server.tickCount
        val startNanos = System.nanoTime()
        helper.startSequence()
            .thenWaitUntil {
                cache.runDistanceManagerUpdates()
                if (cache.chunkMap.getChunkToSend(far.toLong()) == null) {
                    helper.fail("the far chunk should become sendable (${farChunkState(level, far, null, startTick, startNanos)})")
                }
            }
            .thenExecute {
                try {
                    level.setBlock(farPos, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState(), 3)
                    placed = true
                    val screen = level.getBlockEntity(farPos) as? ScreenBlockEntity
                    helper.assertTrue(screen != null, "the far screen should have a block entity")
                    // 画面のそばに立たせ、追跡の範囲に入れる（送る順番待ちには入れない）
                    player.teleportTo(farPos.x + 2.5, farPos.y + 0.5 - player.eyeHeight, farPos.z + 0.5)
                    player.setChunkTrackingView(ChunkTrackingView.of(far, 2))
                    helper.assertTrue(player.chunkTrackingView.contains(far), "the far chunk should be in the tracking view")
                    helper.assertTrue(!player.connection.chunkSender.isPending(far.toLong()), "the far chunk should not be pending")
                    helper.assertTrue(cache.chunkMap.getChunkToSend(far.toLong()) != null, "the far chunk should be sendable")
                    helper.assertTrue(!ScreenSync.isChunkSent(level, player, far), "a chunk never sent should not count as sent")
                    val d = ScreenSync.Host(screen!!).distanceSquared(player)
                    helper.assertTrue(d == Double.POSITIVE_INFINITY, "a screen in a chunk not yet sent should not be visible but was $d")
                } finally {
                    cleanUpAll()
                }
            }
            .thenSucceed()
    }

    /**
     * 見ている画面のチャンクを、同じ tick の中で送り直すと（追跡から外れて戻る）、クライアントは画面のブロックエンティティを
     * 作り直して画面が空になる。tick の終わりの距離の確かめでは外れないので、送ったときに見ている人から外し、
     * 次に見はじめる人を探す tick にキーフレームを送り直す（#655）。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun resentChunkResendsKeyframe(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get())
        val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
        be.text.write(0, 0, "resent chunk", 0xFFFFFF, 0)
        val (player, client) = watcherAt(helper, ScreenTier.COLOR, 2.0)
        val level = helper.level
        val here = ChunkPos(helper.absolutePos(POS))
        helper.startSequence()
            .thenWaitUntil {
                helper.assertTrue(be.isWatchedBy(player), "the player should be watching")
                helper.assertTrue(client.keyframes == 1, "the first keyframe should arrive")
            }
            .thenExecute {
                val back = player.position()
                // 追跡の範囲（2 チャンク）の外へ行って戻る。画面のチャンクは UnWatch → Watch → Sent になる
                player.teleportTo(back.x + 16 * 8, back.y, back.z)
                level.chunkSource.move(player)
                helper.assertTrue(!ScreenSync.isChunkSent(level, player, here), "an unwatched chunk should not count as sent")
                player.teleportTo(back.x, back.y, back.z)
                level.chunkSource.move(player)
                helper.assertTrue(!ScreenSync.isChunkSent(level, player, here), "a chunk waiting to be resent should not count as sent")
                deliverChunks(player, helper.absolutePos(POS))
                helper.assertTrue(ScreenSync.isChunkSent(level, player, here), "the chunk should be sent again")
                helper.assertTrue(!be.isWatchedBy(player), "resending the chunk should drop the watcher until the next scan")
            }
            .thenWaitUntil { helper.assertTrue(client.keyframes == 2, "a keyframe should be resent after the chunk is resent") }
            .thenExecute {
                helper.assertTrue(be.isWatchedBy(player), "the player should watch again")
                helper.assertTrue(client.matches(be), "the client should match the server")
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
