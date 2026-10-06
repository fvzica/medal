package br.com.bb.atlasestilo.core;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.bb.atlasestilo.dao.ConfigDao;
import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Csv;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;
import br.com.bb.atlasestilo.util.Xlsx;

/**
 * Imports administráveis (Master): cada planilha tem um tipo, com modelo CSV,
 * prévia (nada é gravado) e confirmação (upsert em transação + auditoria).
 * Aceita CSV (';' ou ',', UTF-8 com BOM) e .xlsx (leitor próprio, sem POI).
 * Cabeçalhos são casados por nome normalizado, com sinônimos tolerados.
 */
public final class ImportService {

    private ImportService() { }

    public static final String[] TIPOS = { "agencias", "funcis", "carteiras", "pdg", "metas" };

    /** Modelo CSV (cabeçalho + exemplo) para o Master baixar. */
    public static String modelo(String tipo) {
        switch (tipo) {
            case "agencias": return
                "prefixo;nome;uf;municipio;endereco;cep;lat;lng;regional;super_regional;gmaps_url\n" +
                "1881;Estilo Faria Lima;SP;São Paulo;Av. Brig. Faria Lima 3064;01451-000;-23.586;-46.681;SUP PF SP NOROESTE;SUPER PF SP;\n";
            case "funcis": return
                "matricula;nome;prefixo;cargo;funcao;tipo;carteira;posse_cargo;posse_funcao\n" +
                "F0000001;Fulana de Tal;1881;Gerente de Relacionamento;Gerente Estilo;GERENTE;EST-01;15/03/2022;01/08/2023\n";
            case "carteiras": return
                "prefixo;codigo;nome;tipo;gerente_matricula;qtd_clientes\n" +
                "1881;EST-01;Estilo Alta Renda I;ESTILO;F0000001;320\n";
            case "pdg": return
                "prefixo;semestre;atingiu;pontuacao\n" +
                "1881;2026-1;S;104,3\n";
            case "metas": return
                "prefixo;periodo;indicador;meta;realizado;projecao\n" +
                "1881;2026-2;Captação;1200000;830000;1150000\n";
            default: return null;
        }
    }

