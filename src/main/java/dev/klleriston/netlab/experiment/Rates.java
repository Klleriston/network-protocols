package dev.klleriston.netlab.experiment;

import java.math.BigDecimal;
import java.util.Locale;

/** Interpretação de taxas em bits por segundo (base 1000). */
public final class Rates {

    private Rates() { }

    /**
     * Aceita {@code kbps}, {@code mbps} ou {@code gbps} (sem diferenciar maiúsculas, número decimal opcional) ou um
     * número puro em bps; valor inválido, zero ou negativo lança {@code IllegalArgumentException}.
     */
    public static long parseBitsPerSecond(String text) {
        if (text == null) throw invalid(text);
        String s = text.trim().toLowerCase(Locale.ROOT);
        long factor = 1;
        if (s.endsWith("kbps")) {
            factor = 1_000L;
        } else if (s.endsWith("mbps")) {
            factor = 1_000_000L;
        } else if (s.endsWith("gbps")) {
            factor = 1_000_000_000L;
        }
        String number = factor == 1 ? s : s.substring(0, s.length() - 4).trim();
        if (!number.matches("\\d+(\\.\\d+)?")) throw invalid(text);
        try {
            BigDecimal bps = new BigDecimal(number).multiply(BigDecimal.valueOf(factor));
            if (bps.compareTo(BigDecimal.ONE) < 0 || bps.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) throw invalid(text);
            return bps.longValue();
        } catch (NumberFormatException e) {
            throw invalid(text);
        }
    }

    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException("taxa inválida: " + text);
    }
}
