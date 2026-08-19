package bb.apigol.core;

import bb.apigol.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class ProdutoConfig {

    /**
     * Referencia de topico do catalogo. Cada entrada de vendas/cancelamentos/
     * pendentes/contatos pode ser uma String simples (usa os blocos do produto)
     * ou um objeto {"topico": "...", "blocos": "{...}"} com blocos proprios —
     * necessario quando o mesmo produto assina origens com blocos distintos
     * (ex.: Seguridade Total, BB Regulariza Agro).
     */
    public static final class Topico {
        public final String template;
        public final String blocos;

        Topico(String template, String blocos) {
            this.template = template;
            this.blocos = blocos;
        }

        public String resolver(String visao) {
            return String.format(this.template, visao);
        }
    }

    /**
     * Metrica adicional exigida pelo requisito (apigol.docx): quantidade de
     * operacoes com prestamista nos produtos de credito. O topico e consultado
     * com o mesmo prefixo/visao do produto e, por padrao, com os blocos do
     * proprio credito ({1001}..{1004}); "blocos" permite sobrepor sem rebuild.
     */
    public static final class PrestamistaOperacoes {
        public final String topico;
        public final String campo;
        public final String rotulo;
        public final String blocos;

        PrestamistaOperacoes(String topico, String campo, String rotulo, String blocos) {
            this.topico = topico;
            this.campo = campo;
            this.rotulo = rotulo;
            this.blocos = blocos;
        }
    }

    public static final String TIPO_PADRAO = "padrao";
    public static final String TIPO_COMPOSTO = "composto";

    public final String chave;
    public final String label;
    public final String grupo;
    public final String blocos;
    public final String tipo;
    public final List<String> componentes;
    public final List<String> metricasSomadas;
    public final List<Topico> vendas;
    public final List<Topico> cancelamentos;
    public final List<Topico> pendentes;
    public final Topico contatos;
    public final PrestamistaOperacoes prestamistaOperacoes;
    public final LinkedHashMap<String, String> dimensoes;
    public final String contatosCampo;
    public final String metricaVendasRotulo;
    private static volatile Map<String, ProdutoConfig> CACHE;

    private ProdutoConfig(String chave, Map<String, Object> map) {
        this.chave = chave;
        this.label = str(map.get("label"), chave);
        this.grupo = str(map.get("grupo"), "Outros");
        this.blocos = str(map.get("blocos"), "{}");
        this.tipo = TIPO_COMPOSTO.equalsIgnoreCase(str(map.get("tipo"), TIPO_PADRAO)) ? TIPO_COMPOSTO : TIPO_PADRAO;
        this.componentes = listaStr(map.get("componentes"));
        this.metricasSomadas = listaStr(map.get("metricas_somadas"));
        this.vendas = listaTopicos(map.get("vendas"));
        this.cancelamentos = listaTopicos(map.get("cancelamentos"));
        this.pendentes = listaTopicos(map.get("pendentes"));
        this.contatos = topico(map.get("contatos"));
        this.prestamistaOperacoes = prestamista(map.get("prestamista_operacoes"));
        this.contatosCampo = str(map.get("contatos_campo"), "qtde");
        this.metricaVendasRotulo = str(map.get("metrica_vendas_rotulo"), "Vendas");
        LinkedHashMap<String, String> dims = new LinkedHashMap<String, String>();
        Object rawDims = map.get("dimensoes");
        if (rawDims instanceof List) {
            for (Object e : (List<?>) rawDims) {
                if (!(e instanceof Map)) continue;
                Map<?, ?> dim = (Map<?, ?>) e;
                String rotulo = str(dim.get("rotulo"), null);
                String campo = str(dim.get("campo"), null);
                if (rotulo == null || campo == null) continue;
                dims.put(rotulo, campo);
            }
        }
        if (dims.isEmpty()) {
            dims.put("RS", "rs");
            dims.put("Volume", "valor");
            dims.put("Quantidade", "qtde");
            dims.put("RIV", "riv");
        }
        this.dimensoes = dims;
    }

    public boolean isComposto() {
        return TIPO_COMPOSTO.equals(this.tipo);
    }

    public List<String> vendas(String visao) {
        return aplica(this.vendas, visao);
    }

    public List<String> cancelamentos(String visao) {
        return aplica(this.cancelamentos, visao);
    }

    public List<String> pendentes(String visao) {
        return aplica(this.pendentes, visao);
    }

    public String contatos(String visao) {
        return this.contatos == null ? null : this.contatos.resolver(visao);
    }

    /**
     * Mapa ordenado topico resolvido -> blocos efetivos, cobrindo vendas,
     * cancelamentos, pendentes e contatos. Quando o mesmo topico aparece mais
     * de uma vez com blocos diferentes, os blocos sao unidos ("{a}"+"{b}" =>
     * "{a,b}"), pois o transporte nao distingue duas assinaturas do mesmo
     * topico na mesma sessao.
     */
    public LinkedHashMap<String, String> topicosBlocos(String visao) {
        LinkedHashMap<String, String> mapa = new LinkedHashMap<String, String>();
        acumular(mapa, this.vendas, visao);
        acumular(mapa, this.cancelamentos, visao);
        acumular(mapa, this.pendentes, visao);
        if (this.contatos != null) {
            acumular(mapa, Collections.singletonList(this.contatos), visao);
        }
        return mapa;
    }

    private void acumular(LinkedHashMap<String, String> mapa, List<Topico> topicos, String visao) {
        for (Topico t : topicos) {
            String resolvido = t.resolver(visao);
            String blocosEfetivos = t.blocos != null ? t.blocos : this.blocos;
            String atual = mapa.get(resolvido);
            mapa.put(resolvido, atual == null ? blocosEfetivos : unirBlocos(atual, blocosEfetivos));
        }
    }

    public static String unirBlocos(String a, String b) {
        LinkedHashSet<String> itens = new LinkedHashSet<String>();
        separarBlocos(a, itens);
        separarBlocos(b, itens);
        StringBuilder sb = new StringBuilder("{");
        for (String item : itens) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(item);
        }
        return sb.append('}').toString();
    }

    private static void separarBlocos(String blocos, LinkedHashSet<String> destino) {
        if (blocos == null) {
            return;
        }
        for (String parte : blocos.replace("{", "").replace("}", "").split(",")) {
            String item = parte.trim();
            if (!item.isEmpty()) {
                destino.add(item);
            }
        }
    }

    public boolean temCancelamentos() {
        return this.cancelamentos != null && !this.cancelamentos.isEmpty();
    }

    public boolean temPendentes() {
        return this.pendentes != null && !this.pendentes.isEmpty();
    }

    public boolean temContatos() {
        return this.contatos != null;
    }

    private static List<String> aplica(List<Topico> topicos, String visao) {
        ArrayList<String> resolvidos = new ArrayList<String>();
        if (topicos != null) {
            for (Topico t : topicos) {
                resolvidos.add(t.resolver(visao));
            }
        }
        return resolvidos;
    }

    public static Map<String, ProdutoConfig> todos() {
        if (CACHE != null) {
            return CACHE;
        }
        synchronized (ProdutoConfig.class) {
            if (CACHE != null) {
                return CACHE;
            }
            LinkedHashMap<String, ProdutoConfig> produtos = new LinkedHashMap<String, ProdutoConfig>();
            try {
                InputStream in = ProdutoConfig.class.getResourceAsStream("/produtos.json");
                if (in != null) {
                    int n;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    in.close();
                    Object json = Json.parse(new String(out.toByteArray(), "UTF-8"));
                    if (json instanceof Map) {
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) json).entrySet()) {
                            if (!(entry.getValue() instanceof Map)) continue;
                            String chave = (String) entry.getKey();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> cfg = (Map<String, Object>) entry.getValue();
                            produtos.put(chave, new ProdutoConfig(chave, cfg));
                        }
                    }
                }
            }
            catch (Exception e) {
                // catalogo ausente/invalido: segue com o mapa vazio
            }
            CACHE = produtos;
            return CACHE;
        }
    }

    public static ProdutoConfig get(String chave) {
        return todos().get(chave);
    }

    private static List<Topico> listaTopicos(Object raw) {
        ArrayList<Topico> topicos = new ArrayList<Topico>();
        if (raw instanceof List) {
            for (Object e : (List<?>) raw) {
                Topico t = topico(e);
                if (t != null) {
                    topicos.add(t);
                }
            }
        }
        return topicos;
    }

    private static Topico topico(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) raw;
            String template = str(m.get("topico"), null);
            if (template == null) {
                return null;
            }
            return new Topico(template, str(m.get("blocos"), null));
        }
        return new Topico(raw.toString(), null);
    }

    private static PrestamistaOperacoes prestamista(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) raw;
        String topico = str(m.get("topico"), null);
        if (topico == null) {
            return null;
        }
        return new PrestamistaOperacoes(
                topico,
                str(m.get("campo"), "qtde"),
                str(m.get("rotulo"), "Operações com Prestamista"),
                str(m.get("blocos"), null));
    }

    private static List<String> listaStr(Object raw) {
        ArrayList<String> itens = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object e : (List<?>) raw) {
                if (e == null) continue;
                itens.add(e.toString());
            }
        }
        return itens;
    }

    private static String str(Object valor, String padrao) {
        return valor == null ? padrao : valor.toString();
    }
}
