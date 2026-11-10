package dev.klleriston.netlab.cli;

import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.util.Map;

/** Comando digitado no prompt do cliente. */
public sealed interface Command {

    record Run(String experiment, Map<String, String> params) implements Command {
        public Run {
            params = Map.copyOf(params);
        }
    }

    record Impair(ImpairmentProfile profile) implements Command { }

    record Stop() implements Command { }

    record Help() implements Command { }

    record Quit() implements Command { }

    record Empty() implements Command { }
}
