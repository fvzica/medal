package br.com.bb.atlasestilo.core;

import java.io.File;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.com.bb.atlasestilo.dao.FonteDao;
import br.com.bb.atlasestilo.dao.FonteDao.Fonte;
import br.com.bb.atlasestilo.util.Json;

/**
 * Monitor da pasta de CSV: a cada N minutos (parâmetro csv.monitor.minutos,
 * 0 = desligado) reimporta as fontes automáticas cujo arquivo mudou
 * (data de modificação diferente da última leitura). Roda numa thread
 * daemon única; falhas de uma fonte não derrubam as demais.
 */
public final class MonitorCsv {

    private MonitorCsv() { }

    private static final Logger LOG = Logger.getLogger("atlasestilo.monitor");
    private static ScheduledExecutorService exec;
    private static final Object TRAVA = new Object();
    private static volatile long ultimaExecucao;
    private static volatile String ultimoResultado = "null";

    public static synchronized void iniciar() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "atlasestilo-monitor-csv");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(MonitorCsv::tique, 20, 30, TimeUnit.SECONDS);
    }

    public static synchronized void parar() {
        if (exec == null) return;
        // no redeploy, espera a importação em andamento acabar: a versão nova não pode migrar o banco
        // enquanto a thread antiga ainda escreve nele
        exec.shutdownNow();
        try {
            if (!exec.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warning("Monitor de CSV: importação ainda em andamento após 60 s no encerramento.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exec = null;
    }

    static void tique() {
        try {
            int minutos = Integer.parseInt(FonteDao.param(FonteDao.P_MINUTOS, "10"));
            if (minutos <= 0) return;
            long agora = System.currentTimeMillis();
            if (agora - ultimaExecucao < minutos * 60000L) return;
            ultimoResultado = rodar(agora, false);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Monitor de CSV: " + t.getMessage(), t);
        }
    }

    /**
     * Varre as fontes ativas: importa as que mudaram (ou todas, se forcar).
     * Devolve um resumo JSON por fonte.
     */
    public static String rodar(long agora, boolean forcar) throws SQLException {
        synchronized (TRAVA) {
            ultimaExecucao = agora;
            File pasta = FonteService.pasta();
            Json.Arr itens = Json.arr();
            int importadas = 0;
            for (Fonte f : FonteDao.fontes()) {
                if (Thread.currentThread().isInterrupted()) break; // encerramento do contexto
                if (!f.ativo || (!f.automatico && !forcar)) continue;
                String acao, status = f.ultimoStatus;
                try {
                    File arq = FonteService.resolverArquivo(pasta, f.arquivo);
                    if (arq == null) {
                        acao = "sem arquivo";
                        if (!"SEM ARQUIVO".equals(f.ultimoStatus)) {
                            FonteDao.fonteStatus(f.id, null, null, "SEM ARQUIVO",
                                "Nenhum arquivo casa com \"" + f.arquivo + "\" em " + pasta.getPath(),
                                null, null, agora);
                        }
                        status = "SEM ARQUIVO";
                    } else if (!forcar && f.ultimoMtime != null && f.ultimoMtime == arq.lastModified()
                               && arq.getName().equals(f.ultimoArquivo) && !"ERRO".equals(f.ultimoStatus)) {
                        acao = "sem mudança";
                    } else {
                        String json = FonteService.analisarFonte(f.id, true, FonteService.MATRICULA_MONITOR, agora);
                        Fonte depois = FonteDao.fonte(f.id);
                        status = depois == null ? "?" : depois.ultimoStatus;
                        acao = json.contains("\"confirmado\":true") ? "importado" : "não gravado";
                        if (acao.equals("importado")) importadas++;
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Fonte " + f.nome + ": " + e.getMessage(), e);
                    acao = "erro";
                    status = "ERRO";
                    FonteDao.fonteStatus(f.id, f.ultimoArquivo, null, "ERRO",
                        "Falha ao ler: " + e.getMessage(), null, null, agora);
                }
                itens.add(Json.obj().put("id", f.id).put("nome", f.nome).put("tipo", f.tipo)
                    .put("acao", acao).put("status", status).fim());
            }
            return Json.obj().put("executadoEm", agora).put("pasta", pasta.getPath())
                .put("importadas", importadas).putRaw("fontes", itens.fim()).fim();
        }
    }

    public static String estado() throws SQLException {
        int minutos = Integer.parseInt(FonteDao.param(FonteDao.P_MINUTOS, "10"));
        return Json.obj().put("ativo", exec != null && minutos > 0).put("minutos", minutos)
            .putNum("ultimaExecucao", ultimaExecucao == 0 ? null : ultimaExecucao)
            .putNum("proximaExecucao", (exec == null || minutos <= 0) ? null
                : (ultimaExecucao == 0 ? System.currentTimeMillis() : ultimaExecucao + minutos * 60000L))
            .putRaw("ultimoResultado", ultimoResultado).fim();
    }
}
