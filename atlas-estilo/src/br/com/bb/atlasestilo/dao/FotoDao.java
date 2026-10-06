package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;

/** Fotos das agências e das pessoas (metadados; o arquivo fica no disco). */
public final class FotoDao {

    private FotoDao() { }

    /** Fotos de uma agência. Sem veTudo, fotos de PESSOA ficam de fora. */
    public static String listar(String prefixo, boolean veTudo) throws SQLException {
        String sql = "SELECT id, prefixo, matricula, tipo, legenda, origem, criado_em " +
                     "FROM foto WHERE prefixo = ? " +
                     (veTudo ? "" : "AND tipo <> 'PESSOA' ") +
                     "ORDER BY CASE tipo WHEN 'FACHADA' THEN 0 ELSE 1 END, criado_em DESC";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("id", rs.getString("id"))
                        .put("prefixo", rs.getString("prefixo"))
                        .put("matricula", rs.getString("matricula"))
                        .put("tipo", rs.getString("tipo"))
                        .put("legenda", rs.getString("legenda"))
                        .put("origem", rs.getString("origem"))
                        .put("criadoEm", rs.getLong("criado_em"))
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    public static void inserir(String id, String prefixo, String matricula, String tipo,
                               String legenda, String arquivo, String mime, String por,
                               long agora) throws SQLException {
        String sql = "INSERT INTO foto (id,prefixo,matricula,tipo,legenda,arquivo,mime," +
                     "origem,criado_por,criado_em) VALUES (?,?,?,?,?,?,?,'ADMIN',?,?)";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, prefixo);
            ps.setString(3, Texto.vazio(matricula) ? null : Texto.matricula(matricula));
            ps.setString(4, tipo);
            ps.setString(5, Texto.aparar(legenda, 500));
            ps.setString(6, arquivo);
            ps.setString(7, mime);
            ps.setString(8, por);
            ps.setLong(9, agora);
            ps.executeUpdate();
        }
    }

    /** [arquivo, mime, tipo] da foto, ou null. */
    public static String[] obter(String id) throws SQLException {
        String sql = "SELECT arquivo, mime, tipo FROM foto WHERE id = ?";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[] { rs.getString(1), rs.getString(2), rs.getString(3) };
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
}
