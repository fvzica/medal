import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import br.com.bb.atlasestilo.core.DadosExemplo;
import br.com.bb.atlasestilo.core.ImportService;
import br.com.bb.atlasestilo.dao.AgenciaDao;
import br.com.bb.atlasestilo.dao.GestaoDao;
import br.com.bb.atlasestilo.dao.MetricaDao;
import br.com.bb.atlasestilo.dao.Selecao;
import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Csv;
import br.com.bb.atlasestilo.util.Texto;
import br.com.bb.atlasestilo.util.Xlsx;
import br.com.bb.atlasestilo.web.AppListener;
import br.com.bb.atlasestilo.web.Sessao;

/**
 * Self-test do Atlas Estilo: sobe um banco temporário, roda o schema real,
 * semeia os dados de exemplo e exercita DAOs, perfis e imports — sem Tomcat.
 * Falhou -> o build falha.
 */
public final class SelfTest {

    private static int verificacoes;

    public static void main(String[] args) throws Exception {
        File dir = File.createTempFile("atlasestilo-teste", "");
        if (!dir.delete() || !dir.mkdirs()) throw new IllegalStateException("temp dir");
        File dbFile = new File(dir, "atlas.db");

        Db.iniciar(dbFile.getPath());
        try (FileInputStream schema = new FileInputStream(
                new File("WebContent/WEB-INF/sql/schema.sql"))) {
            AppListener.executarSql(schema);
        }
        br.com.bb.atlasestilo.dao.ConfigDao.semearMastersSeVazio();
        DadosExemplo.semear();
        long agora = System.currentTimeMillis();

        // ---------------------------------------------------------- contagens
        verifica("agências semeadas", contar("SELECT COUNT(*) FROM agencia") == 26);
        verifica("funcis semeados", contar("SELECT COUNT(*) FROM funci") > 200);
        verifica("carteiras semeadas", contar("SELECT COUNT(*) FROM carteira") > 50);
        verifica("pdg 7 semestres/agência", contar("SELECT COUNT(*) FROM pdg") == 26 * 7);
        verifica("metas semeadas", contar("SELECT COUNT(*) FROM meta") == 26 * 5);
        verifica("masters iniciais", contar("SELECT COUNT(*) FROM config_master") == 3);

        // ------------------------------------------------------------- perfis
        Sessao master = Sessao.montar("f3548926", "Teste Master", "9999", null);
        verifica("master detectado", master != null && master.master()
                && master.regionalJurisdicao == null);
        Sessao moderador = Sessao.montar("F0000001", "Teste Moderador", "9007", null);
        verifica("moderador por prefixo 9007", moderador != null && moderador.moderador()
                && !moderador.master());
        Sessao colega = Sessao.montar("F0000002", "Teste Colega", "9101", null);
        verifica("colega com jurisdição", colega != null
                && "ESTILO SP CAPITAL".equals(colega.regionalJurisdicao));
        Sessao perdido = Sessao.montar("F0000003", "Sem Mapa", "1234", null);
        verifica("prefixo não mapeado", perdido != null
                && "NÃO MAPEADA".equals(perdido.regionalJurisdicao));

        // --------------------------------------------------------------- mapa
        String mapaMaster = AgenciaDao.mapa(master);
        verifica("mapa tem 26 pins p/ master",
                ocorrencias(mapaMaster, "\"prefixo\":") == 26);
        verifica("mapa resume por UF", mapaMaster.contains("\"uf\":\"SP\"")
                && mapaMaster.contains("\"ufs\":["));
        String mapaColega = AgenciaDao.mapa(colega);
        verifica("jurisdição limita o mapa do colega",
                ocorrencias(mapaColega, "\"prefixo\":") == 4);

        // ------------------------------------------------------------ métricas
        int spAgencias = (int) contar("SELECT COUNT(*) FROM agencia WHERE uf = 'SP'");
        String resumoSp = MetricaDao.resumo(master,
                Selecao.de(master, "SP", null, null, null), agora);
        verifica("resumo de SP conta agências",
                resumoSp.contains("\"agencias\":" + spAgencias));
        verifica("resumo traz tempos médios",
                resumoSp.contains("tempoMedioCargoMeses") && !resumoSp.contains(":NaN"));
        String listaGerentes = MetricaDao.lista(master,
                Selecao.de(master, "SP", null, null, null), "gerentes", agora);
        verifica("lista de gerentes não vazia", ocorrencias(listaGerentes, "\"matricula\":") > 10);

        // ------------------------------------------------------------- gestão
        long idVisita = GestaoDao.visitaCriar("9101", "PLANEJADA", agora + 86400000L,
                null, "Visita teste", "F3548926", agora);
        verifica("visita criada", idVisita > 0);
        verifica("visita atualizada", GestaoDao.visitaAtualizar(idVisita, "REALIZADA",
                null, agora, "Feita", agora));
        long idPonto = GestaoDao.pontoCriar("9101", "Teste de ponto", agora - 86400000L,
                "F3548926", agora);
        verifica("ponto criado", idPonto > 0);
        String planejamento = GestaoDao.planejamento(agora);
        verifica("planejamento com seções", planejamento.contains("naoVisitadas")
                && planejamento.contains("pontosEstourados")
                && planejamento.contains("Teste de ponto"));
        verifica("ponto resolvido", GestaoDao.pontoAtualizar(idPonto, "RESOLVIDO",
                "Resolvido no teste", null, agora));

        // ------------------------------------------------------------ imports
        String csv = "prefixo;nome;uf;municipio;regional\n" +
                     "7777;Estilo Teste;SP;Sorocaba;ESTILO SP INTERIOR\n" +
                     "7777;Estilo Teste Corrigida;SP;Sorocaba;ESTILO SP INTERIOR\n";
        String previa = ImportService.processar("agencias", "teste.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                false, "F3548926", agora);
        verifica("prévia não grava", previa.contains("\"inseridos\":1")
                && contar("SELECT COUNT(*) FROM agencia WHERE prefixo='7777'") == 0);
        String commit = ImportService.processar("agencias", "teste.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                true, "F3548926", agora);
        verifica("import grava a última ocorrência", commit.contains("\"confirmado\":true")
                && contar("SELECT COUNT(*) FROM agencia WHERE prefixo='7777' " +
                          "AND nome='Estilo Teste Corrigida'") == 1);
        String denovo = ImportService.processar("agencias", "teste.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                true, "F3548926", agora);
        verifica("reimport conta atualizado", denovo.contains("\"atualizados\":1"));
        verifica("import auditado",
                contar("SELECT COUNT(*) FROM import_log WHERE tipo='agencias'") == 2);

        // CSV com aspas e vírgula
        List<String[]> linhas = Csv.ler(new ByteArrayInputStream(
                "a,b,c\n\"x, y\",\"com \"\"aspas\"\"\",3\n".getBytes(StandardCharsets.UTF_8)));
        verifica("csv com aspas", linhas.get(1)[0].equals("x, y")
                && linhas.get(1)[1].equals("com \"aspas\""));

        // XLSX mínimo gerado na mão
        byte[] xlsx = xlsxDeTeste();
        List<String[]> lx = Xlsx.ler(new ByteArrayInputStream(xlsx));
        verifica("xlsx lido", lx.size() == 2 && lx.get(0)[0].equals("prefixo")
                && lx.get(1)[0].equals("8888") && lx.get(1)[1].equals("Estilo Planilha"));

        // normalização
        verifica("semestre normalizado",
                "2026-1".equals(ImportService.normalizarSemestre("1º SEM 2026"))
                && "2026-2".equals(ImportService.normalizarSemestre("2026/2"))
                && "2025-1".equals(ImportService.normalizarSemestre("20251")));
        verifica("prefixo canônico", Texto.prefixo("01881-0").equals("1881"));
        verifica("decimal brasileiro", Texto.decimal("1.234,56") == 1234.56
                && Texto.decimal("1.500") == 1500.0
                && Texto.decimal("1.5") == 1.5
                && Texto.decimal("1.200.000") == 1200000.0);
        verifica("data excel serial", Xlsx.dataDeCelula("45000") != null);

        // município com acento (comparação exata, sem depender do UPPER do SQLite)
        String resumoSampa = MetricaDao.resumo(master,
                Selecao.de(master, "SP", "São Paulo", null, null), agora);
        verifica("filtro de município acentuado",
                resumoSampa.contains("\"agencias\":4"));

        // master removido não ressuscita quando o schema roda de novo no boot
        verifica("remover master", br.com.bb.atlasestilo.dao.ConfigDao.masterRemover("F6323371"));
        try (FileInputStream schema = new FileInputStream(
                new File("WebContent/WEB-INF/sql/schema.sql"))) {
            AppListener.executarSql(schema);
        }
        br.com.bb.atlasestilo.dao.ConfigDao.semearMastersSeVazio();
        verifica("master removido não volta no boot",
                contar("SELECT COUNT(*) FROM config_master") == 2);

        // limpar exemplo remove também as anotações gerais do gerador
        br.com.bb.atlasestilo.dao.ConfigDao.limparExemplo();
        verifica("limpar exemplo zera agências EXEMPLO",
                contar("SELECT COUNT(*) FROM agencia WHERE origem='EXEMPLO'") == 0);
        verifica("limpar exemplo zera anotações do gerador",
                contar("SELECT COUNT(*) FROM anotacao WHERE criado_por='EXEMPLO'") == 0);
        verifica("import real sobrevive à limpeza",
                contar("SELECT COUNT(*) FROM agencia WHERE prefixo='7777'") == 1);

        System.out.println("SelfTest OK — " + verificacoes + " verificações.");
    }

    private static void verifica(String nome, boolean ok) {
        verificacoes++;
        if (!ok) throw new AssertionError("FALHOU: " + nome);
        System.out.println("  ok: " + nome);
    }

    private static long contar(String sql) throws Exception {
        try (Connection c = Db.conexao(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int ocorrencias(String s, String trecho) {
        int n = 0, i = 0;
        while ((i = s.indexOf(trecho, i)) >= 0) { n++; i += trecho.length(); }
        return n;
    }

    /** .xlsx mínimo com sharedStrings e uma planilha de 2 linhas. */
    private static byte[] xlsxDeTeste() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(("<?xml version=\"1.0\"?><sst><si><t>prefixo</t></si>" +
                       "<si><t>nome</t></si><si><t>Estilo Planilha</t></si></sst>")
                      .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(("<?xml version=\"1.0\"?><worksheet><sheetData>" +
                       "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>" +
                       "<c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                       "<row r=\"2\"><c r=\"A2\"><v>8888</v></c>" +
                       "<c r=\"B2\" t=\"s\"><v>2</v></c></row>" +
                       "</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    private SelfTest() { }
}
