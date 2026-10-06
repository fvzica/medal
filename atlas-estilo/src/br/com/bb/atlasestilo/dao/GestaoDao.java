package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;

/**
 * Gestão do atendimento (ferramentas do Master): visitas, anotações e
 * pontos de melhoria, mais o painel de planejamento.
 */
public final class GestaoDao {

    private GestaoDao() { }

    // ---------------------------------------------------------------- visitas

    public static String visitas(String prefixo) throws SQLException {
        String sql = "SELECT * FROM visita WHERE prefixo = ? " +
                     "ORDER BY COALESCE(data_realizada, data_planejada, criado_em) DESC";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(visitaJson(rs));
            }
        }
        return arr.fim();
    }

    private static String visitaJson(ResultSet rs) throws SQLException {
        return Json.obj()
            .put("id", rs.getLong("id"))
            .put("prefixo", rs.getString("prefixo"))
            .put("status", rs.getString("status"))
            .putNum("dataPlanejada", epoch(rs, "data_planejada"))
            .putNum("dataRealizada", epoch(rs, "data_realizada"))
            .put("resumo", rs.getString("resumo"))
            .put("criadoPor", rs.getString("criado_por"))
            .putNum("criadoEm", epoch(rs, "criado_em"))
            .fim();
    }

    public static long visitaCriar(String prefixo, String status, Long dataPlanejada,
                                   Long dataRealizada, String resumo, String por,
                                   long agora) throws SQLException {
        String sql = "INSERT INTO visita (prefixo,status,data_planejada,data_realizada," +
                     "resumo,criado_por,criado_em,atualizado_em) VALUES (?,?,?,?,?,?,?,?)";
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
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** Campos nulos são preservados (não sobrescreve o que a tela não enviou). */
    public static boolean visitaAtualizar(long id, String status, Long dataPlanejada,
                                          Long dataRealizada, String resumo,
                                          long agora) throws SQLException {
        String sql = "UPDATE visita SET status = COALESCE(?, status), " +
                     "data_planejada = COALESCE(?, data_planejada), " +
                     "data_realizada = COALESCE(?, data_realizada), " +
                     "resumo = COALESCE(?, resumo), atualizado_em = ? WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Texto.vazio(status) ? null : status);
            setLong(ps, 2, dataPlanejada);
            setLong(ps, 3, dataRealizada);
            ps.setString(4, Texto.vazio(resumo) ? null : Texto.aparar(resumo, 4000));
            ps.setLong(5, agora);
            ps.setLong(6, id);
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean visitaExcluir(long id) throws SQLException {
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement("DELETE FROM visita WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
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

    // ------------------------------------------------------ pontos de melhoria

    public static String pontos(String prefixo, String status) throws SQLException {
        StringBuilder sql = new StringBuilder(
            "SELECT p.*, ag.nome AS agencia FROM ponto_melhoria p " +
            "JOIN agencia ag ON ag.prefixo = p.prefixo WHERE 1=1");
        if (!Texto.vazio(prefixo)) sql.append(" AND p.prefixo = ?");
        if (!Texto.vazio(status))  sql.append(" AND p.status = ?");
        sql.append(" ORDER BY CASE p.status WHEN 'RESOLVIDO' THEN 1 ELSE 0 END, " +
                   "COALESCE(p.previsao, 9e15), p.criado_em DESC");
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int pos = 1;
            if (!Texto.vazio(prefixo)) ps.setString(pos++, prefixo);
            if (!Texto.vazio(status))  ps.setString(pos, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("id", rs.getLong("id"))
                        .put("prefixo", rs.getString("prefixo"))
                        .put("agencia", rs.getString("agencia"))
                        .put("descricao", rs.getString("descricao"))
                        .put("status", rs.getString("status"))
                        .put("solucao", rs.getString("solucao"))
                        .putNum("previsao", epoch(rs, "previsao"))
                        .putNum("resolvidoEm", epoch(rs, "resolvido_em"))
                        .put("criadoPor", rs.getString("criado_por"))
                        .putNum("criadoEm", epoch(rs, "criado_em"))
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    public static long pontoCriar(String prefixo, String descricao, Long previsao,
                                  String por, long agora) throws SQLException {
        String sql = "INSERT INTO ponto_melhoria (prefixo,descricao,previsao," +
                     "criado_por,criado_em,atualizado_em) VALUES (?,?,?,?,?,?)";
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, prefixo);
            ps.setString(2, Texto.aparar(descricao, 4000));
            setLong(ps, 3, previsao);
            ps.setString(4, por);
            ps.setLong(5, agora);
            ps.setLong(6, agora);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static boolean pontoAtualizar(long id, String status, String solucao,
                                         Long previsao, long agora) throws SQLException {
        boolean resolver = "RESOLVIDO".equals(status);
        String sql = "UPDATE ponto_melhoria SET status = COALESCE(?, status), " +
                     "solucao = COALESCE(?, solucao), previsao = COALESCE(?, previsao), " +
                     "resolvido_em = CASE WHEN ? THEN ? ELSE resolvido_em END, " +
                     "atualizado_em = ? WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Texto.vazio(status) ? null : status);
            ps.setString(2, solucao == null ? null : Texto.aparar(solucao, 4000));
            setLong(ps, 3, previsao);
            ps.setBoolean(4, resolver);
            ps.setLong(5, agora);
            ps.setLong(6, agora);
            ps.setLong(7, id);
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean pontoExcluir(long id) throws SQLException {
        try (Connection c = Db.conexao();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ponto_melhoria WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    // ------------------------------------------------------------ planejamento

    /**
     * Painel de planejamento do Master: fila de não visitadas, próximas
     * planejadas, agências sem foto, pontos com previsão estourada e
     * anotações gerais fixadas.
     */
    public static String planejamento(long agora) throws SQLException {
        Json.Arr naoVisitadas = Json.arr();
        Json.Arr planejadas = Json.arr();
        Json.Arr semFoto = Json.arr();
        Json.Arr estourados = Json.arr();

        try (Connection c = Db.conexao()) {
            String sqlNao =
                "SELECT a.prefixo, a.nome, a.uf, a.municipio, a.regional, " +
                "  (SELECT COUNT(*) FROM ponto_melhoria p WHERE p.prefixo = a.prefixo " +
                "     AND p.status <> 'RESOLVIDO') AS pontos " +
                "FROM agencia a WHERE NOT EXISTS (SELECT 1 FROM visita v " +
                "  WHERE v.prefixo = a.prefixo AND v.status = 'REALIZADA') " +
                "ORDER BY a.uf, a.municipio, a.nome";
            try (PreparedStatement ps = c.prepareStatement(sqlNao);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    naoVisitadas.add(Json.obj()
                        .put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome"))
                        .put("uf", rs.getString("uf"))
                        .put("municipio", rs.getString("municipio"))
                        .put("regional", rs.getString("regional"))
                        .put("pontosAbertos", rs.getInt("pontos"))
                        .fim());
                }
            }
            String sqlPlan =
                "SELECT v.id, v.prefixo, a.nome, a.uf, a.municipio, v.data_planejada " +
                "FROM visita v JOIN agencia a ON a.prefixo = v.prefixo " +
                "WHERE v.status = 'PLANEJADA' ORDER BY COALESCE(v.data_planejada, 9e15)";
            try (PreparedStatement ps = c.prepareStatement(sqlPlan);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    planejadas.add(Json.obj()
                        .put("id", rs.getLong("id"))
                        .put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome"))
                        .put("uf", rs.getString("uf"))
                        .put("municipio", rs.getString("municipio"))
                        .putNum("dataPlanejada", epoch(rs, "data_planejada"))
                        .fim());
                }
            }
            String sqlFoto =
                "SELECT a.prefixo, a.nome, a.uf FROM agencia a " +
                "WHERE NOT EXISTS (SELECT 1 FROM foto f WHERE f.prefixo = a.prefixo) " +
                "ORDER BY a.uf, a.nome";
            try (PreparedStatement ps = c.prepareStatement(sqlFoto);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    semFoto.add(Json.obj()
                        .put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome"))
                        .put("uf", rs.getString("uf"))
                        .fim());
                }
            }
            String sqlEst =
                "SELECT p.id, p.prefixo, a.nome, p.descricao, p.previsao, p.status " +
                "FROM ponto_melhoria p JOIN agencia a ON a.prefixo = p.prefixo " +
                "WHERE p.status <> 'RESOLVIDO' AND p.previsao IS NOT NULL AND p.previsao < ? " +
                "ORDER BY p.previsao";
            try (PreparedStatement ps = c.prepareStatement(sqlEst)) {
                ps.setLong(1, agora);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        estourados.add(Json.obj()
                            .put("id", rs.getLong("id"))
                            .put("prefixo", rs.getString("prefixo"))
                            .put("nome", rs.getString("nome"))
                            .put("descricao", rs.getString("descricao"))
                            .putNum("previsao", epoch(rs, "previsao"))
                            .put("status", rs.getString("status"))
                            .fim());
                    }
                }
            }
        }
        return Json.obj()
            .putRaw("naoVisitadas", naoVisitadas.fim())
            .putRaw("planejadas", planejadas.fim())
            .putRaw("semFoto", semFoto.fim())
            .putRaw("pontosEstourados", estourados.fim())
            .putRaw("anotacoesGerais", anotacoes(null))
            .fim();
    }

    // ---------------------------------------------------------------- comuns

    private static Long epoch(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static void setLong(PreparedStatement ps, int pos, Long v) throws SQLException {
        if (v == null) ps.setNull(pos, java.sql.Types.BIGINT);
        else ps.setLong(pos, v);
    }
}
