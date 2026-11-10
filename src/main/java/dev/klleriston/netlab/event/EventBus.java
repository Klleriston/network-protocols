package dev.klleriston.netlab.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Barramento de eventos não bloqueante: cada assinante tem fila própria e nunca atrasa quem publica. */
public final class EventBus {

    private static final int DEFAULT_CAPACITY = 10_000;

    private final int defaultCapacity;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public EventBus() {
        this(DEFAULT_CAPACITY);
    }

    public EventBus(int defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public Subscription subscribe(String name, Consumer<Event> handler) {
        return subscribe(name, defaultCapacity, handler);
    }

    public Subscription subscribe(String name, int capacity, Consumer<Event> handler) {
        Subscription s = new Subscription(name, capacity, handler, subscriptions::remove);
        subscriptions.add(s);
        if (closed) s.close();
        return s;
    }

    public void publish(Event event) {
        if (closed) return;
        for (Subscription s : subscriptions) s.offer(event);
    }

    public void close() {
        closed = true;
        for (Subscription s : subscriptions) s.close();
    }
}
