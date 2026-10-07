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
import br.com.bb.atlasestilo.core.FonteService;
import br.com.bb.atlasestilo.core.ImportService;
import br.com.bb.atlasestilo.dao.AgenciaDao;
import br.com.bb.atlasestilo.dao.GestaoDao;
import br.com.bb.atlasestilo.dao.MetricaDao;
import br.com.bb.atlasestilo.dao.Selecao;
import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Csv;
import br.com.bb.atlasestilo.util.Saneador;
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
        verifica("conexão semeada (6 meses por agência)",
                contar("SELECT COUNT(*) FROM conexao WHERE carteira=''") == 26 * 6
                && contar("SELECT COUNT(*) FROM conexao WHERE carteira<>''") > 26 * 3);
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

        // ------------------------------------------------ saneador de CSV
        testarSaneador();

        // ------------------------------------------- fontes CSV da pasta
        testarFontes(dir, master, agora);

        // limpar exemplo remove também as anotações gerais do gerador
        br.com.bb.atlasestilo.dao.ConfigDao.limparExemplo();
        verifica("limpar exemplo zera Conexão EXEMPLO",
                contar("SELECT COUNT(*) FROM conexao WHERE origem='EXEMPLO'") == 0);
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

    private static boolean igual(Double a, double b) {
        return a != null && Math.abs(a - b) < 1e-9;
    }

    // ------------------------------------------------------------ saneador

    private static void testarSaneador() throws Exception {
        // números "do mundo real"
        verifica("número BR com milhar", igual(Saneador.numero("1.234,56"), 1234.56));
        verifica("número EN com milhar", igual(Saneador.numero("1,234.56", Saneador.Estilo.EN, null, 0, ""), 1234.56));
        verifica("R$ e espaços", igual(Saneador.numero("R$ 1 250,00"), 1250.0));
        verifica("percentual", igual(Saneador.numero("12,5%"), 12.5));
        verifica("parênteses negativo", igual(Saneador.numero("(1.200)"), -1200.0));
        verifica("sinal no fim", igual(Saneador.numero("350-"), -350.0));
        verifica("letra O no lugar de zero", igual(Saneador.numero("1O0"), 100.0));
        verifica("letra l no lugar de um", igual(Saneador.numero("l5,5"), 15.5));
        verifica("erro do Excel vira vazio", Saneador.numero("#N/D") == null && Saneador.numero("-") == null);
        verifica("texto puro é inválido", Saneador.numero("abc") == null);
        verifica("ponto decimal em coluna BR (12.5)", igual(Saneador.numero("12.5"), 12.5));
        verifica("1.500 em coluna BR é milhar", igual(Saneador.numero("1.500"), 1500.0));
        verifica("sufixo mil", igual(Saneador.numero("2,5 mil"), 2500.0));
        verifica("sinal unicode", igual(Saneador.numero("−7"), -7.0));

        Saneador.Relatorio rel = new Saneador.Relatorio();
        Saneador.numero("R$ 1.234,56", Saneador.Estilo.BR, rel, 3, "meta");
        Saneador.numero("xyz", Saneador.Estilo.BR, rel, 4, "meta");
        verifica("relatório registra correção e célula inválida",
                rel.totalCorrecoes == 2 && rel.celulasInvalidas == 1 && rel.correcoes.get(0).linha == 3);

        // estilo inferido pela coluna
        verifica("estilo EN inferido", Saneador.inferirEstilo(
                java.util.Arrays.asList("1,234.5", "980.25", "12.5")) == Saneador.Estilo.EN);
        verifica("estilo BR inferido", Saneador.inferirEstilo(
                java.util.Arrays.asList("1.234,5", "980,25", "12")) == Saneador.Estilo.BR);

        // códigos e competências
        verifica("prefixo com letra O corrigido", Saneador.codigoNumerico("l88O", null, 0, "prefixo").equals("1880"));
        verifica("competência mm/aaaa", "2026-09".equals(Saneador.competencia("09/2026")));
        verifica("competência dd/mm/aaaa", "2026-03".equals(Saneador.competencia("15/03/2026")));
        verifica("competência aaaamm", "2025-12".equals(Saneador.competencia("202512")));
        verifica("competência set/2026", "2026-09".equals(Saneador.competencia("set/2026")));
        verifica("competência ISO", "2026-01".equals(Saneador.competencia("2026-01-31T00:00:00")));
        verifica("competência inválida", Saneador.competencia("ontem") == null);

        // arquivo ANSI com ';', preâmbulo, cabeçalho repetido e colunas sobrando
        String bruto = "Relatório Conexão - gerado em 01/10/2026\n\n" +
                "Prefixo;Competência;Pontos;;\n" +
                "1881;09/2026;812,5;;\n" +
                "Prefixo;Competência;Pontos;;\n" +
                "1882;09/2026;R$ 790;;\n" +
                "\n" +
                "l883;09/2026;1.0O5;;\n";
        byte[] ansi = bruto.getBytes("windows-1252");
        Saneador.Tabela t = Saneador.ler(ansi);
        verifica("encoding ANSI detectado", t.encoding.startsWith("Windows-1252"));
        verifica("separador ; detectado", t.separador == ';');
        verifica("preâmbulo pulado e cabeçalho achado", t.linhaCabecalho == 3 && t.cabecalho.length == 3
                && t.cabecalhoNorm[1].equals("COMPETENCIA"));
        verifica("cabeçalho repetido e linha vazia ignorados", t.linhas.size() == 3 && t.cabecalhosRepetidos == 1);
        verifica("acento sobrevive à conversão", t.cabecalho[1].equals("Competência"));

        // UTF-8 com BOM e vírgula como separador, campo com aspas
        Saneador.Tabela t2 = Saneador.ler(("﻿prefixo,nome,valor\n1881,\"Estilo, Centro\",\"1,5\"\n")
                .getBytes(StandardCharsets.UTF_8));
        verifica("BOM + vírgula", t2.encoding.contains("BOM") && t2.separador == ',' && t2.linhas.get(0)[1].equals("Estilo, Centro"));
        Saneador.Tabela t3 = Saneador.ler("a\tb\n1\t2\n".getBytes(StandardCharsets.UTF_8));
        verifica("TAB detectado", t3.separador == '\t' && t3.linhas.get(0)[1].equals("2"));
    }

    // ------------------------------------------------------------- fontes

    private static void testarFontes(File dir, Sessao master, long agora) throws Exception {
        File pasta = new File(dir, "csv");
        if (!pasta.mkdirs()) throw new IllegalStateException("pasta csv");
        br.com.bb.atlasestilo.dao.FonteDao.paramDefinir(
                br.com.bb.atlasestilo.dao.FonteDao.P_PASTA, pasta.getPath(), "F3548926", agora);

        // arquivo de Conexão bagunçado: ANSI, letra no lugar de dígito, vírgula/ponto trocados,
        // linha sem pontos (rejeitada), competência em formatos variados
        // (competências em 2030 para ficarem mais recentes que as do exemplo semeado)
        String csv = "Prefixo;Mes;Carteira;Matricula;Gerente;Nota Conexao\n" +
                "9101;01/2030;;;;8l2,5\n" +
                "9101;01/2030;EST-01;F0000002;Ana Lima;845\n" +
                "9101;01/2030;EST-02;F0000003;Bruno Dias;R$ 790,0\n" +
                "9101;12/2029;;;;798\n" +
                "9102;jan/2030;;;;abc\n" +
                "91O2;2030-01;;;;1.005\n" +
                "9111;203001;EST-01;F0000090;Caio;900\n" +
                "9111;203001;EST-02;F0000091;Duda;860\n";
        java.nio.file.Files.write(new File(pasta, "conexao_2030-01.csv").toPath(), csv.getBytes("windows-1252"));

        br.com.bb.atlasestilo.dao.FonteDao.Fonte f = new br.com.bb.atlasestilo.dao.FonteDao.Fonte();
        f.nome = "Conexão teste"; f.tipo = "conexao"; f.arquivo = "conexao_*.csv";
        long idConexao = br.com.bb.atlasestilo.dao.FonteDao.fonteSalvar(f, "F3548926", agora);
        verifica("fonte salva", idConexao > 0);
        File resolvido = FonteService.resolverArquivo(pasta, "conexao_*.csv");
        verifica("padrão resolve o arquivo", resolvido != null && resolvido.getName().equals("conexao_2030-01.csv"));
        verifica("padrão sem caminho é recusado", FonteService.resolverArquivo(pasta, "../x.csv") == null);

        String analise = FonteService.analisarFonte(idConexao, false, "F3548926", agora);
        verifica("análise não grava", analise.contains("\"confirmado\":false")
                && contar("SELECT COUNT(*) FROM conexao WHERE prefixo='9101' AND competencia='2030-01' AND origem='IMPORT'") == 0);
        verifica("análise rejeita a linha sem pontos", analise.contains("\"totalRejeitadas\":1")
                && analise.contains("pontos vazios"));
        verifica("análise corrige letra/moeda", analise.contains("letra no lugar de dígito")
                && analise.contains("símbolo monetário"));
        verifica("relatório persistido na fonte",
                br.com.bb.atlasestilo.dao.FonteDao.fonteRelatorio(idConexao).contains("\"status\":\"AVISOS\""));

        String imp = FonteService.analisarFonte(idConexao, true, "F3548926", agora);
        verifica("import grava", imp.contains("\"confirmado\":true"));
        verifica("8l2,5 virou 812.5",
                contar("SELECT COUNT(*) FROM conexao WHERE prefixo='9101' AND competencia='2030-01' AND carteira='' AND pontos=812.5") == 1);
        verifica("91O2 virou 9102 e 1.005 virou 1005",
                contar("SELECT COUNT(*) FROM conexao WHERE prefixo='9102' AND competencia='2030-01' AND pontos=1005") == 1);
        verifica("agência sem nota própria recebe média das carteiras",
                contar("SELECT COUNT(*) FROM conexao WHERE prefixo='9111' AND competencia='2030-01' AND carteira='' AND origem='DERIVADO' AND pontos=880") == 1);
        verifica("rejeitadas em CSV para baixar",
                br.com.bb.atlasestilo.dao.FonteDao.fonteRejeitadas(idConexao).startsWith("linha;motivo;conteudo"));
        verifica("import auditado como conexao",
                contar("SELECT COUNT(*) FROM import_log WHERE tipo='conexao'") == 1);

        // monitor: sem mudança no mtime não reimporta; arquivo novo reimporta
        String rodada = br.com.bb.atlasestilo.core.MonitorCsv.rodar(agora + 1000, false);
        verifica("monitor não reimporta arquivo igual", rodada.contains("\"acao\":\"sem mudança\""));
        File arq = new File(pasta, "conexao_2030-01.csv");
        verifica("mtime alterado", arq.setLastModified(arq.lastModified() + 60000));
        rodada = br.com.bb.atlasestilo.core.MonitorCsv.rodar(agora + 2000, false);
        verifica("monitor reimporta arquivo alterado", rodada.contains("\"acao\":\"importado\""));

        // Conexão no painel da região e na agência
        String resumo = MetricaDao.resumo(master, Selecao.de(master, "SP", null, null, null), agora);
        String kx = br.com.bb.atlasestilo.dao.FonteDao.conexaoResumo(Selecao.de(master, "SP", null, null, null));
        verifica("conexão média de SP na última competência",
                kx.contains("\"competencia\":\"2030-01\"") && kx.contains("\"agencias\":2") && kx.contains("\"media\":909"));
        String ag = br.com.bb.atlasestilo.dao.FonteDao.conexaoAgencia("9101", true);
        verifica("conexão da agência com histórico e carteiras",
                ag.contains("\"pontos\":813") && ag.contains("\"carteiras\":[") && ag.contains("Ana Lima")
                && ag.contains("\"delta\":15") && ag.contains("\"competencia\":\"2029-12\",\"pontos\":798"));
        String listaKx = br.com.bb.atlasestilo.dao.FonteDao.conexaoLista(Selecao.de(master, "SP", null, null, null));
        verifica("lista de Conexão por agência", listaKx.contains("\"prefixo\":\"9102\"") && listaKx.contains("\"faixa\":\"excelencia\""));
        String agColega = br.com.bb.atlasestilo.dao.FonteDao.conexaoAgencia("9101", false);
        verifica("colega não vê gerente na Conexão", !agColega.contains("Ana Lima"));
        verifica("resumo segue válido", resumo.contains("\"agencias\":"));

        // indicadores livres + visão configurada
        String ind = "prefixo,competencia,captacao,nps,observacao\n" +
                "9101,2026-09,\"1,250,000.50\",72,ok\n" +
                "9102,2026-09,980000,68,rever\n" +
                "9101,2026-08,\"1,100,000.00\",70,\n" +
                "9102,2026-08,900000,66,\n" +
                "xx,2026-09,1,2,prefixo ruim\n";
        java.nio.file.Files.write(new File(pasta, "indicadores.csv").toPath(), ind.getBytes(StandardCharsets.UTF_8));
        br.com.bb.atlasestilo.dao.FonteDao.Fonte fi = new br.com.bb.atlasestilo.dao.FonteDao.Fonte();
        fi.nome = "Indicadores teste"; fi.tipo = "indicadores"; fi.arquivo = "indicadores.csv";
        long idInd = br.com.bb.atlasestilo.dao.FonteDao.fonteSalvar(fi, "F3548926", agora);
        String impInd = FonteService.analisarFonte(idInd, true, "F3548926", agora);
        verifica("indicadores: colunas numéricas viram indicadores",
                impInd.contains("\"colunasIndicadores\":[\"CAPTACAO\",\"NPS\"]") && impInd.contains("\"colunasIgnoradas\":[\"observacao\"]"));
        verifica("indicadores: EN com milhar lido", contar(
                "SELECT COUNT(*) FROM indicador_valor WHERE fonte_id=" + idInd + " AND prefixo='9101' AND competencia='2026-09' AND coluna='CAPTACAO' AND valor=1250000.5") == 1);
        verifica("indicadores: prefixo inválido rejeitado", impInd.contains("\"totalRejeitadas\":1"));

        br.com.bb.atlasestilo.dao.FonteDao.Visao v = new br.com.bb.atlasestilo.dao.FonteDao.Visao();
        v.titulo = "Captação"; v.fonteId = idInd; v.coluna = "CAPTACAO"; v.agregacao = "SOMA";
        v.formato = "MOEDA"; v.casas = 2; v.meta = 2000000.0; v.melhor = "MAIOR"; v.perfilMinimo = "COLEGA";
        br.com.bb.atlasestilo.dao.FonteDao.visaoSalvar(v, "F3548926", agora);
        br.com.bb.atlasestilo.dao.FonteDao.Visao v2 = new br.com.bb.atlasestilo.dao.FonteDao.Visao();
        v2.titulo = "NPS"; v2.fonteId = idInd; v2.coluna = "NPS"; v2.agregacao = "MEDIA"; v2.perfilMinimo = "MASTER";
        br.com.bb.atlasestilo.dao.FonteDao.visaoSalvar(v2, "F3548926", agora);
        String visoes = br.com.bb.atlasestilo.dao.FonteDao.visoesCalculadas(master, Selecao.de(master, "SP", null, null, null));
        verifica("visão soma a última competência com tendência",
                visoes.contains("\"titulo\":\"Captação\"") && visoes.contains("\"valor\":2230000.5")
                && visoes.contains("\"anterior\":2000000") && visoes.contains("\"competencia\":\"2026-09\""));
        verifica("visão com meta tem status", visoes.contains("\"status\":\"ok\"") && visoes.contains("\"pct\":111.5"));
        Sessao colega = Sessao.montar("F0000002", "Teste Colega", "9101", null);
        String visoesColega = br.com.bb.atlasestilo.dao.FonteDao.visoesCalculadas(colega, Selecao.de(colega, null, null, null, null));
        verifica("perfil mínimo esconde visão do colega", visoesColega.contains("Captação") && !visoesColega.contains("\"titulo\":\"NPS\""));
        verifica("colunas da fonte para o admin",
                br.com.bb.atlasestilo.dao.FonteDao.colunasDaFonte(idInd).contains("\"coluna\":\"NPS\""));

        // modo estrito bloqueia gravação quando há rejeição
        br.com.bb.atlasestilo.dao.FonteDao.paramDefinir(br.com.bb.atlasestilo.dao.FonteDao.P_ESTRITO, "1", "F3548926", agora);
        String estrito = FonteService.analisarFonte(idInd, true, "F3548926", agora + 5000);
        verifica("modo estrito não grava com rejeição", estrito.contains("\"confirmado\":false") && estrito.contains("Modo estrito"));
        br.com.bb.atlasestilo.dao.FonteDao.paramDefinir(br.com.bb.atlasestilo.dao.FonteDao.P_ESTRITO, "0", "F3548926", agora);

        // remover fonte leva valores e visões junto
        verifica("fonte removida", br.com.bb.atlasestilo.dao.FonteDao.fonteExcluir(idInd));
        verifica("valores e visões da fonte removidos",
                contar("SELECT COUNT(*) FROM indicador_valor WHERE fonte_id=" + idInd) == 0
                && contar("SELECT COUNT(*) FROM visao_dashboard WHERE fonte_id=" + idInd) == 0);
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
