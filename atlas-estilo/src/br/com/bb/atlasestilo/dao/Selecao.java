package br.com.bb.atlasestilo.dao;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import br.com.bb.atlasestilo.util.Texto;
import br.com.bb.atlasestilo.web.Sessao;

/**
 * Seleção de região para as agregações: combina a jurisdição do usuário
 * (Colega só vê a própria regional) com os filtros pedidos pela tela
 * (UF, município, lista de prefixos, regional). Gera a cláusula sobre a
 * tabela agencia (alias "a") sempre com parâmetros preparados.
 */
public final class Selecao {

    private final List<String> conds = new ArrayList<>();
    private final List<String> params = new ArrayList<>();

    public static Selecao de(Sessao s, String uf, String municipio,
                             String prefixosCsv, String regional) {
        Selecao sel = new Selecao();
        if (s != null && s.regionalJurisdicao != null) {
            sel.conds.add("a.regional = ?");
            sel.params.add(s.regionalJurisdicao);
        }
        if (!Texto.vazio(uf)) {
            sel.conds.add("a.uf = ?");
            sel.params.add(Texto.normalizar(uf));
        }
        if (!Texto.vazio(municipio)) {
            // comparação exata: o front manda o nome exatamente como está no
            // banco (vem de /api/municipios); UPPER do SQLite não cobre acento
            sel.conds.add("a.municipio = ?");
            sel.params.add(municipio.trim());
        }
        if (!Texto.vazio(regional)) {
            sel.conds.add("a.regional = ?");
            sel.params.add(regional.trim());
        }
        if (!Texto.vazio(prefixosCsv)) {
            List<String> prefixos = new ArrayList<>();
            for (String p : prefixosCsv.split(",")) {
                String canon = Texto.prefixo(p);
                if (!canon.isEmpty()) prefixos.add(canon);
            }
            if (!prefixos.isEmpty()) {
                StringBuilder in = new StringBuilder("a.prefixo IN (");
                for (int i = 0; i < prefixos.size(); i++) {
                    in.append(i == 0 ? "?" : ",?");
                    params_(sel, prefixos.get(i));
                }
                in.append(')');
                sel.conds.add(in.toString());
            }
        }
        return sel;
    }

    private static void params_(Selecao sel, String v) { sel.params.add(v); }

    /** Cláusula WHERE (sem a palavra WHERE), sempre não vazia. */
    public String where() {
        return conds.isEmpty() ? "1=1" : String.join(" AND ", conds);
    }

    /** Aplica os parâmetros a partir da posição indicada (1-based). */
    public int aplicar(PreparedStatement ps, int posInicial) throws SQLException {
        int pos = posInicial;
        for (String p : params) ps.setString(pos++, p);
        return pos;
    }

    public int qtdParams() { return params.size(); }
}
