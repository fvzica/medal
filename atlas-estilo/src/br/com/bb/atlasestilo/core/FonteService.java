package br.com.bb.atlasestilo.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.bb.atlasestilo.dao.ConfigDao;
import br.com.bb.atlasestilo.dao.FonteDao;
import br.com.bb.atlasestilo.dao.FonteDao.Fonte;
import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Saneador;
import br.com.bb.atlasestilo.util.Texto;

/**
 * Fontes de dados em CSV na pasta do servidor: varre a pasta, resolve o
 * arquivo de cada fonte (nome ou padrão com *), lê com o Saneador, valida,
 * grava (ou só analisa) e guarda o relatório — tudo configurado pelo Master
 * na tela Admin. Tipos tipados (agências, funcis, carteiras, PDG, metas)
 * reaproveitam o ImportService; 'conexao' e 'indicadores' são tratados aqui.
 */
public final class FonteService {

    private FonteService() { }

    public static final String MATRICULA_MONITOR = "MONITOR";
    private static volatile String pastaPadrao = "dados/atlasestilo/csv";

    public static void definirPastaPadrao(String p) { if (p != null) pastaPadrao = p; }
    public static String pastaPadrao() { return pastaPadrao; }

    public static File pasta() throws SQLException {
        return new File(FonteDao.param(FonteDao.P_PASTA, pastaPadrao));
    }

    // ------------------------------------------------------------------ pasta

