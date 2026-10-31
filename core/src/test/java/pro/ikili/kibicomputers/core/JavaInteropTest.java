package pro.ikili.kibicomputers.core;

import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公開APIが Java から自然に呼べることを、コンパイルが通るかで確かめるテスト。
 * Kotlin 固有の書き方（Companion や INSTANCE 経由の呼び出し）が必要になったら失敗させたい。
 */
class JavaInteropTest {
    @Test
    void constantsAreStaticFields() {
        assertEquals("kibicomputers", KiBiComputers.MOD_ID);
    }

    @Test
    void runtimeSelfCheckIsStatic() {
        assertEquals(3, RuntimeSelfCheck.add(1, 2));
        assertTrue(RuntimeSelfCheck.isWorking());
    }
}
