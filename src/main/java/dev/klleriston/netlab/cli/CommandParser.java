package dev.klleriston.netlab.cli;

import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converte uma linha do prompt em {@link Command}. */
public final class CommandParser {

    static final String IMPAIR_USAGE = """
            uso: impair loss=<0-100> delay=<ms> jitter=<ms> reorder=<0-100>
                 impair preset=<wifi-ruim|3g>
                 impair off""";

    private static final Set<String> IMPAIR_KEYS = Set.of("loss", "delay", "jitter", "reorder");

    private final Set<String> experiments;
    private final String runUsage;

    /** {@code experimentNames} na ordem em que aparecem nas mensagens de uso. */
    public CommandParser(Set<String> experimentNames) {
        this.experiments = new LinkedHashSet<>(experimentNames);
        this.runUsage = "uso: run <experimento> [chave=valor ...] — experimentos: "
                + String.join(", ", experiments);
    }

    public Command parse(String line) throws CommandException {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return new Command.Empty();
        List<String> tokens = Arrays.asList(trimmed.split("\\s+"));
        String name = tokens.get(0);
        List<String> rest = tokens.subList(1, tokens.size());
        return switch (name) {
            case "run" -> run(rest);
            case "impair" -> impair(rest);
            case "stop" -> simple(rest, "stop", new Command.Stop());
            case "help" -> simple(rest, "help", new Command.Help());
            case "quit" -> simple(rest, "quit", new Command.Quit());
            default -> throw new CommandException(
                    "comando desconhecido: " + name + ". Comandos: run, impair, stop, help, quit (digite help).");
        };
    }

    /** Texto do comando {@code help}: comandos, experimentos, parâmetros e padrões. */
    public static String helpText() {
        return """
                Comandos:
                  run <experimento> [chave=valor ...]   inicia um experimento (um por vez)
                  impair loss=<0-100> delay=<ms> jitter=<ms> reorder=<0-100>
                                                         piora o UDP do cliente (campos ausentes = 0)
                  impair preset=<wifi-ruim|3g>           wifi-ruim: 5% 30ms 20ms 3% | 3g: 2% 150ms 60ms 1%
                  impair off                             desliga o impair
                  stop                                   interrompe o experimento em andamento (ou Ctrl+C)
                  help                                   mostra esta ajuda
                  quit                                   encerra o cliente

                Experimentos (parâmetros e padrões):
                  lifecycle     closed-port=7999
                                handshake, FIN, RST, porta fechada (TCP e UDP)
                  reliability   count=1000 rate=200
                                mensagens numeradas por TCP e UDP: entregues, perdidas, reordenadas
                  latency       samples=200 nagle=off size=64
                                ping-pong TCP e UDP: RTT e jitter (nagle=on desliga TCP_NODELAY)
                  throughput    seconds=10 udp-rate=20mbps size=1200
                                TCP contínuo e UDP a taxa fixa: vazão e perda acima da capacidade""";
    }

    private Command run(List<String> args) throws CommandException {
        if (args.isEmpty()) throw new CommandException(runUsage);
        String experiment = args.get(0);
        if (!experiments.contains(experiment)) {
            throw new CommandException("experimento desconhecido: " + experiment + "\n" + runUsage);
        }
        return new Command.Run(experiment, keyValues(args.subList(1, args.size()), runUsage));
    }

    private static Command impair(List<String> args) throws CommandException {
        if (args.equals(List.of("off"))) return new Command.Impair(ImpairmentProfile.NONE);
        Map<String, String> values = keyValues(args, IMPAIR_USAGE);
        if (values.isEmpty()) throw new CommandException(IMPAIR_USAGE);
        if (values.containsKey("preset")) {
            if (values.size() > 1) throw new CommandException("preset não combina com outros campos\n" + IMPAIR_USAGE);
            return new Command.Impair(ImpairmentProfile.preset(values.get("preset")).orElseThrow(
                    () -> new CommandException("preset desconhecido: " + values.get("preset") + "\n" + IMPAIR_USAGE)));
        }
        for (String key : values.keySet()) {
            if (!IMPAIR_KEYS.contains(key)) throw new CommandException("campo desconhecido: " + key + "\n" + IMPAIR_USAGE);
        }
        try {
            return new Command.Impair(new ImpairmentProfile(
                    intValue(values, "loss"), intValue(values, "delay"),
                    intValue(values, "jitter"), intValue(values, "reorder")));
        } catch (IllegalArgumentException e) {
            throw new CommandException("valor fora do intervalo: " + e.getMessage() + "\n" + IMPAIR_USAGE);
        }
    }

    private static int intValue(Map<String, String> values, String key) throws CommandException {
        String value = values.get(key);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CommandException(key + " deve ser inteiro: " + value + "\n" + IMPAIR_USAGE);
        }
    }

    private static Map<String, String> keyValues(List<String> args, String usage) throws CommandException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0 || eq == arg.length() - 1) {
                throw new CommandException("parâmetro malformado (esperado chave=valor): " + arg + "\n" + usage);
            }
            out.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        return out;
    }

    private static Command simple(List<String> args, String name, Command command) throws CommandException {
        if (!args.isEmpty()) throw new CommandException("uso: " + name + " (sem argumentos)");
        return command;
    }
}
