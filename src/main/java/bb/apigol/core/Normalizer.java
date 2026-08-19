package bb.apigol.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class Normalizer {
    static final Map<String, List<String>> FIELD_MAP = new LinkedHashMap<String, List<String>>();
    static final List<String> KEY_PREFIXO = Arrays.asList("prefixo", "numero", "dependencia", "cod");
    static final List<String> KEY_NOME = Arrays.asList("nome", "nome_reduzido", "descricao");
    static final String[] DIMS = new String[]{"RS", "Volume", "Quantidade", "RIV"};

    static List<Map<String, Object>> linhas(Object payload) {
        if (payload instanceof Map) {
            Object data = ((Map<?, ?>) payload).get("data");
            if (data instanceof List) {
                return castLinhas((List<?>) data);
            }
            for (Object valor : ((Map<?, ?>) payload).values()) {
                if (!(valor instanceof List) || ((List<?>) valor).isEmpty() || !(((List<?>) valor).get(0) instanceof Map)) continue;
                return castLinhas((List<?>) valor);
            }
        }
        if (payload instanceof List) {
            return castLinhas((List<?>) payload);
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castLinhas(List<?> lista) {
        return (List<Map<String, Object>>) lista;
    }

    static Object pega(Map<String, Object> linha, List<String> campos) {
        if (linha == null) {
            return null;
        }
        for (String campo : campos) {
            if (!linha.containsKey(campo)) continue;
            return linha.get(campo);
        }
        return null;
    }

    static double num(Object valor) {
        if (valor == null) {
            return 0.0;
        }
        if (valor instanceof Number) {
            return ((Number) valor).doubleValue();
        }
        try {
            String texto = valor.toString().trim().replace(".", "").replace(",", ".");
            return texto.isEmpty() ? 0.0 : Double.parseDouble(texto);
        }
        catch (Exception e) {
            return 0.0;
        }
    }

    static Long canon(Object valor) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof Number) {
            return Math.round(((Number) valor).doubleValue());
        }
        try {
            return Math.round(Double.parseDouble(valor.toString().trim()));
        }
        catch (Exception e) {
            return null;
        }
    }

    static Long chaveDe(Map<String, Object> linha, String tipoChave) {
        if ("carteira".equals(tipoChave)) {
            return canon(linha.get("carteira"));
        }
        return canon(pega(linha, KEY_PREFIXO));
    }

    static Map<Long, double[]> somar(List<Object> payloads, String tipoChave) {
        LinkedHashMap<Long, double[]> somas = new LinkedHashMap<Long, double[]>();
        if (payloads == null) {
            return somas;
        }
        for (Object payload : payloads) {
            for (Map<String, Object> linha : linhas(payload)) {
                Long chave = chaveDe(linha, tipoChave);
                if (chave == null) continue;
                double[] valores = somas.get(chave);
                if (valores == null) {
                    valores = new double[DIMS.length];
                    somas.put(chave, valores);
                }
                for (int i = 0; i < DIMS.length; ++i) {
                    valores[i] += num(pega(linha, FIELD_MAP.get(DIMS[i])));
                }
            }
        }
        return somas;
    }

    static Map<Long, Double> somarCampo(Object payload, String dimensao, String tipoChave) {
        LinkedHashMap<Long, Double> somas = new LinkedHashMap<Long, Double>();
        for (Map<String, Object> linha : linhas(payload)) {
            Long chave = chaveDe(linha, tipoChave);
            if (chave == null) continue;
            somas.merge(chave, num(pega(linha, FIELD_MAP.get(dimensao))), Double::sum);
        }
        return somas;
    }

    public static List<Map<String, Object>> consolidar(List<Object> vendas, List<Object> cancelamentos, List<Object> pendentes, Object contatos, Map<Long, String> nomes) {
        return consolidar(vendas, cancelamentos, pendentes, contatos, nomes, null, "prefixo");
    }

    public static List<Map<String, Object>> consolidar(List<Object> vendas, List<Object> cancelamentos, List<Object> pendentes, Object contatos, Map<Long, String> nomes, Long prefixoConsultado) {
        return consolidar(vendas, cancelamentos, pendentes, contatos, nomes, prefixoConsultado, "prefixo");
    }

    public static List<Map<String, Object>> consolidar(List<Object> vendas, List<Object> cancelamentos, List<Object> pendentes, Object contatos, Map<Long, String> nomes, Long prefixoConsultado, String tipoChave) {
        LinkedHashMap<String, String> dims = new LinkedHashMap<String, String>();
        dims.put("RS", "rs");
        dims.put("Volume", "valor");
        dims.put("Quantidade", "qtde");
        dims.put("RIV", "riv");
        return consolidarDim(vendas, cancelamentos, pendentes, contatos, nomes, prefixoConsultado, tipoChave, dims, "Vendas", "qtde");
    }

    public static List<Map<String, Object>> consolidarDim(List<Object> vendas, List<Object> cancelamentos, List<Object> pendentes, Object contatos, Map<Long, String> nomes, Long prefixoConsultado, String tipoChave, LinkedHashMap<String, String> dimensoes, String rotuloVendas, String campoContatos) {
        return consolidarDim(vendas, cancelamentos, pendentes, contatos, nomes, prefixoConsultado, tipoChave, dimensoes, rotuloVendas, campoContatos, null);
    }

    /**
     * Consolida os payloads por chave de negocio (Prefixo ou Carteira).
     * "extras" acrescenta colunas ja somadas por chave (rotulo -> chave ->
     * valor), usado para "Operações com Prestamista"; as chaves presentes so
     * nos extras tambem entram no resultado, com as demais metricas zeradas.
     */
    public static List<Map<String, Object>> consolidarDim(List<Object> vendas, List<Object> cancelamentos, List<Object> pendentes, Object contatos, Map<Long, String> nomes, Long prefixoConsultado, String tipoChave, LinkedHashMap<String, String> dimensoes, String rotuloVendas, String campoContatos, LinkedHashMap<String, Map<Long, Double>> extras) {
        boolean porCarteira = "carteira".equals(tipoChave);
        String[] rotulos = dimensoes.keySet().toArray(new String[0]);
        String[] campos = dimensoes.values().toArray(new String[0]);
        Map<Long, double[]> somaVendas = somarD(vendas, tipoChave, campos);
        Map<Long, double[]> somaCancelamentos = somarD(cancelamentos, tipoChave, campos);
        Map<Long, double[]> somaPendentes = somarD(pendentes, tipoChave, campos);
        Map<Long, Double> somaContatos = somarCampoRaw(contatos, campoContatos, tipoChave);
        LinkedHashSet<Long> chaves = new LinkedHashSet<Long>();
        chaves.addAll(somaVendas.keySet());
        chaves.addAll(somaCancelamentos.keySet());
        chaves.addAll(somaPendentes.keySet());
        chaves.addAll(somaContatos.keySet());
        if (extras != null) {
            for (Map<Long, Double> valores : extras.values()) {
                chaves.addAll(valores.keySet());
            }
        }
        if (nomes != null) {
            chaves.addAll(nomes.keySet());
        }
        if (prefixoConsultado != null) {
            chaves.remove(prefixoConsultado);
        }
        if (porCarteira) {
            chaves.remove(-1L);
        }
        String colunaChave = porCarteira ? "Carteira" : "Prefixo";
        boolean comCancelamentos = cancelamentos != null && !cancelamentos.isEmpty();
        boolean comPendentes = pendentes != null && !pendentes.isEmpty();
        String rotuloCancelamentos = "Cancelamentos";
        int n = rotulos.length;
        double[] zeros = new double[n];
        ArrayList<Map<String, Object>> resultado = new ArrayList<Map<String, Object>>();
        for (Long chave : chaves) {
            int i;
            double[] vendasChave = somaVendas.getOrDefault(chave, zeros);
            double[] cancelamentosChave = somaCancelamentos.getOrDefault(chave, zeros);
            double[] pendentesChave = somaPendentes.getOrDefault(chave, zeros);
            LinkedHashMap<String, Object> linha = new LinkedHashMap<String, Object>();
            linha.put(colunaChave, chave);
            if (porCarteira) {
                linha.put("Nome", "Carteira " + chave);
            } else {
                linha.put("Nome", nomes != null && nomes.get(chave) != null ? nomes.get(chave) : "");
            }
            for (i = 0; i < n; ++i) {
                double venda = round(vendasChave[i]);
                if (comCancelamentos) {
                    linha.put(rotulos[i] + " " + rotuloVendas, venda);
                    double cancelamento = round(cancelamentosChave[i]);
                    linha.put(rotulos[i] + " " + rotuloCancelamentos, cancelamento);
                    linha.put(rotulos[i] + " Total", round(venda - cancelamento));
                    continue;
                }
                linha.put(rotulos[i], venda);
            }
            linha.put("Contatos", round(somaContatos.getOrDefault(chave, 0.0)));
            if (comPendentes) {
                for (i = 0; i < n; ++i) {
                    linha.put("Pendentes " + rotulos[i], round(pendentesChave[i]));
                }
            }
            if (extras != null) {
                for (Map.Entry<String, Map<Long, Double>> extra : extras.entrySet()) {
                    linha.put(extra.getKey(), round(extra.getValue().getOrDefault(chave, 0.0)));
                }
            }
            resultado.add(linha);
        }
        final String colunaOrdenacao = colunaChave;
        resultado.sort(Comparator.comparingLong(linha -> (Long) linha.get(colunaOrdenacao)));
        return resultado;
    }

    static Map<Long, double[]> somarD(List<Object> payloads, String tipoChave, String[] campos) {
        LinkedHashMap<Long, double[]> somas = new LinkedHashMap<Long, double[]>();
        if (payloads == null) {
            return somas;
        }
        for (Object payload : payloads) {
            for (Map<String, Object> linha : linhas(payload)) {
                Long chave = chaveDe(linha, tipoChave);
                if (chave == null) continue;
                double[] valores = somas.get(chave);
                if (valores == null) {
                    valores = new double[campos.length];
                    somas.put(chave, valores);
                }
                for (int i = 0; i < campos.length; ++i) {
                    valores[i] += num(linha.get(campos[i]));
                }
            }
        }
        return somas;
    }

    static Map<Long, Double> somarCampoRaw(Object payload, String campo, String tipoChave) {
        LinkedHashMap<Long, Double> somas = new LinkedHashMap<Long, Double>();
        for (Map<String, Object> linha : linhas(payload)) {
            Long chave = chaveDe(linha, tipoChave);
            if (chave == null) continue;
            somas.merge(chave, num(linha.get(campo)), Double::sum);
        }
        return somas;
    }

    public static Map<String, Object> totais(List<Map<String, Object>> linhas) {
        LinkedHashMap<String, Object> totais = new LinkedHashMap<String, Object>();
        if (linhas.isEmpty()) {
            return totais;
        }
        for (String coluna : linhas.get(0).keySet()) {
            if (coluna.equals("Prefixo") || coluna.equals("Nome")) continue;
            double soma = 0.0;
            for (Map<String, Object> linha : linhas) {
                soma += num(linha.get(coluna));
            }
            totais.put(coluna, round(soma));
        }
        return totais;
    }

    public static Map<Long, String> mapaNomes(List<Map<String, Object>> lista) {
        LinkedHashMap<Long, String> nomes = new LinkedHashMap<Long, String>();
        if (lista == null) {
            return nomes;
        }
        for (Map<String, Object> linha : lista) {
            Long chave = canon(pega(linha, KEY_PREFIXO));
            Object nome = pega(linha, KEY_NOME);
            if (chave == null) continue;
            nomes.put(chave, nome == null ? "" : nome.toString().trim());
        }
        return nomes;
    }

    static double round(double valor) {
        return (double) Math.round(valor * 100.0) / 100.0;
    }

    private Normalizer() {
    }

    static {
        FIELD_MAP.put("RS", Arrays.asList("rs", "vl_rs", "reais"));
        FIELD_MAP.put("Volume", Arrays.asList("valor", "volume", "vol"));
        FIELD_MAP.put("Quantidade", Arrays.asList("qtde", "quantidade", "qtd"));
        FIELD_MAP.put("RIV", Arrays.asList("riv", "vl_riv"));
        FIELD_MAP.put("Contatos", Arrays.asList("qtde", "contatos", "qtd_contatos"));
    }
}
