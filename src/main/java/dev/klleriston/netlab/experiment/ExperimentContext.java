package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.impair.ImpairedDatagramSocket;
import dev.klleriston.netlab.impair.ImpairmentEngine;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.io.IOException;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Tudo que um experimento enxerga durante um run: alvo, barramento, relógio, impair, parâmetros e recursos. */
public final class ExperimentContext {

    private static final int UDP_RECEIVE_BUFFER = 4 * 1024 * 1024;

    private final Target target;
    private final EventBus bus;
    private final LongSupplier clock;
    private final Supplier<ImpairmentProfile> impairment;
    private final Random random;
    private final Map<String, String> params;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile boolean cancelled;

    public ExperimentContext(Target target, EventBus bus, LongSupplier clock, Supplier<ImpairmentProfile> impairment,
                             Random random, Map<String, String> params) {
        this.target = target;
        this.bus = bus;
        this.clock = clock;
        this.impairment = impairment;
        this.random = random;
        this.params = Map.copyOf(params);
    }

    public Target target() {
        return target;
    }

    public EventBus bus() {
        return bus;
    }

    /** Tempo atual em nanossegundos, do relógio injetado. */
    public long now() {
        return clock.getAsLong();
    }

    /** Perfil de impair ativo neste instante. */
    public ImpairmentProfile impairment() {
        return impairment.get();
    }

    public String param(String key, String def) {
        return params.getOrDefault(key, def);
    }

    /** Parâmetro inteiro; lança IllegalArgumentException se o valor não for inteiro. */
    public int intParam(String key, int def) {
        String value = params.get(key);
        if (value == null) return def;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parâmetro " + key + " deve ser inteiro: " + value);
        }
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Registra um recurso para ser fechado no {@code stop} e ao fim do run; se já cancelado, fecha na hora. */
    public <T extends AutoCloseable> T track(T resource) {
        synchronized (resources) {
            if (!cancelled) {
                resources.add(resource);
                return resource;
            }
        }
        closeQuietly(resource);
        return resource;
    }

    /** DatagramSocket conectado à porta UDP do alvo, com impair aplicado e rastreado. */
    public ImpairedDatagramSocket openUdp() throws IOException {
        DatagramSocket delegate = new DatagramSocket();
        try {
            delegate.setReceiveBufferSize(UDP_RECEIVE_BUFFER);
            delegate.connect(target.host(), target.udpPort());
        } catch (IOException | RuntimeException e) {
            delegate.close();
            throw e;
        }
        return track(new ImpairedDatagramSocket(delegate, impairment, new ImpairmentEngine(random)));
    }

    /** Marca o run como cancelado e fecha os recursos rastreados (desbloqueia read/receive). */
    void cancel() {
        cancelled = true;
        closeTracked();
    }

    /** Fecha os recursos rastreados, do mais novo para o mais antigo. */
    void closeTracked() {
        List<AutoCloseable> toClose;
        synchronized (resources) {
            toClose = new ArrayList<>(resources);
            resources.clear();
        }
        for (int i = toClose.size() - 1; i >= 0; i--) closeQuietly(toClose.get(i));
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // fechar é melhor esforço: o recurso pode já estar fechado ou quebrado
        }
    }
}
