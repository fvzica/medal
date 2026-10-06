package br.com.bb.atlasestilo.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Acesso ao SQLite: conexão por operação (sem pool), WAL + busy_timeout.
 * Os DAOs isolam o SQL para permitir troca futura por DB2 (mexer aqui,
 * no dialeto do schema e nos pontos de ON CONFLICT).
 */
public final class Db {

    private static volatile String url;

    private Db() { }

    public static void iniciar(String caminhoDb) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Driver SQLite ausente do WAR (WEB-INF/lib).", e);
        }
        url = "jdbc:sqlite:" + caminhoDb;
        try (Connection c = conexao(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao abrir o banco em " + caminhoDb, e);
        }
    }

    public static Connection conexao() throws SQLException {
        if (url == null) throw new IllegalStateException("Db não inicializado (AppListener).");
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
        }
        return c;
    }
}
