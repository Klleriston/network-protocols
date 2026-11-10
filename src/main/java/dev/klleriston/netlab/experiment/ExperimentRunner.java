package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Roda um experimento por vez numa thread de plataforma e publica seu ciclo de vida no barramento. */
public final class ExperimentRunner {

    private static final String UNREACHABLE =
            "Sem resposta de %s. O server está rodando? O Firewall do Windows está liberando Java nas portas TCP/UDP?";

    private record Run(Thread thread, ExperimentContext ctx) { }

    private final EventBus bus;
    private final LongSupplier clock;
    private final Supplier<ImpairmentProfile> impairment;
    private final Random random;
    private final Target target;
    private Run current;

    public ExperimentRunner(EventBus bus, LongSupplier clock, Supplier<ImpairmentProfile> impairment, Random random,
                            Target target) {
        this.bus = bus;
        this.clock = clock;
        this.impairment = impairment;
        this.random = random;
        this.target = target;
    }

    /** Inicia o experimento; false se já houver um rodando. */
    public synchronized boolean start(Experiment e, Map<String, String> params) {
        if (current != null) return false;
        ExperimentContext ctx = new ExperimentContext(target, bus, clock, impairment, random, params);
        Thread thread = Thread.ofPlatform().name("experiment-" + e.name()).unstarted(() -> execute(e, ctx));
        current = new Run(thread, ctx);
        bus.publish(new Events.ExperimentStarted(clock.getAsLong(), e.name()));
        thread.start();
        return true;
    }

    /** Cancela o run atual, fecha seus recursos e interrompe a thread sem esperar por ela; false se nada rodando. */
    public boolean stop() {
        Run run;
        synchronized (this) {
            run = current;
        }
        if (run == null) return false;
        run.ctx().cancel();
        run.thread().interrupt();
        return true;
    }

    public synchronized boolean isRunning() {
        return current != null;
    }

    /** Espera o run atual terminar; true se ficou ocioso dentro do prazo. */
    public synchronized boolean awaitIdle(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (current != null) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000;
            if (leftMs <= 0) return false;
            try {
                wait(leftMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return current == null;
            }
        }
        return true;
    }

    private void execute(Experiment e, ExperimentContext ctx) {
        String summary = null;
        Throwable failure = null;
        try {
            summary = e.run(ctx);
        } catch (Throwable t) {
            failure = t;
        } finally {
            ctx.closeTracked();
        }
        Event terminal = terminalEvent(e.name(), ctx, summary, failure);
        synchronized (this) {
            current = null;
            bus.publish(terminal);
            notifyAll();
        }
    }

    private Event terminalEvent(String name, ExperimentContext ctx, String summary, Throwable failure) {
        long ts = clock.getAsLong();
        if (ctx.cancelled()) return new Events.ExperimentAborted(ts, name);
        if (failure == null) return new Events.ExperimentFinished(ts, name, summary);
        if (failure instanceof Exception ex && NetErrors.isUnreachable(ex)) {
            return new Events.ExperimentFailed(ts, name, String.format(UNREACHABLE, target.tcpRemote()));
        }
        return new Events.ExperimentFailed(ts, name, failure.toString());
    }
}
