package dev.klleriston.netlab.cli;

import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Converte uma linha do prompt em {@link Command}. */
public final class CommandParser {

    static final String IMPAIR_USAGE = """
            uso: impair loss=<0-100> delay=<ms> jitter=<ms> reorder=<0-100>
                 impair preset=<wifi-ruim|3g>
                 impair off""";

    private static final Set<String> IMPAIR_KEYS = Set.of("loss", "delay", "jitter", "reorder");

    /** Parâmetro aceito por um experimento, com o padrão mostrado no help. */
    record Param(String key, String defaultValue) { }

    /** Experimento conhecido: nome, parâmetros aceitos e descrição do help. */
    record Spec(String name, List<Param> params, String description) {
        String paramList() {
            return String.join(" ", params.stream().map(p -> p.key() + "=" + p.defaultValue()).toList());
        }

        Set<String> keys() {
            return params.stream().map(Param::key).collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /** Tabela única de experimentos: valida o {@code run} e gera o {@code help}. */
    static final List<Spec> EXPERIMENTS = List.of(
            new Spec("lifecycle", List.of(new Param("closed-port", "7999")),
                    "handshake, FIN, RST, porta fechada (TCP e UDP)"),
            new Spec("reliability", List.of(new Param("count", "1000"), new Param("rate", "200")),
                    "mensagens numeradas por TCP e UDP: entregues, perdidas, reordenadas"),
            new Spec("latency", List.of(new Param("samples", "200"), new Param("nagle", "off"),
                    new Param("size", "64")),
                    "ping-pong TCP e UDP: RTT e jitter (nagle=on desliga TCP_NODELAY)"),
            new Spec("throughput", List.of(new Param("seconds", "10"), new Param("udp-rate", "20mbps"),
                    new Param("size", "1200")),
                    "TCP contínuo e UDP a taxa fixa: vazão e perda acima da capacidade"));

    private final Map<String, Spec> experiments = new LinkedHashMap<>();
    private final String runUsage;

    /**
     * Aceita os experimentos de {@code experimentNames}, na ordem em que aparecem nas mensagens de uso;
     * cada nome precisa estar em {@link #EXPERIMENTS}.
     */
    public CommandParser(Set<String> experimentNames) {
        for (String name : experimentNames) {
            Spec spec = EXPERIMENTS.stream().filter(s -> s.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("experimento sem parâmetros definidos: " + name));
            experiments.put(name, spec);
        }
        this.runUsage = "uso: run <experimento> [chave=valor ...] — experimentos: "
                + String.join(", ", experiments.keySet());
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
        StringBuilder sb = new StringBuilder("""
                Comandos:
                  run <experimento> [chave=valor ...]   inicia um experimento (um por vez)
                  impair loss=<0-100> delay=<ms> jitter=<ms> reorder=<0-100>
                                                         piora o UDP do cliente (campos ausentes = 0)
                  impair preset=<wifi-ruim|3g>           wifi-ruim: 5% 30ms 20ms 3% | 3g: 2% 150ms 60ms 1%
                  impair off                             desliga o impair
                  stop                                   interrompe o experimento em andamento (ou Ctrl+C)
                  help                                   mostra esta ajuda
                  quit                                   encerra o cliente

                Experimentos (parâmetros e padrões):""");
        for (Spec spec : EXPERIMENTS) {
            sb.append(String.format("%n  %-13s %s%n  %-13s %s", spec.name(), spec.paramList(), "", spec.description()));
        }
        return sb.toString();
    }

    private Command run(List<String> args) throws CommandException {
        if (args.isEmpty()) throw new CommandException(runUsage);
        String experiment = args.get(0);
        Spec spec = experiments.get(experiment);
        if (spec == null) {
            throw new CommandException("experimento desconhecido: " + experiment + "\n" + runUsage);
        }
        Map<String, String> params = keyValues(args.subList(1, args.size()), runUsage);
        for (String key : params.keySet()) {
            if (!spec.keys().contains(key)) {
                throw new CommandException("parâmetro desconhecido para " + experiment + ": " + key
                        + "\nparâmetros de " + experiment + ": " + spec.paramList() + "\n" + runUsage);
            }
        }
        return new Command.Run(experiment, params);
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
