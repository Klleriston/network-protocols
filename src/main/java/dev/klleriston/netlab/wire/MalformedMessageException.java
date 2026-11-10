package dev.klleriston.netlab.wire;

import java.io.IOException;

/** Mensagem com formato inválido. */
public class MalformedMessageException extends IOException {
    public MalformedMessageException(String message) {
        super(message);
    }
}
