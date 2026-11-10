package dev.klleriston.netlab.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventBusTest {

    private static Event ev(long seq) {
        return new Events.PacketSent(seq, Protocol.UDP, seq, 64);
    }

    @Test
    void deliversEventsInOrderToEachSubscriber() throws Exception {
        EventBus bus = new EventBus();
        List<Long> a = new CopyOnWriteArrayList<>();
        List<Long> b = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(100);
        bus.subscribe("a", e -> { a.add(e.timestampNanos()); done.countDown(); });
        bus.subscribe("b", e -> { b.add(e.timestampNanos()); done.countDown(); });
        // 100 latch counts = 50 events * 2 subscribers
        for (long i = 0; i < 50; i++) bus.publish(ev(i));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        List<Long> expected = new java.util.ArrayList<>();
        for (long i = 0; i < 50; i++) expected.add(i);
        assertEquals(expected, a);
        assertEquals(expected, b);
        bus.close();
    }

    @Test
    void publishNeverBlocksWhenSubscriberIsStuck() throws Exception {
        EventBus bus = new EventBus();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        Subscription s = bus.subscribe("stuck", 10, e -> {
            started.countDown();
            try { never.await(); } catch (InterruptedException ignored) { }
        });
        bus.publish(ev(0));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        for (long i = 1; i <= 1000; i++) bus.publish(ev(i));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 200, "publish took " + elapsedMs + " ms");
        assertTrue(s.droppedCount() >= 989, "dropped " + s.droppedCount());
        bus.close();
    }

    @Test
    void fullQueueDropsOldestKeepingNewest() throws Exception {
        EventBus bus = new EventBus();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch gotLast = new CountDownLatch(1);
        List<Long> seen = new CopyOnWriteArrayList<>();
        bus.subscribe("slow", 10, e -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException ignored) { return; }
            seen.add(e.timestampNanos());
            if (e.timestampNanos() == 500) gotLast.countDown();
        });
        bus.publish(ev(0));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        for (long i = 1; i <= 500; i++) bus.publish(ev(i));
        release.countDown();
        assertTrue(gotLast.await(5, TimeUnit.SECONDS));
        assertEquals(500L, seen.get(seen.size() - 1));
        assertTrue(seen.size() <= 11, "seen " + seen.size());
        bus.close();
    }

    @Test
    void closedSubscriptionReceivesNothingMore() throws Exception {
        EventBus bus = new EventBus();
        List<Long> seen = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        Subscription s = bus.subscribe("c", e -> { seen.add(e.timestampNanos()); first.countDown(); });
        bus.publish(ev(1));
        assertTrue(first.await(5, TimeUnit.SECONDS));
        s.close();
        s.close(); // idempotent
        bus.publish(ev(2));
        bus.publish(ev(3));
        Thread.sleep(100);
        assertEquals(List.of(1L), seen);
        bus.close();
    }

    @Test
    void throwingHandlerDoesNotKillSubscriber() throws Exception {
        EventBus bus = new EventBus();
        CountDownLatch second = new CountDownLatch(1);
        bus.subscribe("t", e -> {
            if (e.timestampNanos() == 1) throw new IllegalStateException("boom");
            second.countDown();
        });
        bus.publish(ev(1));
        bus.publish(ev(2));
        assertTrue(second.await(5, TimeUnit.SECONDS));
        bus.close();
    }
}
