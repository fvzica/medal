package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.web.Sessao;

/**
 * Agregações da região selecionada (estado, município, regional ou conjunto
 * de prefixos): os "grandes números" que todo perfil enxerga, e as listas de
 * detalhe (pessoas) que só Master/Moderador recebem.
 */
public final class MetricaDao {

    private MetricaDao() { }

    /** Grandes números da seleção. */
    public static String resumo(Sessao s, Selecao sel, long agora) throws SQLException {
        String emAgencias = "(SELECT prefixo FROM agencia a WHERE " + sel.where() + ")";
        String sql =
            "SELECT " +
            " (SELECT COUNT(*) FROM agencia a WHERE " + sel.where() + ") AS agencias, " +
            " (SELECT COUNT(*) FROM funci WHERE prefixo IN " + emAgencias + ") AS funcis, " +
            " (SELECT COUNT(*) FROM funci WHERE tipo = 'GERENTE' AND prefixo IN " + emAgencias + ") AS gerentes, " +
            " (SELECT COUNT(*) FROM funci WHERE tipo = 'ASSISTENTE' AND prefixo IN " + emAgencias + ") AS assistentes, " +
            " (SELECT COUNT(*) FROM carteira WHERE prefixo IN " + emAgencias + ") AS carteiras, " +
            " (SELECT AVG(? - posse_cargo) FROM funci WHERE posse_cargo IS NOT NULL " +
            "    AND prefixo IN " + emAgencias + ") AS media_cargo_ms, " +
            " (SELECT AVG(? - posse_funcao) FROM funci WHERE posse_funcao IS NOT NULL " +
            "    AND prefixo IN " + emAgencias + ") AS media_funcao_ms, " +
            " (SELECT COALESCE(SUM(atingiu),0) FROM pdg WHERE prefixo IN " + emAgencias + ") AS pdg_ganhos, " +
            " (SELECT COUNT(DISTINCT prefixo) FROM pdg WHERE prefixo IN " + emAgencias + ") AS pdg_agencias, " +
            " (SELECT COUNT(DISTINCT semestre) FROM pdg WHERE prefixo IN " + emAgencias + ") AS pdg_semestres, " +
            " (SELECT COUNT(DISTINCT v.prefixo) FROM visita v WHERE v.status = 'REALIZADA' " +
            "    AND v.prefixo IN " + emAgencias + ") AS visitadas, " +
            " (SELECT COUNT(*) FROM ponto_melhoria p WHERE p.status <> 'RESOLVIDO' " +
            "    AND p.prefixo IN " + emAgencias + ") AS pontos_abertos";

        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            int pos = 1;
            pos = sel.aplicar(ps, pos);          // agencias
            pos = sel.aplicar(ps, pos);          // funcis
            pos = sel.aplicar(ps, pos);          // gerentes
            pos = sel.aplicar(ps, pos);          // assistentes
            pos = sel.aplicar(ps, pos);          // carteiras
            ps.setLong(pos++, agora);            // media cargo (agora)
            pos = sel.aplicar(ps, pos);
            ps.setLong(pos++, agora);            // media funcao (agora)
            pos = sel.aplicar(ps, pos);
            pos = sel.aplicar(ps, pos);          // pdg ganhos
            pos = sel.aplicar(ps, pos);          // pdg agencias
            pos = sel.aplicar(ps, pos);          // pdg semestres
            pos = sel.aplicar(ps, pos);          // visitadas
            sel.aplicar(ps, pos);                // pontos abertos
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int agencias = rs.getInt("agencias");
                int funcis = rs.getInt("funcis");
                double mediaFuncis = agencias == 0 ? 0 : (double) funcis / agencias;
                return Json.obj()
                    .put("agencias", agencias)
                    .put("funcis", funcis)
                    .put("gerentes", rs.getInt("gerentes"))
                    .put("assistentes", rs.getInt("assistentes"))
                    .put("carteiras", rs.getInt("carteiras"))
                    .put("mediaFuncisPorAgencia", Math.round(mediaFuncis * 10) / 10.0)
                    .putNum("tempoMedioCargoMeses", meses(rs, "media_cargo_ms"))
                    .putNum("tempoMedioFuncaoMeses", meses(rs, "media_funcao_ms"))
                    .put("pdgGanhos", rs.getInt("pdg_ganhos"))
                    .put("pdgAgencias", rs.getInt("pdg_agencias"))
                    .put("pdgSemestres", rs.getInt("pdg_semestres"))
                    // registros do Master (visitas e ações) só aparecem para o Master
                    .put("visitadas", s.master() ? rs.getInt("visitadas") : 0)
                    .put("pontosAbertos", s.master() ? rs.getInt("pontos_abertos") : 0)
                    .fim();
            }
        }
    }

    private static Double meses(ResultSet rs, String col) throws SQLException {
        double ms = rs.getDouble(col);
        if (rs.wasNull()) return null;
        return Math.round(ms / 2629800000.0 * 10) / 10.0; // ms médios de um mês
    }

    /**
     * Lista de detalhe da seleção. Tipos com PESSOAS (funcis, gerentes,
     * assistentes) exigem veTudo — o chamador já validou o perfil.
     */
    public static String lista(Sessao s, Selecao sel, String tipo, long agora)
            throws SQLException {
        String emAgencias = "(SELECT prefixo FROM agencia a WHERE " + sel.where() + ")";
        if (tipo.equals("funcis") || tipo.equals("gerentes") || tipo.equals("assistentes")) {
            String filtroTipo = tipo.equals("gerentes") ? " AND f.tipo = 'GERENTE'"
                              : tipo.equals("assistentes") ? " AND f.tipo = 'ASSISTENTE'" : "";
            String sql =
                "SELECT f.matricula, f.nome, f.prefixo, ag.nome AS agencia, f.cargo, " +
                "       f.funcao, f.tipo, f.carteira, f.posse_cargo, f.posse_funcao " +
                "FROM funci f JOIN agencia ag ON ag.prefixo = f.prefixo " +
                "WHERE f.prefixo IN " + emAgencias + filtroTipo +
                " ORDER BY f.nome LIMIT 500";
            Json.Arr arr = Json.arr();
            try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
                sel.aplicar(ps, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        arr.add(Json.obj()
                            .put("matricula", rs.getString("matricula"))
                            .put("nome", rs.getString("nome"))
                            .put("prefixo", rs.getString("prefixo"))
                            .put("agencia", rs.getString("agencia"))
                            .put("cargo", rs.getString("cargo"))
                            .put("funcao", rs.getString("funcao"))
                            .put("tipo", rs.getString("tipo"))
                            .put("carteira", rs.getString("carteira"))
                            .putNum("mesesCargo", mesesDesde(rs, "posse_cargo", agora))
                            .putNum("mesesFuncao", mesesDesde(rs, "posse_funcao", agora))
                            .fim());
                    }
                }
            }
            return arr.fim();
        }
        if (tipo.equals("carteiras")) {
            String sql =
                "SELECT ct.prefixo, ag.nome AS agencia, ct.codigo, ct.nome, ct.tipo, " +
                "       ct.qtd_clientes, ct.gerente_matricula, f.nome AS gerente_nome " +
                "FROM carteira ct JOIN agencia ag ON ag.prefixo = ct.prefixo " +
                "LEFT JOIN funci f ON f.matricula = ct.gerente_matricula " +
                "WHERE ct.prefixo IN " + emAgencias +
                " ORDER BY ag.nome, ct.codigo LIMIT 500";
            Json.Arr arr = Json.arr();
            try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
                sel.aplicar(ps, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        arr.add(Json.obj()
                            .put("prefixo", rs.getString("prefixo"))
                            .put("agencia", rs.getString("agencia"))
                            .put("codigo", rs.getString("codigo"))
                            .put("nome", rs.getString("nome"))
                            .put("tipo", rs.getString("tipo"))
                            .put("qtdClientes", rs.getInt("qtd_clientes"))
                            // Colega vê só os grandes números — nada de pessoas
                            .put("gerenteMatricula", s.veTudo() ? rs.getString("gerente_matricula") : null)
                            .put("gerenteNome", s.veTudo() ? rs.getString("gerente_nome") : null)
                            .fim());
                    }
                }
            }
            return arr.fim();
        }
        if (tipo.equals("pdg")) {
            String sql =
                "SELECT p.prefixo, ag.nome AS agencia, " +
                "       COUNT(*) AS semestres, SUM(p.atingiu) AS ganhos " +
                "FROM pdg p JOIN agencia ag ON ag.prefixo = p.prefixo " +
                "WHERE p.prefixo IN " + emAgencias +
                " GROUP BY p.prefixo ORDER BY ganhos DESC, ag.nome LIMIT 500";
            Json.Arr arr = Json.arr();
            try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
                sel.aplicar(ps, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        arr.add(Json.obj()
                            .put("prefixo", rs.getString("prefixo"))
                            .put("agencia", rs.getString("agencia"))
                            .put("semestres", rs.getInt("semestres"))
                            .put("ganhos", rs.getInt("ganhos"))
                            .fim());
                    }
                }
            }
            return arr.fim();
        }
        // padrão: agências da seleção com os grandes números por agência
        String sql =
            "SELECT a.prefixo, a.nome, a.uf, a.municipio, a.regional, " +
            "   (SELECT COUNT(*) FROM funci f WHERE f.prefixo = a.prefixo) AS funcis, " +
            "   (SELECT COUNT(*) FROM funci f WHERE f.prefixo = a.prefixo AND f.tipo = 'GERENTE') AS gerentes, " +
            "   (SELECT COUNT(*) FROM funci f WHERE f.prefixo = a.prefixo AND f.tipo = 'ASSISTENTE') AS assistentes, " +
            "   (SELECT COUNT(*) FROM carteira ct WHERE ct.prefixo = a.prefixo) AS carteiras, " +
            "   (SELECT COALESCE(SUM(atingiu),0) FROM pdg p WHERE p.prefixo = a.prefixo) AS pdg_ganhos, " +
            "   EXISTS(SELECT 1 FROM visita v WHERE v.prefixo = a.prefixo AND v.status = 'REALIZADA') AS visitada " +
            "FROM agencia a WHERE " + sel.where() + " ORDER BY a.nome LIMIT 500";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            sel.aplicar(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome"))
                        .put("uf", rs.getString("uf"))
                        .put("municipio", rs.getString("municipio"))
                        .put("regional", rs.getString("regional"))
                        .put("funcis", rs.getInt("funcis"))
                        .put("gerentes", rs.getInt("gerentes"))
                        .put("assistentes", rs.getInt("assistentes"))
                        .put("carteiras", rs.getInt("carteiras"))
                        .put("pdgGanhos", rs.getInt("pdg_ganhos"))
                        .put("visitada", s.master() && rs.getInt("visitada") == 1)
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    private static Double mesesDesde(ResultSet rs, String col, long agora) throws SQLException {
        long t = rs.getLong(col);
        if (rs.wasNull() || t <= 0) return null;
        return Math.round((agora - t) / 2629800000.0 * 10) / 10.0;
    }
}
