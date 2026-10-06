package br.com.bb.atlasestilo.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Leitor de CSV dos imports: detecta ';' ou ',' pelo cabeçalho, tolera BOM,
 * aspas duplas (RFC 4180) e linhas vazias. Devolve linhas como String[].
 */
public final class Csv {

    private Csv() { }

    public static List<String[]> ler(InputStream in) throws IOException {
        List<String> linhas = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) linhas.add(l);
        }
        if (!linhas.isEmpty() && linhas.get(0).startsWith("﻿")) {
            linhas.set(0, linhas.get(0).substring(1));
        }
        char sep = detectarSeparador(linhas.isEmpty() ? "" : linhas.get(0));

        List<String[]> out = new ArrayList<>();
        // Reagrupa linhas quebradas dentro de aspas antes de dividir
        StringBuilder pendente = null;
        for (String linha : linhas) {
            String atual = pendente == null ? linha : pendente + "\n" + linha;
            if (aspasAbertas(atual)) { pendente = new StringBuilder(atual); continue; }
            pendente = null;
            if (atual.trim().isEmpty()) continue;
            out.add(dividir(atual, sep));
        }
        if (pendente != null && pendente.length() > 0) {
            out.add(dividir(pendente.toString(), sep));
        }
        return out;
    }

    private static char detectarSeparador(String cabecalho) {
        int pv = conta(cabecalho, ';'), vg = conta(cabecalho, ',');
        return pv >= vg ? ';' : ',';
    }

    private static int conta(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static boolean aspasAbertas(String s) {
        boolean dentro = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') dentro = !dentro;
        }
        return dentro;
    }

    private static String[] dividir(String linha, char sep) {
        List<String> campos = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean dentro = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (dentro) {
                if (c == '"') {
                    if (i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                        sb.append('"'); i++;
                    } else {
                        dentro = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                dentro = true;
            } else if (c == sep) {
                campos.add(sb.toString().trim()); sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        campos.add(sb.toString().trim());
        return campos.toArray(new String[0]);
    }
}
