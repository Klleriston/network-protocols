package dev.klleriston.netlab.impair;

import java.util.Random;

/** Decide, pacote a pacote, perda e atraso. Ordem dos sorteios: perda, jitter, reordenação. */
public final class ImpairmentEngine {
    private final Random random;

    public ImpairmentEngine(Random random) {
        this.random = random;
    }

    public synchronized Decision decide(ImpairmentProfile p) {
        if (random.nextInt(100) < p.lossPercent()) return new Decision(true, 0);
        long delay = p.delayMs() + (p.jitterMs() == 0 ? 0 : random.nextInt(2 * p.jitterMs() + 1) - p.jitterMs());
        delay = Math.max(0, delay);
        if (random.nextInt(100) < p.reorderPercent()) delay += 20 + 2L * p.jitterMs();
        return new Decision(false, delay);
    }
}
