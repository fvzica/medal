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
        String sql = "SELECT id, prefixo, matricula, tipo, legenda, origem, criado_em, visita_id, restrita, ponto_id, momento " +
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
        String sql = "SELECT id, prefixo, matricula, tipo, legenda, origem, criado_em, visita_id, restrita, ponto_id, momento " +
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
        long pid = rs.getLong("ponto_id");
        boolean semPonto = rs.wasNull();
        return Json.obj()
            .put("id", rs.getString("id"))
            .put("prefixo", rs.getString("prefixo"))
            .put("matricula", rs.getString("matricula"))
            .put("tipo", rs.getString("tipo"))
            .put("legenda", rs.getString("legenda"))
            .put("origem", rs.getString("origem"))
            .put("criadoEm", rs.getLong("criado_em"))
            .putNum("visitaId", semVisita ? null : vid)
            .putNum("pontoId", semPonto ? null : pid)
            .put("momento", rs.getString("momento"))
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
        inserir(id, prefixo, matricula, tipo, legenda, arquivo, mime, por, agora, visitaId, restrita, null, null);
    }

    /**
     * Inserção completa. Foto de ação (pontoId + momento ANTES/DEPOIS) é sempre
     * restrita ao Master, como a de visita.
     */
    public static void inserir(String id, String prefixo, String matricula, String tipo,
                               String legenda, String arquivo, String mime, String por,
                               long agora, Long visitaId, boolean restrita,
                               Long pontoId, String momento) throws SQLException {
        String sql = "INSERT INTO foto (id,prefixo,matricula,tipo,legenda,arquivo,mime," +
                     "origem,criado_por,criado_em,visita_id,restrita,ponto_id,momento) " +
                     "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, prefixo);
            ps.setString(3, Texto.vazio(matricula) ? null : Texto.matricula(matricula));
            ps.setString(4, tipo);
            ps.setString(5, Texto.aparar(legenda, 500));
            ps.setString(6, arquivo);
            ps.setString(7, mime);
            ps.setString(8, pontoId != null ? "ACAO" : visitaId == null ? "ADMIN" : "VISITA");
            ps.setString(9, por);
            ps.setLong(10, agora);
            if (visitaId == null) ps.setNull(11, java.sql.Types.BIGINT); else ps.setLong(11, visitaId);
            ps.setInt(12, restrita || pontoId != null ? 1 : 0);
            if (pontoId == null) ps.setNull(13, java.sql.Types.BIGINT); else ps.setLong(13, pontoId);
            ps.setString(14, pontoId == null ? null : "DEPOIS".equals(momento) ? "DEPOIS" : "ANTES");
            ps.executeUpdate();
        }
    }

    /** Fotos (antes/depois) de um conjunto de ações: id da ação -> [idFoto, momento, legenda]. */
    public static java.util.Map<Long, java.util.List<String[]>> dasAcoes(java.util.Collection<Long> ids)
            throws SQLException {
        java.util.Map<Long, java.util.List<String[]>> mapa = new java.util.HashMap<>();
        if (ids.isEmpty()) return mapa;
        java.util.List<Long> lista = new java.util.ArrayList<>(ids);
        try (Connection c = Db.conexao()) {
            for (int ini = 0; ini < lista.size(); ini += 400) {
                java.util.List<Long> parte = lista.subList(ini, Math.min(lista.size(), ini + 400));
                StringBuilder sql = new StringBuilder(
                    "SELECT id, ponto_id, momento, legenda FROM foto WHERE ponto_id IN (");
                for (int i = 0; i < parte.size(); i++) sql.append(i == 0 ? "?" : ",?");
                sql.append(") ORDER BY criado_em");
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    for (int i = 0; i < parte.size(); i++) ps.setLong(i + 1, parte.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long pid = rs.getLong("ponto_id");
                            java.util.List<String[]> l = mapa.get(pid);
                            if (l == null) { l = new java.util.ArrayList<>(); mapa.put(pid, l); }
                            l.add(new String[] { rs.getString("id"), rs.getString("momento"), rs.getString("legenda") });
                        }
                    }
                }
            }
        }
        return mapa;
    }

    /** Remove as fotos de uma ação e devolve os arquivos físicos a apagar. */
    public static java.util.List<String> excluirDaAcao(long pontoId) throws SQLException {
        java.util.List<String> arquivos = new java.util.ArrayList<>();
        try (Connection c = Db.conexao()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT arquivo FROM foto WHERE ponto_id = ?")) {
                ps.setLong(1, pontoId);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) arquivos.add(rs.getString(1)); }
            }
            if (!arquivos.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM foto WHERE ponto_id = ?")) {
                    ps.setLong(1, pontoId);
                    ps.executeUpdate();
                }
            }
        }
        return arquivos;
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
