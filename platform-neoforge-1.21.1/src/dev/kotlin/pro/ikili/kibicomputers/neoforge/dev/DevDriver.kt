package pro.ikili.kibicomputers.neoforge.dev

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.tutorial.TutorialSteps
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.loading.FMLEnvironment
import org.lwjgl.glfw.GLFW
import pro.ikili.kibicomputers.devdriver.DevDriverServer
import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.devdriver.DevRequest
import pro.ikili.kibicomputers.devdriver.DevResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/**
 * 開発クライアントを外から操作する口（#252）。`runClient` でだけ起動し、リリースの jar には入らない。
 * 使い方は `.claude/skills/run-minecraft/SKILL.md` を参照。
 */
object DevDriver {
    private val LOGGER = LogUtils.getLogger()
    private var server: DevDriverServer? = null
    private var screenshotCount = 0

    @JvmStatic
    fun start(port: Int, portFile: String?) {
        if (!FMLEnvironment.dist.isClient || server != null) return
        val s = DevDriverServer(port, portFile?.let { Path.of(it) }, ::handle)
        val actual = s.start()
        server = s
        LOGGER.info("Dev driver listening on 127.0.0.1:{}", actual)
    }

    private fun handle(request: DevRequest): DevResponse {
        val body = if (request.body.isBlank()) JsonObject() else JsonParser.parseString(request.body).asJsonObject
        return when ("${request.method} ${request.path}") {
            "GET /status" -> onMain { status() }
            "GET /widgets" -> onMain { widgets() }
            "POST /world" -> onMain { world(body) }
            "POST /command" -> command(body)
            "POST /key" -> onMain { key(body) }
            "POST /type" -> onMain { type(body) }
            "POST /click" -> onMain { click(body) }
            "POST /scroll" -> onMain { scroll(body) }
            "POST /use" -> onMain { use(body) }
            "POST /close" -> onMain { mc().setScreen(null); ok() }
            "POST /screenshot" -> onMain { screenshot(body) }
            "POST /screen" -> onMain { screen(body) }
            // クライアントを正常に終了する（このクライアントだけを閉じる）
            "POST /quit" -> onMain { mc().stop(); ok() }
            else -> DevResponse.error(404, "unknown endpoint: ${request.method} ${request.path}")
        }
    }

    private fun mc(): Minecraft = Minecraft.getInstance()

