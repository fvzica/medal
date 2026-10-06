package br.com.bb.atlasestilo.util;

import java.util.List;

/**
 * Geração manual de JSON, sem biblioteca (convenção SUPER PF1).
 * Builder pequeno: Json.obj().put("a",1).put("b","x").fim()
 * Escapa aspas, barras, controles e '<' (como <) para uso seguro em HTML.
 */
public final class Json {

    private Json() { }

    /** Escapa e envolve em aspas. null vira literal null. */
    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '<':  sb.append("\\u003c"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String num(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) return "null";
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.valueOf((long) (double) d);
        return String.valueOf(d);
    }

    public static Obj obj() { return new Obj(); }
    public static Arr arr() { return new Arr(); }

    /** Objeto JSON em construção. */
    public static final class Obj {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean tem;

        private void virgula() { if (tem) sb.append(','); tem = true; }

        public Obj put(String chave, String valor) {
            virgula(); sb.append(str(chave)).append(':').append(str(valor)); return this;
        }
        public Obj put(String chave, long valor) {
            virgula(); sb.append(str(chave)).append(':').append(valor); return this;
        }
        public Obj put(String chave, double valor) {
            virgula(); sb.append(str(chave)).append(':').append(num(valor)); return this;
        }
        public Obj put(String chave, boolean valor) {
            virgula(); sb.append(str(chave)).append(':').append(valor); return this;
        }
        public Obj putNum(String chave, Double valor) {
            virgula(); sb.append(str(chave)).append(':').append(num(valor)); return this;
        }
        public Obj putNum(String chave, Long valor) {
            virgula(); sb.append(str(chave)).append(':')
                       .append(valor == null ? "null" : String.valueOf(valor)); return this;
        }
        /** Valor já em JSON (objeto/array aninhado). */
        public Obj putRaw(String chave, String json) {
            virgula(); sb.append(str(chave)).append(':').append(json == null ? "null" : json); return this;
        }
        public String fim() { return sb.toString() + "}"; }
    }

    /** Array JSON em construção. */
    public static final class Arr {
        private final StringBuilder sb = new StringBuilder("[");
        private boolean tem;

        public Arr add(String json) {
            if (tem) sb.append(','); tem = true;
            sb.append(json == null ? "null" : json);
            return this;
        }
        public Arr addStr(String s) { return add(str(s)); }
        public Arr addTodos(List<String> jsons) {
            for (String j : jsons) add(j);
            return this;
        }
        public String fim() { return sb.toString() + "]"; }
    }
}