    /**
     * Processa o arquivo. confirmar=false -> só valida e devolve a prévia;
     * confirmar=true -> grava em transação e registra na auditoria.
     */
    public static String processar(String tipo, String nomeArquivo, InputStream in,
                                   boolean confirmar, String matricula, long agora)
            throws IOException, SQLException {
        List<String[]> linhas = nomeArquivo != null
                && nomeArquivo.toLowerCase().endsWith(".xlsx")
                ? Xlsx.ler(in) : Csv.ler(in);
        if (linhas.size() < 2) {
            return Json.obj().put("erro", "Arquivo sem linhas de dados (só cabeçalho?).").fim();
        }

        Map<String, Integer> col = mapearCabecalho(tipo, linhas.get(0));
        String faltando = obrigatorias(tipo, col);
        if (faltando != null) {
            return Json.obj().put("erro", "Coluna obrigatória ausente: " + faltando
                    + ". Baixe o modelo para conferir o formato.").fim();
        }

        List<Map<String, String>> registros = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        for (int i = 1; i < linhas.size(); i++) {
            String[] l = linhas.get(i);
            Map<String, String> reg = extrair(tipo, col, l);
            String erro = validar(tipo, reg);
            if (erro != null) {
                if (erros.size() < 10) erros.add("Linha " + (i + 1) + ": " + erro);
                continue;
            }
            registros.add(reg);
        }
        // duplicatas: vale a última ocorrência
        Map<String, Map<String, String>> porChave = new java.util.LinkedHashMap<>();
        for (Map<String, String> r : registros) porChave.put(chave(tipo, r), r);

        int ignorados = (linhas.size() - 1) - porChave.size();
        int inseridos = 0, atualizados = 0;

        try (Connection c = Db.conexao()) {
            c.setAutoCommit(false);
            try {
                for (Map<String, String> r : porChave.values()) {
                    boolean existia = existe(c, tipo, r);
                    if (existia) atualizados++; else inseridos++;
                    if (confirmar) gravar(c, tipo, r, agora);
                }
                if (confirmar) {
                    c.commit();
                    ConfigDao.importLog(tipo, nomeArquivo, inseridos, atualizados,
                                        ignorados, matricula, agora);
                } else {
                    c.rollback();
                }
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }

        Json.Arr errosJson = Json.arr();
        for (String e : erros) errosJson.addStr(e);
        return Json.obj()
            .put("tipo", tipo)
            .put("confirmado", confirmar)
            .put("linhas", linhas.size() - 1)
            .put("inseridos", inseridos)
            .put("atualizados", atualizados)
            .put("ignorados", ignorados)
            .putRaw("erros", errosJson.fim())
            .fim();
    }

    // -------------------------------------------------------------- cabeçalho

    private static final Map<String, String[]> SINONIMOS = new HashMap<>();
    static {
        SINONIMOS.put("prefixo", new String[] { "PREFIXO", "PREF", "AGENCIA PREFIXO", "DEPENDENCIA" });
        SINONIMOS.put("nome", new String[] { "NOME", "AGENCIA", "NOME AGENCIA", "DEPENDENCIA NOME" });
        SINONIMOS.put("uf", new String[] { "UF", "ESTADO", "SIGLA UF" });
        SINONIMOS.put("municipio", new String[] { "MUNICIPIO", "CIDADE" });
        SINONIMOS.put("endereco", new String[] { "ENDERECO", "LOGRADOURO" });
        SINONIMOS.put("cep", new String[] { "CEP" });
        SINONIMOS.put("lat", new String[] { "LAT", "LATITUDE" });
        SINONIMOS.put("lng", new String[] { "LNG", "LON", "LONG", "LONGITUDE" });
        SINONIMOS.put("regional", new String[] { "REGIONAL", "SUPER REGIONAL PF", "GERENCIA REGIONAL" });
        SINONIMOS.put("super_regional", new String[] { "SUPER_REGIONAL", "SUPER REGIONAL", "SUPER" });
        SINONIMOS.put("gmaps_url", new String[] { "GMAPS_URL", "GMAPS", "MAPS", "STREETVIEW", "STREET VIEW" });
        SINONIMOS.put("matricula", new String[] { "MATRICULA", "CHAVE", "FUNCI" });
        SINONIMOS.put("cargo", new String[] { "CARGO", "COMISSAO", "NOME COMISSAO" });
        SINONIMOS.put("funcao", new String[] { "FUNCAO", "FUNCAO GRATIFICADA" });
        SINONIMOS.put("tipo", new String[] { "TIPO", "CATEGORIA" });
        SINONIMOS.put("carteira", new String[] { "CARTEIRA", "COD CARTEIRA" });
        SINONIMOS.put("posse_cargo", new String[] { "POSSE_CARGO", "POSSE CARGO", "DATA POSSE CARGO", "DT CARGO" });
        SINONIMOS.put("posse_funcao", new String[] { "POSSE_FUNCAO", "POSSE FUNCAO", "DATA POSSE FUNCAO", "DT FUNCAO" });
        SINONIMOS.put("codigo", new String[] { "CODIGO", "COD", "NUMERO" });
        SINONIMOS.put("gerente_matricula", new String[] { "GERENTE_MATRICULA", "GERENTE", "MATRICULA GERENTE" });
        SINONIMOS.put("qtd_clientes", new String[] { "QTD_CLIENTES", "CLIENTES", "QTD CLIENTES", "QUANTIDADE CLIENTES" });
        SINONIMOS.put("semestre", new String[] { "SEMESTRE", "PERIODO" });
        SINONIMOS.put("atingiu", new String[] { "ATINGIU", "GANHOU", "PDG", "RESULTADO" });
        SINONIMOS.put("pontuacao", new String[] { "PONTUACAO", "PONTOS", "SCORE" });
        SINONIMOS.put("periodo", new String[] { "PERIODO", "SEMESTRE", "REFERENCIA" });
        SINONIMOS.put("indicador", new String[] { "INDICADOR", "ITEM", "LINHA" });
        SINONIMOS.put("meta", new String[] { "META", "ORCADO", "OBJETIVO" });
        SINONIMOS.put("realizado", new String[] { "REALIZADO", "REAL", "ATUAL" });
        SINONIMOS.put("projecao", new String[] { "PROJECAO", "PROJETADO", "TENDENCIA" });
    }

    private static String[] campos(String tipo) {
        switch (tipo) {
            case "agencias":  return new String[] { "prefixo", "nome", "uf", "municipio",
                "endereco", "cep", "lat", "lng", "regional", "super_regional", "gmaps_url" };
            case "funcis":    return new String[] { "matricula", "nome", "prefixo", "cargo",
                "funcao", "tipo", "carteira", "posse_cargo", "posse_funcao" };
            case "carteiras": return new String[] { "prefixo", "codigo", "nome", "tipo",
                "gerente_matricula", "qtd_clientes" };
            case "pdg":       return new String[] { "prefixo", "semestre", "atingiu", "pontuacao" };
            case "metas":     return new String[] { "prefixo", "periodo", "indicador",
                "meta", "realizado", "projecao" };
            default: throw new IllegalArgumentException("Tipo de import desconhecido: " + tipo);
        }
    }

    private static Map<String, Integer> mapearCabecalho(String tipo, String[] cabecalho) {
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < cabecalho.length; i++) {
            String nome = Texto.normalizar(cabecalho[i]);
            if (nome.isEmpty()) continue;
            for (String campo : campos(tipo)) {
                if (col.containsKey(campo)) continue;
                for (String sin : SINONIMOS.get(campo)) {
                    if (nome.equals(sin)) { col.put(campo, i); break; }
                }
            }
        }
        return col;
    }

