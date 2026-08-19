package bb.apigol.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Valida sem rede as regras do apigol.docx: catalogo, coluna de prestamista e
 * Credito Total composto. Roda com o build/classes no classpath:
 *   java -cp build/classes:build/test-classes bb.apigol.core.EngineSelfTest
 */
public final class EngineSelfTest {
    private static int falhas = 0;

    public static void main(String[] args) {
        catalogo();
        unirBlocos();
        consolidarComExtras();
        compostoSomaPorChave();
        if (falhas > 0) {
            System.err.println("FALHOU: " + falhas + " verificacao(oes)");
            System.exit(1);
        }
        System.out.println("OK: todas as verificacoes passaram");
    }

    private static void catalogo() {
        Map<String, ProdutoConfig> todos = ProdutoConfig.todos();
        check("catalogo com 49 produtos", todos.size() == 49);

        ProdutoConfig total = todos.get("credito_total");
        check("credito_total existe", total != null);
        check("credito_total e composto", total != null && total.isComposto());
        check("credito_total sem topicos proprios (nao usa bloco {1000})", total != null && total.topicosBlocos("jurisdicao").isEmpty());
        check("credito_total soma os 4 componentes", total != null && total.componentes.equals(
                Arrays.asList("credito_pessoal", "credito_consignado", "credito_veiculo", "credito_demais")));
        check("credito_total soma Operações com Prestamista", total != null && total.metricasSomadas.contains("Operações com Prestamista"));

        for (String chave : Arrays.asList("credito_pessoal", "credito_consignado", "credito_veiculo", "credito_demais")) {
            ProdutoConfig credito = todos.get(chave);
            check(chave + " existe", credito != null);
            if (credito == null) continue;
            check(chave + " tem prestamista_operacoes", credito.prestamistaOperacoes != null);
            if (credito.prestamistaOperacoes != null) {
                check(chave + " topico do prestamista", "web~gol2~cdc~prestamista~relacionamento~vendas-%s".equals(credito.prestamistaOperacoes.topico));
                check(chave + " campo qtde", "qtde".equals(credito.prestamistaOperacoes.campo));
                check(chave + " rotulo do requisito", "Operações com Prestamista".equals(credito.prestamistaOperacoes.rotulo));
            }
            check(chave + " dimensao Com Prestamista", credito.dimensoes.containsKey("Com Prestamista"));
        }
        check("blocos pessoal {1002}", "{1002}".equals(todos.get("credito_pessoal").blocos));
        check("blocos consignado {1001}", "{1001}".equals(todos.get("credito_consignado").blocos));
        check("blocos veiculo {1003}", "{1003}".equals(todos.get("credito_veiculo").blocos));
        check("blocos demais {1004}", "{1004}".equals(todos.get("credito_demais").blocos));

        ProdutoConfig seguridade = todos.get("seguridade_total");
        check("seguridade_total existe", seguridade != null);
        if (seguridade != null) {
            LinkedHashMap<String, String> topicos = seguridade.topicosBlocos("jurisdicao");
            check("seguridade: bpr vendas com blocos proprios", "{3040,3041}".equals(topicos.get("web~gol2~bpr~seguridade~relacionamento~vendas-jurisdicao")));
            check("seguridade: contatos bic com blocos proprios", "{3040,3071,3072,3030,3031,3032,3010,3011,3012,3013,3021,3020,3000,3060,3050}".equals(topicos.get("web~gol2~bic~relacionamento~jurisdicao")));
            check("seguridade: svl vendas {3000}", "{3000}".equals(topicos.get("web~gol2~svl~relacionamento~vendas-jurisdicao")));
        }

        ProdutoConfig regulariza = todos.get("bb_regulariza_agro");
        check("regulariza: swp {5101} nas vendas", regulariza != null
                && "{5101}".equals(regulariza.topicosBlocos("jurisdicao").get("web~gol2~swp~relacionamento~jurisdicao")));
        check("regulariza: liquidacao {5200}", regulariza != null
                && "{5200}".equals(regulariza.topicosBlocos("jurisdicao").get("web~gol2~cop~relacionamento~liquidacao-prevista-dia-jurisdicao")));

        ProdutoConfig prestamista = todos.get("prestamista");
        check("prestamista preservado (4 origens de vendas)", prestamista != null && prestamista.vendas.size() == 4);
        check("prestamista blocos {3030,3031}", prestamista != null && "{3030,3031}".equals(prestamista.blocos));
    }

    private static void unirBlocos() {
        check("uniao de blocos", "{1001,1002,1003}".equals(ProdutoConfig.unirBlocos("{1001,1002}", "{1002,1003}")));
        check("uniao preserva ordem", "{3040,3041,3071}".equals(ProdutoConfig.unirBlocos("{3040,3041}", "{3071}")));
    }

