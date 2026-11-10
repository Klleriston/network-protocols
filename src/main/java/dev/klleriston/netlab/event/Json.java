package dev.klleriston.netlab.event;

import java.util.Locale;

/** Serializador JSON mínimo para objetos planos. */
public final class Json {

    private Json() { }

    public static Builder object() {
        return new Builder();
    }

    public static final class Builder {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private Builder() { }

        private StringBuilder key(String name) {
            if (!first) sb.append(',');
            first = false;
            quote(sb, name);
            return sb.append(':');
        }

        public Builder field(String name, String value) {
            StringBuilder s = key(name);
            if (value == null) s.append("null"); else quote(s, value);
            return this;
        }

        public Builder field(String name, long value) {
            key(name).append(value);
            return this;
        }

        public Builder field(String name, int value) {
            key(name).append(value);
            return this;
        }

        /** Doubles são sempre escritos com 2 casas decimais. */
        public Builder field(String name, double value) {
            key(name).append(String.format(Locale.ROOT, "%.2f", value));
            return this;
        }

        public Builder field(String name, boolean value) {
            key(name).append(value);
            return this;
        }

        public String build() {
            return sb.toString() + "}";
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
