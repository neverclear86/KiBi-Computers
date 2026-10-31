package pro.ikili.kibicomputers.core.display;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画面の同期（#140）が Java から自然に使えることを、コンパイルが通るかで確かめるテスト。
 */
class ScreenSyncJavaTest {
    @Test
    void syncFromJava() {
        TextBuffer serverText = new TextBuffer(8, 2);
        ScreenSyncSender sender = new ScreenSyncSender(serverText);
        new ScreenSyncSender(serverText, ScreenSyncSender.DEFAULT_BYTES_PER_SECOND);
        new ScreenSyncSender(serverText, 4096, ScreenSyncSender.DEFAULT_MAX_FRAGMENT_SIZE);
        sender.setPixels(null);
        serverText.write(0, 0, "java", 0xFFFFFF, 0);

        ScreenSyncFlush flush = sender.flush(false, true);
        assertFalse(flush.isEmpty());
        assertTrue(sender.isRecording());
        List<byte[]> keyframe = flush.forNewWatchers;

        TextBuffer clientText = new TextBuffer(8, 2);
        ScreenSyncTarget target = new ScreenSyncTarget() {
            @Override
            public TextBuffer getText() {
                return clientText;
            }

            @Override
            public PixelBuffer pixels() {
                return null;
            }

            @Override
            public PixelBuffer pixelsIfCreated() {
                return null;
            }
        };
        ScreenSyncReceiver receiver = new ScreenSyncReceiver();
        for (byte[] fragment : keyframe) receiver.receive(fragment, target);
        receiver.reset();
        assertTrue(serverText.contentEquals(clientText));
        assertTrue(ScreenSyncFlush.EMPTY.isEmpty());
    }
}
