package dev.klleriston.netlab.event;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Assinatura com fila limitada (descarta o mais antigo quando cheia) e uma virtual thread consumidora. */
public final class Subscription {

    private final BlockingQueue<Event> queue;
    private final Consumer<Event> handler;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private final Consumer<Subscription> onClose;
    private volatile boolean closed;

    Subscription(String name, int capacity, Consumer<Event> handler, Consumer<Subscription> onClose) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.handler = handler;
        this.onClose = onClose;
        this.thread = Thread.ofVirtual().name("eventbus-" + name).start(this::run);
    }

    private void run() {
        while (!closed) {
            Event e;
            try {
                e = queue.take();
            } catch (InterruptedException ie) {
                return;
            }
            if (closed) return;
            try {
                handler.accept(e);
            } catch (RuntimeException | Error ignored) {
                // um handler defeituoso não derruba o assinante
            }
        }
    }

    void offer(Event e) {
        if (closed) return;
        while (!queue.offer(e)) {
            if (queue.poll() != null) dropped.incrementAndGet();
        }
    }

    /** Eventos descartados por fila cheia. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Idempotente; para a thread do assinante. */
    public void close() {
        if (closed) return;
        closed = true;
        queue.clear();
        thread.interrupt();
        onClose.accept(this);
    }
}
