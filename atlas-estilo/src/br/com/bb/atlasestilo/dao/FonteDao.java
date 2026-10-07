package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;
import br.com.bb.atlasestilo.web.Sessao;

/**
 * Fontes de dados em CSV (pasta do servidor), parâmetros, Conexão,
 * indicadores genéricos e as visões (cards) do dashboard.
 */
public final class FonteDao {

    private FonteDao() { }

    public static final String[] TIPOS = { "agencias", "funcis", "carteiras", "pdg", "metas",
                                           "conexao", "indicadores" };
    public static final String P_PASTA   = "csv.pasta";
    public static final String P_MINUTOS = "csv.monitor.minutos";
    public static final String P_ESTRITO = "csv.estrito";

    public static boolean tipoValido(String t) {
        for (String x : TIPOS) if (x.equals(t)) return true;
        return false;
    }

    // ------------------------------------------------------------- parâmetros

    public static String param(String chave, String padrao) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT valor FROM config_parametro WHERE chave = ?")) {
            ps.setString(1, chave);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) return rs.getString(1);
            }
        }
        return padrao;
    }

    public static void paramDefinir(String chave, String valor, String por, long agora)
            throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO config_parametro (chave,valor,atualizado_por,atualizado_em) " +
                "VALUES (?,?,?,?) ON CONFLICT(chave) DO UPDATE SET valor=excluded.valor, " +
                "atualizado_por=excluded.atualizado_por, atualizado_em=excluded.atualizado_em")) {
            ps.setString(1, chave);
            ps.setString(2, valor);
            ps.setString(3, por);
            ps.setLong(4, agora);
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------------------- fontes

    /** Uma fonte configurada. */
    public static final class Fonte {
        public long id; public String nome; public String tipo; public String arquivo;
        public boolean ativo = true, automatico = true;
        public final Map<String, String> mapeamento = new LinkedHashMap<>();
        public Long ultimaLeituraEm, ultimoMtime; public String ultimoArquivo, ultimoStatus, ultimoResumo;

        public String json() {
            Json.Obj m = Json.obj();
            for (Map.Entry<String, String> e : mapeamento.entrySet()) m.put(e.getKey(), e.getValue());
            return Json.obj().put("id", id).put("nome", nome).put("tipo", tipo).put("arquivo", arquivo)
                .put("ativo", ativo).put("automatico", automatico).putRaw("mapeamento", m.fim())
                .putNum("ultimaLeituraEm", ultimaLeituraEm).put("ultimoArquivo", ultimoArquivo)
                .putNum("ultimoMtime", ultimoMtime).put("ultimoStatus", ultimoStatus)
                .put("ultimoResumo", ultimoResumo).fim();
        }
    }

    private static Fonte lerFonte(ResultSet rs) throws SQLException {
        Fonte f = new Fonte();
        f.id = rs.getLong("id"); f.nome = rs.getString("nome"); f.tipo = rs.getString("tipo");
        f.arquivo = rs.getString("arquivo"); f.ativo = rs.getInt("ativo") == 1;
        f.automatico = rs.getInt("automatico") == 1;
        lerMapeamento(rs.getString("mapeamento"), f.mapeamento);
        long t = rs.getLong("ultima_leitura_em"); f.ultimaLeituraEm = rs.wasNull() || t == 0 ? null : t;
        long mt = rs.getLong("ultimo_mtime"); f.ultimoMtime = rs.wasNull() || mt == 0 ? null : mt;
        f.ultimoArquivo = rs.getString("ultimo_arquivo"); f.ultimoStatus = rs.getString("ultimo_status");
        f.ultimoResumo = rs.getString("ultimo_resumo");
        return f;
    }

    /** Mapeamento guardado como "campo=CABECALHO;campo2=OUTRO" (sem parser de JSON). */
    static void lerMapeamento(String s, Map<String, String> alvo) {
        if (Texto.vazio(s)) return;
        for (String par : s.split(";")) {
            int i = par.indexOf('=');
            if (i > 0) alvo.put(par.substring(0, i).trim(), par.substring(i + 1).trim());
        }
    }

    public static String mapeamentoTexto(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (Texto.vazio(e.getValue())) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().trim()).append('=').append(e.getValue().trim());
        }
        return sb.toString();
    }

    public static List<Fonte> fontes() throws SQLException {
        List<Fonte> out = new ArrayList<>();
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM fonte_csv ORDER BY tipo, nome")) {
            while (rs.next()) out.add(lerFonte(rs));
        }
        return out;
    }

    public static String fontesJson() throws SQLException {
        Json.Arr arr = Json.arr();
        for (Fonte f : fontes()) arr.add(f.json());
        return arr.fim();
    }

    public static Fonte fonte(long id) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM fonte_csv WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? lerFonte(rs) : null; }
        }
    }

    public static long fonteSalvar(Fonte f, String por, long agora) throws SQLException {
        try (Connection c = Db.conexao()) {
            if (f.id > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE fonte_csv SET nome=?, tipo=?, arquivo=?, ativo=?, automatico=?, " +
                        "mapeamento=? WHERE id=?")) {
                    ps.setString(1, f.nome); ps.setString(2, f.tipo); ps.setString(3, f.arquivo);
                    ps.setInt(4, f.ativo ? 1 : 0); ps.setInt(5, f.automatico ? 1 : 0);
                    ps.setString(6, mapeamentoTexto(f.mapeamento)); ps.setLong(7, f.id);
                    ps.executeUpdate();
                }
                return f.id;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fonte_csv (nome,tipo,arquivo,ativo,automatico,mapeamento," +
                    "criado_por,criado_em) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, f.nome); ps.setString(2, f.tipo); ps.setString(3, f.arquivo);
                ps.setInt(4, f.ativo ? 1 : 0); ps.setInt(5, f.automatico ? 1 : 0);
                ps.setString(6, mapeamentoTexto(f.mapeamento)); ps.setString(7, por); ps.setLong(8, agora);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getLong(1); }
            }
        }
    }

    public static boolean fonteExcluir(long id) throws SQLException {
        try (Connection c = Db.conexao()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM indicador_valor WHERE fonte_id = ?")) {
                ps.setLong(1, id); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM visao_dashboard WHERE fonte_id = ?")) {
                ps.setLong(1, id); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fonte_csv WHERE id = ?")) {
                ps.setLong(1, id); return ps.executeUpdate() > 0;
            }
        }
    }

    public static void fonteStatus(long id, String arquivo, Long mtime, String status,
                                   String resumo, String relatorioJson, String rejeitadasCsv,
                                   long agora) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "UPDATE fonte_csv SET ultima_leitura_em=?, ultimo_arquivo=?, ultimo_mtime=?, " +
                "ultimo_status=?, ultimo_resumo=?, ultimo_relatorio=?, ultimo_rejeitadas=? WHERE id=?")) {
            ps.setLong(1, agora);
            ps.setString(2, arquivo);
            if (mtime == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, mtime);
            ps.setString(4, status);
            ps.setString(5, Texto.aparar(resumo, 500));
            ps.setString(6, relatorioJson);
            ps.setString(7, rejeitadasCsv);
            ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    public static String fonteRelatorio(long id) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT ultimo_relatorio FROM fonte_csv WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public static String fonteRejeitadas(long id) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT ultimo_rejeitadas FROM fonte_csv WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    // ---------------------------------------------------------- indicadores

    /** Célula numérica a gravar. */
    public static final class Valor {
        public final String prefixo, competencia, coluna; public final Double valor;
        public Valor(String prefixo, String competencia, String coluna, Double valor) {
            this.prefixo = prefixo; this.competencia = competencia == null ? "" : competencia;
            this.coluna = coluna; this.valor = valor;
        }
    }

    /** Grava (upsert) os valores da fonte; competências antigas ficam como histórico. */
    public static void indicadoresGravar(Connection c, long fonteId, List<Valor> valores)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO indicador_valor (fonte_id,prefixo,competencia,coluna,valor) " +
                "VALUES (?,?,?,?,?) ON CONFLICT(fonte_id,prefixo,competencia,coluna) " +
                "DO UPDATE SET valor=excluded.valor")) {
            for (Valor v : valores) {
                ps.setLong(1, fonteId); ps.setString(2, v.prefixo); ps.setString(3, v.competencia);
                ps.setString(4, v.coluna);
                if (v.valor == null) ps.setNull(5, java.sql.Types.DOUBLE); else ps.setDouble(5, v.valor);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Colunas numéricas disponíveis numa fonte (para o formulário de visões). */
    public static String colunasDaFonte(long fonteId) throws SQLException {
        Fonte f = fonte(fonteId);
        Json.Arr arr = Json.arr();
        if (f == null) return arr.fim();
        if (f.tipo.equals("conexao")) {
            arr.add(Json.obj().put("coluna", "PONTOS").put("competencias", competenciasConexao()).fim());
            return arr.fim();
        }
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT coluna, COUNT(DISTINCT competencia) AS comps, COUNT(*) AS n, " +
                "MAX(competencia) AS ultima FROM indicador_valor WHERE fonte_id = ? " +
                "GROUP BY coluna ORDER BY coluna")) {
            ps.setLong(1, fonteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj().put("coluna", rs.getString(1)).put("competencias", rs.getInt(2))
                        .put("celulas", rs.getInt(3)).put("ultima", rs.getString(4)).fim());
                }
            }
        }
        return arr.fim();
    }

    private static int competenciasConexao() throws SQLException {
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT competencia) FROM conexao")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // --------------------------------------------------------------- conexão

    /** Linha de Conexão a gravar (carteira vazia = agência). */
    public static final class LinhaConexao {
        public String prefixo, competencia, carteira = "", gerenteMatricula, gerenteNome; public double pontos;
    }

    public static void conexaoGravar(Connection c, List<LinhaConexao> linhas, String origem, long agora)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conexao (prefixo,competencia,carteira,gerente_matricula,gerente_nome," +
                "pontos,origem,atualizado_em) VALUES (?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(prefixo,competencia,carteira) DO UPDATE SET " +
                "gerente_matricula=excluded.gerente_matricula, gerente_nome=excluded.gerente_nome, " +
                "pontos=excluded.pontos, origem=excluded.origem, atualizado_em=excluded.atualizado_em")) {
            for (LinhaConexao l : linhas) {
                ps.setString(1, l.prefixo); ps.setString(2, l.competencia);
                ps.setString(3, l.carteira == null ? "" : l.carteira);
                ps.setString(4, Texto.vazio(l.gerenteMatricula) ? null : l.gerenteMatricula);
                ps.setString(5, Texto.vazio(l.gerenteNome) ? null : l.gerenteNome);
                ps.setDouble(6, l.pontos); ps.setString(7, origem); ps.setLong(8, agora);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Média de Conexão das agências da seleção na última competência, com variação. */
    public static String conexaoResumo(Selecao sel) throws SQLException {
        String emAg = "(SELECT a.prefixo FROM agencia a WHERE " + sel.where() + ")";
        String sql = "SELECT competencia, AVG(pontos) AS media, COUNT(*) AS n FROM conexao " +
                     "WHERE carteira = '' AND prefixo IN " + emAg +
                     " GROUP BY competencia ORDER BY competencia DESC LIMIT 2";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            sel.aplicar(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";
                String comp = rs.getString(1); double media = rs.getDouble(2); int n = rs.getInt(3);
                Double anterior = rs.next() ? rs.getDouble(2) : null;
                return Json.obj().put("competencia", comp).put("media", Math.round(media))
                    .put("agencias", n).putNum("anterior", anterior == null ? null : (double) Math.round(anterior))
                    .putNum("delta", anterior == null ? null : (double) Math.round(media - anterior))
                    .put("faixa", faixaConexao(media)).fim();
            }
        }
    }

    public static String faixaConexao(double p) {
        return p >= 900 ? "excelencia" : p >= 750 ? "forte" : p >= 600 ? "atencao" : "critico";
    }

    /** Conexão da agência: atual, histórico (12 meses) e carteiras da última competência. */
    public static String conexaoAgencia(String prefixo, boolean vePessoas) throws SQLException {
        Json.Arr hist = Json.arr();
        String comp = null; Double atual = null, anterior = null;
        try (Connection c = Db.conexao()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT competencia, pontos FROM conexao WHERE prefixo = ? AND carteira = '' " +
                    "ORDER BY competencia DESC LIMIT 12")) {
                ps.setString(1, prefixo);
                List<String[]> linhas = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) linhas.add(new String[] { rs.getString(1), String.valueOf(rs.getDouble(2)) });
                }
                if (linhas.isEmpty()) return "null";
                comp = linhas.get(0)[0]; atual = Double.valueOf(linhas.get(0)[1]);
                if (linhas.size() > 1) anterior = Double.valueOf(linhas.get(1)[1]);
                for (int i = linhas.size() - 1; i >= 0; i--) {
                    hist.add(Json.obj().put("competencia", linhas.get(i)[0])
                        .put("pontos", Math.round(Double.parseDouble(linhas.get(i)[1]))).fim());
                }
            }
            Json.Arr carts = Json.arr();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT k.carteira, k.gerente_matricula, k.gerente_nome, k.pontos, " +
                    "  (SELECT a.pontos FROM conexao a WHERE a.prefixo = k.prefixo AND a.carteira = k.carteira " +
                    "   AND a.competencia < k.competencia ORDER BY a.competencia DESC LIMIT 1) AS anterior, " +
                    "  f.nome AS nome_funci " +
                    "FROM conexao k LEFT JOIN funci f ON f.matricula = k.gerente_matricula " +
                    "WHERE k.prefixo = ? AND k.competencia = ? AND k.carteira <> '' ORDER BY k.pontos DESC")) {
                ps.setString(1, prefixo); ps.setString(2, comp);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double p = rs.getDouble("pontos");
                        double ant = rs.getDouble("anterior"); boolean semAnt = rs.wasNull();
                        String nome = rs.getString("nome_funci") != null ? rs.getString("nome_funci") : rs.getString("gerente_nome");
                        carts.add(Json.obj().put("carteira", rs.getString("carteira"))
                            .put("gerenteMatricula", vePessoas ? rs.getString("gerente_matricula") : null)
                            .put("gerenteNome", vePessoas ? nome : null)
                            .put("pontos", Math.round(p))
                            .putNum("delta", semAnt ? null : (double) Math.round(p - ant))
                            .put("faixa", faixaConexao(p)).fim());
                    }
                }
            }
            return Json.obj().put("competencia", comp).put("pontos", Math.round(atual))
                .putNum("anterior", anterior == null ? null : (double) Math.round(anterior))
                .putNum("delta", anterior == null ? null : (double) Math.round(atual - anterior))
                .put("faixa", faixaConexao(atual)).putRaw("historico", hist.fim())
                .putRaw("carteiras", carts.fim()).fim();
        }
    }

    /** Conexão por agência (última competência) da seleção — para a lista de detalhe. */
    public static String conexaoLista(Selecao sel) throws SQLException {
        String sql = "SELECT k.prefixo, ag.nome, ag.regional, k.competencia, k.pontos FROM conexao k " +
                     "JOIN agencia a ON a.prefixo = k.prefixo JOIN agencia ag ON ag.prefixo = k.prefixo " +
                     "WHERE k.carteira = '' AND k.competencia = (SELECT MAX(competencia) FROM conexao) " +
                     "AND " + sel.where() + " ORDER BY k.pontos DESC LIMIT 500";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            sel.aplicar(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double p = rs.getDouble(5);
                    arr.add(Json.obj().put("prefixo", rs.getString(1)).put("agencia", rs.getString(2))
                        .put("regional", rs.getString(3)).put("competencia", rs.getString(4))
                        .put("pontos", Math.round(p)).put("faixa", faixaConexao(p)).fim());
                }
            }
        }
        return arr.fim();
    }

    // ----------------------------------------------------------------- visões

    /** Configuração de um card. */
    public static final class Visao {
        public long id; public String titulo; public long fonteId; public String coluna;
        public String agregacao = "MEDIA", formato = "INTEIRO", melhor = "MAIOR", perfilMinimo = "COLEGA";
        public int casas, ordem; public Double meta, minimo, maximo; public String colunaMeta;
        public boolean ativo = true;

        public String json() {
            return Json.obj().put("id", id).put("titulo", titulo).put("fonteId", fonteId).put("coluna", coluna)
                .put("agregacao", agregacao).put("formato", formato).put("casas", casas)
                .putNum("meta", meta).put("colunaMeta", colunaMeta).put("melhor", melhor)
                .putNum("minimo", minimo).putNum("maximo", maximo).put("perfilMinimo", perfilMinimo)
                .put("ordem", ordem).put("ativo", ativo).fim();
        }
    }

    private static Visao lerVisao(ResultSet rs) throws SQLException {
        Visao v = new Visao();
        v.id = rs.getLong("id"); v.titulo = rs.getString("titulo"); v.fonteId = rs.getLong("fonte_id");
        v.coluna = rs.getString("coluna"); v.agregacao = rs.getString("agregacao"); v.formato = rs.getString("formato");
        v.casas = rs.getInt("casas"); v.meta = nulavel(rs, "meta"); v.colunaMeta = rs.getString("coluna_meta");
        v.melhor = rs.getString("melhor"); v.minimo = nulavel(rs, "minimo"); v.maximo = nulavel(rs, "maximo");
        v.perfilMinimo = rs.getString("perfil_minimo"); v.ordem = rs.getInt("ordem"); v.ativo = rs.getInt("ativo") == 1;
        return v;
    }

    private static Double nulavel(ResultSet rs, String col) throws SQLException {
        double d = rs.getDouble(col); return rs.wasNull() ? null : d;
    }

    public static List<Visao> visoes(boolean soAtivas) throws SQLException {
        List<Visao> out = new ArrayList<>();
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM visao_dashboard " +
                     (soAtivas ? "WHERE ativo = 1 " : "") + "ORDER BY ordem, titulo")) {
            while (rs.next()) out.add(lerVisao(rs));
        }
        return out;
    }

    public static String visoesJson() throws SQLException {
        Json.Arr arr = Json.arr();
        Map<Long, Fonte> fontes = new LinkedHashMap<>();
        for (Fonte f : fontes()) fontes.put(f.id, f);
        for (Visao v : visoes(false)) {
            Fonte f = fontes.get(v.fonteId);
            String j = v.json();
            arr.add(j.substring(0, j.length() - 1) + ",\"fonteNome\":" + Json.str(f == null ? null : f.nome) + "}");
        }
        return arr.fim();
    }

    public static long visaoSalvar(Visao v, String por, long agora) throws SQLException {
        try (Connection c = Db.conexao()) {
            if (v.id > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE visao_dashboard SET titulo=?, fonte_id=?, coluna=?, agregacao=?, formato=?, " +
                        "casas=?, meta=?, coluna_meta=?, melhor=?, minimo=?, maximo=?, perfil_minimo=?, " +
                        "ordem=?, ativo=? WHERE id=?")) {
                    preencher(ps, v); ps.setLong(15, v.id); ps.executeUpdate();
                }
                return v.id;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO visao_dashboard (titulo,fonte_id,coluna,agregacao,formato,casas,meta," +
                    "coluna_meta,melhor,minimo,maximo,perfil_minimo,ordem,ativo,criado_por,criado_em) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                preencher(ps, v); ps.setString(15, por); ps.setLong(16, agora); ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); return k.getLong(1); }
            }
        }
    }

    private static void preencher(PreparedStatement ps, Visao v) throws SQLException {
        ps.setString(1, v.titulo); ps.setLong(2, v.fonteId); ps.setString(3, v.coluna);
        ps.setString(4, v.agregacao); ps.setString(5, v.formato); ps.setInt(6, v.casas);
        setD(ps, 7, v.meta); ps.setString(8, Texto.vazio(v.colunaMeta) ? null : v.colunaMeta);
        ps.setString(9, v.melhor); setD(ps, 10, v.minimo); setD(ps, 11, v.maximo);
        ps.setString(12, v.perfilMinimo); ps.setInt(13, v.ordem); ps.setInt(14, v.ativo ? 1 : 0);
    }

    private static void setD(PreparedStatement ps, int pos, Double d) throws SQLException {
        if (d == null) ps.setNull(pos, java.sql.Types.DOUBLE); else ps.setDouble(pos, d);
    }

    public static boolean visaoExcluir(long id) throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM visao_dashboard WHERE id = ?")) {
            ps.setLong(1, id); return ps.executeUpdate() > 0;
        }
    }

    private static boolean perfilAlcanca(Sessao s, String minimo) {
        if ("MASTER".equals(minimo)) return s.master();
        if ("MODERADOR".equals(minimo)) return s.veTudo();
        return true;
    }

    private static String agg(String a) {
        if ("SOMA".equals(a)) return "SUM";
        if ("MIN".equals(a)) return "MIN";
        if ("MAX".equals(a)) return "MAX";
        return "AVG";
    }

    /**
     * Fonte de valores da visão como subconsulta (prefixo, competencia, valor):
     * tabela conexao (nota da agência) ou indicador_valor da fonte/coluna.
     */
    private static String subValores(Fonte f, String coluna) {
        if ("conexao".equals(f.tipo)) {
            return "(SELECT prefixo, competencia, pontos AS valor FROM conexao WHERE carteira = '')";
        }
        return "(SELECT prefixo, competencia, valor FROM indicador_valor WHERE fonte_id = ? AND coluna = ?)";
    }

    /**
     * Cards calculados para a seleção (ou para uma agência, quando sel aponta
     * só para ela), respeitando o perfil mínimo de cada visão.
     */
    public static String visoesCalculadas(Sessao s, Selecao sel) throws SQLException {
        Json.Arr arr = Json.arr();
        Map<Long, Fonte> fontes = new LinkedHashMap<>();
        for (Fonte f : fontes()) fontes.put(f.id, f);
        String emAg = "(SELECT a.prefixo FROM agencia a WHERE " + sel.where() + ")";
        try (Connection c = Db.conexao()) {
            for (Visao v : visoes(true)) {
                if (!perfilAlcanca(s, v.perfilMinimo)) continue;
                Fonte f = fontes.get(v.fonteId);
                if (f == null) continue;
                boolean generica = !"conexao".equals(f.tipo);
                String sub = subValores(f, v.coluna);
                // duas últimas competências desta coluna (globais, não da seleção)
                List<String> comps = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT DISTINCT competencia FROM " + sub + " x ORDER BY competencia DESC LIMIT 2")) {
                    int pos = 1;
                    if (generica) { ps.setLong(pos++, f.id); ps.setString(pos++, v.coluna); }
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) comps.add(rs.getString(1)); }
                }
                if (comps.isEmpty()) continue;
                Double valor = null, anterior = null, meta = v.meta; int n = 0;
                for (int i = 0; i < comps.size(); i++) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT " + agg(v.agregacao) + "(x.valor), COUNT(x.valor) FROM " + sub + " x " +
                            "WHERE x.competencia = ? AND x.prefixo IN " + emAg)) {
                        int pos = 1;
                        if (generica) { ps.setLong(pos++, f.id); ps.setString(pos++, v.coluna); }
                        ps.setString(pos++, comps.get(i));
                        sel.aplicar(ps, pos);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                double d = rs.getDouble(1); boolean nulo = rs.wasNull();
                                if (i == 0) { valor = nulo ? null : d; n = rs.getInt(2); }
                                else anterior = nulo ? null : d;
                            }
                        }
                    }
                }
                if (generica && meta == null && !Texto.vazio(v.colunaMeta)) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT " + agg(v.agregacao) + "(x.valor) FROM " + subValores(f, v.colunaMeta) + " x " +
                            "WHERE x.competencia = ? AND x.prefixo IN " + emAg)) {
                        int pos = 1;
                        ps.setLong(pos++, f.id); ps.setString(pos++, v.colunaMeta); ps.setString(pos++, comps.get(0));
                        sel.aplicar(ps, pos);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) { double d = rs.getDouble(1); meta = rs.wasNull() ? null : d; }
                        }
                    }
                }
                Double pct = (valor != null && meta != null && meta != 0) ? valor / meta * 100 : null;
                String status = null;
                if (valor != null && meta != null && meta != 0) {
                    double razao = "MENOR".equals(v.melhor) ? meta / Math.max(1e-9, valor) : valor / meta;
                    status = razao >= 1 ? "ok" : razao >= .9 ? "atencao" : "critico";
                }
                Double delta = (valor != null && anterior != null) ? valor - anterior : null;
                arr.add(Json.obj().put("id", v.id).put("titulo", v.titulo).put("formato", v.formato)
                    .put("casas", v.casas).put("agregacao", v.agregacao).put("melhor", v.melhor)
                    .putNum("valor", valor).putNum("meta", meta).putNum("pct", pct)
                    .put("competencia", comps.get(0)).put("competenciaAnterior", comps.size() > 1 ? comps.get(1) : null)
                    .putNum("anterior", anterior).putNum("delta", delta).put("status", status)
                    .put("agenciasComDado", n).put("fonte", f.nome).fim());
            }
        }
        return arr.fim();
    }

    /** Alertas de faixa plausível (mín/máx) da última competência — para o relatório. */
    public static List<String> foraDaFaixa(long fonteId) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Visao v : visoes(true)) {
            if (v.fonteId != fonteId || (v.minimo == null && v.maximo == null)) continue;
            try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                    "SELECT prefixo, competencia, valor FROM indicador_valor WHERE fonte_id = ? AND coluna = ? " +
                    "AND competencia = (SELECT MAX(competencia) FROM indicador_valor WHERE fonte_id = ? AND coluna = ?) " +
                    "AND ((? IS NOT NULL AND valor < ?) OR (? IS NOT NULL AND valor > ?)) LIMIT 50")) {
                ps.setLong(1, fonteId); ps.setString(2, v.coluna); ps.setLong(3, fonteId); ps.setString(4, v.coluna);
                setD(ps, 5, v.minimo); setD(ps, 6, v.minimo); setD(ps, 7, v.maximo); setD(ps, 8, v.maximo);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(v.titulo + ": prefixo " + rs.getString(1) + " em " + rs.getString(2) + " = " +
                            Json.num(rs.getDouble(3)) + " (faixa " + (v.minimo == null ? "−∞" : Json.num(v.minimo)) +
                            " a " + (v.maximo == null ? "+∞" : Json.num(v.maximo)) + ")");
                    }
                }
            }
        }
        return out;
    }
}
