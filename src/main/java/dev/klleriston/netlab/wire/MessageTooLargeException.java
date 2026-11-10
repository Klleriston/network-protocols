package dev.klleriston.netlab.wire;

import java.io.IOException;

/** Tamanho de mensagem fora do limite permitido. */
public class MessageTooLargeException extends IOException {
    public MessageTooLargeException(String message) {
        super(message);
    }
}
