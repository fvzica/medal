package bb.apigol.web;

import bb.apigol.core.Config;
import bb.apigol.core.Normalizer;
import bb.apigol.core.ProdutoConfig;
import bb.apigol.core.ProdutoEngine;
import bb.apigol.core.XlsxWriter;
import bb.apigol.json.Json;
import bb.apigol.ws.GolHttpClient;
import bb.apigol.ws.GolWebSocketClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ApiServlet extends HttpServlet {
    private static final int TIMEOUT = 25;

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path == null) {
            path = "/";
        }
        String[] partes = path.split("/");
        try {
            if (path.equals("/") || path.equals("/painel") || path.equals("/index.html")) {
                this.servirRecurso(response, "/painel.html", "text/html");
                return;
            }
            if (path.equals("/captura")) {
                this.servirRecurso(response, "/captura_produto.html", "text/html");
                return;
            }
            if (path.equals("/health")) {
                writeJson(response, 200, mapOf("status", "ok", "servico", "apigol", "transporte", "websocket"));
                return;
            }
            if (path.equals("/glossario")) {
                writeJson(response, 200, Config.GLOSSARIO);
                return;
            }
            if (path.equals("/produtos")) {
                this.handleProdutos(response);
                return;
            }
            if (partes.length >= 4 && partes[1].equals("produto")) {
                this.handleProduto(request, response, partes[2], partes[3]);
                return;
            }
            if (partes.length >= 5 && partes[1].equals("export") && partes[2].equals("produto")) {
                this.handleExportProduto(request, response, partes[3], partes[4]);
                return;
            }
            if (partes.length >= 3 && partes[1].equals("unificado")) {
                this.handleUnificado(request, response, partes[2]);
                return;
            }
            if (partes.length >= 4 && partes[1].equals("export") && partes[2].equals("unificado")) {
                this.handleExportUnificado(request, response, partes[3]);
                return;
            }
            if (partes.length >= 3 && partes[1].equals("prestamista")) {
                this.handleProduto(request, response, "prestamista", partes[2]);
                return;
            }
            if (partes.length >= 4 && partes[1].equals("export") && partes[2].equals("prestamista")) {
                this.handleExportProduto(request, response, "prestamista", partes[3]);
                return;
            }
            if (partes.length >= 3 && partes[1].equals("raw")) {
                this.handleRaw(request, response, partes[2]);
                return;
            }
            writeJson(response, 404, mapOf("erro", "rota nao encontrada: " + path));
        }
        catch (Exception e) {
            writeJson(response, 500, mapOf("erro", String.valueOf(e.getMessage())));
        }
    }

    private void handleProdutos(HttpServletResponse response) throws IOException {
        ArrayList<Map<String, String>> produtos = new ArrayList<Map<String, String>>();
        for (ProdutoConfig produto : ProdutoConfig.todos().values()) {
            LinkedHashMap<String, String> resumo = new LinkedHashMap<String, String>();
            resumo.put("chave", produto.chave);
            resumo.put("label", produto.label);
            resumo.put("grupo", produto.grupo);
            produtos.add(resumo);
        }
        writeJson(response, 200, mapOf("produtos", produtos));
    }

    private List<Map<String, Object>> coletarProduto(HttpServletRequest request, ProdutoConfig produto, String prefixo, String visao, String nivel, String carteira) throws Exception {
        String cookie = GolSession.cookieHeader(request);
        Map<Long, String> nomes = null;
        if (!"carteira".equals(nivel)) {
            try {
                List<Map<String, Object>> jurisdicao = new GolHttpClient(cookie, TIMEOUT).listaJurisdicao(prefixo);
                if (jurisdicao != null && !jurisdicao.isEmpty()) {
                    nomes = Normalizer.mapaNomes(jurisdicao);
                }
            }
            catch (Exception e) {
                // sem nomes, as linhas saem apenas com o prefixo
            }
        }
        return new ProdutoEngine(cookie, TIMEOUT).consolidar(produto, prefixo, visao, nivel, carteira, nomes);
    }

    private void handleProduto(HttpServletRequest request, HttpServletResponse response, String chave, String prefixo) throws Exception {
        ProdutoConfig produto = ProdutoConfig.get(chave);
        if (produto == null) {
            writeJson(response, 404, mapOf("erro", "produto desconhecido: " + chave));
            return;
        }
        String visao = param(request, "visao", "jurisdicao");
        String nivel = param(request, "nivel", "regional");
        String carteira = request.getParameter("carteira");
        if (!Config.VISOES.contains(visao)) {
            writeJson(response, 400, mapOf("erro", "visao invalida"));
            return;
        }
        if (!Config.NIVEIS.contains(nivel)) {
            writeJson(response, 400, mapOf("erro", "nivel invalido"));
            return;
        }
        List<Map<String, Object>> linhas = this.coletarProduto(request, produto, prefixo, visao, nivel, carteira);
        if (linhas.isEmpty()) {
            String erro = "carteira".equals(nivel) ? "sem carteiras para este prefixo." : "sem dados do WebSocket (sessao/token?).";
            writeJson(response, 504, mapOf("erro", erro));
            return;
        }
        LinkedHashMap<String, Object> resposta = new LinkedHashMap<String, Object>();
        resposta.put("produto", chave);
        resposta.put("label", produto.label);
        resposta.put("prefixo", prefixo);
        resposta.put("visao", visao);
        resposta.put("nivel", nivel);
        resposta.put("total_linhas", linhas.size());
        resposta.put("totais", Normalizer.totais(linhas));
        resposta.put("linhas", linhas);
        writeJson(response, 200, resposta);
    }

    private void handleExportProduto(HttpServletRequest request, HttpServletResponse response, String chave, String prefixo) throws Exception {
        ProdutoConfig produto = ProdutoConfig.get(chave);
        if (produto == null) {
            writeJson(response, 404, mapOf("erro", "produto desconhecido"));
            return;
        }
        String formato = param(request, "formato", "xlsx");
        String visao = param(request, "visao", "jurisdicao");
        String nivel = param(request, "nivel", "regional");
        String carteira = request.getParameter("carteira");
        List<Map<String, Object>> linhas = this.coletarProduto(request, produto, prefixo, visao, nivel, carteira);
        if (linhas.isEmpty()) {
            writeJson(response, 404, mapOf("erro", "sem dados para exportar"));
            return;
        }
        String nomeArquivo = chave + "_" + prefixo + "_" + visao + "_" + nivel;
        this.exportar(response, formato, nomeArquivo, linhas);
    }

    private void handleUnificado(HttpServletRequest request, HttpServletResponse response, String prefixo) throws Exception {
        String visao = param(request, "visao", "jurisdicao");
        List<ProdutoConfig> produtos = this.selecionarProdutos(request);
        String cookie = GolSession.cookieHeader(request);
        Map<String, Object> resposta = new ProdutoEngine(cookie, TIMEOUT).consolidarUnificado(produtos, prefixo, visao);
        writeJson(response, 200, resposta);
    }

    private void handleExportUnificado(HttpServletRequest request, HttpServletResponse response, String prefixo) throws Exception {
        String formato = param(request, "formato", "xlsx");
        String visao = param(request, "visao", "jurisdicao");
        List<ProdutoConfig> produtos = this.selecionarProdutos(request);
        String cookie = GolSession.cookieHeader(request);
        Map<String, Object> resultado = new ProdutoEngine(cookie, TIMEOUT).consolidarUnificado(produtos, prefixo, visao);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> linhas = (List<Map<String, Object>>) resultado.get("linhas");
        if (linhas == null || linhas.isEmpty()) {
            writeJson(response, 404, mapOf("erro", "sem dados"));
            return;
        }
        this.exportar(response, formato, "unificado_" + prefixo + "_" + visao, linhas);
    }

    private List<ProdutoConfig> selecionarProdutos(HttpServletRequest request) {
        String produtos = request.getParameter("produtos");
        ArrayList<ProdutoConfig> selecionados = new ArrayList<ProdutoConfig>();
        if (produtos == null || produtos.isEmpty()) {
            selecionados.addAll(ProdutoConfig.todos().values());
            return selecionados;
        }
        for (String chave : produtos.split(",")) {
            ProdutoConfig produto = ProdutoConfig.get(chave.trim());
            if (produto == null) continue;
            selecionados.add(produto);
        }
        if (selecionados.isEmpty()) {
            selecionados.addAll(ProdutoConfig.todos().values());
        }
        return selecionados;
    }

    private void exportar(HttpServletResponse response, String formato, String nomeArquivo, List<Map<String, Object>> linhas) throws IOException {
        if (formato.equals("csv")) {
            response.setStatus(200);
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + nomeArquivo + ".csv");
            PrintWriter writer = response.getWriter();
            writer.write(65279);
            LinkedHashSet<String> colunasSet = new LinkedHashSet<String>();
            for (Map<String, Object> linha : linhas) {
                colunasSet.addAll(linha.keySet());
            }
            ArrayList<String> colunas = new ArrayList<String>(colunasSet);
            writer.println(String.join(";", colunas));
            for (Map<String, Object> linha : linhas) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < colunas.size(); ++i) {
                    if (i > 0) {
                        sb.append(';');
                    }
                    Object valor = linha.get(colunas.get(i));
                    sb.append(valor == null ? "" : valor.toString().replace(";", ","));
                }
                writer.println(sb.toString());
            }
            writer.flush();
            return;
        }
        byte[] xlsx = XlsxWriter.gerar(linhas, Normalizer.totais(linhas), nomeArquivo, "");
        response.setStatus(200);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + nomeArquivo + ".xlsx");
        ServletOutputStream out = response.getOutputStream();
        out.write(xlsx);
        out.flush();
    }

    private void handleRaw(HttpServletRequest request, HttpServletResponse response, String prefixo) throws Exception {
        String topico = request.getParameter("topico");
        if (topico == null || topico.isEmpty()) {
            writeJson(response, 400, mapOf("erro", "informe 'topico'"));
            return;
        }
        String blocos = request.getParameter("blocos");
        String cookie = GolSession.cookieHeader(request);
        GolWebSocketClient ws = new GolWebSocketClient(cookie, TIMEOUT);
        Map<String, Object> respostas = ws.consultar(Collections.singletonList(topico), prefixo, null, blocos == null || blocos.isEmpty() ? "{3030,3031}" : blocos);
        Object dados = respostas.get(topico);
        if (dados == null) {
            writeJson(response, 504, mapOf("erro", "sem resposta"));
            return;
        }
        writeJson(response, 200, mapOf("prefixo", prefixo, "topico", topico, "data", dados));
    }

    private void servirRecurso(HttpServletResponse response, String recurso, String contentType) throws IOException {
        int n;
        InputStream in = ApiServlet.class.getResourceAsStream(recurso);
        response.setStatus(200);
        response.setContentType(contentType + "; charset=UTF-8");
        if (in == null) {
            response.getWriter().write("<h1>" + recurso + " nao encontrado</h1>");
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        response.getOutputStream().write(out.toByteArray());
        response.getOutputStream().flush();
    }

    private static String param(HttpServletRequest request, String nome, String padrao) {
        String valor = request.getParameter(nome);
        return valor == null || valor.isEmpty() ? padrao : valor;
    }

    private static Map<String, Object> mapOf(Object... pares) {
        LinkedHashMap<String, Object> mapa = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return mapa;
    }

    private static void writeJson(HttpServletResponse response, int status, Object corpo) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write(Json.write(corpo));
    }
}
