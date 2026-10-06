package br.com.bb.atlasestilo.util;

import java.text.Normalizer;

/** Utilidades de texto: escape HTML, normalização sem acento, aparas. */
public final class Texto {

    private Texto() { }

    /** Escapa texto para saída HTML. */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;");  break;
                case '<': sb.append("&lt;");   break;
                case '>': sb.append("&gt;");   break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** MAIÚSCULAS sem acento, para busca/comparação ("Crédito" -> "CREDITO"). */
    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                             .replaceAll("\\p{M}+", "");
        return n.toUpperCase().trim();
    }

    public static String aparar(String s) {
        return s == null ? "" : s.trim();
    }

    public static String aparar(String s, int max) {
        String v = aparar(s);
        return v.length() > max ? v.substring(0, max) : v;
    }

    public static boolean vazio(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Matrícula canônica: maiúscula, sem espaços. */
    public static String matricula(String s) {
        return normalizar(s).replace(" ", "");
    }

    /** Prefixo canônico: só dígitos (aceita "1881", "1881-0", " 1881 "). */
    public static String prefixo(String s) {
        if (s == null) return "";
        String d = s.trim();
        int corte = d.indexOf('-');
        if (corte > 0) d = d.substring(0, corte);
        d = d.replaceAll("\\D", "");
        // remove zeros à esquerda, preservando ao menos um dígito
        d = d.replaceFirst("^0+(?=\\d)", "");
        return d;
    }

    /** Interpreta número decimal tolerando vírgula brasileira e milhar. */
    public static Double decimal(String s) {
        if (vazio(s)) return null;
        String v = s.trim().replace("R$", "").replace("%", "").trim();
        if (v.contains(",")) v = v.replace(".", "").replace(',', '.');
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }

    /** Interpreta inteiro tolerante. */
    public static Integer inteiro(String s) {
        Double d = decimal(s);
        return d == null ? null : (int) Math.round(d);
    }

    /**
     * Interpreta data em epoch millis. Aceita dd/mm/aaaa, aaaa-mm-dd e epoch.
     * Devolve null quando não reconhece.
     */
    public static Long data(String s) {
        if (vazio(s)) return null;
        String v = s.trim();
        try {
            if (v.matches("\\d{13}")) return Long.parseLong(v);
            if (v.matches("\\d{1,2}/\\d{1,2}/\\d{4}.*")) {
                String[] p = v.split("[/ ]");
                java.util.Calendar c = java.util.Calendar.getInstance(
                        java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
                c.clear();
                c.set(Integer.parseInt(p[2]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[0]));
                return c.getTimeInMillis();
            }
            if (v.matches("\\d{4}-\\d{1,2}-\\d{1,2}.*")) {
                String[] p = v.substring(0, 10).split("-");
                java.util.Calendar c = java.util.Calendar.getInstance(
                        java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
                c.clear();
                c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
                return c.getTimeInMillis();
            }
        } catch (RuntimeException ignorada) { /* formato não reconhecido */ }
        return null;
    }
}