    private static String obrigatorias(String tipo, Map<String, Integer> col) {
        String[] obriga;
        switch (tipo) {
            case "agencias":  obriga = new String[] { "prefixo", "nome" }; break;
            case "funcis":    obriga = new String[] { "matricula", "nome", "prefixo" }; break;
            case "carteiras": obriga = new String[] { "prefixo", "codigo" }; break;
            case "pdg":       obriga = new String[] { "prefixo", "semestre", "atingiu" }; break;
            default:          obriga = new String[] { "prefixo", "periodo", "indicador" };
        }
        for (String o : obriga) if (!col.containsKey(o)) return o;
        return null;
    }

    private static Map<String, String> extrair(String tipo, Map<String, Integer> col,
                                               String[] linha) {
        Map<String, String> reg = new HashMap<>();
        for (Map.Entry<String, Integer> e : col.entrySet()) {
            int i = e.getValue();
            reg.put(e.getKey(), i < linha.length ? Texto.aparar(linha[i]) : "");
        }
        return reg;
    }

    // -------------------------------------------------------------- validação

    private static String validar(String tipo, Map<String, String> r) {
        String prefixo = Texto.prefixo(r.get("prefixo"));
        if (prefixo.isEmpty()) return "prefixo vazio ou inválido";
        r.put("prefixo", prefixo);
        switch (tipo) {
            case "agencias":
                if (Texto.vazio(r.get("nome"))) return "nome vazio";
                if (!Texto.vazio(r.get("uf")) && Texto.normalizar(r.get("uf")).length() != 2) {
                    return "UF inválida: " + r.get("uf");
                }
                r.put("uf", Texto.normalizar(r.get("uf")));
                return null;
            case "funcis": {
                String m = Texto.matricula(r.get("matricula"));
                if (m.isEmpty()) return "matrícula vazia";
                r.put("matricula", m);
                if (Texto.vazio(r.get("nome"))) return "nome vazio";
                String t = Texto.normalizar(r.get("tipo"));
                if (t.isEmpty()) {
                    String cf = Texto.normalizar(r.get("cargo")) + " " + Texto.normalizar(r.get("funcao"));
                    t = cf.contains("GERENTE") ? "GERENTE"
                      : (cf.contains("ASSIST") || cf.contains("ASSESSOR")) ? "ASSISTENTE" : "OUTRO";
                } else if (!t.equals("GERENTE") && !t.equals("ASSISTENTE")) {
                    t = "OUTRO";
                }
                r.put("tipo", t);
                return null;
            }
            case "carteiras":
                if (Texto.vazio(r.get("codigo"))) return "código da carteira vazio";
                return null;
            case "pdg": {
                String sem = normalizarSemestre(r.get("semestre"));
                if (sem == null) return "semestre inválido: " + r.get("semestre");
                r.put("semestre", sem);
                String a = Texto.normalizar(r.get("atingiu"));
                boolean atingiu = a.equals("1") || a.equals("S") || a.equals("SIM")
                        || a.equals("TRUE") || a.equals("GANHOU") || a.equals("ATINGIU");
                r.put("atingiu", atingiu ? "1" : "0");
                return null;
            }
            default:
                if (Texto.vazio(r.get("periodo"))) return "período vazio";
                if (Texto.vazio(r.get("indicador"))) return "indicador vazio";
                String p = normalizarSemestre(r.get("periodo"));
                if (p != null) r.put("periodo", p);
                return null;
        }
    }

