package dev.klleriston.netlab.cli;

/** Comando inválido; a mensagem é o uso do comando, pronta para o terminal. */
public final class CommandException extends Exception {

    public CommandException(String message) {
        super(message);
    }
}
