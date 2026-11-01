package pro.ikili.kibicomputers.core.display;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 見ている人の集合と送信量の勘定（#359）が Java から自然に使えることを、コンパイルが通るかで確かめるテスト。
 */
class ScreenWatchersJavaTest {
    @Test
    void watchersFromJava() {
        new ScreenWatchers<String>();
        new ScreenWatchers<String>(ScreenWatchers.DEFAULT_START_DISTANCE);
        new ScreenWatchers<String>(10.0, 10.0 + ScreenWatchers.DEFAULT_HYSTERESIS);
        new ScreenWatchers<String>(10.0, 18.0, ScreenWatchers.DEFAULT_RESYNC_INTERVAL_TICKS);
        ScreenWatchers<String> watchers =
            new ScreenWatchers<>(10.0, 18.0, 40, ScreenWatchers.DEFAULT_SCAN_INTERVAL_TICKS);

        List<String> sent = new ArrayList<>();
        ScreenSyncHost<String> host = new ScreenSyncHost<String>() {
            @Override
            public Iterable<String> candidates() {
                return List.of("alice", "bob");
            }

            @Override
            public double distanceSquared(String player) {
                return player.equals("alice") ? 4.0 : Double.POSITIVE_INFINITY;
            }

            @Override
            public void send(String player, byte[] fragment) {
                sent.add(player);
            }
        };
        ScreenSyncTraffic<String> traffic = new ScreenSyncTraffic<>();
        ScreenSyncSender sender = new ScreenSyncSender(new TextBuffer(4, 2));
        watchers.tick(0L, sender, host, traffic);
        watchers.tick(1L, sender, host, null);

        assertTrue(watchers.isWatching("alice"));
        assertFalse(watchers.isWatching("bob"));
        assertEquals(1, watchers.getCount());
        assertEquals(List.of("alice"), sent);
        assertTrue(watchers.requestResync("alice", 2L));
        assertFalse(watchers.requestResync("bob", 2L));
        assertEquals(1L, traffic.keyframesSent("alice"));
        assertTrue(traffic.bytesSent("alice") > 0);
        assertEquals(traffic.bytesSent("alice"), traffic.getTotalBytes());
        traffic.recordBytes("bob", 1);
        traffic.recordKeyframe("bob");
        traffic.forget("bob");
        traffic.clear();
        watchers.remove("alice");
        watchers.clear();
    }
}
