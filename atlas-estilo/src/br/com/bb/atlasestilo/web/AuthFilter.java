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

    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpServletResponse resp = (HttpServletResponse) sresp;
        if (req.getCharacterEncoding() == null) req.setCharacterEncoding("UTF-8");
        String caminho = req.getRequestURI().substring(req.getContextPath().length());

        // página de acesso negado é standalone (o usuário pode nem existir);
        // estáticos não carregam dados — não precisam de perfil
        if (caminho.equals("/negado.jsp")
                || caminho.startsWith("/css/") || caminho.startsWith("/js/")) {
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
            // cache por sessão HTTP, com validade curta
            if (http != null) {
                Object[] cache = (Object[]) http.getAttribute("sessao.atlas");
                if (cache != null
                        && System.currentTimeMillis() - (Long) cache[1] < CACHE_MS) {
                    sessao = (Sessao) cache[0];
                }
            }
            if (sessao == null) {
                Object usuarioSso = http == null ? null : http.getAttribute("usuario");
                if (usuarioSso != null) {
                    sessao = Sessao.montar(usuarioSso);
                } else if (devSimular) {
                    sessao = Sessao.montar("F3548926", "Desenvolvedor (simulado)", "9007", "DEV");
                }
                if (sessao != null) {
                    req.getSession(true).setAttribute("sessao.atlas",
                        new Object[] { sessao, System.currentTimeMillis() });
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

    @Override
    public void destroy() { }
}