    /** Situação da pasta e arquivos candidatos (csv/txt/xlsx). */
    public static String varrerPasta() throws SQLException {
        File dir = pasta();
        Json.Obj o = Json.obj().put("pasta", dir.getPath()).put("pastaPadrao", pastaPadrao)
            .put("existe", dir.exists()).put("ehPasta", dir.isDirectory()).put("legivel", dir.canRead());
        Json.Arr arr = Json.arr();
        File[] lista = dir.isDirectory() ? dir.listFiles() : null;
        int total = 0;
        if (lista != null) {
            java.util.Arrays.sort(lista, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : lista) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase();
                if (!(n.endsWith(".csv") || n.endsWith(".txt") || n.endsWith(".xlsx"))) continue;
                total++;
                if (total > 200) continue;
                arr.add(Json.obj().put("nome", f.getName()).put("tamanho", f.length())
                    .put("modificadoEm", f.lastModified()).fim());
            }
        }
        return o.put("total", total).putRaw("arquivos", arr.fim())
            .put("monitorMinutos", Integer.parseInt(FonteDao.param(FonteDao.P_MINUTOS, "10")))
            .put("estrito", "1".equals(FonteDao.param(FonteDao.P_ESTRITO, "0"))).fim();
    }

    /**
     * Arquivo da fonte: nome exato (sem diferenciar maiúsculas) ou padrão com
     * '*' e '?'; havendo vários, vale o modificado por último.
     */
    public static File resolverArquivo(File dir, String padrao) {
        if (dir == null || !dir.isDirectory() || Texto.vazio(padrao)) return null;
        String p = padrao.trim();
        // segurança: só nomes dentro da pasta (sem caminhos)
        if (p.indexOf('/') >= 0 || p.indexOf('\\') >= 0 || p.contains("..")) return null;
        StringBuilder rx = new StringBuilder("(?i)^");
        for (char ch : p.toCharArray()) {
            if (ch == '*') rx.append(".*");
            else if (ch == '?') rx.append('.');
            else rx.append(Pattern.quote(String.valueOf(ch)));
        }
        rx.append('$');
        Pattern pat = Pattern.compile(rx.toString());
        File melhor = null;
        File[] lista = dir.listFiles();
        if (lista == null) return null;
        for (File f : lista) {
            if (!f.isFile() || !pat.matcher(f.getName()).matches()) continue;
            if (melhor == null || f.lastModified() > melhor.lastModified()) melhor = f;
        }
        return melhor;
    }

    // ----------------------------------------------------------------- modelos

    public static String modelo(String tipo) {
        switch (tipo) {
            case "conexao": return
                "prefixo;competencia;carteira;gerente_matricula;gerente_nome;pontos\n" +
                "1881;09/2026;;;;812,5\n" +
                "1881;09/2026;EST-01;F0000001;Fulana de Tal;845\n" +
                "1881;09/2026;EST-02;F0000002;Beltrano Silva;790\n";
            case "indicadores": return
                "prefixo;competencia;captacao;credito;nps;reclamacoes\n" +
                "1881;09/2026;1.250.000,00;980.500,50;72;3\n" +
                "1882;09/2026;R$ 870.000,00;1.100.000,00;68;5\n";
            default: return ImportService.modelo(tipo);
        }
    }

    /** Campos aceitos por tipo (com sinônimos) — alimenta o de-para do admin. */
    public static String camposJson(String tipo) {
        Json.Arr arr = Json.arr();
        if (tipo.equals("conexao") || tipo.equals("indicadores")) {
            String[] campos = tipo.equals("conexao")
                ? new String[] { "prefixo", "pontos", "competencia", "carteira", "gerente_matricula", "gerente_nome" }
                : new String[] { "prefixo", "competencia" };
            for (String c : campos) {
                Json.Arr sin = Json.arr();
                for (String x : SIN.get(c)) sin.addStr(x);
                arr.add(Json.obj().put("campo", c).put("obrigatorio", c.equals("prefixo") || c.equals("pontos"))
                    .putRaw("sinonimos", sin.fim()).fim());
            }
            return arr.fim();
        }
        for (String c : ImportService.camposDe(tipo)) {
            Json.Arr sin = Json.arr();
            for (String x : ImportService.sinonimos(c)) sin.addStr(x);
            arr.add(Json.obj().put("campo", c).put("obrigatorio", ImportService.obrigatorio(tipo, c))
                .putRaw("sinonimos", sin.fim()).fim());
        }
        return arr.fim();
    }

    // ------------------------------------------------------------ processamento

    /** Lê o arquivo da fonte na pasta e processa (prévia ou gravação). */
    public static String analisarFonte(long id, boolean confirmar, String matricula, long agora)
            throws SQLException, IOException {
        Fonte f = FonteDao.fonte(id);
        if (f == null) return Json.obj().put("erro", "Fonte não encontrada.").fim();
        File arq = resolverArquivo(pasta(), f.arquivo);
        if (arq == null) {
            String msg = "Nenhum arquivo em " + pasta().getPath() + " casa com \"" + f.arquivo + "\".";
            FonteDao.fonteStatus(id, null, null, "SEM ARQUIVO", msg, null, null, agora);
            return Json.obj().put("erro", msg).fim();
        }
        if (arq.length() > 60L * 1024 * 1024) {
            return Json.obj().put("erro", "Arquivo acima de 60 MB: " + arq.getName()).fim();
        }
        byte[] dados = Files.readAllBytes(arq.toPath());
        return processarFonte(f, arq.getName(), dados, arq.lastModified(), confirmar, matricula, agora);
    }

    /**
     * Núcleo: tabela saneada -> validação -> gravação opcional -> relatório
     * persistido na fonte. Em modo estrito nada é gravado se houver qualquer
     * linha rejeitada ou célula inválida.
     */
    public static String processarFonte(Fonte f, String nomeArquivo, byte[] dados, Long mtime,
                                        boolean confirmar, String matricula, long agora)
            throws SQLException, IOException {
        boolean estrito = "1".equals(FonteDao.param(FonteDao.P_ESTRITO, "0"));
        Saneador.Tabela t = ImportService.lerTabela(nomeArquivo, dados);
        Saneador.Relatorio rel = new Saneador.Relatorio();
        Json.Obj rep = Json.obj()
            .putRaw("fonte", Json.obj().put("id", f.id).put("nome", f.nome).put("tipo", f.tipo).fim())
            .put("arquivo", nomeArquivo).put("tamanho", dados.length).putNum("modificadoEm", mtime)
            .put("encoding", t.encoding).put("separador", t.separador == '\t' ? "TAB" : String.valueOf(t.separador))
            .put("linhaCabecalho", t.linhaCabecalho).put("estrito", estrito);
        Json.Arr cab = Json.arr();
        for (String c : t.cabecalho) cab.addStr(c);
        rep.putRaw("cabecalho", cab.fim());
        List<String> avisos = new ArrayList<>(t.avisos);

        if (t.linhas.isEmpty()) {
            return fechar(f, nomeArquivo, mtime, rep, rel, avisos, "ERRO",
                "Arquivo sem linhas de dados (só cabeçalho?).", 0, 0, 0, 0, false, agora);
        }

        int linhas = t.linhas.size(), inseridos = 0, atualizados = 0, ignorados = 0;
        boolean gravado = false;
        String erroFatal = null;

        if (f.tipo.equals("conexao") || f.tipo.equals("indicadores")) {
            Pacote p = f.tipo.equals("conexao") ? lerConexao(f, nomeArquivo, t, rel, avisos, agora)
                                                : lerIndicadores(f, nomeArquivo, t, rel, avisos, agora);
            if (p.erroFatal != null) erroFatal = p.erroFatal;
            else {
                ignorados = p.ignorados;
                boolean bloqueia = estrito && (rel.totalRejeitadas > 0 || rel.celulasInvalidas > 0);
                if (confirmar && !bloqueia) {
                    try (Connection c = Db.conexao()) {
                        c.setAutoCommit(false);
                        try {
                            if (f.tipo.equals("conexao")) {
                                FonteDao.conexaoGravar(c, p.conexao, "IMPORT", agora);
                                FonteDao.conexaoGravar(c, p.conexaoDerivada, "DERIVADO", agora);
                            } else {
                                FonteDao.indicadoresGravar(c, f.id, p.valores);
                            }
                            c.commit();
                        } catch (SQLException e) { c.rollback(); throw e; }
                        finally { c.setAutoCommit(true); }
                    }
                    gravado = true;
                    inseridos = p.novos; atualizados = p.existentes;
                    ConfigDao.importLog(f.tipo, nomeArquivo, inseridos, atualizados, ignorados, matricula, agora);
                    if (f.tipo.equals("indicadores")) avisos.addAll(FonteDao.foraDaFaixa(f.id));
                } else {
                    inseridos = p.novos; atualizados = p.existentes;
                    if (confirmar && bloqueia) {
                        avisos.add("Modo estrito: nada foi gravado porque há " + rel.totalRejeitadas +
                            " linha(s) rejeitada(s) e " + rel.celulasInvalidas + " célula(s) inválida(s).");
                    }
                }
                rep.putRaw("mapeamento", p.mapeamentoJson);
                if (p.colunasIndicadores != null) rep.putRaw("colunasIndicadores", p.colunasIndicadores);
                if (p.colunasIgnoradas != null) rep.putRaw("colunasIgnoradas", p.colunasIgnoradas);
                rep.putRaw("competencias", p.competenciasJson);
                rep.put("derivadas", p.conexaoDerivada.size());
            }
        } else {
            // tipos tipados: dry-run primeiro (estrito precisa saber antes de gravar)
            ImportService.Resultado r = ImportService.processarTabela(f.tipo, nomeArquivo, t,
                f.mapeamento, false, matricula, agora, rel);
            if (r.erroFatal != null) erroFatal = r.erroFatal;
            else {
                boolean bloqueia = estrito && (rel.totalRejeitadas > 0 || rel.celulasInvalidas > 0);
                if (confirmar && !bloqueia) {
                    Saneador.Relatorio rel2 = new Saneador.Relatorio();
                    r = ImportService.processarTabela(f.tipo, nomeArquivo, t, f.mapeamento, true,
                        matricula, agora, rel2);
                    gravado = true;
                } else if (confirmar) {
                    avisos.add("Modo estrito: nada foi gravado porque há " + rel.totalRejeitadas +
                        " linha(s) rejeitada(s) e " + rel.celulasInvalidas + " célula(s) inválida(s).");
                }
                inseridos = r.inseridos; atualizados = r.atualizados; ignorados = r.ignorados;
                Json.Obj m = Json.obj();
                for (Map.Entry<String, String> e : r.mapeamento.entrySet()) m.put(e.getKey(), e.getValue());
                rep.putRaw("mapeamento", m.fim());
                avisos.addAll(r.erros);
                avisos.addAll(prefixosDesconhecidos(t, r));
            }
        }

        if (erroFatal != null) {
            return fechar(f, nomeArquivo, mtime, rep, rel, avisos, "ERRO", erroFatal, linhas, 0, 0, 0, false, agora);
        }
        int validas = linhas - rel.totalRejeitadas;
        String status = rel.totalRejeitadas > 0 || rel.celulasInvalidas > 0 || !avisos.isEmpty() ? "AVISOS" : "OK";
        if (confirmar && !gravado) status = "ERRO";
        String resumo = (gravado ? "Importado" : confirmar ? "Bloqueado" : "Analisado") + " · " + linhas +
            " linha(s), " + validas + " válida(s), " + rel.totalCorrecoes + " correção(ões), " +
            rel.totalRejeitadas + " rejeitada(s)" + (gravado ? " · " + inseridos + " nova(s), " + atualizados + " atualizada(s)" : "");
        return fechar(f, nomeArquivo, mtime, rep, rel, avisos, status, resumo, linhas, inseridos, atualizados,
            ignorados, gravado, agora);
    }

    private static String fechar(Fonte f, String nomeArquivo, Long mtime, Json.Obj rep, Saneador.Relatorio rel,
                                 List<String> avisos, String status, String resumo, int linhas, int inseridos,
                                 int atualizados, int ignorados, boolean gravado, long agora) throws SQLException {
        Json.Arr av = Json.arr();
        for (String a : avisos) av.addStr(a);
        rep.put("status", status).put("resumo", resumo).put("linhas", linhas)
           .put("validas", Math.max(0, linhas - rel.totalRejeitadas))
           .put("inseridos", inseridos).put("atualizados", atualizados).put("ignorados", ignorados)
           .put("confirmado", gravado).putRaw("avisos", av.fim()).putRaw("saneamento", rel.json())
           .put("quando", agora);
        String json = rep.fim();
        FonteDao.fonteStatus(f.id, nomeArquivo, mtime, status, resumo, json,
            rel.totalRejeitadas > 0 ? rel.rejeitadasCsv() : null, agora);
        return json;
    }

    // -------------------------------------------------------------- pacotes

    /** Dados prontos para gravar + metadados do relatório. */
    private static final class Pacote {
        final List<FonteDao.LinhaConexao> conexao = new ArrayList<>();
        final List<FonteDao.LinhaConexao> conexaoDerivada = new ArrayList<>();
        final List<FonteDao.Valor> valores = new ArrayList<>();
        String mapeamentoJson = "{}", colunasIndicadores, colunasIgnoradas, competenciasJson = "[]", erroFatal;
        int ignorados, novos, existentes;
    }

    private static final Map<String, String[]> SIN = new HashMap<>();
    static {
        SIN.put("prefixo", new String[] { "PREFIXO", "PREF", "AGENCIA", "AGENCIA PREFIXO", "DEPENDENCIA",
            "PREFIXO AGENCIA", "COD AGENCIA", "CODIGO AGENCIA", "PREFIXO DEPENDENCIA", "AG", "UNIDADE" });
        SIN.put("competencia", new String[] { "COMPETENCIA", "MES", "MES REFERENCIA", "MES REF", "REFERENCIA",
            "PERIODO", "DATA", "DATA BASE", "DATA REFERENCIA", "ANO MES", "ANOMES", "SAFRA", "COMP" });
        SIN.put("pontos", new String[] { "PONTOS", "CONEXAO", "NOTA", "SCORE", "PONTUACAO", "RESULTADO",
            "INDICE", "PONTOS CONEXAO", "NOTA CONEXAO", "VALOR" });
        SIN.put("carteira", new String[] { "CARTEIRA", "COD CARTEIRA", "CODIGO CARTEIRA", "CARTEIRA COD" });
        SIN.put("gerente_matricula", new String[] { "GERENTE MATRICULA", "MATRICULA GERENTE", "MATRICULA",
            "CHAVE", "FUNCI", "CHAVE GERENTE", "MATRICULA DO GERENTE" });
        SIN.put("gerente_nome", new String[] { "GERENTE NOME", "NOME GERENTE", "GERENTE", "NOME",
            "NOME DO GERENTE" });
    }

    private static Map<String, Integer> mapear(Saneador.Tabela t, Map<String, String> manual, String... campos) {
        Map<String, Integer> col = new LinkedHashMap<>();
        if (manual != null) {
            for (Map.Entry<String, String> e : manual.entrySet()) {
                int i = t.indice(ImportService.chaveColuna(e.getValue()));
                if (i >= 0) col.put(e.getKey(), i);
            }
        }
        for (String campo : campos) {
            if (col.containsKey(campo)) continue;
            for (String sin : SIN.get(campo)) {
                int i = t.indice(ImportService.chaveColuna(sin));
                if (i >= 0 && !col.containsValue(i)) { col.put(campo, i); break; }
            }
        }
        return col;
    }

    private static String mapeamentoJson(Saneador.Tabela t, Map<String, Integer> col) {
        Json.Obj m = Json.obj();
        for (Map.Entry<String, Integer> e : col.entrySet()) m.put(e.getKey(), t.cabecalho[e.getValue()]);
        return m.fim();
    }

    /** "conexao_2026-09.csv", "conexao_092026.csv", "indicadores 09-2026.xlsx" -> AAAA-MM. */
    static String competenciaDoNome(String nome) {
        if (nome == null) return null;
        Matcher m = Pattern.compile("(20\\d{2})[-_. ]?(0[1-9]|1[0-2])(?!\\d)").matcher(nome);
        if (m.find()) return m.group(1) + "-" + m.group(2);
        m = Pattern.compile("(?<!\\d)(0[1-9]|1[0-2])[-_. ]?(20\\d{2})").matcher(nome);
        if (m.find()) return m.group(2) + "-" + m.group(1);
        return null;
    }

    private static String competenciaAtual(long agora) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
        c.setTimeInMillis(agora);
        int mes = c.get(java.util.Calendar.MONTH) + 1;
        return c.get(java.util.Calendar.YEAR) + "-" + (mes < 10 ? "0" + mes : String.valueOf(mes));
    }

    private static Pacote lerConexao(Fonte f, String nomeArquivo, Saneador.Tabela t, Saneador.Relatorio rel,
                                     List<String> avisos, long agora) throws SQLException {
        Pacote p = new Pacote();
        Map<String, Integer> col = mapear(t, f.mapeamento, "prefixo", "pontos", "competencia", "carteira",
            "gerente_matricula", "gerente_nome");
        p.mapeamentoJson = mapeamentoJson(t, col);
        if (!col.containsKey("prefixo") || !col.containsKey("pontos")) {
            p.erroFatal = "Colunas obrigatórias ausentes (prefixo e pontos). Encontradas: " +
                String.join(", ", t.cabecalho) + ". Use o de-para da fonte ou baixe o modelo.";
            return p;
        }
        String compPadrao = null;
        if (!col.containsKey("competencia")) {
            compPadrao = competenciaDoNome(nomeArquivo);
            if (compPadrao == null) { compPadrao = competenciaAtual(agora); avisos.add("Sem coluna de competência e sem mês no nome do arquivo: assumido " + compPadrao + "."); }
            else avisos.add("Sem coluna de competência: usado o mês do nome do arquivo (" + compPadrao + ").");
        }
        Saneador.Estilo estilo = Saneador.inferirEstilo(Saneador.valoresDe(t, col.get("pontos")));
        Map<String, FonteDao.LinhaConexao> porChave = new LinkedHashMap<>();
        Set<String> comps = new LinkedHashSet<>();
        int foraFaixa = 0;
        for (int i = 0; i < t.linhas.size(); i++) {
            String[] l = t.linhas.get(i);
            int n = t.numeroLinha.get(i);
            String prefixo = Texto.prefixo(Saneador.codigoNumerico(l[col.get("prefixo")], rel, n, "prefixo"));
            if (prefixo.isEmpty()) { rel.rejeitou(n, "prefixo vazio ou inválido", String.join(";", l)); continue; }
            Double pontos = Saneador.numero(l[col.get("pontos")], estilo, rel, n, "pontos");
            if (pontos == null) { rel.rejeitou(n, "pontos vazios ou não numéricos", String.join(";", l)); continue; }
            if (pontos < 0 || pontos > 1000) foraFaixa++;
            String comp = compPadrao;
            if (col.containsKey("competencia")) {
                String bruto = l[col.get("competencia")];
                comp = Saneador.competencia(bruto);
                if (comp == null) {
                    if (Saneador.celulaVazia(bruto)) {
                        comp = competenciaDoNome(nomeArquivo);
                        if (comp == null) comp = competenciaAtual(agora);
                        rel.corrigiu(n, "competencia", bruto, comp, "competência vazia → assumida");
                    } else { rel.rejeitou(n, "competência não reconhecida: \"" + bruto + "\"", String.join(";", l)); continue; }
                } else if (!Texto.normalizar(bruto).equals(comp)) {
                    rel.corrigiu(n, "competencia", bruto, comp, "competência normalizada");
                }
            }
            FonteDao.LinhaConexao k = new FonteDao.LinhaConexao();
            k.prefixo = prefixo; k.competencia = comp; k.pontos = pontos;
            k.carteira = col.containsKey("carteira") ? Saneador.limparTexto(l[col.get("carteira")]) : "";
            if (Saneador.celulaVazia(k.carteira)) k.carteira = "";
            if (col.containsKey("gerente_matricula")) {
                k.gerenteMatricula = Texto.matricula(Saneador.codigoNumerico(l[col.get("gerente_matricula")], rel, n, "gerente_matricula"));
            }
            if (col.containsKey("gerente_nome")) k.gerenteNome = Saneador.limparTexto(l[col.get("gerente_nome")]);
            String chave = prefixo + "|" + comp + "|" + k.carteira;
            if (porChave.containsKey(chave)) p.ignorados++;
            porChave.put(chave, k);
            comps.add(comp);
        }
        p.conexao.addAll(porChave.values());
        if (foraFaixa > 0) avisos.add(foraFaixa + " valor(es) de Conexão fora da faixa 0–1000 (gravados mesmo assim).");

        // agência sem linha própria: nota derivada da média das carteiras
        Map<String, List<FonteDao.LinhaConexao>> porAg = new LinkedHashMap<>();
        Set<String> comNotaPropria = new java.util.HashSet<>();
        for (FonteDao.LinhaConexao k : p.conexao) {
            String ag = k.prefixo + "|" + k.competencia;
            if (k.carteira.isEmpty()) comNotaPropria.add(ag);
            else { if (!porAg.containsKey(ag)) porAg.put(ag, new ArrayList<>()); porAg.get(ag).add(k); }
        }
        for (Map.Entry<String, List<FonteDao.LinhaConexao>> e : porAg.entrySet()) {
            if (comNotaPropria.contains(e.getKey())) continue;
            double soma = 0;
            for (FonteDao.LinhaConexao k : e.getValue()) soma += k.pontos;
            FonteDao.LinhaConexao d = new FonteDao.LinhaConexao();
            d.prefixo = e.getValue().get(0).prefixo; d.competencia = e.getValue().get(0).competencia;
            d.carteira = ""; d.pontos = Math.round(soma / e.getValue().size() * 10) / 10.0;
            p.conexaoDerivada.add(d);
        }
        if (!p.conexaoDerivada.isEmpty()) {
            avisos.add(p.conexaoDerivada.size() + " agência(s) sem nota própria: Conexão derivada da média das carteiras.");
        }
        Json.Arr cj = Json.arr();
        for (String c : comps) cj.addStr(c);
        p.competenciasJson = cj.fim();
        contarNovosConexao(p);
        avisos.addAll(prefixosDesconhecidos(prefixosDe(p.conexao)));
        return p;
    }

    private static void contarNovosConexao(Pacote p) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM conexao WHERE prefixo = ? AND competencia = ? AND carteira = ?")) {
            for (FonteDao.LinhaConexao k : p.conexao) {
                ps.setString(1, k.prefixo); ps.setString(2, k.competencia); ps.setString(3, k.carteira);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) p.existentes++; else p.novos++; }
            }
        }
    }

    private static Pacote lerIndicadores(Fonte f, String nomeArquivo, Saneador.Tabela t, Saneador.Relatorio rel,
                                         List<String> avisos, long agora) throws SQLException {
        Pacote p = new Pacote();
        Map<String, Integer> col = mapear(t, f.mapeamento, "prefixo", "competencia");
        p.mapeamentoJson = mapeamentoJson(t, col);
        if (!col.containsKey("prefixo")) {
            p.erroFatal = "Coluna de prefixo ausente. Encontradas: " + String.join(", ", t.cabecalho) +
                ". Use o de-para da fonte (prefixo = nome da coluna) ou baixe o modelo.";
            return p;
        }
        String compPadrao = null;
        if (!col.containsKey("competencia")) {
            compPadrao = competenciaDoNome(nomeArquivo);
            if (compPadrao == null) { compPadrao = ""; avisos.add("Sem coluna de competência e sem mês no nome do arquivo: valores tratados como foto atual (sem histórico)."); }
            else avisos.add("Sem coluna de competência: usado o mês do nome do arquivo (" + compPadrao + ").");
        }
        // colunas de indicador = numéricas que não são prefixo/competência
        List<Integer> cols = new ArrayList<>();
        Json.Arr ci = Json.arr(), cig = Json.arr();
        Map<Integer, Saneador.Estilo> estilos = new HashMap<>();
        for (int i = 0; i < t.cabecalho.length; i++) {
            if (col.containsValue(i)) continue;
            if (Saneador.colunaNumerica(t, i)) {
                cols.add(i); ci.addStr(t.cabecalhoNorm[i]);
                estilos.put(i, Saneador.inferirEstilo(Saneador.valoresDe(t, i)));
            } else cig.addStr(t.cabecalho[i]);
        }
        p.colunasIndicadores = ci.fim(); p.colunasIgnoradas = cig.fim();
        if (cols.isEmpty()) { p.erroFatal = "Nenhuma coluna numérica encontrada além do prefixo."; return p; }

        Map<String, List<FonteDao.Valor>> porChave = new LinkedHashMap<>();
        Set<String> comps = new LinkedHashSet<>();
        for (int i = 0; i < t.linhas.size(); i++) {
            String[] l = t.linhas.get(i);
            int n = t.numeroLinha.get(i);
            String prefixo = Texto.prefixo(Saneador.codigoNumerico(l[col.get("prefixo")], rel, n, "prefixo"));
            if (prefixo.isEmpty()) { rel.rejeitou(n, "prefixo vazio ou inválido", String.join(";", l)); continue; }
            String comp = compPadrao;
            if (col.containsKey("competencia")) {
                String bruto = l[col.get("competencia")];
                comp = Saneador.competencia(bruto);
                if (comp == null) {
                    if (Saneador.celulaVazia(bruto)) { comp = competenciaDoNome(nomeArquivo); if (comp == null) comp = ""; rel.corrigiu(n, "competencia", bruto, comp, "competência vazia → assumida"); }
                    else { rel.rejeitou(n, "competência não reconhecida: \"" + bruto + "\"", String.join(";", l)); continue; }
                } else if (!Texto.normalizar(bruto).equals(comp)) rel.corrigiu(n, "competencia", bruto, comp, "competência normalizada");
            }
            List<FonteDao.Valor> vs = new ArrayList<>();
            for (int ci2 : cols) {
                String bruto = l[ci2];
                if (Saneador.celulaVazia(bruto)) continue;
                Double d = Saneador.numero(bruto, estilos.get(ci2), rel, n, t.cabecalho[ci2]);
                if (d != null) vs.add(new FonteDao.Valor(prefixo, comp, t.cabecalhoNorm[ci2], d));
            }
            if (vs.isEmpty()) { rel.rejeitou(n, "linha sem nenhum valor numérico", String.join(";", l)); continue; }
            String chave = prefixo + "|" + comp;
            if (porChave.containsKey(chave)) p.ignorados++;
            porChave.put(chave, vs);
            comps.add(comp);
        }
        for (List<FonteDao.Valor> vs : porChave.values()) p.valores.addAll(vs);
        Json.Arr cj = Json.arr();
        for (String c : comps) cj.addStr(c);
        p.competenciasJson = cj.fim();
        // novos x existentes (por prefixo+competência)
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM indicador_valor WHERE fonte_id = ? AND prefixo = ? AND competencia = ? LIMIT 1")) {
            for (String chave : porChave.keySet()) {
                String[] pc = chave.split("\\|", -1);
                ps.setLong(1, f.id); ps.setString(2, pc[0]); ps.setString(3, pc[1]);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) p.existentes++; else p.novos++; }
            }
        }
        Set<String> prefs = new LinkedHashSet<>();
        for (FonteDao.Valor v : p.valores) prefs.add(v.prefixo);
        avisos.addAll(prefixosDesconhecidos(prefs));
        return p;
    }

    private static Set<String> prefixosDe(List<FonteDao.LinhaConexao> ls) {
        Set<String> s = new LinkedHashSet<>();
        for (FonteDao.LinhaConexao k : ls) s.add(k.prefixo);
        return s;
    }

    private static List<String> prefixosDesconhecidos(Saneador.Tabela t, ImportService.Resultado r) throws SQLException {
        if (!r.mapeamento.containsKey("prefixo")) return new ArrayList<>();
        int i = t.indice(ImportService.chaveColuna(r.mapeamento.get("prefixo")));
        if (i < 0) return new ArrayList<>();
        Set<String> prefs = new LinkedHashSet<>();
        for (String[] l : t.linhas) { String p = Texto.prefixo(l[i]); if (!p.isEmpty()) prefs.add(p); }
        return prefixosDesconhecidos(prefs);
    }

    /** Prefixos do arquivo que não existem na tabela agencia (aviso, não erro). */
    private static List<String> prefixosDesconhecidos(Set<String> prefs) throws SQLException {
        List<String> faltam = new ArrayList<>();
        if (prefs.isEmpty()) return faltam;
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM agencia WHERE prefixo = ?")) {
            for (String p : prefs) {
                ps.setString(1, p);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) faltam.add(p); }
            }
        }
        List<String> out = new ArrayList<>();
        if (!faltam.isEmpty()) {
            List<String> amostra = faltam.subList(0, Math.min(10, faltam.size()));
            out.add(faltam.size() + " prefixo(s) do arquivo não estão no cadastro de agências (ficam fora do mapa até o import de agências): " +
                String.join(", ", amostra) + (faltam.size() > 10 ? "…" : ""));
        }
        return out;
    }
}
