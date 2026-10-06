package br.com.bb.atlasestilo.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.servlet.http.HttpServletRequest;

import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Texto;

/**
 * Usuário logado: lido do objeto do SSO (atributo "usuario" na sessão) por
 * REFLEXÃO — a aplicação compila sem o JAR do BB e tolera variações do bean.
 * Perfis cumulativos: MASTER ⊃ MODERADOR ⊃ COLEGA.
 *
 * Jurisdição: Master e Moderador enxergam tudo; Colega enxerga apenas os
 * grandes números da regional do seu prefixo (resolvida pela tabela agencia).
 */
public final class Sessao {

    public static final String PERFIL_COLEGA    = "COLEGA";
    public static final String PERFIL_MODERADOR = "MODERADOR";
    public static final String PERFIL_MASTER    = "MASTER";

    public final String matricula;
    public final String nome;
    public final String prefixo;
    public final String comissao;
    public final String perfil;
    /** null = sem filtro (vê tudo); senão, regional da jurisdição do Colega. */
    public final String regionalJurisdicao;
    public final boolean somenteLeitura;

    private Sessao(String matricula, String nome, String prefixo, String comissao,
                   String perfil, String regionalJurisdicao, boolean somenteLeitura) {
        this.matricula = matricula;
        this.nome = nome;
        this.prefixo = prefixo;
        this.comissao = comissao;
        this.perfil = perfil;
        this.regionalJurisdicao = regionalJurisdicao;
        this.somenteLeitura = somenteLeitura;
    }

    public boolean master()    { return PERFIL_MASTER.equals(perfil); }
    public boolean moderador() { return PERFIL_MASTER.equals(perfil) || PERFIL_MODERADOR.equals(perfil); }

    /** Vê dados completos (pessoas, metas, visitas, anotações, pontos)? */
    public boolean veTudo() { return moderador(); }

    /** Sessão montada pelo AuthFilter e guardada no request. */
    public static Sessao de(HttpServletRequest req) {
        return (Sessao) req.getAttribute("sessao");
    }

    // ------------------------------------------------------- montagem (filtro)

    static Sessao montar(Object usuarioSso) {
        String matricula = Texto.matricula(primeiro(
                call(usuarioSso, "getChaveUsuario"),
                call(usuarioSso, "getUid"),
                call(usuarioSso, "getUsername")));
        String nome = primeiro(
                call(usuarioSso, "getNomeUsuario"),
                call(usuarioSso, "getName"),
                call(usuarioSso, "getDisplayName"),
                call(usuarioSso, "getNomeGuerra"));
        String prefixo = Texto.prefixo(primeiro(call(usuarioSso, "getPrefixo")));
        String comissao = primeiro(
                call(usuarioSso, "getNomeComissao"),
                call(usuarioSso, "getCargo"));
        return montar(matricula, nome, prefixo, comissao);
    }

    public static Sessao montar(String matricula, String nome, String prefixo, String comissao) {
        if (Texto.vazio(matricula)) return null;
        matricula = Texto.matricula(matricula);

        boolean master = false, bloqueado = false, somenteLeitura = false;
        String regional = null;
        try (Connection c = Db.conexao()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM config_master WHERE matricula = ?")) {
                ps.setString(1, matricula);
                try (ResultSet rs = ps.executeQuery()) { master = rs.next(); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT flag FROM usuario_flag WHERE matricula = ?")) {
                ps.setString(1, matricula);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String f = rs.getString(1);
                        bloqueado = "BLOQUEADO".equals(f);
                        somenteLeitura = "SOMENTE_LEITURA".equals(f);
                    }
                }
            }
            if (!master && !"9007".equals(prefixo)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT regional FROM agencia WHERE prefixo = ?")) {
                    ps.setString(1, prefixo);
                    try (ResultSet rs = ps.executeQuery()) {
                        regional = rs.next() ? rs.getString(1) : "NÃO MAPEADA";
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao montar a sessão: " + e.getMessage(), e);
        }
        if (bloqueado) return null;

        String perfil = master ? PERFIL_MASTER
                      : "9007".equals(prefixo) ? PERFIL_MODERADOR
                      : PERFIL_COLEGA;
        String jurisdicao = PERFIL_COLEGA.equals(perfil) ? regional : null;
        return new Sessao(matricula, nome == null ? matricula : nome, prefixo,
                          comissao, perfil, jurisdicao, somenteLeitura);
    }

    private static String call(Object alvo, String metodo) {
        if (alvo == null) return null;
        try {
            Object v = alvo.getClass().getMethod(metodo).invoke(alvo);
            return v == null ? null : String.valueOf(v);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String primeiro(String... valores) {
        for (String v : valores) if (!Texto.vazio(v)) return v;
        return null;
    }
}
