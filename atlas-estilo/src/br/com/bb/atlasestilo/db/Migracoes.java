package br.com.bb.atlasestilo.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * Evoluções de schema que o CREATE TABLE IF NOT EXISTS não cobre: colunas
 * novas em tabelas que já existem em bancos de produção. Idempotente — roda
 * em toda subida depois do schema.sql (SQLite 3.36 não tem ADD COLUMN IF NOT
 * EXISTS, então conferimos o PRAGMA table_info antes).
 */
public final class Migracoes {

    private Migracoes() { }

    private static final String[][] COLUNAS = {
        // tabela, coluna, definição
        // -- checklist da visita (notas de 1 a 5, movimento, claros, nota geral...)
        { "visita", "ambiencia",     "INTEGER" },
        { "visita", "atendimento",   "INTEGER" },
        { "visita", "organizacao",   "INTEGER" },
        { "visita", "equipe",        "INTEGER" },
        { "visita", "movimento",     "TEXT" },
        { "visita", "claros",        "INTEGER" },
        { "visita", "nota_geral",    "REAL" },
        { "visita", "melhorias",     "TEXT" },
        { "visita", "percepcao",     "TEXT" },
        { "visita", "checklist_em",  "INTEGER" },
        // -- fotos ligadas a uma visita e restritas ao Master
        { "foto", "visita_id",       "INTEGER" },
        { "foto", "restrita",        "INTEGER NOT NULL DEFAULT 0" },
        // -- pontos de melhoria viram ações com dono, prioridade e origem
        { "ponto_melhoria", "visita_id",   "INTEGER" },
        { "ponto_melhoria", "responsavel", "TEXT" },
        { "ponto_melhoria", "prioridade",  "TEXT NOT NULL DEFAULT 'MEDIA'" },
        { "ponto_melhoria", "tipo",        "TEXT NOT NULL DEFAULT 'ACAO'" },
        // -- cadência de cobrança e fechamento comprovado
        { "ponto_melhoria", "proxima_cobranca_em",  "INTEGER" },
        { "ponto_melhoria", "informado_em",         "INTEGER" },
        { "ponto_melhoria", "verificado_em",        "INTEGER" },
        { "ponto_melhoria", "verificado_visita_id", "INTEGER" },
        { "ponto_melhoria", "reaberturas",          "INTEGER NOT NULL DEFAULT 0" },
        // -- linha do tempo: retorno do responsável, cobrança do Master, mudança de status, conferência
        { "acao_atualizacao", "tipo", "TEXT NOT NULL DEFAULT 'RETORNO'" },
        // -- fotos de ação (antes/depois), também restritas
        { "foto", "ponto_id", "INTEGER" },
        { "foto", "momento",  "TEXT" },
    };

    public static void aplicar() throws SQLException {
        try (Connection c = Db.conexao(); Statement st = c.createStatement()) {
            String tabelaAtual = null;
            Set<String> existentes = null;
            for (String[] col : COLUNAS) {
                if (!col[0].equals(tabelaAtual)) {
                    tabelaAtual = col[0];
                    existentes = colunasDe(st, tabelaAtual);
                }
                if (existentes.contains(col[1].toLowerCase())) continue;
                st.executeUpdate("ALTER TABLE " + col[0] + " ADD COLUMN " + col[1] + " " + col[2]);
                existentes.add(col[1].toLowerCase());
            }
        }
    }

    private static Set<String> colunasDe(Statement st, String tabela) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabela + ")")) {
            while (rs.next()) cols.add(rs.getString("name").toLowerCase());
        }
        return cols;
    }
}
