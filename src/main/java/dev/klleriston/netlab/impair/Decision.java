package dev.klleriston.netlab.impair;

/** Resultado de uma decisão: descartar o pacote ou entregá-lo após um atraso. */
public record Decision(boolean drop, long delayMillis) {}
