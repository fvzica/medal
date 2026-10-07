package br.com.bb.atlasestilo.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.web.Sessao;

/** Agências: mapa (resumo por UF + pins) e cadastro básico. */
public final class AgenciaDao {

    private AgenciaDao() { }

    /**
     * Dados do mapa: resumo por UF e um pin por agência, já respeitando a
     * jurisdição do usuário. Status de visita/fotos/pontos vai junto para o
     * mapa contar a história sem novas chamadas.
     */
    public static String mapa(Sessao s) throws SQLException {
        Selecao sel = Selecao.de(s, null, null, null, null);
        String sql =
            "SELECT a.prefixo, a.nome, a.uf, a.municipio, a.lat, a.lng, a.regional, " +
            "       a.super_regional, " +
            "       EXISTS(SELECT 1 FROM visita v WHERE v.prefixo = a.prefixo " +
            "              AND v.status = 'REALIZADA') AS visitada, " +
            "       EXISTS(SELECT 1 FROM visita v WHERE v.prefixo = a.prefixo " +
            "              AND v.status = 'PLANEJADA') AS planejada, " +
            "       EXISTS(SELECT 1 FROM foto f WHERE f.prefixo = a.prefixo AND f.restrita = 0) AS tem_foto, " +
            "       EXISTS(SELECT 1 FROM foto f WHERE f.prefixo = a.prefixo AND f.restrita = 1) AS tem_foto_restrita, " +
            "       (SELECT COUNT(*) FROM ponto_melhoria p WHERE p.prefixo = a.prefixo " +
            "              AND p.status <> 'RESOLVIDO') AS pontos_abertos, " +
            "       (SELECT COUNT(*) FROM funci f WHERE f.prefixo = a.prefixo) AS funcis " +
            "FROM agencia a WHERE " + sel.where() + " ORDER BY a.uf, a.municipio, a.nome";

        Json.Arr agencias = Json.arr();
        java.util.Map<String, int[]> porUf = new java.util.TreeMap<>();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            sel.aplicar(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                boolean master = s != null && s.master();
                while (rs.next()) {
                    // visitas, planos e ações são registros do Master: os outros perfis não os veem
                    boolean visitada = master && rs.getInt("visitada") == 1;
                    // foto restrita (de visita) só conta para quem pode vê-la
                    boolean temFoto = rs.getInt("tem_foto") == 1
                        || (master && rs.getInt("tem_foto_restrita") == 1);
                    int pontos = master ? rs.getInt("pontos_abertos") : 0;
                    String uf = rs.getString("uf");
                    agencias.add(Json.obj()
                        .put("prefixo", rs.getString("prefixo"))
                        .put("nome", rs.getString("nome"))
                        .put("uf", uf)
                        .put("municipio", rs.getString("municipio"))
                        .putNum("lat", (Double) obj(rs, "lat"))
                        .putNum("lng", (Double) obj(rs, "lng"))
                        .put("regional", rs.getString("regional"))
                        .put("visitada", visitada)
                        .put("planejada", master && rs.getInt("planejada") == 1)
                        .put("temFoto", temFoto)
                        .put("pontosAbertos", pontos)
                        .put("funcis", rs.getInt("funcis"))
                        .fim());
                    int[] acc = porUf.get(uf);
                    if (acc == null) { acc = new int[5]; porUf.put(uf, acc); }
                    acc[0]++;                       // agências
                    acc[1] += rs.getInt("funcis");  // funcis
                    if (visitada) acc[2]++;         // visitadas
                    if (!temFoto) acc[3]++;         // sem foto
                    acc[4] += pontos;               // pontos abertos
                }
            }
        }
        Json.Arr ufs = Json.arr();
        for (java.util.Map.Entry<String, int[]> e : porUf.entrySet()) {
            int[] v = e.getValue();
            ufs.add(Json.obj().put("uf", e.getKey() == null ? "" : e.getKey())
                .put("agencias", v[0]).put("funcis", v[1]).put("visitadas", v[2])
                .put("semFoto", v[3]).put("pontosAbertos", v[4]).fim());
        }
        return Json.obj()
            .putRaw("ufs", ufs.fim())
            .putRaw("agencias", agencias.fim())
            .fim();
    }

    /** Cabeçalho da agência (dados cadastrais). null se não existe/fora da jurisdição. */
    public static String cabecalho(Sessao s, String prefixo) throws SQLException {
        Selecao sel = Selecao.de(s, null, null, prefixo, null);
        String sql = "SELECT a.* FROM agencia a WHERE " + sel.where();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            sel.aplicar(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Json.obj()
                    .put("prefixo", rs.getString("prefixo"))
                    .put("nome", rs.getString("nome"))
                    .put("segmento", rs.getString("segmento"))
                    .put("uf", rs.getString("uf"))
                    .put("municipio", rs.getString("municipio"))
                    .put("endereco", rs.getString("endereco"))
                    .put("cep", rs.getString("cep"))
                    .putNum("lat", (Double) obj(rs, "lat"))
                    .putNum("lng", (Double) obj(rs, "lng"))
                    .put("regional", rs.getString("regional"))
                    .put("superRegional", rs.getString("super_regional"))
                    .put("gmapsUrl", rs.getString("gmaps_url"))
                    .fim();
            }
        }
    }

    /** Municípios de uma UF com contagens (para o drill-down do mapa). */
    public static String municipios(Sessao s, String uf) throws SQLException {
        Selecao sel = Selecao.de(s, uf, null, null, null);
        String sql =
            "SELECT a.municipio, COUNT(*) AS agencias, " +
            "       SUM((SELECT COUNT(*) FROM funci f WHERE f.prefixo = a.prefixo)) AS funcis, " +
            "       SUM(EXISTS(SELECT 1 FROM visita v WHERE v.prefixo = a.prefixo " +
            "           AND v.status = 'REALIZADA')) AS visitadas " +
            "FROM agencia a WHERE " + sel.where() +
            " GROUP BY a.municipio ORDER BY agencias DESC, a.municipio";
        Json.Arr arr = Json.arr();
        try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(sql)) {
            sel.aplicar(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(Json.obj()
                        .put("municipio", rs.getString("municipio"))
                        .put("agencias", rs.getInt("agencias"))
                        .put("funcis", rs.getInt("funcis"))
                        .put("visitadas", s != null && s.master() ? rs.getInt("visitadas") : 0)
                        .fim());
                }
            }
        }
        return arr.fim();
    }

    static Double obj(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
