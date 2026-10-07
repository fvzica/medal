package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;

/**
 * Gestão do atendimento — ferramentas do Master, e SÓ dele: visitas com
 * checklist (notas por critério, movimento, claros, nota geral, melhorias,
 * percepção, fotos), anotações, ações com dono/prazo/prioridade e linha do
 * tempo de retorno, e o painel de planejamento. Nada daqui vaza para
 * Colega ou Moderador: a API exige master() antes de chamar.
 */
public final class GestaoDao {

    private GestaoDao() { }

    public static final long DIA = 86400000L;

    private static final java.util.TimeZone FUSO = java.util.TimeZone.getTimeZone("America/Sao_Paulo");

    /** Meia-noite (fuso de Brasília) do dia em que `t` cai: prazos contam por dia-calendário. */
    public static long inicioDia(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance(FUSO);
        c.setTimeInMillis(t);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Dias-calendário de `de` até `ate` (0 = mesmo dia; negativo = já passou). */
    public static int diasEntre(long de, long ate) {
        return (int) Math.round((inicioDia(ate) - inicioDia(de)) / (double) DIA);
    }

    // ---------------------------------------------------------------- visitas

    /** Checklist da visita; campos nulos não sobrescrevem (COALESCE). */
    public static final class Checklist {
        public Integer ambiencia, atendimento, organizacao, equipe, claros;
        public String movimento, melhorias, percepcao;
        public Double notaGeral;
        public boolean vazio() {
            return ambiencia == null && atendimento == null && organizacao == null && equipe == null
                && claros == null && movimento == null && melhorias == null && percepcao == null
                && notaGeral == null;
        }
    }

    public static String visitas(String prefixo) throws SQLException {
        String sql = "SELECT * FROM visita WHERE prefixo = ? " +
                     "ORDER BY COALESCE(data_realizada, data_planejada, criado_em) DESC";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(visitaJson(rs, true));
            }
        }
        return arr.fim();
    }

    private static String visitaJson(ResultSet rs, boolean comFotos) throws SQLException {
        long id = rs.getLong("id");
        String melhorias = rs.getString("melhorias");
        Json.Arr mel = Json.arr();
        if (!Texto.vazio(melhorias)) for (String m : melhorias.split("\\|")) if (!m.trim().isEmpty()) mel.addStr(m.trim());
        Json.Obj o = Json.obj()
            .put("id", id)
            .put("prefixo", rs.getString("prefixo"))
            .put("status", rs.getString("status"))
            .putNum("dataPlanejada", epoch(rs, "data_planejada"))
            .putNum("dataRealizada", epoch(rs, "data_realizada"))
            .put("resumo", rs.getString("resumo"))
            .putNum("ambiencia", intNulo(rs, "ambiencia"))
            .putNum("atendimento", intNulo(rs, "atendimento"))
            .putNum("organizacao", intNulo(rs, "organizacao"))
            .putNum("equipe", intNulo(rs, "equipe"))
            .put("movimento", rs.getString("movimento"))
            .putNum("claros", intNulo(rs, "claros"))
            .putNum("notaGeral", dblNulo(rs, "nota_geral"))
            .putRaw("melhorias", mel.fim())
            .put("percepcao", rs.getString("percepcao"))
            .put("criadoPor", rs.getString("criado_por"))
            .putNum("criadoEm", epoch(rs, "criado_em"))
            .putNum("atualizadoEm", epoch(rs, "atualizado_em"));
        if (comFotos) o.putRaw("fotos", FotoDao.daVisita(id));
        return o.fim();
    }

    public static long visitaCriar(String prefixo, String status, Long dataPlanejada,
                                   Long dataRealizada, String resumo, String por,
                                   long agora) throws SQLException {
        return visitaCriar(prefixo, status, dataPlanejada, dataRealizada, resumo, por, agora, null);
    }

    public static long visitaCriar(String prefixo, String status, Long dataPlanejada,
                                   Long dataRealizada, String resumo, String por,
                                   long agora, Checklist ck) throws SQLException {
        String sql = "INSERT INTO visita (prefixo,status,data_planejada,data_realizada," +
                     "resumo,criado_por,criado_em,atualizado_em,ambiencia,atendimento,organizacao," +
                     "equipe,movimento,claros,nota_geral,melhorias,percepcao,checklist_em) " +
                     "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, prefixo);
            ps.setString(2, status);
            setLong(ps, 3, dataPlanejada);
            setLong(ps, 4, dataRealizada);
            ps.setString(5, Texto.aparar(resumo, 4000));
            ps.setString(6, por);
            ps.setLong(7, agora);
            ps.setLong(8, agora);
            Checklist k = ck == null ? new Checklist() : ck;
            setInt(ps, 9, k.ambiencia); setInt(ps, 10, k.atendimento);
            setInt(ps, 11, k.organizacao); setInt(ps, 12, k.equipe);
            ps.setString(13, k.movimento); setInt(ps, 14, k.claros);
            setDouble(ps, 15, k.notaGeral);
            ps.setString(16, Texto.vazio(k.melhorias) ? null : Texto.aparar(k.melhorias, 2000));
            ps.setString(17, Texto.vazio(k.percepcao) ? null : Texto.aparar(k.percepcao, 8000));
            setLong(ps, 18, k.vazio() ? null : agora);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static boolean visitaAtualizar(long id, String status, Long dataPlanejada,
                                          Long dataRealizada, String resumo,
                                          long agora) throws SQLException {
        return visitaAtualizar(id, status, dataPlanejada, dataRealizada, resumo, agora, null);
    }

    /** Campos nulos são preservados (não sobrescreve o que a tela não enviou). */
    public static boolean visitaAtualizar(long id, String status, Long dataPlanejada,
                                          Long dataRealizada, String resumo,
                                          long agora, Checklist ck) throws SQLException {
        Checklist k = ck == null ? new Checklist() : ck;
        String sql = "UPDATE visita SET status = COALESCE(?, status), " +
                     "data_planejada = COALESCE(?, data_planejada), " +
                     "data_realizada = COALESCE(?, data_realizada), " +
                     "resumo = COALESCE(?, resumo), " +
                     "ambiencia = COALESCE(?, ambiencia), atendimento = COALESCE(?, atendimento), " +
                     "organizacao = COALESCE(?, organizacao), equipe = COALESCE(?, equipe), " +
                     "movimento = COALESCE(?, movimento), claros = COALESCE(?, claros), " +
                     "nota_geral = COALESCE(?, nota_geral), melhorias = COALESCE(?, melhorias), " +
                     "percepcao = COALESCE(?, percepcao), " +
                     "checklist_em = CASE WHEN ? THEN ? ELSE checklist_em END, " +
                     "atualizado_em = ? WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Texto.vazio(status) ? null : status);
            setLong(ps, 2, dataPlanejada);
            setLong(ps, 3, dataRealizada);
            // null mantém; "" limpa (a tela manda "" de propósito ao apagar o resumo)
            ps.setString(4, resumo == null ? null : Texto.aparar(resumo, 4000));
            setInt(ps, 5, k.ambiencia); setInt(ps, 6, k.atendimento);
            setInt(ps, 7, k.organizacao); setInt(ps, 8, k.equipe);
            ps.setString(9, k.movimento); setInt(ps, 10, k.claros);
            setDouble(ps, 11, k.notaGeral);
            ps.setString(12, k.melhorias == null ? null : Texto.aparar(k.melhorias, 2000));
            ps.setString(13, k.percepcao == null ? null : Texto.aparar(k.percepcao, 8000));
            ps.setBoolean(14, !k.vazio());
            ps.setLong(15, agora);
            ps.setLong(16, agora);
            ps.setLong(17, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Prefixo da visita (para validar uploads de foto). */
    public static String visitaPrefixo(long id) throws SQLException {
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement("SELECT prefixo FROM visita WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public static boolean visitaExcluir(long id) throws SQLException {
        try (Connection c = Db.conexao()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ponto_melhoria SET visita_id = NULL WHERE visita_id = ?")) {
                    ps.setLong(1, id); ps.executeUpdate();
                }
                // a conferência feita nessa visita continua válida, mas não pode apontar para visita inexistente
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ponto_melhoria SET verificado_visita_id = NULL WHERE verificado_visita_id = ?")) {
                    ps.setLong(1, id); ps.executeUpdate();
                }
                boolean ok;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM visita WHERE id = ?")) {
                    ps.setLong(1, id);
                    ok = ps.executeUpdate() > 0;
                }
                c.commit();
                return ok;
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    // -------------------------------------------------------------- anotações

    /** prefixo null -> anotações gerais (sem agência). */
    public static String anotacoes(String prefixo) throws SQLException {
        String sql = prefixo == null
            ? "SELECT * FROM anotacao WHERE excluida = 0 AND prefixo IS NULL " +
              "ORDER BY fixada DESC, COALESCE(atualizado_em, criado_em) DESC"
            : "SELECT * FROM anotacao WHERE excluida = 0 AND prefixo = ? " +
              "ORDER BY fixada DESC, COALESCE(atualizado_em, criado_em) DESC";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (prefixo != null) ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("id", rs.getLong("id"))
                        .put("prefixo", rs.getString("prefixo"))
                        .put("texto", rs.getString("texto"))
                        .put("fixada", rs.getInt("fixada") == 1)
                        .put("criadoPor", rs.getString("criado_por"))
                        .putNum("criadoEm", epoch(rs, "criado_em"))
                        .putNum("atualizadoEm", epoch(rs, "atualizado_em"))
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    public static long anotacaoCriar(String prefixo, String texto, String por, long agora)
            throws SQLException {
        String sql = "INSERT INTO anotacao (prefixo,texto,criado_por,criado_em,atualizado_em) " +
                     "VALUES (?,?,?,?,?)";
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, Texto.vazio(prefixo) ? null : prefixo);
            ps.setString(2, Texto.aparar(texto, 8000));
            ps.setString(3, por);
            ps.setLong(4, agora);
            ps.setLong(5, agora);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static boolean anotacaoAtualizar(long id, String texto, Boolean fixada, long agora)
            throws SQLException {
        String sql = "UPDATE anotacao SET " +
                     (texto != null ? "texto = ?, " : "") +
                     (fixada != null ? "fixada = ?, " : "") +
                     "atualizado_em = ? WHERE id = ? AND excluida = 0";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            int pos = 1;
            if (texto != null) ps.setString(pos++, Texto.aparar(texto, 8000));
            if (fixada != null) ps.setInt(pos++, fixada ? 1 : 0);
            ps.setLong(pos++, agora);
            ps.setLong(pos, id);
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean anotacaoExcluir(long id, long agora) throws SQLException {
        String sql = "UPDATE anotacao SET excluida = 1, atualizado_em = ? WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, agora);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    // ----------------------------------------------------------------- ações
    // (tabela ponto_melhoria: cada ação tem dono, prazo, prioridade, visita de
    //  origem e uma linha do tempo de retorno em acao_atualizacao)

    /** Status intermediário: o responsável avisou que fez; o Master confere na próxima visita. */
    public static final String ST_AGUARDANDO = "AGUARDANDO_VERIFICACAO";

    private static final String SQL_ACOES_BASE =
        "SELECT p.*, ag.nome AS agencia, ag.uf, ag.municipio, ag.regional, " +
        "  (SELECT COUNT(*) FROM acao_atualizacao u WHERE u.ponto_id = p.id) AS atualizacoes, " +
        "  (SELECT MAX(u.criado_em) FROM acao_atualizacao u WHERE u.ponto_id = p.id) AS ultima_atualizacao, " +
        "  (SELECT MAX(u.criado_em) FROM acao_atualizacao u WHERE u.ponto_id = p.id " +
        "     AND u.tipo IN ('RETORNO','VERIFICACAO')) AS ultimo_retorno, " +
        "  (SELECT MAX(u.criado_em) FROM acao_atualizacao u WHERE u.ponto_id = p.id AND u.tipo = 'COBRANCA') AS ultima_cobranca, " +
        "  (SELECT COUNT(*) FROM acao_atualizacao u WHERE u.ponto_id = p.id AND u.tipo = 'COBRANCA') AS cobrancas, " +
        "  (SELECT COUNT(*) FROM foto f WHERE f.ponto_id = p.id AND f.momento = 'ANTES') AS fotos_antes, " +
        "  (SELECT COUNT(*) FROM foto f WHERE f.ponto_id = p.id AND f.momento = 'DEPOIS') AS fotos_depois " +
        "FROM ponto_melhoria p JOIN agencia ag ON ag.prefixo = p.prefixo WHERE 1=1";

    public static String pontos(String prefixo, String status) throws SQLException {
        return acoes(prefixo, status, null, null, null, null, 0);
    }

    // ------------------------------------------------------------ cadência

    /**
     * Ritmo de cobrança por prioridade (dias) e limite de "parada" (dias sem
     * retorno). Guardado em config_parametro; o Master ajusta no Admin.
     */
    public static final class Cadencia {
        public int alta = 7, media = 15, baixa = 30, parada = 14;
        public int dias(String prioridade) {
            return "ALTA".equals(prioridade) ? alta : "BAIXA".equals(prioridade) ? baixa : media;
        }
        public String json() {
            return Json.obj().put("ALTA", alta).put("MEDIA", media).put("BAIXA", baixa).put("parada", parada).fim();
        }
    }

    public static Cadencia cadencia() throws SQLException {
        Cadencia c = new Cadencia();
        c.alta = intParam("cadencia.ALTA", c.alta);
        c.media = intParam("cadencia.MEDIA", c.media);
        c.baixa = intParam("cadencia.BAIXA", c.baixa);
        c.parada = intParam("cadencia.parada", c.parada);
        return c;
    }

    public static void cadenciaDefinir(int alta, int media, int baixa, int parada, String por, long agora)
            throws SQLException {
        FonteDao.paramDefinir("cadencia.ALTA", String.valueOf(limitar(alta)), por, agora);
        FonteDao.paramDefinir("cadencia.MEDIA", String.valueOf(limitar(media)), por, agora);
        FonteDao.paramDefinir("cadencia.BAIXA", String.valueOf(limitar(baixa)), por, agora);
        FonteDao.paramDefinir("cadencia.parada", String.valueOf(limitar(parada)), por, agora);
    }

    private static int limitar(int dias) { return Math.max(1, Math.min(365, dias)); }

    private static int intParam(String chave, int padrao) throws SQLException {
        try { return limitar(Integer.parseInt(FonteDao.param(chave, String.valueOf(padrao)).trim())); }
        catch (NumberFormatException e) { return padrao; }
    }

    /** Uma ação lida do banco com os cálculos de prazo/cobrança já feitos. */
    private static final class AcaoLida {
        long id; String prefixo, status, prioridade;
        boolean aberta, aguardando, vencida, parada, cobrarHoje;
        Long previsao, proximaCobranca; int fotosDepois;
        Json.Obj obj;
        String json() { return obj.fim(); }
    }

    private static List<AcaoLida> lerAcoes(String sql, List<Object> params, long agora, Cadencia cad,
                                           boolean comFotos) throws SQLException {
        List<AcaoLida> lista = new ArrayList<>();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object v = params.get(i);
                if (v instanceof Long) ps.setLong(i + 1, (Long) v); else ps.setString(i + 1, (String) v);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(lerAcao(rs, agora, cad));
            }
        }
        if (comFotos && !lista.isEmpty()) {
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (AcaoLida a : lista) ids.add(a.id);
            java.util.Map<Long, List<String[]>> fotos = FotoDao.dasAcoes(ids);
            for (AcaoLida a : lista) {
                Json.Arr arr = Json.arr();
                List<String[]> fs = fotos.get(a.id);
                if (fs != null) for (String[] f : fs) {
                    arr.add(Json.obj().put("id", f[0]).put("momento", f[1]).put("legenda", f[2]).fim());
                }
                a.obj.putRaw("fotos", arr.fim());
            }
        }
        return lista;
    }

    /** Compatibilidade (sem filtro de prova). */
    public static String acoes(String prefixo, String status, String prazo, String regional,
                               String prioridade, long agora) throws SQLException {
        return acoes(prefixo, status, prazo, regional, prioridade, null, agora);
    }

    /** Verdadeiro quando a visita planejada para `dataPlanejada` já passou (dia-calendário). */
    public static boolean visitaAtrasada(Long dataPlanejada, long agora) {
        return dataPlanejada != null && dataPlanejada < inicioDia(agora);
    }

    /**
     * Ações com filtros: status (ABERTO/EM_TRATATIVA/AGUARDANDO_VERIFICACAO/
     * RESOLVIDO/PENDENTES), prazo (VENCIDAS/7DIAS/30DIAS/SEM/COBRAR/PARADAS),
     * regional, prioridade e prova (SEM = concluídas sem foto do depois).
     * COBRAR e PARADAS dependem da cadência, então são filtrados em Java.
     */
    public static String acoes(String prefixo, String status, String prazo, String regional,
                               String prioridade, String prova, long agora) throws SQLException {
        StringBuilder sql = new StringBuilder(SQL_ACOES_BASE);
        List<Object> params = new ArrayList<>();
        if (!Texto.vazio(prefixo)) { sql.append(" AND p.prefixo = ?"); params.add(prefixo); }
        if ("PENDENTES".equals(status)) sql.append(" AND p.status <> 'RESOLVIDO'");
        else if (!Texto.vazio(status)) { sql.append(" AND p.status = ?"); params.add(status); }
        if (!Texto.vazio(regional)) { sql.append(" AND ag.regional = ?"); params.add(regional); }
        if (!Texto.vazio(prioridade)) { sql.append(" AND p.prioridade = ?"); params.add(prioridade); }
        boolean emJava = "COBRAR".equals(prazo) || "PARADAS".equals(prazo);
        long hoje = inicioDia(agora); // prazos por dia-calendário: vence só no dia seguinte ao prazo
        if ("VENCIDAS".equals(prazo)) {
            sql.append(" AND p.status NOT IN ('RESOLVIDO','" + ST_AGUARDANDO + "') AND p.previsao IS NOT NULL AND p.previsao < ?");
            params.add(hoje);
        } else if ("7DIAS".equals(prazo) || "30DIAS".equals(prazo)) {
            long ate = hoje + (("7DIAS".equals(prazo) ? 7 : 30) + 1) * DIA;
            sql.append(" AND p.status NOT IN ('RESOLVIDO','" + ST_AGUARDANDO + "') AND p.previsao IS NOT NULL AND p.previsao >= ? AND p.previsao < ?");
            params.add(hoje); params.add(ate);
        } else if ("SEM".equals(prazo)) {
            sql.append(" AND p.status <> 'RESOLVIDO' AND p.previsao IS NULL");
        } else if (emJava) {
            sql.append(" AND p.status NOT IN ('RESOLVIDO','" + ST_AGUARDANDO + "')");
        }
        if ("SEM".equals(prova)) {
            // "sem prova" = concluída sem conferência in loco E sem foto do depois (mesma regra do KPI)
            sql.append(" AND p.status = 'RESOLVIDO' AND p.verificado_em IS NULL" +
                       " AND NOT EXISTS (SELECT 1 FROM foto f WHERE f.ponto_id = p.id AND f.momento = 'DEPOIS')");
        }
        sql.append(" ORDER BY CASE p.status WHEN 'RESOLVIDO' THEN 2 WHEN '" + ST_AGUARDANDO + "' THEN 1 ELSE 0 END, " +
                   "CASE p.prioridade WHEN 'ALTA' THEN 0 WHEN 'MEDIA' THEN 1 ELSE 2 END, " +
                   "COALESCE(p.previsao, 9e15), p.criado_em DESC LIMIT 1000");
        List<AcaoLida> lista = lerAcoes(sql.toString(), params, agora, cadencia(), true);
        if ("COBRAR".equals(prazo)) {
            List<AcaoLida> f = new ArrayList<>();
            for (AcaoLida a : lista) if (a.cobrarHoje) f.add(a);
            ordenarParaCobranca(f);
            lista = f;
        } else if ("PARADAS".equals(prazo)) {
            List<AcaoLida> f = new ArrayList<>();
            for (AcaoLida a : lista) if (a.parada) f.add(a);
            lista = f;
        }
        Json.Arr arr = Json.arr();
        for (AcaoLida a : lista) arr.add(a.json());
        return arr.fim();
    }

    /** Vencidas primeiro, depois paradas, depois por prioridade e prazo (ordem estável). */
    private static void ordenarParaCobranca(List<AcaoLida> lista) {
        java.util.Collections.sort(lista, new java.util.Comparator<AcaoLida>() {
            @Override public int compare(AcaoLida a, AcaoLida b) {
                if (a.vencida != b.vencida) return a.vencida ? -1 : 1;
                if (a.parada != b.parada) return a.parada ? -1 : 1;
                int pa = peso(a.prioridade), pb = peso(b.prioridade);
                if (pa != pb) return pa - pb;
                long da = a.previsao == null ? Long.MAX_VALUE : a.previsao, db = b.previsao == null ? Long.MAX_VALUE : b.previsao;
                return Long.compare(da, db);
            }
        });
    }

    private static int peso(String prioridade) {
        return "ALTA".equals(prioridade) ? 0 : "BAIXA".equals(prioridade) ? 2 : 1;
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    /**
     * Lê uma linha de SQL_ACOES_BASE e calcula prazo, cobrança e parada:
     *  - vencida: aberta, não aguardando conferência, com previsão passada;
     *  - semRetornoDias: dias desde o último retorno/conferência (ou criação);
     *  - parada: sem retorno há mais que o limite da cadência;
     *  - cobrarHoje: a próxima cobrança já chegou. Depois de uma cobrança o
     *    Master marca quando quer cobrar de novo (proxima_cobranca_em); um
     *    retorno posterior reinicia o relógio pela cadência da prioridade.
     */
    private static AcaoLida lerAcao(ResultSet rs, long agora, Cadencia cad) throws SQLException {
        AcaoLida a = new AcaoLida();
        a.id = rs.getLong("id");
        a.prefixo = rs.getString("prefixo");
        a.status = rs.getString("status");
        a.prioridade = rs.getString("prioridade") == null ? "MEDIA" : rs.getString("prioridade");
        a.previsao = epoch(rs, "previsao");
        a.aberta = !"RESOLVIDO".equals(a.status);
        a.aguardando = ST_AGUARDANDO.equals(a.status);
        // vencida só a partir do dia seguinte ao prazo (prazo gravado ao meio-dia local)
        a.vencida = a.aberta && !a.aguardando && a.previsao != null && agora > 0 && a.previsao < inicioDia(agora);
        a.fotosDepois = rs.getInt("fotos_depois");
        long criadoEm = nz(epoch(rs, "criado_em"));
        Long ultimoRetorno = epoch(rs, "ultimo_retorno"), ultimaCobranca = epoch(rs, "ultima_cobranca");
        Long proximaCobrancaEm = epoch(rs, "proxima_cobranca_em");
        long vid = rs.getLong("visita_id"); boolean semVisita = rs.wasNull();
        long vvid = rs.getLong("verificado_visita_id"); boolean semVerifVisita = rs.wasNull();

        Integer semRetornoDias = null, cobradaHaDias = null;
        Long proximaCobranca = null;
        if (a.aberta && !a.aguardando && agora > 0) {
            long baseRetorno = Math.max(criadoEm, nz(ultimoRetorno));
            semRetornoDias = (int) Math.floor((agora - baseRetorno) / (double) DIA);
            a.parada = semRetornoDias >= cad.parada;
            if (ultimaCobranca != null) cobradaHaDias = (int) Math.floor((agora - ultimaCobranca) / (double) DIA);
            boolean explicita = proximaCobrancaEm != null && ultimaCobranca != null
                && (ultimoRetorno == null || ultimoRetorno <= ultimaCobranca);
            long base = Math.max(baseRetorno, nz(ultimaCobranca));
            proximaCobranca = explicita ? proximaCobrancaEm : base + cad.dias(a.prioridade) * DIA;
            a.cobrarHoje = explicita ? proximaCobranca <= agora : (a.vencida || a.parada || proximaCobranca <= agora);
        }
        a.proximaCobranca = proximaCobranca;

        a.obj = Json.obj()
            .put("id", a.id)
            .put("prefixo", a.prefixo)
            .put("agencia", rs.getString("agencia"))
            .put("uf", rs.getString("uf"))
            .put("municipio", rs.getString("municipio"))
            .put("regional", rs.getString("regional"))
            .put("descricao", rs.getString("descricao"))
            .put("status", a.status)
            .put("solucao", rs.getString("solucao"))
            .putNum("previsao", a.previsao)
            .put("vencida", a.vencida)
            .put("aguardando", a.aguardando)
            .putNum("diasParaPrazo", a.previsao == null || agora <= 0 ? null
                    : (double) diasEntre(agora, a.previsao))
            .putNum("resolvidoEm", epoch(rs, "resolvido_em"))
            .putNum("visitaId", semVisita ? null : vid)
            .put("responsavel", rs.getString("responsavel"))
            .put("prioridade", a.prioridade)
            .put("tipo", rs.getString("tipo") == null ? "ACAO" : rs.getString("tipo"))
            .put("atualizacoes", rs.getInt("atualizacoes"))
            .putNum("ultimaAtualizacao", epoch(rs, "ultima_atualizacao"))
            .putNum("ultimoRetorno", ultimoRetorno)
            .putNum("ultimaCobranca", ultimaCobranca)
            .put("cobrancas", rs.getInt("cobrancas"))
            .putNum("semRetornoDias", semRetornoDias)
            .putNum("cobradaHaDias", cobradaHaDias)
            .putNum("proximaCobranca", proximaCobranca)
            .put("cobrarHoje", a.cobrarHoje)
            .put("parada", a.parada)
            .put("cadenciaDias", cad.dias(a.prioridade))
            .putNum("informadoEm", epoch(rs, "informado_em"))
            .putNum("verificadoEm", epoch(rs, "verificado_em"))
            .putNum("verificadoVisitaId", semVerifVisita ? null : vvid)
            .put("reaberturas", rs.getInt("reaberturas"))
            .put("fotosAntes", rs.getInt("fotos_antes"))
            .put("fotosDepois", a.fotosDepois)
            .put("comprovada", !a.aberta && (epoch(rs, "verificado_em") != null || a.fotosDepois > 0))
            .put("criadoPor", rs.getString("criado_por"))
            .putNum("criadoEm", epoch(rs, "criado_em"));
        return a;
    }

    public static String pontoPrefixo(long id) throws SQLException {
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement("SELECT prefixo FROM ponto_melhoria WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    /** Descrição curta da ação (legenda das fotos antes/depois). */
    public static String pontoDescricao(long id) throws SQLException {
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement("SELECT descricao FROM ponto_melhoria WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public static long pontoCriar(String prefixo, String descricao, Long previsao,
                                  String por, long agora) throws SQLException {
        return pontoCriar(prefixo, descricao, previsao, por, agora, null, null, "MEDIA");
    }

    public static long pontoCriar(String prefixo, String descricao, Long previsao, String por,
                                  long agora, Long visitaId, String responsavel, String prioridade)
            throws SQLException {
        String sql = "INSERT INTO ponto_melhoria (prefixo,descricao,previsao,criado_por,criado_em," +
                     "atualizado_em,visita_id,responsavel,prioridade,tipo) VALUES (?,?,?,?,?,?,?,?,?,'ACAO')";
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, prefixo);
            ps.setString(2, Texto.aparar(descricao, 4000));
            setLong(ps, 3, previsao);
            ps.setString(4, por);
            ps.setLong(5, agora);
            ps.setLong(6, agora);
            setLong(ps, 7, visitaId);
            ps.setString(8, Texto.vazio(responsavel) ? null : Texto.aparar(responsavel, 200));
            ps.setString(9, prioridadeValida(prioridade));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static String prioridadeValida(String p) {
        return "ALTA".equals(p) || "BAIXA".equals(p) ? p : "MEDIA";
    }

    public static boolean pontoAtualizar(long id, String status, String solucao,
                                         Long previsao, long agora) throws SQLException {
        return pontoAtualizar(id, status, solucao, previsao, agora, null, null, null);
    }

    public static boolean pontoAtualizar(long id, String status, String solucao, Long previsao,
                                         long agora, String responsavel, String prioridade,
                                         String descricao) throws SQLException {
        boolean resolver = "RESOLVIDO".equals(status);
        boolean reabrir = "ABERTO".equals(status) || "EM_TRATATIVA".equals(status);
        String sql = "UPDATE ponto_melhoria SET status = COALESCE(?, status), " +
                     "solucao = COALESCE(?, solucao), previsao = COALESCE(?, previsao), " +
                     "responsavel = COALESCE(?, responsavel), prioridade = COALESCE(?, prioridade), " +
                     "descricao = COALESCE(?, descricao), " +
                     "resolvido_em = CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE resolvido_em END, " +
                     "informado_em = CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE informado_em END, " +
                     "verificado_em = CASE WHEN ? THEN NULL ELSE verificado_em END, " +
                     "verificado_visita_id = CASE WHEN ? THEN NULL ELSE verificado_visita_id END, " +
                     "atualizado_em = ? WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Texto.vazio(status) ? null : status);
            ps.setString(2, solucao == null ? null : Texto.aparar(solucao, 4000));
            setLong(ps, 3, previsao);
            ps.setString(4, responsavel == null ? null : Texto.aparar(responsavel, 200));
            ps.setString(5, Texto.vazio(prioridade) ? null : prioridadeValida(prioridade));
            ps.setString(6, Texto.vazio(descricao) ? null : Texto.aparar(descricao, 4000));
            ps.setBoolean(7, resolver);
            ps.setLong(8, agora);
            ps.setBoolean(9, reabrir);
            ps.setBoolean(10, ST_AGUARDANDO.equals(status));
            ps.setLong(11, agora);
            ps.setBoolean(12, reabrir);
            ps.setBoolean(13, reabrir);
            ps.setBoolean(14, reabrir);
            ps.setLong(15, agora);
            ps.setLong(16, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Registra um retorno na linha do tempo; statusNovo opcional muda o status. */
    public static long pontoComentar(long id, String texto, String statusNovo, String por, long agora)
            throws SQLException {
        return pontoComentar(id, texto, statusNovo, por, agora, "RETORNO", null);
    }

    public static String tipoAtualizacaoValido(String tipo) {
        return "COBRANCA".equals(tipo) || "STATUS".equals(tipo) || "VERIFICACAO".equals(tipo) ? tipo : "RETORNO";
    }

    /**
     * Linha do tempo com tipo. COBRANCA marca a próxima cobrança (adiarDias à
     * frente; sem valor, usa a cadência da prioridade) e não zera o "sem
     * retorno". Status AGUARDANDO_VERIFICACAO registra informado_em.
     */
    public static long pontoComentar(long id, String texto, String statusNovo, String por, long agora,
                                     String tipo, Integer adiarDias) throws SQLException {
        tipo = tipoAtualizacaoValido(tipo);
        try (Connection c = Db.conexao()) {
            c.setAutoCommit(false);
            try {
                long novoId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO acao_atualizacao (ponto_id,texto,status_novo,criado_por,criado_em,tipo) " +
                        "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, id);
                    ps.setString(2, Texto.vazio(texto) ? null : Texto.aparar(texto, 4000));
                    ps.setString(3, Texto.vazio(statusNovo) ? null : statusNovo);
                    ps.setString(4, por);
                    ps.setLong(5, agora);
                    ps.setString(6, tipo);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); novoId = rs.getLong(1); }
                }
                if (!Texto.vazio(statusNovo)) {
                    // reabrir (ABERTO / EM_TRATATIVA) apaga a prova antiga: a ação volta a dever conclusão e prova novas
                    boolean reabrir = "ABERTO".equals(statusNovo) || "EM_TRATATIVA".equals(statusNovo);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE ponto_melhoria SET status = ?, " +
                            "resolvido_em = CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE resolvido_em END, " +
                            "informado_em = CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE informado_em END, " +
                            "verificado_em = CASE WHEN ? THEN NULL ELSE verificado_em END, " +
                            "verificado_visita_id = CASE WHEN ? THEN NULL ELSE verificado_visita_id END, " +
                            "atualizado_em = ? WHERE id = ?")) {
                        ps.setString(1, statusNovo);
                        ps.setBoolean(2, "RESOLVIDO".equals(statusNovo));
                        ps.setLong(3, agora);
                        ps.setBoolean(4, reabrir);
                        ps.setBoolean(5, ST_AGUARDANDO.equals(statusNovo));
                        ps.setLong(6, agora);
                        ps.setBoolean(7, reabrir);
                        ps.setBoolean(8, reabrir);
                        ps.setBoolean(9, reabrir);
                        ps.setLong(10, agora); ps.setLong(11, id);
                        ps.executeUpdate();
                    }
                }
                if ("COBRANCA".equals(tipo)) {
                    int dias = adiarDias != null ? limitar(adiarDias) : -1;
                    if (dias < 0) {
                        Cadencia cad = cadencia();
                        String prio = "MEDIA";
                        try (PreparedStatement ps = c.prepareStatement("SELECT prioridade FROM ponto_melhoria WHERE id = ?")) {
                            ps.setLong(1, id);
                            try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getString(1) != null) prio = rs.getString(1); }
                        }
                        dias = cad.dias(prio);
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE ponto_melhoria SET proxima_cobranca_em = ?, atualizado_em = ? WHERE id = ?")) {
                        ps.setLong(1, agora + dias * DIA); ps.setLong(2, agora); ps.setLong(3, id);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return novoId;
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    /**
     * Conferência in loco de uma ação que o responsável disse ter feito:
     * confirmada -> concluída (com a visita como prova); não feita -> volta a
     * ABERTO e conta uma reabertura. Entra na linha do tempo como VERIFICACAO.
     */
    public static boolean pontoVerificar(long id, boolean confirmada, Long visitaId, String texto,
                                         String por, long agora) throws SQLException {
        try (Connection c = Db.conexao()) {
            c.setAutoCommit(false);
            try {
                int n;
                // só confere o que está aguardando conferência: um duplo clique não duplica a conferência
                if (confirmada) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE ponto_melhoria SET status = 'RESOLVIDO', resolvido_em = ?, verificado_em = ?, " +
                            "verificado_visita_id = ?, solucao = COALESCE(solucao, ?), atualizado_em = ? " +
                            "WHERE id = ? AND status = '" + ST_AGUARDANDO + "'")) {
                        ps.setLong(1, agora); ps.setLong(2, agora);
                        setLong(ps, 3, visitaId);
                        ps.setString(4, Texto.vazio(texto) ? null : Texto.aparar(texto, 4000));
                        ps.setLong(5, agora); ps.setLong(6, id);
                        n = ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE ponto_melhoria SET status = 'ABERTO', informado_em = NULL, " +
                            "reaberturas = COALESCE(reaberturas, 0) + 1, atualizado_em = ? " +
                            "WHERE id = ? AND status = '" + ST_AGUARDANDO + "'")) {
                        ps.setLong(1, agora); ps.setLong(2, id);
                        n = ps.executeUpdate();
                    }
                }
                if (n > 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO acao_atualizacao (ponto_id,texto,status_novo,criado_por,criado_em,tipo) " +
                            "VALUES (?,?,?,?,?,'VERIFICACAO')")) {
                        ps.setLong(1, id);
                        ps.setString(2, Texto.vazio(texto)
                            ? (confirmada ? "Conferido na visita: feito." : "Conferido na visita: não estava feito.")
                            : Texto.aparar(texto, 4000));
                        ps.setString(3, confirmada ? "RESOLVIDO" : "ABERTO");
                        ps.setString(4, por);
                        ps.setLong(5, agora);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return n > 0;
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    public static String atualizacoes(long pontoId) throws SQLException {
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM acao_atualizacao WHERE ponto_id = ? ORDER BY criado_em DESC")) {
            ps.setLong(1, pontoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj().put("id", rs.getLong("id")).put("texto", rs.getString("texto"))
                        .put("statusNovo", rs.getString("status_novo")).put("criadoPor", rs.getString("criado_por"))
                        .put("tipo", rs.getString("tipo") == null ? "RETORNO" : rs.getString("tipo"))
                        .putNum("criadoEm", epoch(rs, "criado_em")).fim());
                }
            }
        }
        return arr.fim();
    }

    public static boolean pontoExcluir(long id) throws SQLException {
        try (Connection c = Db.conexao()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM acao_atualizacao WHERE ponto_id = ?")) {
                    ps.setLong(1, id); ps.executeUpdate();
                }
                boolean ok;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ponto_melhoria WHERE id = ?")) {
                    ps.setLong(1, id);
                    ok = ps.executeUpdate() > 0;
                }
                c.commit();
                return ok;
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    // ------------------------------------------------------------ planejamento

    /**
     * Painel do Master: KPIs do giro, agenda da semana, fila de não visitadas,
     * agências frias (sem visita há 120 dias), ações vencidas e vencendo,
     * evolução entre visitas e anotações gerais fixadas.
     */
    public static String planejamento(long agora) throws SQLException {
        Json.Arr naoVisitadas = Json.arr(), planejadas = Json.arr(), semFoto = Json.arr();
        Json.Arr estourados = Json.arr(), vencendo = Json.arr(), frias = Json.arr(), evolucao = Json.arr();
        Json.Arr cobrarHoje = Json.arr(), aConferir = Json.arr();
        int total = 0, visitadas = 0, visitas90 = 0, acoesAbertas = 0, acoesVencidas = 0, agendaSemana = 0;
        int nCobrar = 0, nParadas = 0, nAguardando = 0, concluidas180 = 0, comprovadas180 = 0;
        Double notaMedia = null;
        Cadencia cad = cadencia();

        try (Connection c = Db.conexao()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT (SELECT COUNT(*) FROM agencia) AS total, " +
                    " (SELECT COUNT(DISTINCT prefixo) FROM visita WHERE status = 'REALIZADA') AS visitadas, " +
                    " (SELECT COUNT(*) FROM visita WHERE status = 'REALIZADA' AND data_realizada >= " + (agora - 90 * DIA) + ") AS v90, " +
                    " (SELECT AVG(nota_geral) FROM visita WHERE status = 'REALIZADA' AND nota_geral IS NOT NULL) AS nota, " +
                    " (SELECT COUNT(*) FROM ponto_melhoria WHERE status <> 'RESOLVIDO') AS abertas, " +
                    " (SELECT COUNT(*) FROM visita WHERE status = 'PLANEJADA' AND data_planejada >= " + inicioDia(agora) + " AND data_planejada < " + (inicioDia(agora) + 8 * DIA) + ") AS semana, " +
                    " (SELECT COUNT(*) FROM ponto_melhoria WHERE status = 'RESOLVIDO' AND resolvido_em >= " + (agora - 180 * DIA) + ") AS c180, " +
                    " (SELECT COUNT(*) FROM ponto_melhoria p WHERE p.status = 'RESOLVIDO' AND p.resolvido_em >= " + (agora - 180 * DIA) +
                    "    AND (p.verificado_em IS NOT NULL OR EXISTS (SELECT 1 FROM foto f WHERE f.ponto_id = p.id AND f.momento = 'DEPOIS'))) AS p180")) {
                if (rs.next()) {
                    total = rs.getInt("total"); visitadas = rs.getInt("visitadas"); visitas90 = rs.getInt("v90");
                    double n = rs.getDouble("nota"); notaMedia = rs.wasNull() ? null : Math.round(n * 10) / 10.0;
                    acoesAbertas = rs.getInt("abertas"); agendaSemana = rs.getInt("semana");
                    concluidas180 = rs.getInt("c180"); comprovadas180 = rs.getInt("p180");
                }
            }
            String sqlNao =
                "SELECT a.prefixo, a.nome, a.uf, a.municipio, a.regional, " +
                "  (SELECT COUNT(*) FROM ponto_melhoria p WHERE p.prefixo = a.prefixo " +
                "     AND p.status <> 'RESOLVIDO') AS pontos, " +
                "  (SELECT k.pontos FROM conexao k WHERE k.prefixo = a.prefixo AND k.carteira = '' " +
                "     ORDER BY k.competencia DESC LIMIT 1) AS conexao " +
                "FROM agencia a WHERE NOT EXISTS (SELECT 1 FROM visita v " +
                "  WHERE v.prefixo = a.prefixo AND v.status = 'REALIZADA') " +
                "ORDER BY conexao, a.uf, a.municipio, a.nome";
            try (PreparedStatement ps = c.prepareStatement(sqlNao); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    naoVisitadas.add(Json.obj()
                        .put("prefixo", rs.getString("prefixo")).put("nome", rs.getString("nome"))
                        .put("uf", rs.getString("uf")).put("municipio", rs.getString("municipio"))
                        .put("regional", rs.getString("regional")).put("pontosAbertos", rs.getInt("pontos"))
                        .putNum("conexao", dblNulo(rs, "conexao")).fim());
                }
            }
            String sqlPlan =
                "SELECT v.id, v.prefixo, a.nome, a.uf, a.municipio, v.data_planejada, " +
                "  (SELECT COUNT(*) FROM ponto_melhoria p WHERE p.prefixo = v.prefixo AND p.status <> 'RESOLVIDO') AS pontos " +
                "FROM visita v JOIN agencia a ON a.prefixo = v.prefixo " +
                "WHERE v.status = 'PLANEJADA' ORDER BY COALESCE(v.data_planejada, 9e15)";
            try (PreparedStatement ps = c.prepareStatement(sqlPlan); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long dp = epoch(rs, "data_planejada");
                    planejadas.add(Json.obj()
                        .put("id", rs.getLong("id")).put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome")).put("uf", rs.getString("uf"))
                        .put("municipio", rs.getString("municipio")).putNum("dataPlanejada", dp)
                        .put("pontosAbertos", rs.getInt("pontos"))
                        .put("atrasada", visitaAtrasada(dp, agora))
                        .put("estaSemana", dp != null && dp >= inicioDia(agora) && dp < inicioDia(agora) + 8 * DIA).fim());
                }
            }
            String sqlFoto =
                "SELECT a.prefixo, a.nome, a.uf FROM agencia a " +
                "WHERE NOT EXISTS (SELECT 1 FROM foto f WHERE f.prefixo = a.prefixo) ORDER BY a.uf, a.nome";
            try (PreparedStatement ps = c.prepareStatement(sqlFoto); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    semFoto.add(Json.obj().put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome")).put("uf", rs.getString("uf")).fim());
                }
            }
        }
        // uma passada sobre as ações em aberto: vencidas, vencendo, a cobrar hoje, paradas e a conferir
        List<AcaoLida> abertas = lerAcoes(SQL_ACOES_BASE + " AND p.status <> 'RESOLVIDO' ORDER BY " +
            "CASE p.prioridade WHEN 'ALTA' THEN 0 WHEN 'MEDIA' THEN 1 ELSE 2 END, COALESCE(p.previsao, 9e15)",
            new ArrayList<Object>(), agora, cad, false);
        List<AcaoLida> paraCobrar = new ArrayList<>();
        for (AcaoLida a : abertas) {
            if (a.aguardando) { nAguardando++; aConferir.add(a.json()); continue; }
            if (a.vencida) { acoesVencidas++; estourados.add(a.json()); }
            else if (a.previsao != null && a.previsao < inicioDia(agora) + 8 * DIA) vencendo.add(a.json());
            if (a.parada) nParadas++;
            if (a.cobrarHoje) { nCobrar++; paraCobrar.add(a); }
        }
        ordenarParaCobranca(paraCobrar);
        for (AcaoLida a : paraCobrar) cobrarHoje.add(a.json());
        try (Connection c = Db.conexao()) {
            // agências frias: última visita realizada há mais de 120 dias
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a.prefixo, a.nome, a.uf, a.municipio, MAX(v.data_realizada) AS ultima " +
                    "FROM agencia a JOIN visita v ON v.prefixo = a.prefixo AND v.status = 'REALIZADA' " +
                    "GROUP BY a.prefixo HAVING ultima < ? ORDER BY ultima")) {
                ps.setLong(1, agora - 120 * DIA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Long ultima = epoch(rs, "ultima");
                        frias.add(Json.obj().put("prefixo", rs.getString("prefixo")).put("nome", rs.getString("nome"))
                            .put("uf", rs.getString("uf")).put("municipio", rs.getString("municipio"))
                            .putNum("ultimaVisita", ultima)
                            .put("dias", ultima == null ? 0 : (long) Math.floor((agora - ultima) / (double) DIA)).fim());
                    }
                }
            }
            // evolução: as duas últimas notas gerais por agência (as ações estão funcionando?)
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT v.prefixo, a.nome, v.nota_geral, v.data_realizada FROM visita v " +
                    "JOIN agencia a ON a.prefixo = v.prefixo " +
                    "WHERE v.status = 'REALIZADA' AND v.nota_geral IS NOT NULL ORDER BY v.prefixo, v.data_realizada DESC");
                 ResultSet rs = ps.executeQuery()) {
                String atual = null; Double n1 = null, n2 = null; String nome = null; Long d1 = null;
                while (true) {
                    boolean tem = rs.next();
                    String pfx = tem ? rs.getString("prefixo") : null;
                    if (atual != null && (!tem || !atual.equals(pfx))) {
                        if (n1 != null) {
                            evolucao.add(Json.obj().put("prefixo", atual).put("nome", nome).putNum("ultima", n1)
                                .putNum("anterior", n2).putNum("delta", n2 == null ? null : Math.round((n1 - n2) * 10) / 10.0)
                                .putNum("dataUltima", d1).fim());
                        }
                        n1 = n2 = null; d1 = null;
                    }
                    if (!tem) break;
                    atual = pfx; nome = rs.getString("nome");
                    double n = rs.getDouble("nota_geral");
                    if (n1 == null) { n1 = n; d1 = epoch(rs, "data_realizada"); } else if (n2 == null) n2 = n;
                }
            }
        }
        Integer pct = concluidas180 == 0 ? null : (int) Math.round(100.0 * comprovadas180 / concluidas180);
        return Json.obj()
            .putRaw("kpis", Json.obj().put("total", total).put("visitadas", visitadas).put("visitas90", visitas90)
                .putNum("notaMedia", notaMedia).put("acoesAbertas", acoesAbertas).put("acoesVencidas", acoesVencidas)
                .put("agendaSemana", agendaSemana)
                .put("cobrarHoje", nCobrar).put("paradas", nParadas).put("aguardando", nAguardando)
                .put("concluidas180", concluidas180).put("comprovadas180", comprovadas180)
                .putNum("fechamentoComprovadoPct", pct).fim())
            .putRaw("cadencia", cad.json())
            .putRaw("naoVisitadas", naoVisitadas.fim())
            .putRaw("planejadas", planejadas.fim())
            .putRaw("semFoto", semFoto.fim())
            .putRaw("pontosEstourados", estourados.fim())
            .putRaw("acoesVencendo", vencendo.fim())
            .putRaw("cobrarHoje", cobrarHoje.fim())
            .putRaw("aConferir", aConferir.fim())
            .putRaw("frias", frias.fim())
            .putRaw("evolucao", evolucao.fim())
            .putRaw("anotacoesGerais", anotacoes(null))
            .fim();
    }

    /**
     * Aviso do dia (abertura da ferramenta): quantas cobranças, vencidas,
     * paradas, a conferir, visitas planejadas para hoje e atrasadas.
     */
    public static String resumoDoDia(long agora) throws SQLException {
        Cadencia cad = cadencia();
        int cobrar = 0, vencidas = 0, paradas = 0, aguardando = 0, altasSemRetorno = 0;
        for (AcaoLida a : lerAcoes(SQL_ACOES_BASE + " AND p.status <> 'RESOLVIDO'", new ArrayList<Object>(), agora, cad, false)) {
            if (a.aguardando) { aguardando++; continue; }
            if (a.cobrarHoje) cobrar++;
            if (a.vencida) vencidas++;
            if (a.parada) paradas++;
            if ("ALTA".equals(a.prioridade) && a.parada) altasSemRetorno++;
        }
        int visitasHoje = 0, atrasadas = 0;
        java.util.Calendar hoje = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
        hoje.setTimeInMillis(agora);
        int diaHoje = hoje.get(java.util.Calendar.DAY_OF_YEAR), anoHoje = hoje.get(java.util.Calendar.YEAR);
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "SELECT data_planejada FROM visita WHERE status = 'PLANEJADA' AND data_planejada IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long d = rs.getLong(1);
                java.util.Calendar k = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
                k.setTimeInMillis(d);
                if (k.get(java.util.Calendar.YEAR) == anoHoje && k.get(java.util.Calendar.DAY_OF_YEAR) == diaHoje) visitasHoje++;
                else if (visitaAtrasada(d, agora)) atrasadas++;
            }
        }
        return Json.obj().put("cobrarHoje", cobrar).put("vencidas", vencidas).put("paradas", paradas)
            .put("aConferir", aguardando).put("altasParadas", altasSemRetorno)
            .put("visitasHoje", visitasHoje).put("visitasAtrasadas", atrasadas)
            .putRaw("cadencia", cad.json()).fim();
    }

    // --------------------------------------------------------------- exports

    public static String csvVisitas() throws SQLException {
        return csvVisitas(null);
    }

    /** CSV de visitas; `prefixo` opcional restringe a uma agência. */
    public static String csvVisitas(String prefixo) throws SQLException {
        StringBuilder sb = new StringBuilder("prefixo;agencia;uf;municipio;status;data_planejada;data_realizada;" +
            "ambiencia;atendimento;organizacao;equipe;movimento;claros;nota_geral;melhorias;percepcao;resumo;fotos;registrado_por\n");
        String sql = "SELECT v.*, a.nome, a.uf, a.municipio, (SELECT COUNT(*) FROM foto f WHERE f.visita_id = v.id) AS fotos " +
                "FROM visita v JOIN agencia a ON a.prefixo = v.prefixo " + (Texto.vazio(prefixo) ? "" : "WHERE v.prefixo = ? ") +
                "ORDER BY COALESCE(v.data_realizada, v.data_planejada) DESC";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (!Texto.vazio(prefixo)) ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append(csv(rs.getString("prefixo"), rs.getString("nome"), rs.getString("uf"), rs.getString("municipio"),
                    rs.getString("status"), data(epoch(rs, "data_planejada")), data(epoch(rs, "data_realizada")),
                    num(intNulo(rs, "ambiencia")), num(intNulo(rs, "atendimento")), num(intNulo(rs, "organizacao")),
                    num(intNulo(rs, "equipe")), rs.getString("movimento"), num(intNulo(rs, "claros")),
                    numD(dblNulo(rs, "nota_geral")), rs.getString("melhorias") == null ? "" : rs.getString("melhorias").replace("|", " | "),
                    rs.getString("percepcao"), rs.getString("resumo"), String.valueOf(rs.getInt("fotos")), rs.getString("criado_por")));
            }
            }
        }
        return sb.toString();
    }

    public static String csvAcoes(long agora) throws SQLException {
        return csvAcoes(agora, null);
    }

    /** CSV de ações; `prefixo` opcional restringe a uma agência. */
    public static String csvAcoes(long agora, String prefixo) throws SQLException {
        StringBuilder sb = new StringBuilder("id;prefixo;agencia;regional;descricao;status;prioridade;responsavel;" +
            "prazo;situacao_prazo;solucao;visita_id;registros;cobrancas;ultima_cobranca;proxima_cobranca;sem_retorno_dias;" +
            "informado_em;verificado_em;reaberturas;fotos_antes;fotos_depois;criado_em;resolvido_em\n");
        Cadencia cad = cadencia();
        String sql = SQL_ACOES_BASE + (Texto.vazio(prefixo) ? "" : " AND p.prefixo = ?") +
            " ORDER BY CASE p.status WHEN 'RESOLVIDO' THEN 1 ELSE 0 END, COALESCE(p.previsao, 9e15)";
        long hoje = inicioDia(agora);
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (!Texto.vazio(prefixo)) ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AcaoLida a = lerAcao(rs, agora, cad);
                    Long prev = a.previsao;
                    String situ = a.aguardando ? "aguardando conferência" : !a.aberta ? "concluída" : prev == null ? "sem prazo"
                        : a.vencida ? "vencida" : prev < hoje + 8 * DIA ? "vence em 7 dias" : "no prazo";
                    if (a.aberta && !a.aguardando && a.parada) situ += " · parada";
                    if (a.cobrarHoje) situ += " · cobrar";
                    long vid = rs.getLong("visita_id"); boolean sv = rs.wasNull();
                    Long ultimaCobranca = epoch(rs, "ultima_cobranca");
                    long base = Math.max(nz(epoch(rs, "criado_em")), nz(epoch(rs, "ultimo_retorno")));
                    String semRetorno = a.aberta && !a.aguardando ? String.valueOf((long) Math.floor((agora - base) / (double) DIA)) : "";
                    sb.append(csv(String.valueOf(a.id), a.prefixo, rs.getString("agencia"),
                        rs.getString("regional"), rs.getString("descricao"), a.status, a.prioridade,
                        rs.getString("responsavel"), data(prev), situ, rs.getString("solucao"), sv ? "" : String.valueOf(vid),
                        String.valueOf(rs.getInt("atualizacoes")), String.valueOf(rs.getInt("cobrancas")), data(ultimaCobranca),
                        data(a.proximaCobranca), semRetorno, data(epoch(rs, "informado_em")), data(epoch(rs, "verificado_em")),
                        String.valueOf(rs.getInt("reaberturas")), String.valueOf(rs.getInt("fotos_antes")),
                        String.valueOf(a.fotosDepois), data(epoch(rs, "criado_em")), data(epoch(rs, "resolvido_em"))));
                }
            }
        }
        return sb.toString();
    }

    private static String csv(String... campos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) sb.append(';');
            String v = campos[i] == null ? "" : campos[i];
            if (v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) v = '"' + v.replace("\"", "\"\"") + '"';
            sb.append(v);
        }
        return sb.append('\n').toString();
    }

    private static String data(Long epoch) {
        if (epoch == null) return "";
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
        c.setTimeInMillis(epoch);
        return String.format("%02d/%02d/%04d", c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.YEAR));
    }

    private static String num(Integer i) { return i == null ? "" : String.valueOf(i); }
    private static String numD(Double d) { return d == null ? "" : String.valueOf(d).replace('.', ','); }

    // ---------------------------------------------------------------- comuns

    private static Long epoch(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer intNulo(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double dblNulo(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static void setLong(PreparedStatement ps, int pos, Long v) throws SQLException {
        if (v == null) ps.setNull(pos, java.sql.Types.BIGINT);
        else ps.setLong(pos, v);
    }

    private static void setInt(PreparedStatement ps, int pos, Integer v) throws SQLException {
        if (v == null) ps.setNull(pos, java.sql.Types.INTEGER);
        else ps.setInt(pos, v);
    }

    private static void setDouble(PreparedStatement ps, int pos, Double v) throws SQLException {
        if (v == null) ps.setNull(pos, java.sql.Types.DOUBLE);
        else ps.setDouble(pos, v);
    }
}
