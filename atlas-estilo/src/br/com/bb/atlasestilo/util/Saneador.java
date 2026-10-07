package br.com.bb.atlasestilo.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saneador de CSV "do mundo real": lê os bytes de um arquivo exportado do
 * Excel/SAP/sistemas do BB e devolve uma tabela limpa, registrando TUDO que
 * foi corrigido para o Master conferir.
 *
 * Corrige sozinho (e relata):
 *  - encoding (BOM UTF-8/UTF-16, UTF-8 estrito, senão Windows-1252/ANSI);
 *  - delimitador (';' ',' TAB '|') pela consistência nas primeiras linhas;
 *  - preâmbulo antes do cabeçalho, cabeçalho repetido no meio, colunas vazias
 *    sobrando no fim, linhas em branco, linhas com separador sobrando no fim;
 *  - números com vírgula/ponto trocados, milhar, "R$", "%", espaços, NBSP,
 *    parênteses negativos, sinal no fim, erros do Excel (#N/D, #DIV/0!),
 *    letras no lugar de dígitos (O/o -> 0, l/I/i -> 1) quando o resto é número;
 *  - competência mensal (mm/aaaa, aaaa-mm, dd/mm/aaaa, serial do Excel) -> AAAA-MM.
 *
 * Java 8 puro. Sem dependências.
 */
public final class Saneador {

    private Saneador() { }

    /** Tabela lida: cabeçalho normalizado + linhas com o mesmo nº de colunas. */
    public static final class Tabela {
        public String encoding;
        public char separador;
        /** Nº da linha física (1-based) onde o cabeçalho foi encontrado. */
        public int linhaCabecalho;
        /** Cabeçalho como veio (aparado), já sem colunas vazias sobrando. */
        public String[] cabecalho = new String[0];
        /** Cabeçalho normalizado (MAIÚSCULO, sem acento). */
        public String[] cabecalhoNorm = new String[0];
        /** Linhas de dados; cada uma tem exatamente cabecalho.length campos. */
        public final List<String[]> linhas = new ArrayList<>();
        /** Nº da linha física de cada linha de dados (para o relatório). */
        public final List<Integer> numeroLinha = new ArrayList<>();
        /** Avisos estruturais (preâmbulo pulado, cabeçalho repetido, ...). */
        public final List<String> avisos = new ArrayList<>();
        public int linhasVazias, cabecalhosRepetidos, linhasPreambulo, colunasSobrando;

        public int indice(String nomeNorm) {
            for (int i = 0; i < cabecalhoNorm.length; i++) {
                if (cabecalhoNorm[i].equals(nomeNorm)) return i;
            }
            return -1;
        }
    }

    /** Uma correção aplicada a uma célula. */
    public static final class Correcao {
        public final int linha; public final String coluna;
        public final String de; public final String para; public final String regra;
        public Correcao(int linha, String coluna, String de, String para, String regra) {
            this.linha = linha; this.coluna = coluna; this.de = de; this.para = para; this.regra = regra;
        }
        public String json() {
            return Json.obj().put("linha", linha).put("coluna", coluna).put("de", de)
                .put("para", para).put("regra", regra).fim();
        }
    }

    /** Relatório acumulado de uma leitura (correções e rejeições). */
    public static final class Relatorio {
        public static final int LIMITE_LISTA = 400;
        public final List<Correcao> correcoes = new ArrayList<>();
        public final List<String[]> rejeitadas = new ArrayList<>(); // {linha, motivo, conteudo}
        public int totalCorrecoes, totalRejeitadas, celulasInvalidas;
        public final Map<String, Integer> porRegra = new LinkedHashMap<>();

        public void corrigiu(int linha, String coluna, String de, String para, String regra) {
            totalCorrecoes++;
            porRegra.put(regra, porRegra.containsKey(regra) ? porRegra.get(regra) + 1 : 1);
            if (correcoes.size() < LIMITE_LISTA) correcoes.add(new Correcao(linha, coluna, de, para, regra));
        }
        /** Célula que não deu para interpretar: vira vazia e fica registrada. */
        public void invalida(int linha, String coluna, String original) {
            celulasInvalidas++;
            corrigiu(linha, coluna, original, "", "não numérico → vazio");
        }
        public void rejeitou(int linha, String motivo, String conteudo) {
            totalRejeitadas++;
            if (rejeitadas.size() < LIMITE_LISTA * 5) {
                rejeitadas.add(new String[] { String.valueOf(linha), motivo, conteudo });
            }
        }
        /** Linhas rejeitadas em CSV (linha;motivo;conteudo) para baixar e corrigir. */
        public String rejeitadasCsv() {
            StringBuilder sb = new StringBuilder("linha;motivo;conteudo\n");
            for (String[] r : rejeitadas) {
                sb.append(r[0]).append(';').append(celula(r[1])).append(';').append(celula(r[2])).append('\n');
            }
            return sb.toString();
        }
        private static String celula(String v) {
            v = v == null ? "" : v;
            return (v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0)
                ? '"' + v.replace("\"", "\"\"") + '"' : v;
        }
        public String json() {
            Json.Arr cs = Json.arr();
            for (Correcao c : correcoes) cs.add(c.json());
            Json.Arr rs = Json.arr();
            for (String[] r : rejeitadas) {
                rs.add(Json.obj().put("linha", Integer.parseInt(r[0])).put("motivo", r[1])
                    .put("conteudo", r[2]).fim());
            }
            Json.Obj regras = Json.obj();
            for (Map.Entry<String, Integer> e : porRegra.entrySet()) regras.put(e.getKey(), e.getValue());
            return Json.obj().put("totalCorrecoes", totalCorrecoes)
                .put("totalRejeitadas", totalRejeitadas)
                .put("celulasInvalidas", celulasInvalidas)
                .putRaw("porRegra", regras.fim())
                .putRaw("correcoes", cs.fim())
                .putRaw("rejeitadas", rs.fim()).fim();
        }
    }

    /** Estilo numérico de uma coluna: BR (1.234,56) ou EN (1,234.56). */
    public enum Estilo { BR, EN }

    // ================================================================ leitura

    public static Tabela ler(byte[] dados) {
        Tabela t = new Tabela();
        String texto = decodificar(dados, t);
        List<String> fisicas = quebrarLinhas(texto);
        t.separador = detectarSeparador(fisicas);

        // reagrupa linhas quebradas dentro de aspas e divide
        List<String[]> brutas = new ArrayList<>();
        List<Integer> numeros = new ArrayList<>();
        StringBuilder pendente = null; int inicioPendente = 0;
        for (int i = 0; i < fisicas.size(); i++) {
            String l = fisicas.get(i);
            if (pendente == null) {
                if (aspasAbertas(l)) { pendente = new StringBuilder(l); inicioPendente = i + 1; continue; }
                if (l.trim().isEmpty()) { t.linhasVazias++; continue; }
                brutas.add(dividir(l, t.separador)); numeros.add(i + 1);
            } else {
                pendente.append('\n').append(l);
                if (!aspasAbertas(pendente.toString())) {
                    brutas.add(dividir(pendente.toString(), t.separador)); numeros.add(inicioPendente);
                    pendente = null;
                }
            }
        }
        if (pendente != null) { brutas.add(dividir(pendente.toString(), t.separador)); numeros.add(inicioPendente); }
        if (brutas.isEmpty()) { t.avisos.add("Arquivo vazio."); return t; }

        // cabeçalho = primeira linha com >= 2 campos não vazios e sem cara de número puro
        int iCab = 0;
        for (int i = 0; i < Math.min(brutas.size(), 30); i++) {
            String[] l = brutas.get(i);
            int cheios = 0, numericos = 0;
            for (String c : l) {
                if (!c.trim().isEmpty()) { cheios++; if (numeroSimples(c)) numericos++; }
            }
            if (cheios >= 2 && numericos < cheios) { iCab = i; break; }
        }
        t.linhasPreambulo = iCab;
        if (iCab > 0) t.avisos.add(iCab + " linha(s) antes do cabeçalho foram ignoradas.");
        t.linhaCabecalho = numeros.get(iCab);

        String[] cab = brutas.get(iCab);
        int n = cab.length;
        while (n > 0 && cab[n - 1].trim().isEmpty()) { n--; t.colunasSobrando++; }
        if (t.colunasSobrando > 0) {
            t.avisos.add(t.colunasSobrando + " coluna(s) vazia(s) no fim do cabeçalho foram descartadas.");
        }
        t.cabecalho = new String[n];
        t.cabecalhoNorm = new String[n];
        Map<String, Integer> vistos = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String nome = limparTexto(cab[i]);
            if (nome.isEmpty()) nome = "COLUNA_" + (i + 1);
            String norm = Texto.normalizar(nome).replaceAll("[^A-Z0-9%]+", " ").trim();
            if (norm.isEmpty()) norm = "COLUNA " + (i + 1);
            if (vistos.containsKey(norm)) {
                int k = vistos.get(norm) + 1; vistos.put(norm, k);
                norm = norm + " " + k; nome = nome + " (" + k + ")";
                t.avisos.add("Cabeçalho repetido renomeado: " + nome);
            } else vistos.put(norm, 1);
            t.cabecalho[i] = nome;
            t.cabecalhoNorm[i] = norm;
        }

        for (int i = iCab + 1; i < brutas.size(); i++) {
            String[] l = brutas.get(i);
            if (mesmoCabecalho(l, cab, n)) { t.cabecalhosRepetidos++; continue; }
            String[] fixa = new String[n];
            int extras = 0;
            for (int j = 0; j < Math.max(n, l.length); j++) {
                String v = j < l.length ? limparTexto(l[j]) : "";
                if (j < n) fixa[j] = v;
                else if (!v.isEmpty()) extras++;
            }
            boolean tudoVazio = true;
            for (String v : fixa) if (!v.isEmpty()) { tudoVazio = false; break; }
            if (tudoVazio) { t.linhasVazias++; continue; }
            if (extras > 0) {
                // campos a mais com conteúdo: provável separador dentro de um texto sem aspas
                fixa = recolar(l, n, t.separador);
                t.avisos.add("Linha " + numeros.get(i) + ": " + extras + " campo(s) a mais — texto recolado.");
            }
            t.linhas.add(fixa);
            t.numeroLinha.add(numeros.get(i));
        }
        if (t.cabecalhosRepetidos > 0) {
            t.avisos.add(t.cabecalhosRepetidos + " cabeçalho(s) repetido(s) no meio do arquivo foram ignorados.");
        }
        return t;
    }

    /** Tabela já dividida (ex.: vinda do leitor .xlsx) -> mesma normalização de cabeçalho. */
    public static Tabela deLinhas(List<String[]> linhas) {
        StringBuilder sb = new StringBuilder();
        for (String[] l : linhas) {
            for (int i = 0; i < l.length; i++) {
                if (i > 0) sb.append('\t');
                String v = l[i] == null ? "" : l[i];
                if (v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\t') >= 0) {
                    v = '"' + v.replace("\"", "\"\"") + '"';
                }
                sb.append(v);
            }
            sb.append('\n');
        }
        Tabela t = ler(sb.toString().getBytes(StandardCharsets.UTF_8));
        t.encoding = "xlsx";
        return t;
    }

    // ---------------------------------------------------------------- encoding

    static String decodificar(byte[] b, Tabela t) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            t.encoding = "UTF-8 (BOM)";
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            t.encoding = "UTF-16LE";
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            t.encoding = "UTF-16BE";
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String s = dec.decode(ByteBuffer.wrap(b)).toString();
            t.encoding = "UTF-8";
            return s;
        } catch (CharacterCodingException e) {
            t.encoding = "Windows-1252 (ANSI)";
            t.avisos.add("Arquivo em ANSI/Windows-1252 convertido para UTF-8.");
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    private static List<String> quebrarLinhas(String s) {
        List<String> out = new ArrayList<>();
        int ini = 0, n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(s.substring(ini, i));
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') i++;
                ini = i + 1;
            }
        }
        if (ini < n) out.add(s.substring(ini));
        return out;
    }

    // ------------------------------------------------------------- separador

    static char detectarSeparador(List<String> linhas) {
        char[] cands = { ';', ',', '\t', '|' };
        double melhorNota = -1; char melhor = ';';
        for (char c : cands) {
            List<Integer> contagens = new ArrayList<>();
            for (int i = 0; i < linhas.size() && contagens.size() < 25; i++) {
                String l = linhas.get(i);
                if (l.trim().isEmpty()) continue;
                contagens.add(contarFora(l, c));
            }
            if (contagens.isEmpty()) continue;
            int max = 0; for (int k : contagens) max = Math.max(max, k);
            if (max == 0) continue;
            // moda das contagens e quantas linhas batem com ela
            Map<Integer, Integer> freq = new LinkedHashMap<>();
            for (int k : contagens) freq.put(k, freq.containsKey(k) ? freq.get(k) + 1 : 1);
            int moda = 0, fm = 0;
            for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
                if (e.getValue() > fm || (e.getValue() == fm && e.getKey() > moda)) { fm = e.getValue(); moda = e.getKey(); }
            }
            if (moda == 0) continue;
            double consist = (double) fm / contagens.size();
            double nota = moda * consist + (c == ';' ? .01 : 0); // empate -> ';' (padrão BR)
            if (nota > melhorNota) { melhorNota = nota; melhor = c; }
        }
        return melhor;
    }

    private static int contarFora(String s, char c) {
        int n = 0; boolean dentro = false;
        for (int i = 0; i < s.length(); i++) {
            char x = s.charAt(i);
            if (x == '"') dentro = !dentro;
            else if (!dentro && x == c) n++;
        }
        return n;
    }

    private static boolean aspasAbertas(String s) {
        boolean dentro = false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '"') dentro = !dentro;
        return dentro;
    }

    static String[] dividir(String linha, char sep) {
        List<String> campos = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean dentro = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (dentro) {
                if (c == '"') {
                    if (i + 1 < linha.length() && linha.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else dentro = false;
                } else sb.append(c);
            } else if (c == '"') {
                dentro = true;
            } else if (c == sep) {
                campos.add(sb.toString()); sb.setLength(0);
            } else sb.append(c);
        }
        campos.add(sb.toString());
        return campos.toArray(new String[0]);
    }

    /** Linha com campos a mais: junta o excesso no último campo textual. */
    private static String[] recolar(String[] l, int n, char sep) {
        String[] out = new String[n];
        for (int i = 0; i < n - 1; i++) out[i] = limparTexto(l[i]);
        StringBuilder resto = new StringBuilder();
        for (int i = n - 1; i < l.length; i++) {
            if (i > n - 1) resto.append(sep);
            resto.append(l[i]);
        }
        out[n - 1] = limparTexto(resto.toString());
        return out;
    }

    private static boolean mesmoCabecalho(String[] l, String[] cab, int n) {
        if (l.length < n) return false;
        for (int i = 0; i < n; i++) {
            if (!Texto.normalizar(l[i]).equals(Texto.normalizar(cab[i]))) return false;
        }
        return true;
    }

    /** Apara espaços, NBSP, tabs e aspas soltas. */
    public static String limparTexto(String s) {
        if (s == null) return "";
        String v = s.replace(' ', ' ').replace('​', ' ').replace("﻿", "").trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1).trim();
        if (v.startsWith("'") && v.length() > 1) v = v.substring(1).trim(); // '0123 do Excel
        if (v.startsWith("=") && v.length() > 1 && !v.startsWith("==")) v = v.substring(1).trim(); // ="0123"
        return v;
    }

    private static boolean numeroSimples(String s) {
        return s.trim().matches("[-+]?[\\d.,]+%?");
    }

    // ================================================================ números

    private static final String[] VAZIOS = { "", "-", "--", "—", "–", "N/A", "NA", "N/D", "ND", "NULL",
        "NAO INFORMADO", "NÃO INFORMADO", "#N/D", "#N/A", "#DIV/0!", "#VALOR!", "#VALUE!", "#REF!",
        "#NOME?", "#NUM!", "#NULO!", "S/D", "SD", "NIL", "NAN", "." };

    /** Célula "vazia por convenção" (traço, N/D, erro do Excel...). */
    public static boolean celulaVazia(String s) {
        String v = Texto.normalizar(limparTexto(s));
        for (String x : VAZIOS) if (v.equals(x)) return true;
        return false;
    }

    /**
     * Infere o estilo decimal de uma coluna olhando todos os valores: quem tem
     * vírgula e ponto decide pela posição; quem tem só um separador fora de
     * grupos de 3 decide. Empate/sem pista -> BR (padrão das planilhas do BB).
     */
    public static Estilo inferirEstilo(List<String> valores) {
        int br = 0, en = 0;
        for (String s : valores) {
            if (s == null || celulaVazia(s)) continue;
            String v = pre(s);
            int iV = v.lastIndexOf(','), iP = v.lastIndexOf('.');
            if (iV >= 0 && iP >= 0) { if (iV > iP) br++; else en++; continue; }
            if (iV >= 0) { // só vírgula
                String dep = v.substring(iV + 1).replaceAll("\\D", "");
                if (dep.length() == 3 && conta(v, ',') == 1 && v.indexOf(',') > 0) { /* ambíguo: 1,234 */ }
                else br++;
            } else if (iP >= 0) { // só ponto
                String dep = v.substring(iP + 1).replaceAll("\\D", "");
                if (dep.length() == 3 && v.indexOf('.') > 0) { /* ambíguo: 1.234 */ }
                else en++;
            }
        }
        return en > br ? Estilo.EN : Estilo.BR;
    }

    private static String pre(String s) {
        return limparTexto(s).replace("R$", "").replace("US$", "").replace("$", "").replace("%", "")
            .replace(" ", "").replace("−", "-");
    }

    private static int conta(String s, char c) {
        int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n;
    }

    /**
     * Converte uma célula em número, tolerando o que a planilha trouxe. Devolve
     * null quando a célula é vazia por convenção ou irrecuperável (neste caso
     * registra a rejeição no relatório, se houver). Correções viram entradas.
     */
    public static Double numero(String bruto, Estilo estilo, Relatorio rel, int linha, String coluna) {
        if (bruto == null) return null;
        String original = bruto;
        String v = limparTexto(bruto);
        if (celulaVazia(v)) {
            if (!v.isEmpty() && rel != null) rel.corrigiu(linha, coluna, original, "", "vazio por convenção");
            return null;
        }
        List<String> regras = new ArrayList<>();
        String antes = v;

        // sinais e símbolos
        if (v.indexOf("R$") >= 0 || v.indexOf('$') >= 0) { v = v.replace("R$", "").replace("US$", "").replace("$", "").trim(); regras.add("símbolo monetário"); }
        if (v.indexOf('%') >= 0) { v = v.replace("%", "").trim(); regras.add("percentual"); }
        if (v.indexOf(' ') >= 0) { v = v.replace(" ", ""); regras.add("espaços internos"); }
        if (v.indexOf('−') >= 0) { v = v.replace('−', '-'); regras.add("sinal unicode"); }
        boolean negativo = false;
        if (v.startsWith("(") && v.endsWith(")")) { v = v.substring(1, v.length() - 1); negativo = true; regras.add("parênteses = negativo"); }
        if (v.endsWith("-") && v.length() > 1) { v = v.substring(0, v.length() - 1); negativo = true; regras.add("sinal no fim"); }
        if (v.startsWith("+")) v = v.substring(1);
        if (v.startsWith("-")) { v = v.substring(1); negativo = !negativo; }

        // letras no lugar de dígitos (O->0, l/I->1) quando o resto é numérico
        if (v.matches(".*[OolI].*") && v.matches("[0-9OolI.,]+") && v.matches(".*\\d.*")) {
            v = v.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1');
            regras.add("letra no lugar de dígito");
        }
        // sufixos de escala (mil, mi, k)
        double escala = 1;
        String low = v.toLowerCase();
        if (low.endsWith("mil")) { v = v.substring(0, v.length() - 3); escala = 1000; regras.add("sufixo mil"); }
        else if (low.endsWith("mi")) { v = v.substring(0, v.length() - 2); escala = 1e6; regras.add("sufixo mi"); }
        else if (low.endsWith("k")) { v = v.substring(0, v.length() - 1); escala = 1000; regras.add("sufixo k"); }

        if (!v.matches("[0-9.,]+")) {
            if (rel != null) rel.invalida(linha, coluna, original);
            return null;
        }

        // separadores
        int iV = v.lastIndexOf(','), iP = v.lastIndexOf('.');
        String digitos;
        if (iV >= 0 && iP >= 0) {
            // o último separador é o decimal, o outro é milhar
            char dec = iV > iP ? ',' : '.';
            char mil = dec == ',' ? '.' : ',';
            String sem = v.replace(String.valueOf(mil), "");
            if (conta(sem, dec) > 1) { if (rel != null) rel.invalida(linha, coluna, original); return null; }
            digitos = sem.replace(dec, '.');
            if ((dec == '.' && estilo == Estilo.BR) || (dec == ',' && estilo == Estilo.EN)) regras.add("separador decimal trocado");
            else regras.add("milhar removido");
        } else if (iV >= 0) {
            int q = conta(v, ',');
            String dep = v.substring(iV + 1);
            if (q > 1 || (estilo == Estilo.EN && dep.length() == 3)) {
                digitos = v.replace(",", ""); regras.add("milhar removido");          // 1,234,567 ou 1,234 (EN)
            } else {
                digitos = v.replace(',', '.');
                if (estilo == Estilo.EN) regras.add("separador decimal trocado");
            }
        } else if (iP >= 0) {
            int q = conta(v, '.');
            String dep = v.substring(iP + 1);
            if (q > 1 || (estilo == Estilo.BR && dep.length() == 3 && iP > 0)) {
                digitos = v.replace(".", ""); regras.add("milhar removido");          // 1.234.567 ou 1.234 (BR)
            } else {
                digitos = v;
                if (estilo == Estilo.BR) regras.add("separador decimal trocado");      // 12.5 numa coluna BR
            }
        } else digitos = v;

        if (digitos.isEmpty() || digitos.equals(".")) {
            if (rel != null) rel.invalida(linha, coluna, original);
            return null;
        }
        double d;
        try { d = Double.parseDouble(digitos) * escala; }
        catch (NumberFormatException e) {
            if (rel != null) rel.invalida(linha, coluna, original);
            return null;
        }
        if (negativo) d = -d;
        if (rel != null && !regras.isEmpty()) {
            rel.corrigiu(linha, coluna, original, Json.num(d), String.join(" + ", regras));
        }
        return d;
    }

    /** Atalho sem relatório e com estilo BR. */
    public static Double numero(String bruto) {
        return numero(bruto, Estilo.BR, null, 0, "");
    }

    /** Inteiro tolerante (arredonda). */
    public static Integer inteiro(String bruto, Estilo estilo, Relatorio rel, int linha, String coluna) {
        Double d = numero(bruto, estilo, rel, linha, coluna);
        if (d == null) return null;
        if (d != Math.rint(d) && rel != null) rel.corrigiu(linha, coluna, bruto, String.valueOf(Math.round(d)), "arredondado para inteiro");
        return (int) Math.round(d);
    }

    /** Dígitos de código (prefixo/matrícula) com letras confundíveis corrigidas. */
    public static String codigoNumerico(String bruto, Relatorio rel, int linha, String coluna) {
        String v = limparTexto(bruto);
        if (v.matches("[0-9OolI\\- ]+") && v.matches(".*[OolI].*")) {
            String c = v.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1');
            if (rel != null) rel.corrigiu(linha, coluna, bruto, c, "letra no lugar de dígito");
            v = c;
        }
        return v;
    }

    // ============================================================ competência

    private static final String[] MESES = { "JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO",
        "SET", "OUT", "NOV", "DEZ" };

    /**
     * "mm/aaaa", "aaaa-mm", "aaaamm", "dd/mm/aaaa", "aaaa-mm-dd", "mar/2026",
     * "2026-03-01T00:00", serial do Excel -> "AAAA-MM". null se não entende.
     */
    public static String competencia(String bruto) {
        if (bruto == null) return null;
        String s = limparTexto(bruto);
        if (s.isEmpty() || celulaVazia(s)) return null;
        String n = Texto.normalizar(s);
        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(n);
        if (m.find()) return m.group(3) + "-" + dois(m.group(2));                       // dd/mm/aaaa
        m = java.util.regex.Pattern.compile("^(\\d{4})-(\\d{1,2})(-\\d{1,2})?").matcher(n);
        if (m.find()) return m.group(1) + "-" + dois(m.group(2));                       // aaaa-mm[-dd]
        m = java.util.regex.Pattern.compile("^(\\d{1,2})[/\\-.](\\d{4})$").matcher(n);
        if (m.find()) return m.group(2) + "-" + dois(m.group(1));                       // mm/aaaa
        m = java.util.regex.Pattern.compile("^(\\d{4})[/.](\\d{1,2})$").matcher(n);
        if (m.find()) return m.group(1) + "-" + dois(m.group(2));                       // aaaa/mm
        m = java.util.regex.Pattern.compile("^(\\d{4})(\\d{2})$").matcher(n);
        if (m.find() && Integer.parseInt(m.group(2)) <= 12) return m.group(1) + "-" + m.group(2); // aaaamm
        m = java.util.regex.Pattern.compile("^(\\d{2})(\\d{4})$").matcher(n);
        if (m.find() && Integer.parseInt(m.group(1)) <= 12) return m.group(2) + "-" + m.group(1); // mmaaaa
        m = java.util.regex.Pattern.compile("^([A-Z]{3})[A-Z]*[/\\- ]+(\\d{2,4})$").matcher(n);
        if (m.find()) {                                                                 // mar/2026, março 26
            for (int i = 0; i < MESES.length; i++) {
                if (MESES[i].equals(m.group(1))) {
                    String ano = m.group(2).length() == 2 ? "20" + m.group(2) : m.group(2);
                    return ano + "-" + dois(String.valueOf(i + 1));
                }
            }
        }
        if (n.matches("\\d{4,6}(\\.0+)?")) {                                            // serial do Excel
            Long ms = Xlsx.dataDeCelula(n);
            if (ms != null) {
                java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                c.setTimeInMillis(ms);
                return c.get(java.util.Calendar.YEAR) + "-" + dois(String.valueOf(c.get(java.util.Calendar.MONTH) + 1));
            }
        }
        return null;
    }

    private static String dois(String s) { return s.length() == 1 ? "0" + s : s; }

    // =============================================================== colunas

    /** Coluna parece numérica? (>= 60% das células não vazias viram número) */
    public static boolean colunaNumerica(Tabela t, int col) {
        int cheias = 0, numericas = 0;
        for (String[] l : t.linhas) {
            String v = l[col];
            if (celulaVazia(v)) continue;
            cheias++;
            if (numero(v, Estilo.BR, null, 0, "") != null) numericas++;
        }
        return cheias > 0 && numericas * 10 >= cheias * 6;
    }

    public static List<String> valoresDe(Tabela t, int col) {
        List<String> out = new ArrayList<>(t.linhas.size());
        for (String[] l : t.linhas) out.add(l[col]);
        return out;
    }

    /** Serializa a tabela (ou parte dela) de volta em CSV ';' UTF-8. */
    public static String paraCsv(String[] cabecalho, List<String[]> linhas) {
        StringBuilder sb = new StringBuilder();
        sb.append(linhaCsv(cabecalho));
        for (String[] l : linhas) sb.append(linhaCsv(l));
        return sb.toString();
    }

    private static String linhaCsv(String[] campos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) sb.append(';');
            String v = campos[i] == null ? "" : campos[i];
            if (v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) v = '"' + v.replace("\"", "\"\"") + '"';
            sb.append(v);
        }
        return sb.append('\n').toString();
    }
}
