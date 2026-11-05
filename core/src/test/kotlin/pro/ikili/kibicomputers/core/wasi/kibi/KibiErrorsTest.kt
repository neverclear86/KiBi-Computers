package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import java.io.File

/** `kibi:kernel/types.error-code` と p1 の errno の対応（#73。ADR 0073 の Q4-A） */
class KibiErrorsTest {
    @Test
    fun `判別子に1を足すとp1のerrnoになる`() {
        for (e in Errno.entries) {
            if (e == Errno.SUCCESS) continue
            assertEquals(e.code, KibiErrors.code(e) + 1, e.name)
            assertEquals(KibiErrors.code(e), KibiErrors.code(FsException(e)))
        }
        assertThrows<IllegalArgumentException> { KibiErrors.code(Errno.SUCCESS) }
    }

    @Test
    fun `名前の並びはErrnoの並びと同じで、プランで使う名前が正しい判別子にある`() {
        val errnos = Errno.entries.filter { it != Errno.SUCCESS }.sortedBy { it.code }
        assertEquals(errnos.size, KibiErrors.NAMES.size)
        assertEquals(errnos.indices.toList(), errnos.map { KibiErrors.code(it) })
        val expected = mapOf(
            Errno.TOOBIG to "too-big", Errno.ACCES to "access", Errno.BADF to "bad-descriptor", Errno.FAULT to "fault",
            Errno.INVAL to "invalid", Errno.MFILE to "mfile", Errno.NFILE to "nfile", Errno.NOTCAPABLE to "not-capable",
        )
        for ((e, name) in expected) assertEquals(name, KibiErrors.NAMES[KibiErrors.code(e)], e.name)
    }

    @Test
    fun `types_witのenumの並びはNAMESと一致する`() {
        val wit = File("src/main/wit/kibi-kernel/types.wit").readText()
        val body = wit.substringAfter("enum error-code {").substringBefore("}")
        val names = body.lines().map { it.substringBefore("//").trim().trimEnd(',') }.filter { it.isNotEmpty() }
        assertEquals(KibiErrors.NAMES, names)
    }
}
