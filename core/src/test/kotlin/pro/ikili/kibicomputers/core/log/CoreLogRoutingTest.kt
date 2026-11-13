package pro.ikili.kibicomputers.core.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class CoreLogRoutingTest {
    private data class Line(val name: String, val level: CoreLogLevel, val message: String, val thrown: Throwable?)

    // 本物の `kibicomputers` には付けない（ほかのテストのログを乱さない）
    private val root = "kibicomputers-test-${UUID.randomUUID()}"
    private val lines: MutableList<Line> = Collections.synchronizedList(ArrayList())
    private val sink = CoreLogSink { name, level, message, thrown -> lines += Line(name, level, message, thrown) }

    @Test
    fun routesSystemLoggerLinesToSinkWithNameLevelMessageAndThrowable() {
        val handle = CoreLogRouting.install(sink, root)
        assertNotNull(handle)
        try {
            val log = System.getLogger("$root.child")
            val error = RuntimeException("boom")
            log.log(System.Logger.Level.ERROR, "失敗した", error)
            log.log(System.Logger.Level.WARNING, "警告 {} は書式ではない")
            log.log(System.Logger.Level.INFO, "情報")
            log.log(System.Logger.Level.DEBUG, "細かい")
            log.log(System.Logger.Level.TRACE, "もっと細かい")

            assertEquals(
                listOf(
                    Line("$root.child", CoreLogLevel.ERROR, "失敗した", error),
                    Line("$root.child", CoreLogLevel.WARN, "警告 {} は書式ではない", null),
                    Line("$root.child", CoreLogLevel.INFO, "情報", null),
                    Line("$root.child", CoreLogLevel.DEBUG, "細かい", null),
                    Line("$root.child", CoreLogLevel.TRACE, "もっと細かい", null),
                ),
                lines.toList(),
            )
            assertSame(error, lines[0].thrown)
        } finally {
            handle!!.close()
        }
    }

    @Test
    fun formatsMessageFormatParameters() {
        val handle = CoreLogRouting.install(sink, root)
        try {
            System.getLogger("$root.fmt").log(System.Logger.Level.INFO, "Found {0} machines", 3)
            assertEquals(listOf("Found 3 machines"), lines.map { it.message })
        } finally {
            handle!!.close()
        }
    }

    @Test
    fun doesNotPassLinesToParentConsoleHandler() {
        val toParent = Collections.synchronizedList(ArrayList<String>())
        val capture = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.loggerName?.startsWith(root) == true) toParent += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val jdkRoot = Logger.getLogger("")
        jdkRoot.addHandler(capture)
        val handle = CoreLogRouting.install(sink, root)
        try {
            System.getLogger("$root.child").log(System.Logger.Level.WARNING, "一度だけ")
            assertEquals(1, lines.size)
            assertEquals(emptyList<String>(), toParent.toList())
        } finally {
            handle!!.close()
            jdkRoot.removeHandler(capture)
        }
        // 外した後は元どおり親に流れ、sink には来ない
        jdkRoot.addHandler(capture)
        try {
            System.getLogger("$root.child").log(System.Logger.Level.WARNING, "外した後")
            assertEquals(listOf("外した後"), toParent.toList())
            assertEquals(1, lines.size)
        } finally {
            jdkRoot.removeHandler(capture)
        }
    }

    @Test
    fun secondInstallForSameRootDoesNothing() {
        val first = CoreLogRouting.install(sink, root)
        try {
            assertNotNull(first)
            assertNull(CoreLogRouting.install(sink, root))
            System.getLogger("$root.x").log(System.Logger.Level.INFO, "1回")
            assertEquals(1, lines.size)
        } finally {
            first!!.close()
        }
        // 外したら、また付けられる
        val again = CoreLogRouting.install(sink, root)
        assertNotNull(again)
        again!!.close()
    }

    @Test
    fun minLevelFiltersLowerLines() {
        val handle = CoreLogRouting.install(sink, root, CoreLogLevel.INFO)
        try {
            val log = System.getLogger("$root.lvl")
            assertTrue(!log.isLoggable(System.Logger.Level.DEBUG))
            log.log(System.Logger.Level.DEBUG, "落ちる")
            log.log(System.Logger.Level.INFO, "通る")
            assertEquals(listOf("通る"), lines.map { it.message })
        } finally {
            handle!!.close()
        }
    }

    @Test
    fun sinkFailureDoesNotLeakToCaller() {
        val handle = CoreLogRouting.install({ _, _, _, _ -> throw IllegalStateException("sink broke") }, root)
        try {
            // 失敗は Handler の ErrorManager（標準エラー）に回り、呼び出し側には投げない
            System.getLogger("$root.child").log(System.Logger.Level.WARNING, "投げても漏れない")
        } finally {
            handle!!.close()
        }
    }

    @Test
    fun mapsJulLevelsByThreshold() {
        assertEquals(CoreLogLevel.ERROR, CoreLogLevel.of(Level.SEVERE))
        assertEquals(CoreLogLevel.WARN, CoreLogLevel.of(Level.WARNING))
        assertEquals(CoreLogLevel.INFO, CoreLogLevel.of(Level.INFO))
        assertEquals(CoreLogLevel.INFO, CoreLogLevel.of(Level.CONFIG))
        assertEquals(CoreLogLevel.DEBUG, CoreLogLevel.of(Level.FINE))
        assertEquals(CoreLogLevel.TRACE, CoreLogLevel.of(Level.FINER))
        assertEquals(CoreLogLevel.TRACE, CoreLogLevel.of(Level.FINEST))
        assertEquals(CoreLogLevel.WARN, CoreLogLevel.of(object : Level("CUSTOM", 950) {}))
    }
}
