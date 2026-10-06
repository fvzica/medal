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

    @Override
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpServletResponse resp = (HttpServletResponse) sresp;
        String caminho = req.getRequestURI().substring(req.getContextPath().length());

        // página de acesso negado é standalone (o usuário pode nem existir)
        if (caminho.equals("/negado.jsp")) { chain.doFilter(sreq, sresp); return; }

        Sessao sessao = (Sessao) req.getAttribute("sessao");
        if (sessao == null) {
            HttpSession http = req.getSession(false);
            Object usuarioSso = http == null ? null : http.getAttribute("usuario");
            if (usuarioSso != null) {
                sessao = Sessao.montar(usuarioSso);
            } else if (devSimular) {
                sessao = Sessao.montar("F3548926", "Desenvolvedor (simulado)", "9007", "DEV");
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

        if (sessao.somenteLeitura && !"GET".equalsIgnoreCase(req.getMethod())) {
            Http.erro(resp, 403, "Sua matrícula está em modo somente leitura.");
            return;
        }

        req.setAttribute("sessao", sessao);
        chain.doFilter(sreq, sresp);
    }

    @Override
    public void destroy() { }
}
