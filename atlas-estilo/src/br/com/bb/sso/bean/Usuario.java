package br.com.bb.sso.bean;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Usuário autenticado pelo SSO do BB, guardado na sessão HTTP no atributo
 * "usuario" pelo {@link br.com.bb.sso.filter.FilterOauth2}.
 *
 * Implementação própria da SUPER PF1, compatível com a interface que as
 * ferramentas já leem por reflexão (getChaveUsuario, getNomeUsuario,
 * getPrefixo, getNomeComissao…). Guarda TODAS as claims devolvidas pelo
 * servidor OAuth2 (userinfo + id_token), achatadas ("dependencia.prefixo"),
 * e resolve cada getter pela primeira claim candidata presente. As listas de
 * candidatas podem ser ajustadas no oauth.properties (claim.matricula=…,
 * claim.nome=…, claim.prefixo=…, claim.comissao=…, claim.nomeGuerra=…,
 * claim.email=…), sem recompilar.
 */
public class Usuario implements Serializable {

    private static final long serialVersionUID = 1L;

    private static volatile String[] matriculaDe = {
        "chaveUsuario", "chave", "matricula", "uid", "username", "preferred_username", "sub", "login", "user_id" };
    private static volatile String[] nomeDe = {
        "nomeUsuario", "nome", "name", "displayName", "nomeCompleto", "cn", "fullName", "given_name" };
    private static volatile String[] nomeGuerraDe = {
        "nomeGuerra", "nome_guerra", "nomeguerra", "apelido", "nickname" };
    private static volatile String[] prefixoDe = {
        "prefixo", "prefixoDependencia", "prefixo_dependencia", "codigoPrefixo", "dependencia.prefixo",
        "codigoDependencia", "prefixoLotacao", "uor.prefixo", "lotacao.prefixo", "prefixoUor" };
    private static volatile String[] comissaoDe = {
        "nomeComissao", "comissao", "nome_comissao", "cargo", "funcao", "nomeCargo", "comissao.nome", "nomeFuncao" };
    private static volatile String[] emailDe = { "email", "mail", "emailUsuario" };

    private final LinkedHashMap<String, String> claims = new LinkedHashMap<>();

    public Usuario(Map<String, ?> origem) {
        if (origem != null) achatar("", origem);
    }

    /** Construtor direto (testes, simulações). */
    public Usuario(String matricula, String nome, String prefixo, String comissao) {
        if (matricula != null) claims.put("chaveUsuario", matricula);
        if (nome != null) claims.put("nomeUsuario", nome);
        if (prefixo != null) claims.put("prefixo", prefixo);
        if (comissao != null) claims.put("nomeComissao", comissao);
    }

    /**
     * Ajusta as claims candidatas de cada campo a partir das chaves
     * "claim.matricula", "claim.nome", "claim.nomeGuerra", "claim.prefixo",
     * "claim.comissao" e "claim.email" (listas separadas por vírgula).
     */
    public static void configurarMapeamento(Map<String, String> cfg) {
        if (cfg == null) return;
        String[] v;
        if ((v = lista(cfg.get("claim.matricula"))) != null) matriculaDe = v;
        if ((v = lista(cfg.get("claim.nome"))) != null) nomeDe = v;
        if ((v = lista(cfg.get("claim.nomeGuerra"))) != null) nomeGuerraDe = v;
        if ((v = lista(cfg.get("claim.prefixo"))) != null) prefixoDe = v;
        if ((v = lista(cfg.get("claim.comissao"))) != null) comissaoDe = v;
        if ((v = lista(cfg.get("claim.email"))) != null) emailDe = v;
    }

    private static String[] lista(String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] partes = csv.split(",");
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        for (String p : partes) if (!p.trim().isEmpty()) l.add(p.trim());
        return l.isEmpty() ? null : l.toArray(new String[0]);
    }

    private void achatar(String prefixo, Map<String, ?> m) {
        for (Map.Entry<String, ?> e : m.entrySet()) {
            String chave = prefixo + e.getKey();
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, ?> sub = (Map<String, ?>) v;
                achatar(chave + ".", sub);
            } else if (v instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object o : (List<?>) v) {
                    if (o == null || o instanceof Map || o instanceof List) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(String.valueOf(o));
                }
                if (sb.length() > 0) claims.put(chave, sb.toString());
            } else {
                claims.put(chave, String.valueOf(v));
            }
        }
    }

    // ---------------------------------------------------------------- acesso

    /** Claim pelo nome (exato, depois ignorando maiúsculas); null se ausente. */
    public String get(String claim) {
        if (claim == null) return null;
        String v = claims.get(claim);
        if (v != null) return v;
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (e.getKey().equalsIgnoreCase(claim)) return e.getValue();
        }
        return null;
    }

    private String primeira(String[] candidatas) {
        for (String c : candidatas) {
            String v = get(c);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** Matrícula (ex.: F3548926), em maiúsculas. */
    public String getChaveUsuario() {
        String v = primeira(matriculaDe);
        return v == null ? null : v.toUpperCase();
    }
    public String getUid()      { return getChaveUsuario(); }
    public String getUsername() { return getChaveUsuario(); }

    public String getNomeUsuario() {
        String v = primeira(nomeDe);
        if (v != null && v.equals(get("given_name")) && get("family_name") != null) {
            v = v + " " + get("family_name").trim();
        }
        return v;
    }
    public String getName()        { return getNomeUsuario(); }
    public String getDisplayName() { return getNomeUsuario(); }

    public String getNomeGuerra() { return primeira(nomeGuerraDe); }

    /** Prefixo da dependência (ex.: 9007); "9007.0" vira "9007". */
    public String getPrefixo() {
        String v = primeira(prefixoDe);
        if (v == null) return null;
        if (v.matches("\\d+\\.0+")) v = v.substring(0, v.indexOf('.'));
        return v;
    }

    public String getNomeComissao() { return primeira(comissaoDe); }
    public String getCargo()        { return getNomeComissao(); }

    public String getEmail() { return primeira(emailDe); }

    /** Todas as claims recebidas, achatadas (somente leitura). */
    public Map<String, String> getClaims() { return Collections.unmodifiableMap(claims); }

    @Override
    public String toString() {
        return "Usuario{matricula=" + getChaveUsuario() + ", nome=" + getNomeUsuario()
                + ", prefixo=" + getPrefixo() + ", comissao=" + getNomeComissao() + "}";
    }
}