    private fun onMain(action: () -> DevResponse): DevResponse {
        val mc = Minecraft.getInstance() ?: return DevResponse.error(503, "client is not ready")
        return try {
            mc.submit(action).get(30, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            DevResponse.error(503, "client main thread is busy (still loading?)")
        }
    }

    private fun ok(extra: JsonObject.() -> Unit = {}): DevResponse =
        DevResponse.ok(JsonObject().apply { addProperty("ok", true); extra() }.toString())

    // 背景に回っても止まらず、チュートリアルの案内も出ないようにする
    private fun keepRunning(mc: Minecraft) {
        mc.options.pauseOnLostFocus = false
        mc.options.onboardAccessibility = false
        mc.options.tutorialStep = TutorialSteps.NONE
    }

    private fun status(): DevResponse {
        val mc = mc()
        keepRunning(mc)
        val window = mc.window
        return ok {
            addProperty("ready", mc.overlay == null)
            addProperty("screen", mc.screen?.javaClass?.simpleName)
            addProperty("title", mc.screen?.title?.string)
            addProperty("inWorld", mc.level != null && mc.player != null)
            mc.player?.let { p ->
                addProperty("x", p.x)
                addProperty("y", p.y)
                addProperty("z", p.z)
            }
            addProperty("guiWidth", window.guiScaledWidth)
            addProperty("guiHeight", window.guiScaledHeight)
            addProperty("guiScale", window.guiScale)
        }
    }

    private fun widgets(): DevResponse {
        val screen = mc().screen ?: return DevResponse.error(409, "no screen is open")
        val list = JsonArray()
        screen.children().filterIsInstance<AbstractWidget>().forEachIndexed { i, w ->
            list.add(JsonObject().apply {
                addProperty("index", i)
                addProperty("type", w.javaClass.simpleName)
                addProperty("text", w.message.string)
                if (w is EditBox) addProperty("value", w.value)
                addProperty("x", w.x)
                addProperty("y", w.y)
                addProperty("width", w.width)
                addProperty("height", w.height)
                addProperty("active", w.active)
                addProperty("visible", w.visible)
            })
        }
        return ok { addProperty("screen", screen.javaClass.simpleName); add("widgets", list) }
    }

    // 平らなクリエイティブのワールドを作って入る（reset: true なら作り直す）
    private fun world(body: JsonObject): DevResponse {
        val mc = mc()
        keepRunning(mc)
        if (mc.level != null) return DevResponse.error(409, "already in a world")
        val name = body.get("name")?.asString ?: "kibi-dev"
        val dir = mc.gameDirectory.toPath().resolve("saves").resolve(name)
        val reset = body.get("reset")?.asBoolean ?: false
        if (reset && Files.exists(dir)) {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        if (Files.exists(dir)) {
            mc.createWorldOpenFlows().openWorld(name) {}
        } else {
            val settings = LevelSettings(
                name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT,
            )
            mc.createWorldOpenFlows().createFreshLevel(
                name, settings, WorldOptions(0L, false, false),
                { registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen,
            )
        }
        return ok { addProperty("name", name); addProperty("note", "poll /status until inWorld is true") }
    }

    // 統合サーバーで、プレイヤーとして権限4でコマンドを実行する。出力も返す
    private fun command(body: JsonObject): DevResponse {
        val text = body.get("command")?.asString ?: return DevResponse.error(400, "command is required")
        val mc = mc()
        val server = mc.singleplayerServer ?: return DevResponse.error(409, "not in a singleplayer world")
        val uuid = mc.player?.uuid ?: return DevResponse.error(409, "no player")
        val messages = JsonArray()
        val result = server.submit(Supplier {
            val player = server.playerList.getPlayer(uuid) ?: return@Supplier -1
            val collector = object : CommandSource {
                override fun sendSystemMessage(component: Component) { messages.add(component.string) }
                override fun acceptsSuccess(): Boolean = true
                override fun acceptsFailure(): Boolean = true
                override fun shouldInformAdmins(): Boolean = false
            }
            val source = player.createCommandSourceStack().withPermission(4).withSource(collector)
            server.commands.performPrefixedCommand(source, text.removePrefix("/"))
            0
        }).get(30, TimeUnit.SECONDS)
        if (result < 0) return DevResponse.error(409, "player not found on the server")
        return ok { add("output", messages) }
    }

    // キー名（"e"、"escape"、"enter"、"left.shift" など）か keyCode でキーを押して離す
    private fun key(body: JsonObject): DevResponse {
        val mc = mc()
        val code = body.get("keyCode")?.asInt
            ?: body.get("key")?.asString?.let { InputConstants.getKey("key.keyboard.${it.lowercase()}").value }
            ?: return DevResponse.error(400, "key or keyCode is required")
        if (code == InputConstants.UNKNOWN.value) return DevResponse.error(400, "unknown key")
        val mods = body.get("modifiers")?.asInt ?: 0
        val handle = mc.window.window
        val action = body.get("action")?.asString ?: "tap"
        if (action != "release") mc.keyboardHandler.keyPress(handle, code, 0, GLFW.GLFW_PRESS, mods)
        if (action != "press") mc.keyboardHandler.keyPress(handle, code, 0, GLFW.GLFW_RELEASE, mods)
        return ok { addProperty("keyCode", code) }
    }

    // 開いている画面に文字を入力する（日本語などの IME で確定した文字と同じ経路）
    private fun type(body: JsonObject): DevResponse {
        val screen = mc().screen ?: return DevResponse.error(409, "no screen is open")
        val text = body.get("text")?.asString ?: return DevResponse.error(400, "text is required")
        text.forEach { screen.charTyped(it, 0) }
        return ok { addProperty("typed", text.length) }
    }

    // GUI 座標（guiScaled）でクリックする。widget（ボタンの文字列）か index で指定もできる
    private fun click(body: JsonObject): DevResponse {
        val screen = mc().screen ?: return DevResponse.error(409, "no screen is open")
        val widgets = screen.children().filterIsInstance<AbstractWidget>()
        val target = body.get("widget")?.asString?.let { label -> widgets.firstOrNull { it.message.string == label } }
            ?: body.get("index")?.asInt?.let { widgets.getOrNull(it) }
        val x = target?.let { it.x + it.width / 2.0 } ?: body.get("x")?.asDouble
            ?: return DevResponse.error(400, "x/y, widget or index is required")
        val y = target?.let { it.y + it.height / 2.0 } ?: body.get("y")?.asDouble
            ?: return DevResponse.error(400, "x/y, widget or index is required")
        val button = body.get("button")?.asInt ?: 0
        val clicked = screen.mouseClicked(x, y, button)
        screen.mouseReleased(x, y, button)
        return ok { addProperty("handled", clicked); addProperty("x", x); addProperty("y", y) }
    }

    private fun scroll(body: JsonObject): DevResponse {
        val screen = mc().screen ?: return DevResponse.error(409, "no screen is open")
        val x = body.get("x")?.asDouble ?: 0.0
        val y = body.get("y")?.asDouble ?: 0.0
        val amount = body.get("amount")?.asDouble ?: return DevResponse.error(400, "amount is required")
        return ok { addProperty("handled", screen.mouseScrolled(x, y, 0.0, amount)) }
    }

    // ブロックを右クリックする（GUI を開くなど）
    private fun use(body: JsonObject): DevResponse {
        val mc = mc()
        val player = mc.player ?: return DevResponse.error(409, "no player")
        val gameMode = mc.gameMode ?: return DevResponse.error(409, "no game mode")
        val pos = BlockPos(body.get("x").asInt, body.get("y").asInt, body.get("z").asInt)
        val face = body.get("face")?.asString?.let { Direction.byName(it) } ?: Direction.UP
        val hit = BlockHitResult(Vec3.atCenterOf(pos), face, pos, false)
        val result = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        return ok { addProperty("result", result.toString()) }
    }

    // Minecraft 自身の描画からスクリーンショットを撮る（ウィンドウを前面に出さない）
    private fun screenshot(body: JsonObject): DevResponse {
        val mc = mc()
        val name = body.get("name")?.asString ?: "dev-${++screenshotCount}.png"
        Screenshot.grab(mc.gameDirectory, name, mc.mainRenderTarget) {}
        val path = mc.gameDirectory.toPath().resolve("screenshots").resolve(name).toAbsolutePath().normalize()
        return ok { addProperty("path", path.toString()) }
    }

    // 画面ブロックのバッファに書く。既定では統合サーバー側に書き、画面の同期（#140）でクライアントに届ける。
    // side: "server"（既定）か "client"（クライアントのバッファに直接書く）。
    // lines: 上の行から書く文字列、fg/bg: 色、clear: 先に消すか、pattern: "gradient" でピクセル画面にグラデーションを描く、
    // scroll: 書いたあとに上へずらす行数
    private fun screen(body: JsonObject): DevResponse {
        val level = mc().level ?: return DevResponse.error(409, "not in a world")
        val pos = BlockPos(body.get("x").asInt, body.get("y").asInt, body.get("z").asInt)
        if (body.get("side")?.asString == "client") {
            val be = level.getBlockEntity(pos) as? ScreenBlockEntity ?: return DevResponse.error(404, "no screen at $pos")
            return drawOnScreen(be, body)
        }
        val server = mc().singleplayerServer ?: return DevResponse.error(409, "not a singleplayer world")
        val dimension = level.dimension()
        return server.submit(Supplier {
            val serverLevel = server.getLevel(dimension)
            val be = serverLevel?.getBlockEntity(pos) as? ScreenBlockEntity
            if (be == null) DevResponse.error(404, "no screen at $pos") else drawOnScreen(be, body)
        }).get(30, TimeUnit.SECONDS)
    }

    private fun drawOnScreen(be: ScreenBlockEntity, body: JsonObject): DevResponse {
        val fg = body.get("fg")?.asInt ?: 0xFFFFFF
        val bg = body.get("bg")?.asInt ?: 0x000000
        if (body.get("clear")?.asBoolean != false) be.text.clear()
        val col = body.get("col")?.asInt ?: 0
        val row = body.get("row")?.asInt ?: 0
        // 全角の文字は2セルに描かれるので、右隣のセルに空白を置く
        body.getAsJsonArray("lines")?.forEachIndexed { i, line ->
            var x = col
            line.asString.codePoints().forEach { cp ->
                be.text.set(x, row + i, cp, fg, bg)
                if (CharWidth.isWide(cp)) be.text.set(x + 1, row + i, ' '.code, fg, bg)
                x += CharWidth.of(cp)
            }
        }
        body.get("scroll")?.asInt?.takeIf { it > 0 }?.let { n ->
            val t = be.text
            t.copy(0, n, t.width, t.height - n, 0, -n)
            t.fill(0, t.height - n, t.width, n, ' '.code, fg, bg)
        }
        if (body.get("pattern")?.asString == "gradient") {
            val p = be.pixels ?: return DevResponse.error(409, "this screen has no pixels")
            for (y in 0 until p.height) {
                for (x in 0 until p.width) p.set(x, y, ((x * 255 / p.width) shl 16) or ((y * 255 / p.height) shl 8) or 0x40)
            }
        }
        return ok {
            addProperty("width", be.text.width)
            addProperty("height", be.text.height)
            addProperty("watchers", be.watcherCount)
        }
    }
}
