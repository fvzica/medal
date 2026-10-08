package br.com.bb.sso.filter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import br.com.bb.sso.bean.Usuario;
import br.com.bb.sso.util.JsonLeve;

/**
 * Login OAuth2 (authorization code) no SSO do BB — implementação própria da
 * SUPER PF1, com o mesmo nome de classe e o mesmo contrato das ferramentas
 * existentes: é o 1º filtro do web.xml (/*) e, ao autenticar, coloca um
 * {@link Usuario} na sessão HTTP no atributo "usuario". Quem já tem o
 * FilterOauth2 oficial do BB pode simplesmente sobrescrever esta classe (e a
 * Usuario) dentro do WAR: o resto da aplicação não muda.
 *
 * Fluxo: sem usuário na sessão → redireciona para o endpoint de autorização
 * com state aleatório guardado na sessão → o SSO volta ao redirect_uri com
 * ?code&state → troca o code por token (Basic auth; se o servidor recusar,
 * tenta client_secret no corpo) → lê as claims do userinfo (e do id_token, se
 * vier) → nova sessão com "usuario" → volta à página pedida originalmente.
 * Pedidos de API (/api/, /foto/, ou com X-Atlas/X-Requested-With) sem sessão
 * recebem 401 em JSON em vez de redirecionamento.
 *
 * Configuração: WEB-INF/classes/oauth.properties (client_id, client_secret,
 * redirect_uri, login_endpoint, scopes, cookie_sso) — cada chave pode ser
 * sobreposta por variável de ambiente OAUTH_<CHAVE> ou por system property
 * oauth.<chave>; um arquivo externo indicado por OAUTH_PROPERTIES /
 * -Doauth.properties.path também sobrepõe o do WAR (o segredo pode ficar fora
 * do WAR). Endpoints: descobertos em <login_endpoint>/…/.well-known/openid-configuration
 * ou fixados por authorize_endpoint / token_endpoint / userinfo_endpoint /
 * tokeninfo_endpoint; sem descoberta, assume o layout do OpenAM
 * (<login_endpoint>/sso/oauth2/{authorize,access_token,userinfo}).
 */
public class FilterOauth2 implements Filter {

    public static final String ATTR_USUARIO = "usuario";
    static final String ATTR_STATE   = "oauth2.state";
    static final String ATTR_DESTINO = "oauth2.destino";
    static final String ARQUIVO      = "oauth.properties";

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private ServletContext ctx;
    private Map<String, String> cfg = new LinkedHashMap<>();
    private String erroConfig;
    private int timeoutMs = 10_000;
    private boolean usarProxy, tlsIgnorar, descoberta = true;
    private String separadorScopes = " ";

    /** Endpoints resolvidos: authorize, token, userinfo, tokeninfo (null = indisponível). */
    private volatile String[] endpoints;
    private volatile long ultimaTentativaDescoberta;
    private volatile String origemEndpoints = "ainda não resolvidos";

    // ================================================================ ciclo

    @Override
    public void init(FilterConfig fc) {
        ctx = fc.getServletContext();
        try {
            cfg = carregarConfiguracao(ctx);
            erroConfig = validar(cfg);
            timeoutMs = inteiro(cfg.get("timeout_ms"), 10_000);
            usarProxy = "true".equalsIgnoreCase(cfg.get("usar_proxy"));
            tlsIgnorar = "true".equalsIgnoreCase(cfg.get("tls_ignorar_certificado"));
            descoberta = !"false".equalsIgnoreCase(cfg.get("descoberta"));
            if (cfg.get("scopes_separador") != null && !cfg.get("scopes_separador").isEmpty()) {
                separadorScopes = cfg.get("scopes_separador");
            }
            Usuario.configurarMapeamento(cfg);
            // endpoints fixados no properties dispensam a descoberta
            if (cfg.get("authorize_endpoint") != null && cfg.get("token_endpoint") != null) {
                endpoints = new String[] { cfg.get("authorize_endpoint"), cfg.get("token_endpoint"),
                        cfg.get("userinfo_endpoint"), cfg.get("tokeninfo_endpoint") };
                origemEndpoints = "fixados no " + ARQUIVO;
            }
        } catch (RuntimeException e) {
            erroConfig = "Falha ao ler o " + ARQUIVO + ": " + e.getMessage();
        }
        log(erroConfig != null ? "CONFIGURAÇÃO INVÁLIDA: " + erroConfig
                : "pronto. client_id=" + cfg.get("client_id") + " redirect_uri=" + cfg.get("redirect_uri")
                  + " login_endpoint=" + cfg.get("login_endpoint") + " scopes=" + cfg.get("scopes")
                  + (tlsIgnorar ? " (TLS sem validação de certificado!)" : ""));
    }

