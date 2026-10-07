package br.com.bb.atlasestilo.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import br.com.bb.atlasestilo.core.DadosExemplo;
import br.com.bb.atlasestilo.db.Db;

/**
 * Subida da aplicação: resolve os caminhos do web.xml (tokens ${catalina.base},
 * relativos ancorados no Tomcat), cria as pastas, confirma escrita de verdade,
 * abre o SQLite, roda o schema idempotente e semeia os dados de exemplo quando
 * o banco está vazio.
 */
public class AppListener implements ServletContextListener {

    public static final String ATTR_FOTO_DIR    = "atlas.foto.dir.resolvido";
    public static final String ATTR_MAPS_ATIVO  = "atlas.maps.ativo";
    public static final String ATTR_DEV_SIMULAR = "atlas.dev.simular";

    @Override
    public void contextInitialized(ServletContextEvent ev) {
        ServletContext ctx = ev.getServletContext();
        String base = baseTomcat();

        String dbPath   = resolver(ctx.getInitParameter("atlas.db.path"), base);
        String fotoDir  = resolver(ctx.getInitParameter("atlas.foto.dir"), base);
        String csvDir   = resolver(ctx.getInitParameter("atlas.csv.dir"), base);
        if (dbPath == null)  dbPath  = new File(base, "dados/atlasestilo/atlas.db").getPath();
        if (fotoDir == null) fotoDir = new File(base, "dados/atlasestilo/fotos").getPath();
        if (csvDir == null)  csvDir  = new File(base, "dados/atlasestilo/csv").getPath();

        criarPastaComEscrita(new File(dbPath).getParentFile());
        criarPastaComEscrita(new File(fotoDir));
        // pasta padrão dos CSV (o Master pode apontar outra na tela Admin)
        File csv = new File(csvDir);
        if (!csv.exists() && !csv.mkdirs()) ctx.log("[atlasestilo] Não criei a pasta de CSV " + csvDir);
        br.com.bb.atlasestilo.core.FonteService.definirPastaPadrao(csvDir);

        Db.iniciar(dbPath);
        executarSchema(ctx);
        try {
            br.com.bb.atlasestilo.db.Migracoes.aplicar();
            br.com.bb.atlasestilo.dao.ConfigDao.semearMastersSeVazio();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Falha na migração/carga inicial.", e);
        }

        boolean exemplo = "true".equalsIgnoreCase(
                String.valueOf(ctx.getInitParameter("atlas.dados.exemplo")));
        try {
            // semeia só em banco vazio que nunca teve o exemplo limpo pelo Master:
            // "limpar exemplo" antes do import real não pode ressuscitar as 26 agências no próximo boot
            if (exemplo && bancoVazio() && !br.com.bb.atlasestilo.dao.ConfigDao.exemploLimpo()) {
                DadosExemplo.semear();
                ctx.log("[atlasestilo] Banco vazio: dados de exemplo semeados.");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Falha ao semear o exemplo.", e);
        }

        ctx.setAttribute(ATTR_FOTO_DIR, fotoDir);
        ctx.setAttribute(ATTR_MAPS_ATIVO,
                !"false".equalsIgnoreCase(String.valueOf(ctx.getInitParameter("atlas.maps.ativo"))));
        ctx.setAttribute(ATTR_DEV_SIMULAR,
                "true".equalsIgnoreCase(String.valueOf(ctx.getInitParameter("atlas.dev.simular"))));

        // monitor da pasta de CSV (reimporta quando o arquivo muda)
        if (!"false".equalsIgnoreCase(String.valueOf(ctx.getInitParameter("atlas.csv.monitor")))) {
            br.com.bb.atlasestilo.core.MonitorCsv.iniciar();
        }

        ctx.log("[atlasestilo] Iniciado. db=" + dbPath + " fotos=" + fotoDir + " csv=" + csvDir);
    }

    @Override
    public void contextDestroyed(ServletContextEvent ev) {
        br.com.bb.atlasestilo.core.MonitorCsv.parar();
    }

    // ------------------------------------------------------------------ apoio

    private static String baseTomcat() {
        String b = System.getProperty("catalina.base");
        if (b == null) b = System.getProperty("catalina.home");
        if (b == null) b = System.getProperty("user.dir");
        return b;
    }

    /**
     * Expande ${catalina.base}/${catalina.home}; relativo ancora na base do
     * Tomcat (nunca no diretório de trabalho do serviço); absoluto fica.
     */
    static String resolver(String valor, String base) {
        if (valor == null || valor.trim().isEmpty()) return null;
        String v = valor.trim()
                .replace("${catalina.base}", base)
                .replace("${catalina.home}", base);
        File f = new File(v);
        return (f.isAbsolute() ? f : new File(base, v)).getPath();
    }

    private static void criarPastaComEscrita(File dir) {
        if (dir == null) return;
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException(
                "Não foi possível criar a pasta de dados: " + dir
                + ". Conceda escrita à conta de serviço do Tomcat: "
                + "icacls \"" + dir + "\" /grant \"NETWORK SERVICE\":(OI)(CI)M /T");
        }
        try {
            File t = File.createTempFile("escrita", ".tmp", dir);
            if (!t.delete()) t.deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Sem permissão de ESCRITA em " + dir
                + ". Corrija com: icacls \"" + dir
                + "\" /grant \"NETWORK SERVICE\":(OI)(CI)M /T", e);
        }
    }

    private void executarSchema(ServletContext ctx) {
        try (InputStream in = ctx.getResourceAsStream("/WEB-INF/sql/schema.sql")) {
            if (in == null) throw new IllegalStateException("WEB-INF/sql/schema.sql ausente do WAR.");
            executarSql(in);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Falha ao executar o schema: " + e.getMessage(), e);
        }
    }

    /** Executa um .sql comando a comando (cada um termina em ';'). */
    public static void executarSql(InputStream in) throws IOException, SQLException {
        StringBuilder cmd = new StringBuilder();
        try (Connection c = Db.conexao();
             Statement st = c.createStatement();
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = r.readLine()) != null) {
                String s = linha.trim();
                if (s.isEmpty() || s.startsWith("--")) continue;
                cmd.append(linha).append('\n');
                if (s.endsWith(";")) {
                    st.execute(cmd.toString());
                    cmd.setLength(0);
                }
            }
            if (cmd.toString().trim().length() > 0) st.execute(cmd.toString());
        }
    }

    private static boolean bancoVazio() {
        try (Connection c = Db.conexao();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM agencia")) {
            return rs.next() && rs.getInt(1) == 0;
        } catch (SQLException e) {
            return false;
        }
    }
}
