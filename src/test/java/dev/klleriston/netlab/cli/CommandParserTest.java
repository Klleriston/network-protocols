package dev.klleriston.netlab.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandParserTest {

    private final CommandParser parser =
            new CommandParser(new LinkedHashSet<>(List.of("lifecycle", "reliability", "latency", "throughput")));

    @Test
    void parsesRunWithParams() throws CommandException {
        assertEquals(new Command.Run("latency", Map.of("samples", "200", "nagle", "on")),
                parser.parse("run latency samples=200 nagle=on"));
        assertEquals(new Command.Run("lifecycle", Map.of()), parser.parse("  run   lifecycle  "));
    }

    @Test
    void rejectsUnknownExperiment() {
        CommandException e = assertThrows(CommandException.class, () -> parser.parse("run foo"));
        assertTrue(e.getMessage().contains("lifecycle, reliability, latency, throughput"), e.getMessage());
        assertThrows(CommandException.class, () -> parser.parse("run"));
    }

    @Test
    void parsesImpairValuesDefaultingMissingToZero() throws CommandException {
        assertEquals(new Command.Impair(new ImpairmentProfile(10, 40, 0, 0)), parser.parse("impair loss=10 delay=40"));
        assertEquals(new Command.Impair(new ImpairmentProfile(1, 2, 3, 4)),
                parser.parse("impair reorder=4 jitter=3 delay=2 loss=1"));
    }

    @Test
    void parsesImpairPresetAndOff() throws CommandException {
        assertEquals(new Command.Impair(new ImpairmentProfile(2, 150, 60, 1)), parser.parse("impair preset=3g"));
        assertEquals(new Command.Impair(new ImpairmentProfile(5, 30, 20, 3)), parser.parse("impair preset=wifi-ruim"));
        assertEquals(new Command.Impair(ImpairmentProfile.NONE), parser.parse("impair off"));
    }

    @Test
    void rejectsUnknownImpairKeyAndOutOfRange() {
        for (String line : List.of("impair perda=10", "impair loss=150", "impair reorder=-1", "impair delay=-5",
                "impair loss=abc", "impair", "impair preset=lua", "impair preset=3g loss=1", "impair off loss=1")) {
            CommandException e = assertThrows(CommandException.class, () -> parser.parse(line), line);
            assertTrue(e.getMessage().contains("impair"), e.getMessage());
        }
    }

    @Test
    void blankLineIsEmpty() throws CommandException {
        assertEquals(new Command.Empty(), parser.parse(""));
        assertEquals(new Command.Empty(), parser.parse("   \t "));
    }

    @Test
    void parsesSimpleCommands() throws CommandException {
        assertEquals(new Command.Stop(), parser.parse("stop"));
        assertEquals(new Command.Help(), parser.parse("help"));
        assertEquals(new Command.Quit(), parser.parse(" quit "));
        assertThrows(CommandException.class, () -> parser.parse("stop agora"));
    }

    @Test
    void unknownCommandIsRejected() {
        CommandException e = assertThrows(CommandException.class, () -> parser.parse("pular"));
        assertTrue(e.getMessage().contains("help"), e.getMessage());
    }

    @Test
    void malformedParamIsRejected() {
        for (String line : List.of("run latency samples", "run latency =3", "run latency samples=")) {
            CommandException e = assertThrows(CommandException.class, () -> parser.parse(line), line);
            assertTrue(e.getMessage().contains("run <experimento>"), e.getMessage());
        }
    }

    @Test
    void rejectsUnknownRunParam() {
        CommandException e = assertThrows(CommandException.class, () -> parser.parse("run latency sample=50"));
        assertTrue(e.getMessage().startsWith("parâmetro desconhecido para latency: sample"), e.getMessage());
        assertTrue(e.getMessage().contains("samples=200 nagle=off size=64"), e.getMessage());
        assertThrows(CommandException.class, () -> parser.parse("run lifecycle count=10"));
    }

    @Test
    void acceptsEveryDocumentedParam() throws CommandException {
        assertEquals(new Command.Run("throughput", Map.of("seconds", "1", "udp-rate", "5mbps", "size", "100")),
                parser.parse("run throughput seconds=1 udp-rate=5mbps size=100"));
        assertEquals(new Command.Run("reliability", Map.of("count", "5", "rate", "1")),
                parser.parse("run reliability count=5 rate=1"));
        assertEquals(new Command.Run("lifecycle", Map.of("closed-port", "1")), parser.parse("run lifecycle closed-port=1"));
    }

    @Test
    void helpListsEveryExperimentWithDefaults() {
        String help = CommandParser.helpText();
        for (String expected : List.of("lifecycle     closed-port=7999", "reliability   count=1000 rate=200",
                "latency       samples=200 nagle=off size=64", "throughput    seconds=10 udp-rate=20mbps size=1200",
                "impair preset=<wifi-ruim|3g>")) {
            assertTrue(help.contains(expected), help);
        }
    }

    @Test
    void helpSaysImpairValuesArePerDirection() {
        assertTrue(CommandParser.helpText().contains("valores por sentido"), CommandParser.helpText());
    }
}
