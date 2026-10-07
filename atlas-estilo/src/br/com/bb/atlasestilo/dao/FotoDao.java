package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;

/**
 * Fotos das agências, das pessoas e das visitas (metadados; o arquivo fica
 * no disco). Fotos restritas (registradas pelo Master numa visita) só
 * aparecem para Master; fotos de PESSOA só para quem vê tudo.
 */
public final class FotoDao {

    private FotoDao() { }

    /** Fotos de uma agência visíveis para o perfil. */
    public static String listar(String prefixo, boolean veTudo, boolean master) throws SQLException {
        String sql = "SELECT id, prefixo, matricula, tipo, legenda, origem, criado_em, visita_id, restrita " +
                     "FROM foto WHERE prefixo = ? " +
                     (veTudo ? "" : "AND tipo <> 'PESSOA' ") +
                     (master ? "" : "AND restrita = 0 ") +
                     "ORDER BY CASE tipo WHEN 'FACHADA' THEN 0 ELSE 1 END, criado_em DESC";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(json(rs));
            }
        }
        return arr.fim();
    }

    /** Compatibilidade: sem fotos restritas. */
    public static String listar(String prefixo, boolean veTudo) throws SQLException {
        return listar(prefixo, veTudo, false);
    }

    /** Fotos de uma visita (sempre restritas; o chamador já validou Master). */
    public static String daVisita(long visitaId) throws SQLException {
        String sql = "SELECT id, prefixo, matricula, tipo, legenda, origem, criado_em, visita_id, restrita " +
                     "FROM foto WHERE visita_id = ? ORDER BY criado_em";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, visitaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(json(rs));
            }
        }
        return arr.fim();
    }

    private static String json(ResultSet rs) throws SQLException {
        long vid = rs.getLong("visita_id");
        boolean semVisita = rs.wasNull();
        return Json.obj()
            .put("id", rs.getString("id"))
            .put("prefixo", rs.getString("prefixo"))
            .put("matricula", rs.getString("matricula"))
            .put("tipo", rs.getString("tipo"))
            .put("legenda", rs.getString("legenda"))
            .put("origem", rs.getString("origem"))
            .put("criadoEm", rs.getLong("criado_em"))
            .putNum("visitaId", semVisita ? null : vid)
            .put("restrita", rs.getInt("restrita") == 1)
            .fim();
    }

    public static void inserir(String id, String prefixo, String matricula, String tipo,
                               String legenda, String arquivo, String mime, String por,
                               long agora) throws SQLException {
        inserir(id, prefixo, matricula, tipo, legenda, arquivo, mime, por, agora, null, false);
    }

    public static void inserir(String id, String prefixo, String matricula, String tipo,
                               String legenda, String arquivo, String mime, String por,
                               long agora, Long visitaId, boolean restrita) throws SQLException {
        String sql = "INSERT INTO foto (id,prefixo,matricula,tipo,legenda,arquivo,mime," +
                     "origem,criado_por,criado_em,visita_id,restrita) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, prefixo);
            ps.setString(3, Texto.vazio(matricula) ? null : Texto.matricula(matricula));
            ps.setString(4, tipo);
            ps.setString(5, Texto.aparar(legenda, 500));
            ps.setString(6, arquivo);
            ps.setString(7, mime);
            ps.setString(8, visitaId == null ? "ADMIN" : "VISITA");
            ps.setString(9, por);
            ps.setLong(10, agora);
            if (visitaId == null) ps.setNull(11, java.sql.Types.BIGINT); else ps.setLong(11, visitaId);
            ps.setInt(12, restrita ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /** [arquivo, mime, tipo, restrita("1"/"0")] da foto, ou null. */
    public static String[] obter(String id) throws SQLException {
        String sql = "SELECT arquivo, mime, tipo, restrita FROM foto WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[] { rs.getString(1), rs.getString(2), rs.getString(3),
                                      rs.getInt(4) == 1 ? "1" : "0" };
            }
        }
    }

    /** Remove o registro e devolve o nome do arquivo físico (ou null). */
    public static String excluir(String id) throws SQLException {
        String arquivo = null;
        try (Connection c = Db.conexao()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT arquivo FROM foto WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) arquivo = rs.getString(1);
                }
            }
            if (arquivo != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM foto WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
        }
        return arquivo;
    }

    /** Remove as fotos de uma visita e devolve os arquivos físicos a apagar. */
    public static java.util.List<String> excluirDaVisita(long visitaId) throws SQLException {
        java.util.List<String> arquivos = new java.util.ArrayList<>();
        try (Connection c = Db.conexao()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT arquivo FROM foto WHERE visita_id = ?")) {
                ps.setLong(1, visitaId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) arquivos.add(rs.getString(1));
                }
            }
            if (!arquivos.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM foto WHERE visita_id = ?")) {
                    ps.setLong(1, visitaId);
                    ps.executeUpdate();
                }
            }
        }
        return arquivos;
    }
}
