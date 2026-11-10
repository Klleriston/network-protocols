package dev.klleriston.netlab.impair;

import java.util.Optional;

/** Perfil de rede ruim: perda, atraso base, jitter e reordenação. */
public record ImpairmentProfile(int lossPercent, int delayMs, int jitterMs, int reorderPercent) {
    public static final ImpairmentProfile NONE = new ImpairmentProfile(0, 0, 0, 0);

    public ImpairmentProfile {
        if (lossPercent < 0 || lossPercent > 100) {
            throw new IllegalArgumentException("lossPercent fora de 0-100: " + lossPercent);
        }
        if (reorderPercent < 0 || reorderPercent > 100) {
            throw new IllegalArgumentException("reorderPercent fora de 0-100: " + reorderPercent);
        }
        if (delayMs < 0) throw new IllegalArgumentException("delayMs negativo: " + delayMs);
        if (jitterMs < 0) throw new IllegalArgumentException("jitterMs negativo: " + jitterMs);
    }

    public static Optional<ImpairmentProfile> preset(String name) {
        if (name == null) return Optional.empty();
        return switch (name) {
            case "wifi-ruim" -> Optional.of(new ImpairmentProfile(5, 30, 20, 3));
            case "3g" -> Optional.of(new ImpairmentProfile(2, 150, 60, 1));
            default -> Optional.empty();
        };
    }

    public boolean isNone() {
        return equals(NONE);
    }
}