    /** "2026-1", "2026/1", "2026.1", "20261", "1/2026", "1º SEM 2026" -> "2026-1". */
    public static String normalizarSemestre(String s) {
        if (Texto.vazio(s)) return null;
        String n = Texto.normalizar(s).replaceAll("[^0-9]", " ").trim();
        String[] partes = n.split("\\s+");
        String ano = null, sem = null;
        for (String p : partes) {
            if (p.length() == 5 && (p.endsWith("1") || p.endsWith("2"))) {
                ano = p.substring(0, 4); sem = p.substring(4); break;
            }
            if (p.length() == 4) ano = p;
            else if (p.equals("1") || p.equals("2")) sem = p;
        }
        if (ano == null || sem == null) return null;
        return ano + "-" + sem;
    }

    private static String chave(String tipo, Map<String, String> r) {
        switch (tipo) {
            case "agencias":  return r.get("prefixo");
            case "funcis":    return r.get("matricula");
            case "carteiras": return r.get("prefixo") + "|" + Texto.normalizar(r.get("codigo"));
            case "pdg":       return r.get("prefixo") + "|" + r.get("semestre");
            default:          return r.get("prefixo") + "|" + r.get("periodo") + "|"
                                     + Texto.normalizar(r.get("indicador"));
        }
    }

    // ----------------------------------------------------------------- gravar

