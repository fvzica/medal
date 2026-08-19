package bb.apigol.core;

import bb.apigol.ws.GolHttpClient;
import bb.apigol.ws.GolWebSocketClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProdutoEngine {
    // Timeout curto da consulta auxiliar de prestamista: ela roda em paralelo e
    // nao pode segurar o painel caso o GOL nao responda ao topico com os
    // blocos de credito (associacao ainda em validacao — ver README).
    private static final int TIMEOUT_PRESTAMISTA = 8;
    // Cada produto exige uma sessao WebSocket propria (o transporte nao
    // distingue o mesmo topico assinado com blocos diferentes), entao o
    // unificado e o composto consultam em paralelo com um teto de sessoes.
    private static final int MAX_PARALELO = 6;
    private static final int TIMEOUT_UNIFICADO = 12;

    private final String cookie;
    private final int timeout;

    public ProdutoEngine(String cookie, int timeout) {
        this.cookie = cookie;
        this.timeout = timeout;
    }

    /** Ponto de entrada por produto: despacha entre padrao e composto. */
    public List<Map<String, Object>> consolidar(ProdutoConfig produto, String prefixo, String visao, String nivel, String carteira, Map<Long, String> nomes) throws Exception {
        if (produto.isComposto()) {
            return consolidarComposto(produto, prefixo, visao, nivel, carteira, nomes);
        }
        return consolidarProduto(produto, prefixo, visao, nivel, carteira, nomes);
    }

    public List<Map<String, Object>> consolidarProduto(ProdutoConfig produto, String prefixo, String visao, String nivel, String carteira, Map<Long, String> nomes) throws Exception {
        return consolidarProduto(produto, prefixo, visao, nivel, carteira, nomes, this.timeout);
    }

    private List<Map<String, Object>> consolidarProduto(ProdutoConfig produto, String prefixo, String visao, String nivel, String carteira, Map<Long, String> nomes, int timeoutSec) throws Exception {
        boolean porCarteira = "carteira".equals(nivel);
        String tipoChave = porCarteira ? "carteira" : "prefixo";
        String visaoEfetiva = porCarteira ? "carteira" : visao;
        String carteiraParam = porCarteira ? null : carteira;
        List<String> vendas = produto.vendas(visaoEfetiva);
        List<String> cancelamentos = produto.cancelamentos(visaoEfetiva);
        List<String> pendentes = produto.pendentes(visaoEfetiva);
        String contatos = produto.contatos(visaoEfetiva);
        LinkedHashMap<String, String> topicos = produto.topicosBlocos(visaoEfetiva);
        if (topicos.isEmpty()) {
            return Collections.emptyList();
        }

        ExecutorService execPrestamista = null;
        Future<Object> prestamistaFuturo = null;
        final ProdutoConfig.PrestamistaOperacoes prestamista = produto.prestamistaOperacoes;
        if (prestamista != null) {
            final String topicoPrestamista = String.format(prestamista.topico, visaoEfetiva);
            final String blocosPrestamista = prestamista.blocos != null ? prestamista.blocos : produto.blocos;
            final String prefixoFinal = prefixo;
            final String carteiraFinal = carteiraParam;
            final int timeoutPrestamista = Math.min(timeoutSec, TIMEOUT_PRESTAMISTA);
            execPrestamista = novoExecutor(1, "apigol-prestamista");
            prestamistaFuturo = execPrestamista.submit(new Callable<Object>() {
                public Object call() throws Exception {
                    return new GolWebSocketClient(ProdutoEngine.this.cookie, timeoutPrestamista)
                            .consultarUm(topicoPrestamista, prefixoFinal, carteiraFinal, blocosPrestamista);
                }
            });
        }

        Map<String, Object> respostas;
        try {
            respostas = new GolWebSocketClient(this.cookie, timeoutSec).consultarMulti(topicos, prefixo, carteiraParam);
        }
        catch (Exception e) {
            if (execPrestamista != null) {
                execPrestamista.shutdownNow();
            }
            throw e;
        }
        List<Object> dadosVendas = coletar(vendas, respostas);
        List<Object> dadosCancelamentos = coletar(cancelamentos, respostas);
        List<Object> dadosPendentes = coletar(pendentes, respostas);
        Object dadosContatos = contatos != null ? respostas.get(contatos) : null;

        LinkedHashMap<String, Map<Long, Double>> extras = null;
        if (prestamistaFuturo != null) {
            Object dadosPrestamista = null;
            try {
                dadosPrestamista = prestamistaFuturo.get(TIMEOUT_PRESTAMISTA + 2, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                // best-effort: sem resposta, a coluna sai zerada
            }
            execPrestamista.shutdownNow();
            extras = new LinkedHashMap<String, Map<Long, Double>>();
            extras.put(prestamista.rotulo, Normalizer.somarCampoRaw(dadosPrestamista, prestamista.campo, tipoChave));
        }

        if (porCarteira) {
            return Normalizer.consolidarDim(dadosVendas, dadosCancelamentos, dadosPendentes, dadosContatos, null, null, "carteira", produto.dimensoes, produto.metricaVendasRotulo, produto.contatosCampo, extras);
        }
        Long prefixoConsultado = null;
        try {
            prefixoConsultado = Long.parseLong(prefixo.trim());
        }
        catch (Exception e) {
            // prefixo nao numerico: nao ha linha propria a remover
        }
        return Normalizer.consolidarDim(dadosVendas, dadosCancelamentos, dadosPendentes, dadosContatos, nomes, prefixoConsultado, "prefixo", produto.dimensoes, produto.metricaVendasRotulo, produto.contatosCampo, extras);
    }

    /**
     * Produto composto (requisito do apigol.docx): Credito Total = soma de
     * Credito Pessoal + Consignado + Veiculo + Demais, unida por chave de
     * negocio (Prefixo no nivel regional, Carteira no nivel carteira). O bloco
     * {1000} nao participa — a soma dos componentes e a fonte de verdade.
     */
    public List<Map<String, Object>> consolidarComposto(ProdutoConfig composto, String prefixo, String visao, String nivel, String carteira, Map<Long, String> nomes) throws Exception {
        final List<ProdutoConfig> componentes = new ArrayList<ProdutoConfig>();
        for (String chave : composto.componentes) {
            ProdutoConfig componente = ProdutoConfig.get(chave);
            if (componente == null || componente.isComposto()) continue;
            componentes.add(componente);
        }
        if (componentes.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<Map<String, Object>>> porComponente = consultarEmParalelo(componentes, prefixo, visao, nivel, carteira, nomes, this.timeout);
        boolean porCarteira = "carteira".equals(nivel);
        return mesclarComposto(porComponente, composto.metricasSomadas, porCarteira ? "Carteira" : "Prefixo");
    }

    private List<List<Map<String, Object>>> consultarEmParalelo(List<ProdutoConfig> produtos, final String prefixo, final String visao, final String nivel, final String carteira, final Map<Long, String> nomes, final int timeoutSec) throws Exception {
        ExecutorService exec = novoExecutor(Math.min(MAX_PARALELO, produtos.size()), "apigol-produto");
        try {
            List<Future<List<Map<String, Object>>>> futuros = new ArrayList<Future<List<Map<String, Object>>>>();
            for (final ProdutoConfig produto : produtos) {
                futuros.add(exec.submit(new Callable<List<Map<String, Object>>>() {
                    public List<Map<String, Object>> call() {
                        try {
                            return ProdutoEngine.this.consolidarProduto(produto, prefixo, visao, nivel, carteira, nomes, timeoutSec);
                        }
                        catch (Exception e) {
                            // falha isolada de um produto nao derruba o conjunto
                            return Collections.emptyList();
                        }
                    }
                }));
            }
            List<List<Map<String, Object>>> resultados = new ArrayList<List<Map<String, Object>>>();
            for (Future<List<Map<String, Object>>> futuro : futuros) {
                resultados.add(futuro.get());
            }
            return resultados;
        }
        finally {
            exec.shutdownNow();
        }
    }

    /**
     * Uniao por chave de negocio, nunca por posicao: soma as metricas de cada
     * componente por Prefixo/Carteira, com zero para valores ausentes. "Nome"
     * e identificadores nao sao somados. "metricas" fixa as colunas e a ordem;
     * vazia, usa a uniao das colunas numericas encontradas.
     */
    static List<Map<String, Object>> mesclarComposto(List<List<Map<String, Object>>> porComponente, List<String> metricas, String colunaChave) {
        LinkedHashSet<String> colunas = new LinkedHashSet<String>();
        if (metricas != null) {
            colunas.addAll(metricas);
        }
        if (colunas.isEmpty()) {
            for (List<Map<String, Object>> linhas : porComponente) {
                for (Map<String, Object> linha : linhas) {
                    for (Map.Entry<String, Object> celula : linha.entrySet()) {
                        String coluna = celula.getKey();
                        if (coluna.equals("Prefixo") || coluna.equals("Carteira") || coluna.equals("Nome")) continue;
                        if (!(celula.getValue() instanceof Number)) continue;
                        colunas.add(coluna);
                    }
                }
            }
        }
        LinkedHashMap<Long, double[]> somas = new LinkedHashMap<Long, double[]>();
        LinkedHashMap<Long, String> nomesPorChave = new LinkedHashMap<Long, String>();
        List<String> ordemColunas = new ArrayList<String>(colunas);
        for (List<Map<String, Object>> linhas : porComponente) {
            if (linhas == null) continue;
            for (Map<String, Object> linha : linhas) {
                Long chave = Normalizer.canon(linha.get(colunaChave));
                if (chave == null) continue;
                double[] valores = somas.get(chave);
                if (valores == null) {
                    valores = new double[ordemColunas.size()];
                    somas.put(chave, valores);
                }
                for (int i = 0; i < ordemColunas.size(); ++i) {
                    Object valor = linha.get(ordemColunas.get(i));
                    if (valor == null) continue;
                    valores[i] += Normalizer.num(valor);
                }
                Object nome = linha.get("Nome");
                if (nome != null && !nome.toString().isEmpty() && (nomesPorChave.get(chave) == null || nomesPorChave.get(chave).isEmpty())) {
                    nomesPorChave.put(chave, nome.toString());
                }
            }
        }
        ArrayList<Map<String, Object>> resultado = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Long, double[]> entrada : somas.entrySet()) {
            LinkedHashMap<String, Object> linha = new LinkedHashMap<String, Object>();
            linha.put(colunaChave, entrada.getKey());
            String nome = nomesPorChave.get(entrada.getKey());
            linha.put("Nome", nome == null ? "" : nome);
            for (int i = 0; i < ordemColunas.size(); ++i) {
                linha.put(ordemColunas.get(i), Normalizer.round(entrada.getValue()[i]));
            }
            resultado.add(linha);
        }
        final String colunaOrdenacao = colunaChave;
        resultado.sort(Comparator.comparingLong(linha -> (Long) linha.get(colunaOrdenacao)));
        return resultado;
    }

    /**
     * Visao unificada. Cada produto-base e consultado em sessao WebSocket
     * propria (em paralelo): com o catalogo completo o mesmo topico aparece em
     * varios produtos com blocos diferentes, o que nao pode dividir sessao.
     * Compostos sao derivados dos componentes ja consultados, sem re-consulta.
     */
    public Map<String, Object> consolidarUnificado(List<ProdutoConfig> selecionados, String prefixo, String visao) throws Exception {
        Map<Long, String> nomes = null;
        try {
            List<Map<String, Object>> jurisdicao = new GolHttpClient(this.cookie, this.timeout).listaJurisdicao(prefixo);
            if (jurisdicao != null && !jurisdicao.isEmpty()) {
                nomes = Normalizer.mapaNomes(jurisdicao);
            }
        }
        catch (Exception e) {
            // sem nomes, as linhas saem apenas com o prefixo
        }

        LinkedHashMap<String, ProdutoConfig> bases = new LinkedHashMap<String, ProdutoConfig>();
        for (ProdutoConfig produto : selecionados) {
            if (produto.isComposto()) {
                for (String chave : produto.componentes) {
                    ProdutoConfig componente = ProdutoConfig.get(chave);
                    if (componente == null || componente.isComposto()) continue;
                    bases.put(componente.chave, componente);
                }
            } else {
                bases.put(produto.chave, produto);
            }
        }
        List<ProdutoConfig> listaBases = new ArrayList<ProdutoConfig>(bases.values());
        int timeoutBase = Math.min(this.timeout, TIMEOUT_UNIFICADO);
        List<List<Map<String, Object>>> resultadosBases = consultarEmParalelo(listaBases, prefixo, visao, "regional", null, nomes, timeoutBase);
        LinkedHashMap<String, List<Map<String, Object>>> linhasPorBase = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (int i = 0; i < listaBases.size(); ++i) {
            linhasPorBase.put(listaBases.get(i).chave, resultadosBases.get(i));
        }

        LinkedHashMap<Long, Map<String, Object>> linhasPorPrefixo = new LinkedHashMap<Long, Map<String, Object>>();
        if (nomes != null) {
            for (Map.Entry<Long, String> entry : nomes.entrySet()) {
                LinkedHashMap<String, Object> linha = new LinkedHashMap<String, Object>();
                linha.put("Prefixo", entry.getKey());
                linha.put("Nome", entry.getValue());
                linhasPorPrefixo.put(entry.getKey(), linha);
            }
        }
        List<Map<String, String>> produtosResumo = new ArrayList<Map<String, String>>();
        for (ProdutoConfig produto : selecionados) {
            List<Map<String, Object>> linhasProduto;
            if (produto.isComposto()) {
                List<List<Map<String, Object>>> porComponente = new ArrayList<List<Map<String, Object>>>();
                for (String chave : produto.componentes) {
                    List<Map<String, Object>> linhasComponente = linhasPorBase.get(chave);
                    if (linhasComponente == null) continue;
                    porComponente.add(linhasComponente);
                }
                linhasProduto = mesclarComposto(porComponente, produto.metricasSomadas, "Prefixo");
            } else {
                linhasProduto = linhasPorBase.get(produto.chave);
                if (linhasProduto == null) {
                    linhasProduto = Collections.emptyList();
                }
            }
            LinkedHashMap<String, String> resumo = new LinkedHashMap<String, String>();
            resumo.put("chave", produto.chave);
            resumo.put("label", produto.label);
            resumo.put("grupo", produto.grupo);
            produtosResumo.add(resumo);
            for (Map<String, Object> linhaProduto : linhasProduto) {
                Long chave = ((Number) linhaProduto.get("Prefixo")).longValue();
                Map<String, Object> linha = linhasPorPrefixo.get(chave);
                if (linha == null) {
                    linha = new LinkedHashMap<String, Object>();
                    linha.put("Prefixo", chave);
                    linha.put("Nome", linhaProduto.get("Nome"));
                    linhasPorPrefixo.put(chave, linha);
                }
                for (Map.Entry<String, Object> celula : linhaProduto.entrySet()) {
                    String coluna = celula.getKey();
                    if (coluna.equals("Prefixo") || coluna.equals("Nome")) continue;
                    linha.put(produto.label + " | " + coluna, celula.getValue());
                }
            }
        }
        ArrayList<Map<String, Object>> linhas = new ArrayList<Map<String, Object>>(linhasPorPrefixo.values());
        linhas.sort(Comparator.comparingLong(linha -> ((Number) linha.get("Prefixo")).longValue()));
        LinkedHashMap<String, Object> resposta = new LinkedHashMap<String, Object>();
        resposta.put("prefixo", prefixo);
        resposta.put("visao", visao);
        resposta.put("produtos", produtosResumo);
        resposta.put("total_linhas", linhas.size());
        resposta.put("totais", totaisUnificado(linhas));
        resposta.put("linhas", linhas);
        return resposta;
    }

    private static Map<String, Object> totaisUnificado(List<Map<String, Object>> linhas) {
        LinkedHashMap<String, Object> totais = new LinkedHashMap<String, Object>();
        if (linhas.isEmpty()) {
            return totais;
        }
        LinkedHashSet<String> colunas = new LinkedHashSet<String>();
        for (Map<String, Object> linha : linhas) {
            colunas.addAll(linha.keySet());
        }
        for (String coluna : colunas) {
            if (coluna.equals("Prefixo") || coluna.equals("Nome")) continue;
            double soma = 0.0;
            boolean temNumero = false;
            for (Map<String, Object> linha : linhas) {
                Object valor = linha.get(coluna);
                if (!(valor instanceof Number)) continue;
                soma += ((Number) valor).doubleValue();
                temNumero = true;
            }
            if (!temNumero) continue;
            totais.put(coluna, (double) Math.round(soma * 100.0) / 100.0);
        }
        return totais;
    }

    private static List<Object> coletar(List<String> topicos, Map<String, Object> respostas) {
        ArrayList<Object> dados = new ArrayList<Object>();
        for (String topico : topicos) {
            if (respostas.get(topico) == null) continue;
            dados.add(respostas.get(topico));
        }
        return dados;
    }

    private static ExecutorService novoExecutor(int threads, final String nome) {
        return Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, nome + "-" + this.seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }
}
