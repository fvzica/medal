package bb.apigol.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Config {
    public static final String[] TPL_VENDAS = new String[]{"web~gol2~als~relacionamento~vendas-%s", "web~gol2~bbs~relacionamento~vendas-%s", "web~gol2~cdc~prestamista~relacionamento~vendas-%s", "web~gol2~seg~relacionamento~vendas-%s"};
    public static final String[] TPL_CANCELAMENTOS = new String[]{"web~gol2~als~relacionamento~cancelamentos-%s", "web~gol2~seg~relacionamento~cancelamentos-%s"};
    public static final String[] TPL_PENDENTES = new String[]{"web~gol2~als~relacionamento~pnd-vendas-%s"};
    public static final String TPL_CONTATOS = "web~gol2~bic~relacionamento~%s";
    public static final Set<String> VISOES = new LinkedHashSet<String>(Arrays.asList("jurisdicao", "origem", "canais"));
    public static final Set<String> NIVEIS = new LinkedHashSet<String>(Arrays.asList("regional", "prefixo", "carteira"));
    public static final Map<String, String> GLOSSARIO = Config.ordered(
            "Vendas", "Novas contratacoes de prestamista no periodo (soma das origens: als, bbs, cdc, seg).",
            "Cancelamentos", "Cancelamentos/estornos no periodo (soma das origens: als, seg).",
            "Total", "Saldo liquido = Vendas - Cancelamentos.",
            "RS", "Valor em Reais (R$) das operacoes.",
            "Volume", "Volume financeiro (campo 'valor' do GOL).",
            "Quantidade", "Quantidade de operacoes/contratos (campo 'qtde').",
            "RIV", "Indicador de resultado/receita (campo 'riv').",
            "Contatos", "Quantidade de contatos efetuados (origem bic).",
            "Pendentes", "Pendentes (Duplo Sim) - contratacoes aguardando confirmacao (origem als).",
            "Desembolso", "Valor desembolsado no periodo nos produtos de credito (origem cop, campo 'valor').",
            "Com Prestamista", "Quantidade de operacoes de credito com seguro prestamista informada no proprio desembolso (campo 'qtde_com_prestamista').",
            "Operações com Prestamista", "Quantidade de operacoes com prestamista consultada no topico cdc/prestamista com os blocos do credito (campo 'qtde').",
            "Crédito Total", "Soma de Credito Pessoal + Credito Consignado + Credito Veiculo + Credito Demais, consolidada por Prefixo/Carteira.");

    private static List<String> aplicar(String[] templates, String visao) {
        ArrayList<String> resolvidos = new ArrayList<String>();
        for (String template : templates) {
            resolvidos.add(String.format(template, visao));
        }
        return resolvidos;
    }

    public static List<String> vendas(String visao) {
        return Config.aplicar(TPL_VENDAS, visao);
    }

    public static List<String> cancelamentos(String visao) {
        return Config.aplicar(TPL_CANCELAMENTOS, visao);
    }

    public static List<String> pendentes(String visao) {
        return Config.aplicar(TPL_PENDENTES, visao);
    }

    public static String contatos(String visao) {
        return String.format(TPL_CONTATOS, visao);
    }

    private static Map<String, String> ordered(String... pares) {
        LinkedHashMap<String, String> mapa = new LinkedHashMap<String, String>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put(pares[i], pares[i + 1]);
        }
        return Collections.unmodifiableMap(mapa);
    }

    private Config() {
    }
}
