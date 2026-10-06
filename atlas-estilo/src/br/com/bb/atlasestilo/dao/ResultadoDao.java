package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;

/** Resultados da agência: metas/projeções do período e histórico de PDG. */
public final class ResultadoDao {

    private ResultadoDao() { }

    /** Metas da agência agrupadas por período (mais recente primeiro). */
    public static String metas(String prefixo) throws SQLException {
        String sql = "SELECT periodo, indicador, meta, realizado, projecao " +
                     "FROM meta WHERE prefixo = ? " +
                     "ORDER BY periodo DESC, indicador";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("periodo", rs.getString("periodo"))
                        .put("indicador", rs.getString("indicador"))
                        .putNum("meta", nulavel(rs, "meta"))
                        .putNum("realizado", nulavel(rs, "realizado"))
                        .putNum("projecao", nulavel(rs, "projecao"))
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    /** Histórico de PDG da agência, semestre a semestre (crescente). */
    public static String pdgHistorico(String prefixo) throws SQLException {
        String sql = "SELECT semestre, atingiu, pontuacao FROM pdg " +
                     "WHERE prefixo = ? ORDER BY semestre";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("semestre", rs.getString("semestre"))
                        .put("atingiu", rs.getInt("atingiu") == 1)
                        .putNum("pontuacao", nulavel(rs, "pontuacao"))
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    private static Double nulavel(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
