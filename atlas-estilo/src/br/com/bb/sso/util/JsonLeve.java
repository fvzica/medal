package br.com.bb.sso.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de JSON mínimo e sem dependências, usado pelo FilterOauth2 para as
 * respostas do servidor OAuth2 (token, userinfo, discovery). Devolve
 * Map (LinkedHashMap, preserva a ordem), List, String, Long, Double, Boolean
 * ou null. Não é um parser geral de alto desempenho: é pequeno, estrito e
 * dá mensagens de erro com a posição.
 */
public final class JsonLeve {

    private final String s;
    private int i;

    private JsonLeve(String s) { this.s = s; }

    /** Lê qualquer valor JSON. */
    public static Object ler(String texto) {
        if (texto == null) throw new IllegalArgumentException("JSON vazio");
        JsonLeve p = new JsonLeve(texto);
        p.espacos();
        Object v = p.valor();
        p.espacos();
        if (p.i != p.s.length()) throw p.erro("conteúdo após o fim do JSON");
        return v;
    }

    /** Lê um objeto JSON; falha se a raiz não for um objeto. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> lerObjeto(String texto) {
        Object v = ler(texto);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON não é um objeto: começa com "
                + (texto.trim().isEmpty() ? "(vazio)" : texto.trim().charAt(0)));
        return (Map<String, Object>) v;
    }

    /** Valor de um campo como texto (null se ausente ou nulo). */
    public static String texto(Map<String, Object> obj, String campo) {
        if (obj == null) return null;
        Object v = obj.get(campo);
        return v == null ? null : String.valueOf(v);
    }

    // --------------------------------------------------------------- parser

    private IllegalArgumentException erro(String msg) {
        return new IllegalArgumentException("JSON inválido (posição " + i + "): " + msg);
    }

    private void espacos() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char atual() {
        if (i >= s.length()) throw erro("fim inesperado");
        return s.charAt(i);
    }

    private Object valor() {
        char c = atual();
        switch (c) {
            case '{': return objeto();
            case '[': return lista();
            case '"': return string();
            case 't': literal("true");  return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null");  return null;
            default:  return numero();
        }
    }

    private void literal(String l) {
        if (!s.startsWith(l, i)) throw erro("esperava " + l);
        i += l.length();
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        espacos();
        if (atual() == '}') { i++; return m; }
        while (true) {
            espacos();
            if (atual() != '"') throw erro("esperava o nome de um campo entre aspas");
            String chave = string();
            espacos();
            if (atual() != ':') throw erro("esperava ':'");
            i++;
            espacos();
            m.put(chave, valor());
            espacos();
            char c = atual();
            i++;
            if (c == ',') continue;
            if (c == '}') return m;
            throw erro("esperava ',' ou '}'");
        }
    }

    private List<Object> lista() {
        List<Object> l = new ArrayList<>();
        i++; // [
        espacos();
        if (atual() == ']') { i++; return l; }
        while (true) {
            espacos();
            l.add(valor());
            espacos();
            char c = atual();
            i++;
            if (c == ',') continue;
            if (c == ']') return l;
            throw erro("esperava ',' ou ']'");
        }
    }

    private String string() {
        i++; // aspa de abertura
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = atual();
            i++;
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = atual();
            i++;
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw erro("\\u incompleto");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw erro("\\u inválido");
                    }
                    i += 4;
                    break;
                default: throw erro("escape desconhecido \\" + e);
            }
        }
    }

    private Object numero() {
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (ini == i) throw erro("valor inesperado '" + s.charAt(i) + "'");
        String t = s.substring(ini, i);
        try {
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            throw erro("número inválido '" + t + "'");
        }
    }
}