    private static Map<String, Object> linha(Object... pares) {
        LinkedHashMap<String, Object> mapa = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put((String) pares[i], pares[i + 1]);
        }
        return mapa;
    }

    private static void consolidarComExtras() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("data", Arrays.asList(
                linha("prefixo", 1L, "valor", 100.0, "valor_qualificado", 10.0, "qtde", 4L, "qtde_com_prestamista", 2L),
                linha("prefixo", 2L, "valor", 50.0, "valor_qualificado", 5.0, "qtde", 1L)));
        LinkedHashMap<String, String> dims = new LinkedHashMap<String, String>();
        dims.put("Desembolso", "valor");
        dims.put("RIV", "valor_qualificado");
        dims.put("Quantidade", "qtde");
        dims.put("Com Prestamista", "qtde_com_prestamista");
        LinkedHashMap<String, Map<Long, Double>> extras = new LinkedHashMap<String, Map<Long, Double>>();
        LinkedHashMap<Long, Double> porChave = new LinkedHashMap<Long, Double>();
        porChave.put(1L, 3.0);
        porChave.put(7L, 9.0);
        extras.put("Operações com Prestamista", porChave);
        List<Map<String, Object>> linhas = Normalizer.consolidarDim(
                new ArrayList<Object>(Arrays.<Object>asList(payload)), null, null, null, null, 9007L,
                "prefixo", dims, "Desembolso", "qtde", extras);
        check("extras: 3 linhas (1, 2 e 7)", linhas.size() == 3);
        Map<String, Object> l1 = linhas.get(0);
        check("extras: prefixo 1 com prestamista via topico = 3", eq(l1.get("Operações com Prestamista"), 3.0) && eq(l1.get("Prefixo"), 1L));
        check("extras: prefixo 1 com prestamista via payload = 2", eq(l1.get("Com Prestamista"), 2.0));
        check("extras: prefixo 2 sem prestamista = 0", eq(linhas.get(1).get("Operações com Prestamista"), 0.0));
        Map<String, Object> l7 = linhas.get(2);
        check("extras: chave so do prestamista entra zerada nas demais", eq(l7.get("Prefixo"), 7L) && eq(l7.get("Desembolso"), 0.0) && eq(l7.get("Operações com Prestamista"), 9.0));
        Map<String, Object> totais = Normalizer.totais(linhas);
        check("extras: total da coluna do requisito = 12", eq(totais.get("Operações com Prestamista"), 12.0));
    }

    private static void compostoSomaPorChave() {
        List<Map<String, Object>> pessoal = Arrays.asList(
                linha("Prefixo", 10L, "Nome", "AG CENTRO", "Desembolso", 100.0, "RIV", 10.0, "Quantidade", 2.0, "Com Prestamista", 1.0, "Operações com Prestamista", 1.0, "Contatos", 5.0),
                linha("Prefixo", 20L, "Nome", "AG NORTE", "Desembolso", 30.0, "RIV", 3.0, "Quantidade", 1.0, "Com Prestamista", 0.0, "Operações com Prestamista", 0.0, "Contatos", 2.0));
        List<Map<String, Object>> consignado = Arrays.asList(
                linha("Prefixo", 20L, "Nome", "AG NORTE", "Desembolso", 70.0, "RIV", 7.0, "Quantidade", 3.0, "Com Prestamista", 2.0, "Operações com Prestamista", 2.0, "Contatos", 1.0),
                linha("Prefixo", 30L, "Nome", "", "Desembolso", 5.0, "RIV", 0.5, "Quantidade", 1.0));
        List<String> metricas = Arrays.asList("Desembolso", "RIV", "Quantidade", "Com Prestamista", "Operações com Prestamista", "Contatos");
        List<List<Map<String, Object>>> porComponente = new ArrayList<List<Map<String, Object>>>();
        porComponente.add(pessoal);
        porComponente.add(consignado);
        List<Map<String, Object>> soma = ProdutoEngine.mesclarComposto(porComponente, metricas, "Prefixo");
        check("composto: 3 chaves", soma.size() == 3);
        Map<String, Object> p10 = soma.get(0);
        Map<String, Object> p20 = soma.get(1);
        Map<String, Object> p30 = soma.get(2);
        check("composto: ordenado por prefixo", eq(p10.get("Prefixo"), 10L) && eq(p20.get("Prefixo"), 20L) && eq(p30.get("Prefixo"), 30L));
        check("composto: soma por chave (20 = 30+70)", eq(p20.get("Desembolso"), 100.0));
        check("composto: nome nao e somado", "AG NORTE".equals(p20.get("Nome")));
        check("composto: ausente vale zero (30 sem Contatos)", eq(p30.get("Contatos"), 0.0));
        check("composto: prestamista somado (20 = 0+2)", eq(p20.get("Operações com Prestamista"), 2.0));
        check("composto: chave presente em um so componente", eq(p10.get("Desembolso"), 100.0) && eq(p30.get("Desembolso"), 5.0));
        check("composto: colunas na ordem configurada", new ArrayList<String>(p10.keySet()).equals(
                Arrays.asList("Prefixo", "Nome", "Desembolso", "RIV", "Quantidade", "Com Prestamista", "Operações com Prestamista", "Contatos")));
    }

    private static boolean eq(Object valor, double esperado) {
        return valor instanceof Number && Math.abs(((Number) valor).doubleValue() - esperado) < 1e-9;
    }

    private static boolean eq(Object valor, long esperado) {
        return valor instanceof Number && ((Number) valor).longValue() == esperado;
    }

    private static void check(String descricao, boolean ok) {
        if (ok) {
            System.out.println("  ok  " + descricao);
        } else {
            System.err.println("FALHA " + descricao);
            ++falhas;
        }
    }

    private EngineSelfTest() {
    }
}