    @Override
    public void destroy() { }

    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpServletResponse resp = (HttpServletResponse) sresp;
        if (req.getCharacterEncoding() == null) req.setCharacterEncoding("UTF-8");
        String caminho = req.getRequestURI().substring(req.getContextPath().length());

        HttpSession sessao = req.getSession(false);
        Object usuario = sessao == null ? null : sessao.getAttribute(ATTR_USUARIO);
        if (usuario != null) {
            if ("/sso/diagnostico".equals(caminho)) { diagnostico(resp); return; }
            if ("GET".equalsIgnoreCase(req.getMethod()) && req.getParameter("code") != null
                    && req.getParameter("state") != null) {
                // sobra de um retorno repetido do SSO: limpa a URL
                resp.sendRedirect(req.getContextPath() + "/");
                return;
            }
            chain.doFilter(sreq, sresp);
            return;
        }

        if (erroConfig != null) {
            paginaErro(resp, 500, "SSO não configurado", erroConfig, Arrays.asList(
                    "Confira WEB-INF/classes/" + ARQUIVO + " dentro do WAR (ou o arquivo externo apontado por OAUTH_PROPERTIES).",
                    "Chaves obrigatórias: client_id, client_secret, redirect_uri, login_endpoint."));
            return;
        }

        String state = req.getParameter("state");
        String erroSso = req.getParameter("error");
        if (erroSso != null && state != null && stateConfere(sessao, state)) {
            String desc = req.getParameter("error_description");
            paginaErro(resp, 502, "O login do BB devolveu um erro",
                    erroSso + (desc == null ? "" : ": " + desc), dicasPara(erroSso));
            return;
        }
        String code = req.getParameter("code");
        if (code != null && state != null) {
            tratarRetorno(req, resp, sessao, code, state);
            return;
        }

