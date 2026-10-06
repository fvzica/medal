package br.com.bb.atlasestilo.util;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Helpers de requisição/resposta para as APIs JSON (fetch no front). */
public final class Http {

    private Http() { }

    public static void json(HttpServletResponse resp, String corpo) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(corpo);
    }

    public static void erro(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        json(resp, Json.obj().put("erro", msg).fim());
    }

    public static String param(HttpServletRequest req, String nome, String padrao) {
        String v = req.getParameter(nome);
        return Texto.vazio(v) ? padrao : v.trim();
    }

    public static int paramInt(HttpServletRequest req, String nome, int padrao) {
        try { return Integer.parseInt(req.getParameter(nome).trim()); }
        catch (RuntimeException e) { return padrao; }
    }

    public static long paramLong(HttpServletRequest req, String nome, long padrao) {
        try { return Long.parseLong(req.getParameter(nome).trim()); }
        catch (RuntimeException e) { return padrao; }
    }

    /** Partes do caminho após o servlet (ex.: /api/agencia/1881 -> ["agencia","1881"]). */
    public static String[] caminho(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null || p.equals("/")) return new String[0];
        if (p.startsWith("/")) p = p.substring(1);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.split("/");
    }

    /** Download de arquivo texto (ex.: modelo CSV). */
    public static void download(HttpServletResponse resp, String nomeArquivo, String conteudo)
            throws IOException {
        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + nomeArquivo + "\"");
        // BOM para o Excel abrir UTF-8 corretamente
        resp.getWriter().write('﻿');
        resp.getWriter().write(conteudo);
    }
}