    private static boolean existe(Connection c, String tipo, Map<String, String> r)
            throws SQLException {
        String sql;
        switch (tipo) {
            case "agencias":  sql = "SELECT 1 FROM agencia WHERE prefixo = ?"; break;
            case "funcis":    sql = "SELECT 1 FROM funci WHERE matricula = ?"; break;
            case "carteiras": sql = "SELECT 1 FROM carteira WHERE prefixo = ? AND codigo = ?"; break;
            case "pdg":       sql = "SELECT 1 FROM pdg WHERE prefixo = ? AND semestre = ?"; break;
            default:          sql = "SELECT 1 FROM meta WHERE prefixo = ? AND periodo = ? AND indicador = ?";
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            switch (tipo) {
                case "agencias":  ps.setString(1, r.get("prefixo")); break;
                case "funcis":    ps.setString(1, r.get("matricula")); break;
                case "carteiras": ps.setString(1, r.get("prefixo"));
                                  ps.setString(2, r.get("codigo")); break;
                case "pdg":       ps.setString(1, r.get("prefixo"));
                                  ps.setString(2, r.get("semestre")); break;
                default:          ps.setString(1, r.get("prefixo"));
                                  ps.setString(2, r.get("periodo"));
                                  ps.setString(3, r.get("indicador"));
            }
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static void gravar(Connection c, String tipo, Map<String, String> r, long agora)
            throws SQLException {
        switch (tipo) {
            case "agencias": {
                String sql = "INSERT INTO agencia (prefixo,nome,uf,municipio,endereco,cep," +
                    "lat,lng,regional,super_regional,gmaps_url,origem,atualizado_em) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,'IMPORT',?) " +
                    "ON CONFLICT(prefixo) DO UPDATE SET nome=excluded.nome, uf=excluded.uf, " +
                    "municipio=excluded.municipio, endereco=excluded.endereco, cep=excluded.cep, " +
                    "lat=excluded.lat, lng=excluded.lng, regional=excluded.regional, " +
                    "super_regional=excluded.super_regional, gmaps_url=excluded.gmaps_url, " +
                    "origem='IMPORT', atualizado_em=excluded.atualizado_em";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, r.get("prefixo"));
                    ps.setString(2, r.get("nome"));
                    ps.setString(3, vazioNulo(r.get("uf")));
                    ps.setString(4, vazioNulo(r.get("municipio")));
                    ps.setString(5, vazioNulo(r.get("endereco")));
                    ps.setString(6, vazioNulo(r.get("cep")));
                    setDouble(ps, 7, Texto.decimal(r.get("lat")));
                    setDouble(ps, 8, Texto.decimal(r.get("lng")));
                    ps.setString(9, Texto.vazio(r.get("regional")) ? "NÃO MAPEADA" : r.get("regional"));
                    ps.setString(10, vazioNulo(r.get("super_regional")));
                    ps.setString(11, vazioNulo(r.get("gmaps_url")));
                    ps.setLong(12, agora);
                    ps.executeUpdate();
                }
                return;
            }
            case "funcis": {
                String sql = "INSERT INTO funci (matricula,nome,prefixo,cargo,funcao,tipo," +
                    "carteira,posse_cargo,posse_funcao,origem,atualizado_em) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,'IMPORT',?) " +
                    "ON CONFLICT(matricula) DO UPDATE SET nome=excluded.nome, " +
                    "prefixo=excluded.prefixo, cargo=excluded.cargo, funcao=excluded.funcao, " +
                    "tipo=excluded.tipo, carteira=excluded.carteira, " +
                    "posse_cargo=excluded.posse_cargo, posse_funcao=excluded.posse_funcao, " +
                    "origem='IMPORT', atualizado_em=excluded.atualizado_em";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, r.get("matricula"));
                    ps.setString(2, r.get("nome"));
                    ps.setString(3, r.get("prefixo"));
                    ps.setString(4, vazioNulo(r.get("cargo")));
                    ps.setString(5, vazioNulo(r.get("funcao")));
                    ps.setString(6, r.get("tipo"));
                    ps.setString(7, vazioNulo(r.get("carteira")));
                    setLong(ps, 8, Xlsx.dataDeCelula(r.get("posse_cargo")));
                    setLong(ps, 9, Xlsx.dataDeCelula(r.get("posse_funcao")));
                    ps.setLong(10, agora);
                    ps.executeUpdate();
                }
                return;
            }
            case "carteiras": {
                String sql = "INSERT INTO carteira (prefixo,codigo,nome,tipo," +
                    "gerente_matricula,qtd_clientes,origem) VALUES (?,?,?,?,?,?,'IMPORT') " +
                    "ON CONFLICT(prefixo,codigo) DO UPDATE SET nome=excluded.nome, " +
                    "tipo=excluded.tipo, gerente_matricula=excluded.gerente_matricula, " +
                    "qtd_clientes=excluded.qtd_clientes, origem='IMPORT'";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, r.get("prefixo"));
                    ps.setString(2, r.get("codigo"));
                    ps.setString(3, vazioNulo(r.get("nome")));
                    ps.setString(4, vazioNulo(r.get("tipo")));
                    String g = Texto.matricula(r.get("gerente_matricula"));
                    ps.setString(5, g.isEmpty() ? null : g);
                    Integer qc = Texto.inteiro(r.get("qtd_clientes"));
                    ps.setInt(6, qc == null ? 0 : qc);
                    ps.executeUpdate();
                }
                return;
            }
            case "pdg": {
                String sql = "INSERT INTO pdg (prefixo,semestre,atingiu,pontuacao,origem) " +
                    "VALUES (?,?,?,?,'IMPORT') " +
                    "ON CONFLICT(prefixo,semestre) DO UPDATE SET atingiu=excluded.atingiu, " +
                    "pontuacao=excluded.pontuacao, origem='IMPORT'";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, r.get("prefixo"));
                    ps.setString(2, r.get("semestre"));
                    ps.setInt(3, "1".equals(r.get("atingiu")) ? 1 : 0);
                    setDouble(ps, 4, Texto.decimal(r.get("pontuacao")));
                    ps.executeUpdate();
                }
                return;
            }
            default: {
                String sql = "INSERT INTO meta (prefixo,periodo,indicador,meta,realizado," +
                    "projecao,origem,atualizado_em) VALUES (?,?,?,?,?,?,'IMPORT',?) " +
                    "ON CONFLICT(prefixo,periodo,indicador) DO UPDATE SET meta=excluded.meta, " +
                    "realizado=excluded.realizado, projecao=excluded.projecao, " +
                    "origem='IMPORT', atualizado_em=excluded.atualizado_em";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, r.get("prefixo"));
                    ps.setString(2, r.get("periodo"));
                    ps.setString(3, r.get("indicador"));
                    setDouble(ps, 4, Texto.decimal(r.get("meta")));
                    setDouble(ps, 5, Texto.decimal(r.get("realizado")));
                    setDouble(ps, 6, Texto.decimal(r.get("projecao")));
                    ps.setLong(7, agora);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static String vazioNulo(String s) { return Texto.vazio(s) ? null : s.trim(); }

    private static void setDouble(PreparedStatement ps, int pos, Double v) throws SQLException {
        if (v == null) ps.setNull(pos, java.sql.Types.DOUBLE);
        else ps.setDouble(pos, v);
    }

    private static void setLong(PreparedStatement ps, int pos, Long v) throws SQLException {
        if (v == null) ps.setNull(pos, java.sql.Types.BIGINT);
        else ps.setLong(pos, v);
    }
}
