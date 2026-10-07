package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;

/** Administração: masters, flags de matrícula, log de imports e dados de exemplo. */
public final class ConfigDao {

    private ConfigDao() { }

    // ---------------------------------------------------------------- masters

    /** Carga inicial de masters, SÓ quando a tabela está vazia (1º boot). */
    public static void semearMastersSeVazio() throws SQLException {
        try (Connection c = Db.conexao()) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM config_master")) {
                rs.next();
                if (rs.getInt(1) > 0) return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO config_master (matricula) VALUES (?)")) {
                for (String m : new String[] { "F3548926", "F3191837", "F6323371" }) {
                    ps.setString(1, m);
                    ps.executeUpdate();
                }
            }
        }
    }

    public static String masters() throws SQLException {
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT matricula, incluido_por, criado_em FROM config_master ORDER BY matricula")) {
            while (rs.next()) {
                arr.add(Json.obj()
                    .put("matricula", rs.getString(1))
                    .put("incluidoPor", rs.getString(2))
                    .putNum("criadoEm", rs.getLong(3) == 0 ? null : rs.getLong(3))
                    .fim());
            }
        }
        return arr.fim();
    }

    public static void masterIncluir(String matricula, String por, long agora)
            throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO config_master (matricula,incluido_por,criado_em) " +
                "VALUES (?,?,?)")) {
            ps.setString(1, Texto.matricula(matricula));
            ps.setString(2, por);
            ps.setLong(3, agora);
            ps.executeUpdate();
        }
    }

    /** Recusa remover o último master. */
    public static boolean masterRemover(String matricula) throws SQLException {
        try (Connection c = Db.conexao()) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM config_master")) {
                rs.next();
                if (rs.getInt(1) <= 1) return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM config_master WHERE matricula = ?")) {
                ps.setString(1, Texto.matricula(matricula));
                return ps.executeUpdate() > 0;
            }
        }
    }

    // ------------------------------------------------------------------ flags

    public static String flags() throws SQLException {
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT matricula, flag, criado_por, criado_em FROM usuario_flag " +
                 "ORDER BY matricula")) {
            while (rs.next()) {
                arr.add(Json.obj()
                    .put("matricula", rs.getString(1))
                    .put("flag", rs.getString(2))
                    .put("criadoPor", rs.getString(3))
                    .put("criadoEm", rs.getLong(4))
                    .fim());
            }
        }
        return arr.fim();
    }

    public static void flagDefinir(String matricula, String flag, String por, long agora)
            throws SQLException {
        String m = Texto.matricula(matricula);
        try (Connection c = Db.conexao()) {
            if (Texto.vazio(flag)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM usuario_flag WHERE matricula = ?")) {
                    ps.setString(1, m);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO usuario_flag (matricula,flag,criado_por,criado_em) " +
                        "VALUES (?,?,?,?) ON CONFLICT(matricula) DO UPDATE SET " +
                        "flag = excluded.flag, criado_por = excluded.criado_por, " +
                        "criado_em = excluded.criado_em")) {
                    ps.setString(1, m);
                    ps.setString(2, flag);
                    ps.setString(3, por);
                    ps.setLong(4, agora);
                    ps.executeUpdate();
                }
            }
        }
    }

    // ------------------------------------------------------------- import log

    public static void importLog(String tipo, String arquivo, int inseridos,
                                 int atualizados, int ignorados, String por, long agora)
            throws SQLException {
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO import_log (tipo,arquivo,inseridos,atualizados,ignorados," +
                "criado_por,criado_em) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, tipo);
            ps.setString(2, Texto.aparar(arquivo, 300));
            ps.setInt(3, inseridos);
            ps.setInt(4, atualizados);
            ps.setInt(5, ignorados);
            ps.setString(6, por);
            ps.setLong(7, agora);
            ps.executeUpdate();
        }
    }

    public static String importLogs() throws SQLException {
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT tipo, arquivo, inseridos, atualizados, ignorados, criado_por, " +
                 "criado_em FROM import_log ORDER BY criado_em DESC LIMIT 50")) {
            while (rs.next()) {
                arr.add(Json.obj()
                    .put("tipo", rs.getString(1))
                    .put("arquivo", rs.getString(2))
                    .put("inseridos", rs.getInt(3))
                    .put("atualizados", rs.getInt(4))
                    .put("ignorados", rs.getInt(5))
                    .put("criadoPor", rs.getString(6))
                    .put("criadoEm", rs.getLong(7))
                    .fim());
            }
        }
        return arr.fim();
    }

    // -------------------------------------------------------- dados de exemplo

    /**
     * Apaga tudo que foi semeado como exemplo: registros com origem='EXEMPLO'
     * e os de gestão criados pelo gerador (criado_por='EXEMPLO'), incluindo as
     * anotações gerais (prefixo NULL) do exemplo.
     */
    public static void limparExemplo() throws SQLException {
        try (Connection c = Db.conexao(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM funci WHERE origem = 'EXEMPLO'");
            st.executeUpdate("DELETE FROM carteira WHERE origem = 'EXEMPLO'");
            st.executeUpdate("DELETE FROM pdg WHERE origem = 'EXEMPLO'");
            st.executeUpdate("DELETE FROM meta WHERE origem = 'EXEMPLO'");
            st.executeUpdate("DELETE FROM conexao WHERE origem = 'EXEMPLO'");
            st.executeUpdate("DELETE FROM visita WHERE criado_por = 'EXEMPLO' OR prefixo IN " +
                             "(SELECT prefixo FROM agencia WHERE origem = 'EXEMPLO')");
            st.executeUpdate("DELETE FROM anotacao WHERE criado_por = 'EXEMPLO' OR prefixo IN " +
                             "(SELECT prefixo FROM agencia WHERE origem = 'EXEMPLO')");
            st.executeUpdate("DELETE FROM ponto_melhoria WHERE criado_por = 'EXEMPLO' OR prefixo IN " +
                             "(SELECT prefixo FROM agencia WHERE origem = 'EXEMPLO')");
            st.executeUpdate("DELETE FROM foto WHERE prefixo IN " +
                             "(SELECT prefixo FROM agencia WHERE origem = 'EXEMPLO')");
            st.executeUpdate("DELETE FROM agencia WHERE origem = 'EXEMPLO'");
        }
    }
}