        if (ehApi(req, caminho) || !"GET".equalsIgnoreCase(req.getMethod()) && !"HEAD".equalsIgnoreCase(req.getMethod())) {
            json401(resp);
            return;
        }
        iniciarLogin(req, resp, caminho);
    }

    // =============================================================== etapas

    private void iniciarLogin(HttpServletRequest req, HttpServletResponse resp, String caminho) throws IOException {
        String[] ep;
        try {
            ep = resolverEndpoints();
        } catch (SsoException e) {
            paginaErro(resp, e.status, e.titulo, e.detalhe, e.dicas);
            return;
        }
        HttpSession s = req.getSession(true);
        String state = novoState();
        s.setAttribute(ATTR_STATE, state);
        String qs = req.getQueryString();
        String destino = req.getRequestURI() + (qs == null ? "" : "?" + qs);
        if ("/sso/diagnostico".equals(caminho)) destino = req.getContextPath() + "/";
        s.setAttribute(ATTR_DESTINO, destino);

        String url = urlAutorizacao(ep[0], cfg, separadorScopes, state);
        resp.setHeader("Cache-Control", "no-store");
        resp.sendRedirect(url);
    }

    private void tratarRetorno(HttpServletRequest req, HttpServletResponse resp, HttpSession sessao,
                               String code, String state) throws IOException {
        if (!stateConfere(sessao, state)) {
            paginaErro(resp, 403, "Não foi possível concluir o login",
                    "O parâmetro state devolvido pelo SSO não corresponde ao desta sessão"
                    + (sessao == null ? " (a sessão não existe mais)." : "."),
                    Arrays.asList(
                        "Acesse a ferramenta exatamente pelo endereço registrado como redirect_uri ("
                            + cfg.get("redirect_uri") + "): outro host ou porta cria outra sessão e o state não bate.",
                        "O navegador precisa aceitar o cookie de sessão (JSESSIONID) deste site.",
                        "Se o login demorou muito, a sessão pode ter expirado: tente de novo."));
            return;
        }
        String destino = (String) sessao.getAttribute(ATTR_DESTINO);
        try {
            String[] ep = resolverEndpoints();
            Map<String, Object> tokens = trocarCodigo(ep[1], code);
            Map<String, Object> claims = obterClaims(ep, tokens);
            Usuario u = new Usuario(claims);
            if (u.getChaveUsuario() == null) {
                throw new SsoException(502, "O SSO autenticou, mas não informou a matrícula",
                        "Claims recebidas: " + chavesDe(claims),
                        Arrays.asList(
                            "Confira os scopes no " + ARQUIVO + " (hoje: " + cfg.get("scopes") + ").",
                            "Se a matrícula vier com outro nome de claim, informe-o em claim.matricula=<nome> no " + ARQUIVO + "."));
            }
            // nova sessão (evita fixação) só com o usuário
            sessao.invalidate();
            HttpSession nova = req.getSession(true);
            nova.setAttribute(ATTR_USUARIO, u);
            log("login de " + u.getChaveUsuario() + " (prefixo " + u.getPrefixo() + ")");
            String ctxPath = req.getContextPath();
            if (destino == null || !destino.startsWith(ctxPath) || destino.contains("code=")) destino = ctxPath + "/";
            resp.setHeader("Cache-Control", "no-store");
            resp.sendRedirect(destino);
        } catch (SsoException e) {
            log("falha no retorno do SSO: " + e.titulo + " — " + e.detalhe);
            paginaErro(resp, e.status, e.titulo, e.detalhe, e.dicas);
        }
    }

    private static boolean stateConfere(HttpSession sessao, String state) {
        if (sessao == null || state == null) return false;
        Object esperado = sessao.getAttribute(ATTR_STATE);
        return esperado != null && esperado.equals(state);
    }

    /** POST no token endpoint: Basic auth e, se recusado, client_secret no corpo. */
    private Map<String, Object> trocarCodigo(String tokenEndpoint, String code) {
        String corpo = "grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(cfg.get("redirect_uri"));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (enc(cfg.get("client_id")) + ":" + enc(cfg.get("client_secret"))).getBytes(StandardCharsets.UTF_8)));
        Resposta r = http("POST", tokenEndpoint, h, corpo);
        if (r.status == 401 || (r.status == 400 && r.corpo.contains("invalid_client"))) {
            h.remove("Authorization");
            r = http("POST", tokenEndpoint, h, corpo + "&client_id=" + enc(cfg.get("client_id"))
                    + "&client_secret=" + enc(cfg.get("client_secret")));
        }
        if (r.status != 200) {
            throw new SsoException(502, "A troca do código pelo token falhou",
                    "HTTP " + r.status + " em " + tokenEndpoint + ": " + resumo(r.corpo),
                    Arrays.asList(
                        "redirect_uri_mismatch / invalid_grant: o redirect_uri do " + ARQUIVO + " (" + cfg.get("redirect_uri")
                            + ") precisa ser idêntico ao registrado para o client_id " + cfg.get("client_id") + ".",
                        "invalid_client: client_id ou client_secret incorretos.",
                        "404: o token_endpoint não é este; informe token_endpoint=… no " + ARQUIVO
                            + " (endpoints atuais: " + origemEndpoints + ")."));
        }
        Map<String, Object> tokens;
        try {
            tokens = JsonLeve.lerObjeto(r.corpo);
        } catch (IllegalArgumentException e) {
            throw new SsoException(502, "O token endpoint não devolveu JSON", resumo(r.corpo), null);
        }
        if (tokens.get("access_token") == null) {
            throw new SsoException(502, "O token endpoint não devolveu access_token", resumo(r.corpo), null);
        }
        return tokens;
    }

    /** Claims do id_token (se houver) + userinfo (prevalece) + tokeninfo (reserva). */
    private Map<String, Object> obterClaims(String[] ep, Map<String, Object> tokens) {
        Map<String, Object> claims = new LinkedHashMap<>();
        Object idToken = tokens.get("id_token");
        if (idToken instanceof String) {
            try { claims.putAll(decodificarJwt((String) idToken)); }
            catch (RuntimeException e) { log("id_token ilegível: " + e.getMessage()); }
        }
        String accessToken = String.valueOf(tokens.get("access_token"));
        String falhaUserinfo = null;
        if (ep[2] != null) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Accept", "application/json");
            h.put("Authorization", "Bearer " + accessToken);
            Resposta r = http("GET", ep[2], h, null);
            if (r.status == 200) {
                try { claims.putAll(JsonLeve.lerObjeto(r.corpo)); }
                catch (IllegalArgumentException e) { falhaUserinfo = "userinfo não devolveu JSON: " + resumo(r.corpo); }
            } else {
                falhaUserinfo = "userinfo respondeu HTTP " + r.status + ": " + resumo(r.corpo);
            }
        }
        if (new Usuario(claims).getChaveUsuario() == null && ep[3] != null) {
            Resposta r = http("GET", ep[3] + (ep[3].contains("?") ? "&" : "?") + "access_token=" + enc(accessToken),
                    mapa("Accept", "application/json"), null);
            if (r.status == 200) {
                try { claims.putAll(JsonLeve.lerObjeto(r.corpo)); } catch (IllegalArgumentException ignorado) { }
            }
        }
        if (claims.isEmpty() && accessToken.chars().filter(c -> c == '.').count() == 2) {
            try { claims.putAll(decodificarJwt(accessToken)); } catch (RuntimeException ignorado) { }
        }
        if (claims.isEmpty()) {
            throw new SsoException(502, "O SSO não devolveu os dados do usuário",
                    falhaUserinfo == null ? "Nenhuma claim no id_token nem no userinfo." : falhaUserinfo,
                    Arrays.asList("Confira userinfo_endpoint no " + ARQUIVO + " (endpoints atuais: " + origemEndpoints + ").",
                                  "Confira os scopes (hoje: " + cfg.get("scopes") + ")."));
        }
        if (falhaUserinfo != null) log(falhaUserinfo + " — seguindo com as claims do id_token");
        return claims;
    }

    // ============================================================ endpoints

    /** authorize, token, userinfo, tokeninfo — via discovery (cacheado) ou padrão OpenAM. */
    private String[] resolverEndpoints() {
        String[] ep = endpoints;
        if (ep != null) return ep;
        synchronized (this) {
            if (endpoints != null) return endpoints;
            String login = semBarraFinal(cfg.get("login_endpoint"));
            if (descoberta && System.currentTimeMillis() - ultimaTentativaDescoberta > 60_000L) {
                ultimaTentativaDescoberta = System.currentTimeMillis();
                for (String url : candidatosDescoberta(login)) {
                    try {
                        Resposta r = http("GET", url, mapa("Accept", "application/json"), null);
                        if (r.status != 200) continue;
                        Map<String, Object> d = JsonLeve.lerObjeto(r.corpo);
                        String a = JsonLeve.texto(d, "authorization_endpoint"), t = JsonLeve.texto(d, "token_endpoint");
                        if (a == null || t == null) continue;
                        endpoints = new String[] { a, t, JsonLeve.texto(d, "userinfo_endpoint"),
                                cfg.get("tokeninfo_endpoint") != null ? cfg.get("tokeninfo_endpoint") : padrao(login)[3] };
                        origemEndpoints = "descobertos em " + url;
                        log("endpoints " + origemEndpoints + ": " + Arrays.toString(endpoints));
                        return endpoints;
                    } catch (RuntimeException e) {
                        log("descoberta em " + url + " falhou: " + e.getMessage());
                    }
                }
            }
            // sem discovery: padrão OpenAM, com sobreposição individual pelo properties
            String[] p = padrao(login);
            for (int i = 0; i < 4; i++) {
                String chave = new String[] { "authorize_endpoint", "token_endpoint", "userinfo_endpoint", "tokeninfo_endpoint" }[i];
                if (cfg.get(chave) != null && !cfg.get(chave).isEmpty()) p[i] = cfg.get(chave);
            }
            endpoints = p;
            origemEndpoints = "padrão OpenAM sob " + login + " (discovery indisponível)";
            log("endpoints " + origemEndpoints + ": " + Arrays.toString(endpoints));
            return endpoints;
        }
    }

    public static String[] padrao(String login) {
        return new String[] { login + "/sso/oauth2/authorize", login + "/sso/oauth2/access_token",
                login + "/sso/oauth2/userinfo", login + "/sso/oauth2/tokeninfo" };
    }

    public static List<String> candidatosDescoberta(String login) {
        return Arrays.asList(
                login + "/sso/oauth2/.well-known/openid-configuration",
                login + "/.well-known/openid-configuration",
                login + "/oauth2/.well-known/openid-configuration",
                login + "/openam/oauth2/.well-known/openid-configuration",
                login + "/auth/.well-known/openid-configuration");
    }

    // ========================================================= configuração

    /** oauth.properties do WAR, sobreposto por arquivo externo, system properties e ambiente. */
    public static Map<String, String> carregarConfiguracao(ServletContext ctx) {
        Properties p = new Properties();
        InputStream in = null;
        try {
            if (ctx != null) in = ctx.getResourceAsStream("/WEB-INF/classes/" + ARQUIVO);
            if (in == null) in = FilterOauth2.class.getClassLoader().getResourceAsStream(ARQUIVO);
            if (in != null) { try { p.load(in); } finally { in.close(); } }
        } catch (IOException e) {
            throw new IllegalStateException("não consegui ler " + ARQUIVO + ": " + e.getMessage(), e);
        }
        String externo = valorAmbiente("properties.path");
        if (externo != null && !externo.trim().isEmpty()) {
            File f = new File(externo.trim());
            if (f.isFile()) {
                try (InputStream fin = new FileInputStream(f)) { p.load(fin); }
                catch (IOException e) { throw new IllegalStateException("não consegui ler " + f + ": " + e.getMessage(), e); }
            }
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String nome : p.stringPropertyNames()) m.put(nome, p.getProperty(nome).trim());
        for (String chave : new ArrayList<>(m.keySet())) {
            String v = valorAmbiente(chave);
            if (v != null) m.put(chave, v.trim());
        }
        for (String chave : new String[] { "client_id", "client_secret", "redirect_uri", "login_endpoint", "scopes", "cookie_sso",
                "authorize_endpoint", "token_endpoint", "userinfo_endpoint", "tokeninfo_endpoint", "descoberta",
                "usar_proxy", "tls_ignorar_certificado", "timeout_ms", "scopes_separador",
                "claim.matricula", "claim.nome", "claim.nomeGuerra", "claim.prefixo", "claim.comissao", "claim.email" }) {
            String v = valorAmbiente(chave);
            if (v != null) m.put(chave, v.trim());
        }
        return m;
    }

    /** -Doauth.<chave> ou variável OAUTH_<CHAVE> (pontos viram sublinhado). */
    static String valorAmbiente(String chave) {
        String v = System.getProperty("oauth." + chave);
        if (v == null) v = System.getenv("OAUTH_" + chave.toUpperCase().replace('.', '_'));
        return v;
    }

    public static String validar(Map<String, String> m) {
        List<String> faltam = new ArrayList<>();
        for (String k : new String[] { "client_id", "client_secret", "redirect_uri", "login_endpoint" }) {
            if (m.get(k) == null || m.get(k).isEmpty()) faltam.add(k);
        }
        if (!faltam.isEmpty()) return "faltam no " + ARQUIVO + ": " + faltam;
        if (m.get("client_secret").startsWith("TROQUE_AQUI")) {
            return "client_secret ainda é o valor de exemplo (TROQUE_AQUI…); informe o segredo real do client "
                    + m.get("client_id") + " no " + ARQUIVO + " (ou em OAUTH_CLIENT_SECRET / -Doauth.client_secret)";
        }
        if (!m.get("login_endpoint").startsWith("http")) return "login_endpoint precisa começar com http(s)://";
        return null;
    }

    public static String urlAutorizacao(String authorizeEndpoint, Map<String, String> cfg, String separador, String state) {
        String scopes = cfg.get("scopes") == null ? "" : cfg.get("scopes");
        StringBuilder sc = new StringBuilder();
        for (String s : scopes.split("[,\\s]+")) {
            if (s.isEmpty()) continue;
            if (sc.length() > 0) sc.append(separador);
            sc.append(s);
        }
        return authorizeEndpoint + (authorizeEndpoint.contains("?") ? "&" : "?")
                + "response_type=code&client_id=" + enc(cfg.get("client_id"))
                + "&redirect_uri=" + enc(cfg.get("redirect_uri"))
                + (sc.length() > 0 ? "&scope=" + enc(sc.toString()) : "")
                + "&state=" + enc(state);
    }

    public static String novoState() {
        byte[] b = new byte[16];
        ALEATORIO.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Pedido que não é navegação de página: responde 401 JSON em vez de redirecionar. */
    static boolean ehApi(HttpServletRequest req, String caminho) {
        if (caminho.startsWith("/api/") || caminho.startsWith("/foto/")) return true;
        if (req.getHeader("X-Atlas") != null || req.getHeader("X-Requested-With") != null) return true;
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains("application/json") && !accept.contains("text/html");
    }

    /** Payload de um JWT (base64url), sem validar assinatura: veio direto do token endpoint por TLS. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodificarJwt(String jwt) {
        String[] partes = jwt.split("\\.");
        if (partes.length < 2) throw new IllegalArgumentException("não é um JWT");
        byte[] b = Base64.getUrlDecoder().decode(partes[1]);
        Object o = JsonLeve.ler(new String(b, StandardCharsets.UTF_8));
        if (!(o instanceof Map)) throw new IllegalArgumentException("payload do JWT não é um objeto");
        return (Map<String, Object>) o;
    }

    // ================================================================= http

    static final class Resposta {
        final int status; final String corpo;
        Resposta(int status, String corpo) { this.status = status; this.corpo = corpo == null ? "" : corpo; }
    }

    private Resposta http(String metodo, String url, Map<String, String> cabecalhos, String corpo) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) (usarProxy ? u.openConnection() : u.openConnection(Proxy.NO_PROXY));
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(metodo);
            c.setRequestProperty("User-Agent", "AtlasEstilo-SSO/1.0");
            if (tlsIgnorar && c instanceof HttpsURLConnection) semValidacaoTls((HttpsURLConnection) c);
            if (cabecalhos != null) for (Map.Entry<String, String> e : cabecalhos.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (corpo != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(corpo.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Resposta(status, in == null ? "" : lerTudo(in));
        } catch (javax.net.ssl.SSLException e) {
            throw new SsoException(502, "Falha de TLS ao falar com o SSO",
                    url + ": " + e.getMessage(),
                    Arrays.asList("A JRE do Tomcat não confia no certificado de " + url + ". Importe a CA do BB no cacerts da JRE "
                            + "(keytool -importcert -keystore <JRE>/lib/security/cacerts -alias bb-ca -file ca.cer)",
                            "Só na intranet e como medida provisória: tls_ignorar_certificado=true no " + ARQUIVO + "."));
        } catch (IOException e) {
            throw new SsoException(502, "Não consegui falar com o servidor do SSO",
                    metodo + " " + url + ": " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " — " + e.getMessage()),
                    Arrays.asList("O servidor do Tomcat precisa alcançar " + url + " (firewall/proxy). usar_proxy=true no " + ARQUIVO
                            + " faz a chamada passar pelo proxy configurado na JVM.",
                            "Se o endpoint não existir (404), informe-o no " + ARQUIVO + " (endpoints atuais: " + origemEndpoints + ")."));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String lerTudo(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void semValidacaoTls(HttpsURLConnection c) {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] x, String a) { }
                public void checkServerTrusted(X509Certificate[] x, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new SecureRandom());
            c.setSSLSocketFactory(sc.getSocketFactory());
            c.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String h, SSLSession s) { return true; }
            });
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ============================================================ respostas

    private void json401(HttpServletResponse resp) throws IOException {
        resp.setStatus(401);
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"erro\":\"Sessão expirada. Recarregue a página para entrar de novo.\",\"login\":true}");
    }

    private void paginaErro(HttpServletResponse resp, int status, String titulo, String detalhe, List<String> dicas)
            throws IOException {
        resp.setStatus(status);
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter w = resp.getWriter();
        w.write("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><title>Login · SSO</title>"
                + "<style>body{font-family:Segoe UI,Arial,sans-serif;background:#f4f1ea;color:#1b2434;margin:0;padding:48px 16px}"
                + ".c{max-width:720px;margin:0 auto;background:#fff;border:1px solid #ddd6c6;border-radius:12px;padding:28px 32px}"
                + "h1{font-size:20px;margin:0 0 8px}p{line-height:1.5}code{background:#f0ece2;padding:1px 5px;border-radius:4px;word-break:break-all}"
                + "ul{padding-left:20px}li{margin:6px 0}a.b{display:inline-block;margin-top:14px;padding:9px 16px;border-radius:8px;"
                + "background:#1b2434;color:#fff;text-decoration:none}</style></head><body><div class=\"c\">"
                + "<h1>" + esc(titulo) + "</h1><p><code>" + esc(detalhe) + "</code></p>");
        if (dicas != null && !dicas.isEmpty()) {
            w.write("<p>O que conferir:</p><ul>");
            for (String d : dicas) w.write("<li>" + esc(d) + "</li>");
            w.write("</ul>");
        }
        w.write("<a class=\"b\" href=\"" + esc(ctx.getContextPath() + "/") + "\">Tentar de novo</a>"
                + "<p style=\"color:#6b7280;font-size:12px\">Mensagem gerada pelo filtro de login (FilterOauth2) desta ferramenta.</p>"
                + "</div></body></html>");
    }

    private void diagnostico(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        String[] ep = endpoints;
        PrintWriter w = resp.getWriter();
        w.println("SSO OAuth2 — diagnóstico (sem segredos)");
        w.println("client_id      = " + cfg.get("client_id"));
        w.println("redirect_uri   = " + cfg.get("redirect_uri"));
        w.println("login_endpoint = " + cfg.get("login_endpoint"));
        w.println("scopes         = " + cfg.get("scopes") + "  (separador '" + separadorScopes + "')");
        w.println("descoberta     = " + descoberta + "   usar_proxy = " + usarProxy + "   tls_ignorar_certificado = " + tlsIgnorar);
        w.println("endpoints      : " + origemEndpoints);
        if (ep != null) {
            w.println("  authorize = " + ep[0]);
            w.println("  token     = " + ep[1]);
            w.println("  userinfo  = " + ep[2]);
            w.println("  tokeninfo = " + ep[3]);
        }
        w.println("claims aceitas para a matrícula: configuráveis por claim.matricula=… no " + ARQUIVO);
    }

    // ============================================================ utilidades

    static final class SsoException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status; final String titulo; final String detalhe; final transient List<String> dicas;
        SsoException(int status, String titulo, String detalhe, List<String> dicas) {
            super(titulo + ": " + detalhe);
            this.status = status; this.titulo = titulo; this.detalhe = detalhe; this.dicas = dicas;
        }
    }

    private void log(String msg) {
        if (ctx != null) ctx.log("[SSO] " + msg); else System.err.println("[SSO] " + msg);
    }

    private static List<String> dicasPara(String erro) {
        if (erro == null) return null;
        if (erro.contains("redirect_uri")) return Arrays.asList(
                "Peça à equipe do SSO/BB o registro do redirect_uri exatamente como está no oauth.properties (sem barra final).");
        if (erro.contains("invalid_client")) return Arrays.asList("client_id ou client_secret incorretos no oauth.properties.");
        if (erro.contains("invalid_scope")) return Arrays.asList("Algum scope não é aceito por este client; ajuste scopes= no oauth.properties.");
        if (erro.contains("access_denied")) return Arrays.asList("O usuário negou o acesso ou não tem permissão neste client.");
        return null;
    }

    private static Map<String, String> mapa(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static String chavesDe(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            String v = String.valueOf(e.getValue());
            if (e.getKey().toLowerCase().contains("token")) v = "…";
            sb.append(e.getKey()).append('=').append(v.length() > 60 ? v.substring(0, 60) + "…" : v);
        }
        return sb.length() == 0 ? "(nenhuma)" : sb.toString();
    }

    private static String resumo(String corpo) {
        String t = corpo == null ? "" : corpo.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "(resposta vazia)";
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String semBarraFinal(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static int inteiro(String s, int padrao) {
        try { return s == null || s.isEmpty() ? padrao : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return padrao; }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
