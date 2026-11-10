package dev.klleriston.netlab.cli;

import dev.klleriston.netlab.dashboard.DashboardServer;
import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.experiment.Experiment;
import dev.klleriston.netlab.experiment.ExperimentRunner;
import dev.klleriston.netlab.experiment.LatencyExperiment;
import dev.klleriston.netlab.experiment.LifecycleExperiment;
import dev.klleriston.netlab.experiment.ReliabilityExperiment;
import dev.klleriston.netlab.experiment.Target;
import dev.klleriston.netlab.experiment.ThroughputExperiment;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import dev.klleriston.netlab.metrics.MetricsAggregator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Modo cliente: painel, métricas, runner de experimentos e o prompt interativo {@code netlab>}. */
public final class ClientMode {

    private static final String PROMPT = "netlab> ";
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);
    private static final long PRINTER_DRAIN_MS = 1000;

    private final Args.ClientArgs args;
    private final InputStream in;
    private final PrintStream out;
    private final Map<String, Experiment> experiments = new LinkedHashMap<>();
    private final AtomicReference<ImpairmentProfile> impairment = new AtomicReference<>(ImpairmentProfile.NONE);
    private final AtomicInteger runsStarted = new AtomicInteger();
    private final AtomicInteger runsEnded = new AtomicInteger();
    private volatile ExperimentRunner runner;
    private boolean stopPending;

    public ClientMode(Args.ClientArgs args, InputStream in, PrintStream out) {
        this.args = args;
        this.in = in;
        this.out = out;
        for (Experiment e : List.of(new LifecycleExperiment(), new ReliabilityExperiment(),
                new LatencyExperiment(), new ThroughputExperiment())) {
            experiments.put(e.name(), e);
        }
    }

    /** Roda o prompt até {@code quit} ou EOF; depois para o experimento e fecha tudo. */
    public void run() throws IOException {
        EventBus bus = new EventBus();
        MetricsAggregator metrics = new MetricsAggregator(bus, System::nanoTime);
        DashboardServer dashboard = new DashboardServer(bus, args.httpPort(), System::nanoTime);
        try {
            metrics.start();
            try {
                dashboard.start();
            } catch (BindException e) {
                println("Porta HTTP " + args.httpPort() + " em uso. Use --http-port para escolher outra.");
                throw e;
            }
            // o painel precisa de um estado de impair desde o início; o terminal só mostra as mudanças
            bus.publish(changed(ImpairmentProfile.NONE));
            ConsolePrinter printer = new ConsolePrinter(out);
            bus.subscribe("console", e -> {
                printer.accept(e);
                if (isTerminal(e)) runsEnded.incrementAndGet();
            });
            Target target = new Target(args.target(), args.tcpPort(), args.udpPort());
            runner = new ExperimentRunner(bus, System::nanoTime, impairment::get, new Random(), target);

            String host = LanAddress.find().map(InetAddress::getHostAddress).orElse("localhost");
            println("Painel: http://" + host + ":" + dashboard.port());
            println("Alvo: TCP " + target.tcpRemote() + ", UDP " + target.udpRemote());
            println("Digite help para ver os comandos.");
            prompt(bus);
        } finally {
            shutdown(dashboard, metrics, bus);
        }
    }

    /**
     * Ctrl+C: interrompe o experimento em andamento se ainda não houver um stop pendente.
     * Retorna false quando não havia o que interromper (quem chamou decide encerrar o processo).
     */
    public boolean interruptExperiment() {
        ExperimentRunner r = runner;
        if (r == null) return false;
        synchronized (this) {
            if (stopPending || !r.isRunning()) return false;
            if (!r.stop()) return false;
            stopPending = true;
        }
        println("interrompendo o experimento (Ctrl+C de novo encerra o cliente)");
        return true;
    }

    private void prompt(EventBus bus) throws IOException {
        CommandParser parser = new CommandParser(experiments.keySet());
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        while (true) {
            synchronized (out) {
                out.print(PROMPT);
                out.flush();
            }
            String line = reader.readLine();
            if (line == null) return;
            Command command;
            try {
                command = parser.parse(line);
            } catch (CommandException e) {
                println(e.getMessage());
                continue;
            }
            switch (command) {
                case Command.Run r -> start(r);
                case Command.Impair i -> {
                    impairment.set(i.profile());
                    bus.publish(changed(i.profile()));
                }
                case Command.Stop s -> stop();
                case Command.Help h -> println(CommandParser.helpText());
                case Command.Quit q -> {
                    return;
                }
                case Command.Empty e -> { }
            }
        }
    }

    private void start(Command.Run run) {
        synchronized (this) {
            if (!runner.start(experiments.get(run.experiment()), run.params())) {
                println("já existe um experimento em andamento");
                return;
            }
            stopPending = false;
            runsStarted.incrementAndGet();
        }
    }

    private void stop() {
        synchronized (this) {
            if (runner.stop()) {
                stopPending = true;
                return;
            }
        }
        println("nenhum experimento em andamento");
    }

    private void shutdown(DashboardServer dashboard, MetricsAggregator metrics, EventBus bus) {
        ExperimentRunner r = runner;
        if (r != null) {
            r.stop();
            r.awaitIdle(STOP_WAIT);
            awaitPrinterDrain();
        }
        dashboard.close();
        metrics.close();
        bus.close();
    }

    /** Dá ao console a chance de imprimir o evento final de cada run antes de o bus fechar. */
    private void awaitPrinterDrain() {
        long deadline = System.nanoTime() + PRINTER_DRAIN_MS * 1_000_000;
        while (runsEnded.get() < runsStarted.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isTerminal(Event e) {
        return e instanceof Events.ExperimentFinished || e instanceof Events.ExperimentFailed
                || e instanceof Events.ExperimentAborted;
    }

    private static Events.ImpairmentChanged changed(ImpairmentProfile p) {
        return new Events.ImpairmentChanged(System.nanoTime(), p.lossPercent(), p.delayMs(), p.jitterMs(),
                p.reorderPercent());
    }

    private void println(String text) {
        synchronized (out) {
            out.println(text);
            out.flush();
        }
    }
}
