package br.com.bb.atlasestilo.web;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import br.com.bb.atlasestilo.util.Http;

/**
 * Porteiro da aplicação (2º filtro, depois do FilterOauth2 do BB): lê o
 * Usuario da sessão, monta a Sessao (perfil + jurisdição), recusa BLOQUEADO
 * e barra escrita de quem tem SOMENTE_LEITURA. Autorização fina é revalidada
 * em cada endpoint (ApiServlet) — esconder botão no front não é proteção.
 */
public class AuthFilter implements Filter {

    private boolean devSimular;

    @Override
    public void init(FilterConfig cfg) {
        Object dev = cfg.getServletContext().getAttribute(AppListener.ATTR_DEV_SIMULAR);
        devSimular = Boolean.TRUE.equals(dev);
    }

    /** Validade do cache da Sessao na HttpSession (perfil muda raramente). */
    private static final long CACHE_MS = 60_000L;

    /** Geração dos perfis: incrementa quando masters/flags mudam e invalida os caches. */
    private static volatile long geracaoPerfis = 0;

    public static void invalidarPerfis() { geracaoPerfis++; }

    /**
     * Normaliza "." e ".." do caminho; null quando tenta sair da raiz ou usa
     * truques de segmento (";param", "%2e") que o container trataria diferente.
     */
    public static String normalizar(String caminho) {
        if (caminho.indexOf(';') >= 0 || caminho.indexOf('%') >= 0) return null;
        if (caminho.indexOf("/.") < 0 && caminho.indexOf("//") < 0) return caminho;
        java.util.ArrayDeque<String> partes = new java.util.ArrayDeque<>();
        for (String p : caminho.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (partes.isEmpty()) return null;
                partes.removeLast();
            } else {
                partes.addLast(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : partes) sb.append('/').append(p);
        if (caminho.endsWith("/") && sb.length() > 0) sb.append('/');
        return sb.length() == 0 ? "/" : sb.toString();
    }

    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpServletResponse resp = (HttpServletResponse) sresp;
        if (req.getCharacterEncoding() == null) req.setCharacterEncoding("UTF-8");
        // Usa o caminho que o container já decodificou, limpou de ";param" e
        // normalizou para mapear o servlet — é esse que decide quem atende.
        // A URI crua só serve de defesa extra contra ".." que escape da raiz.
        String caminho = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        if (normalizar(req.getRequestURI().substring(req.getContextPath().length())) == null
                || caminho.indexOf(';') >= 0 || caminho.indexOf('%') >= 0) {
            Http.erro(resp, 400, "Caminho inválido.");
            return;
        }

        // página de acesso negado e de erro são standalone (o usuário pode nem
        // existir); estáticos não carregam dados — não precisam de perfil
        if (caminho.equals("/negado.jsp") || caminho.equals("/erro.jsp")
                || caminho.startsWith("/css/") || caminho.startsWith("/js/")) {
            // estáticos sempre revalidam: depois de um deploy ninguém fica com o JS antigo
            if (!caminho.endsWith(".jsp")) resp.setHeader("Cache-Control", "no-cache");
            chain.doFilter(sreq, sresp);
            return;
        }

        boolean escrita = !"GET".equalsIgnoreCase(req.getMethod())
                && !"HEAD".equalsIgnoreCase(req.getMethod());

        // Anti-CSRF: toda escrita exige o cabeçalho X-Atlas, que formulários
        // de outros sites não conseguem enviar (o fetch da própria ferramenta
        // envia sempre).
        if (escrita && req.getHeader("X-Atlas") == null) {
            Http.erro(resp, 403, "Requisição sem o cabeçalho de proteção (X-Atlas).");
            return;
        }

        Sessao sessao = (Sessao) req.getAttribute("sessao");
        if (sessao == null) {
            HttpSession http = req.getSession(false);
            // cache por sessão HTTP, com validade curta — e descartado na hora
            // quando o Admin mexe em masters/flags (geração de perfis)
            if (http != null) {
                Object[] cache = (Object[]) http.getAttribute("sessao.atlas");
                if (cache != null
                        && System.currentTimeMillis() - (Long) cache[1] < CACHE_MS
                        && (Long) cache[2] == geracaoPerfis) {
                    sessao = (Sessao) cache[0];
                }
            }
            if (sessao == null) {
                Object usuarioSso = http == null ? null : http.getAttribute("usuario");
                if (usuarioSso != null) {
                    sessao = Sessao.montar(usuarioSso);
                } else if (devSimular) {
                    sessao = simulada(req);
                }
                if (sessao != null) {
                    req.getSession(true).setAttribute("sessao.atlas",
                        new Object[] { sessao, System.currentTimeMillis(), geracaoPerfis });
                }
            }
        }

        if (sessao == null) {
            // Sem usuário (bloqueado ou SSO ausente): API responde JSON, tela vai ao negado
            if (caminho.startsWith("/api/")) {
                Http.erro(resp, 403, "Acesso negado.");
            } else {
                resp.sendRedirect(req.getContextPath() + "/negado.jsp");
            }
            return;
        }

        if (sessao.somenteLeitura && escrita) {
            Http.erro(resp, 403, "Sua matrícula está em modo somente leitura.");
            return;
        }

        req.setAttribute("sessao", sessao);
        chain.doFilter(sreq, sresp);
    }

    /**
     * SÓ no WAR de desenvolvimento (atlas.dev.simular=true, sem SSO): usuário
     * simulado. `?perfil=COLEGA|MODERADOR|MASTER` troca o perfil para testar
     * o que cada um enxerga; a escolha fica na HttpSession.
     */
    private static Sessao simulada(HttpServletRequest req) {
        HttpSession http = req.getSession(true);
        String pedido = req.getParameter("perfil");
        if (pedido != null) {
            http.setAttribute("dev.perfil", pedido.trim().toUpperCase());
            http.removeAttribute("sessao.atlas"); // perfil novo: descarta o cache
        }
        String perfil = String.valueOf(http.getAttribute("dev.perfil"));
        if ("COLEGA".equals(perfil)) {
            return Sessao.montar("F0000002", "Colega (simulado)", "9101", "DEV");
        }
        if ("MODERADOR".equals(perfil)) {
            return Sessao.montar("F0000001", "Moderador (simulado)", "9007", "DEV");
        }
        return Sessao.montar("F3548926", "Desenvolvedor (simulado)", "9007", "DEV");
    }

    @Override
    public void destroy() { }
}
