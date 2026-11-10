package dev.klleriston.netlab.cli;

import sun.misc.Signal;

/** Instala um tratador de Ctrl+C (SIGINT); isolado aqui por usar a API interna {@code sun.misc.Signal}. */
final class InterruptHandler {

    private InterruptHandler() {}

    /** true se instalou; false se a plataforma não permite (o Ctrl+C mantém o comportamento padrão). */
    static boolean install(Runnable onInterrupt) {
        try {
            Signal.handle(new Signal("INT"), signal -> onInterrupt.run());
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return false;
        }
    }
}
