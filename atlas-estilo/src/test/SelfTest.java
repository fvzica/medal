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
        // migrações de coluna (idempotentes): rodam duas vezes para provar que
        // uma base já migrada não quebra na subida seguinte
        br.com.bb.atlasestilo.db.Migracoes.aplicar();
        br.com.bb.atlasestilo.db.Migracoes.aplicar();
        verifica("migração adiciona colunas do checklist",
                contar("SELECT COUNT(*) FROM pragma_table_info('visita') WHERE name IN " +
                       "('ambiencia','nota_geral','melhorias','percepcao','claros')") == 5
                && contar("SELECT COUNT(*) FROM pragma_table_info('foto') WHERE name IN ('visita_id','restrita')") == 2
                && contar("SELECT COUNT(*) FROM pragma_table_info('ponto_melhoria') WHERE name IN " +
                          "('visita_id','responsavel','prioridade','proxima_cobranca_em','verificado_em','reaberturas')") == 6
                && contar("SELECT COUNT(*) FROM pragma_table_info('acao_atualizacao') WHERE name = 'tipo'") == 1
                && contar("SELECT COUNT(*) FROM pragma_table_info('foto') WHERE name IN ('ponto_id','momento')") == 2
                && contar("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN " +
                          "('idx_foto_ponto','idx_ponto_status','idx_acao_atu_tipo')") == 3);
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

        // ------------------------------- visitas com checklist, fotos e ações
        testarVisitasEAcoes(master, moderador, colega, idVisita, agora);

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
        // reimport só com prefixo;nome não pode apagar o que o arquivo não traz (link do Maps, regional, município)
        try (Connection c = Db.conexao(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE agencia SET gmaps_url='https://www.google.com/maps/embed?pb=t' WHERE prefixo='7777'");
        }
        ImportService.processar("agencias", "so-nome.csv",
                new ByteArrayInputStream("prefixo;nome\n7777;Estilo Teste Renomeada\n".getBytes(StandardCharsets.UTF_8)),
                true, "F3548926", agora);
        verifica("reimport parcial preserva gmaps_url, regional e município",
                contar("SELECT COUNT(*) FROM agencia WHERE prefixo='7777' AND nome='Estilo Teste Renomeada' " +
                       "AND gmaps_url LIKE 'https://%' AND regional='ESTILO SP INTERIOR' AND municipio='Sorocaba'") == 1);
        ImportService.processar("agencias", "limpa-maps.csv",
                new ByteArrayInputStream("prefixo;nome;gmaps_url\n7777;Estilo Teste Corrigida;\n".getBytes(StandardCharsets.UTF_8)),
                true, "F3548926", agora);
        verifica("coluna presente e vazia continua limpando",
                contar("SELECT COUNT(*) FROM agencia WHERE prefixo='7777' AND gmaps_url IS NULL AND regional='ESTILO SP INTERIOR'") == 1);

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
        verifica("limpar exemplo deixa a marca para o boot não semear de novo",
                br.com.bb.atlasestilo.dao.ConfigDao.exemploLimpo());
        verifica("limpar exemplo não deixa linha do tempo nem foto órfã",
                contar("SELECT COUNT(*) FROM acao_atualizacao WHERE ponto_id NOT IN (SELECT id FROM ponto_melhoria)") == 0
                && contar("SELECT COUNT(*) FROM foto WHERE ponto_id IS NOT NULL AND ponto_id NOT IN (SELECT id FROM ponto_melhoria)") == 0);
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

    // ------------------------------------------- visitas, fotos e ações

    private static void testarVisitasEAcoes(Sessao master, Sessao moderador, Sessao colega,
                                            long idVisitaAnterior, long agora) throws Exception {
        final long dia = 86400000L;

        // visita realizada com checklist completo
        GestaoDao.Checklist ck = new GestaoDao.Checklist();
        ck.ambiencia = 4; ck.atendimento = 5; ck.organizacao = 3; ck.equipe = 4;
        ck.movimento = "CHEIA"; ck.claros = 2; ck.notaGeral = 8.5;
        ck.melhorias = "Fachada|Sala Estilo"; ck.percepcao = "Equipe motivada, fila no caixa.";
        long idVisita = GestaoDao.visitaCriar("9101", "REALIZADA", null, agora + dia,
                "Visita com checklist", "F3548926", agora + dia, ck);
        verifica("visita com checklist criada", idVisita > 0);
        String visitas = GestaoDao.visitas("9101");
        verifica("visita expõe checklist no JSON", visitas.contains("\"notaGeral\":8.5")
                && visitas.contains("\"claros\":2")
                && visitas.contains("\"movimento\":\"CHEIA\"")
                && visitas.contains("\"melhorias\":[\"Fachada\",\"Sala Estilo\"]"));
        verifica("prefixo da visita", "9101".equals(GestaoDao.visitaPrefixo(idVisita)));

        // atualizar só o resumo não apaga o checklist (COALESCE)
        verifica("atualizar visita sem checklist", GestaoDao.visitaAtualizar(idVisita, "REALIZADA",
                null, agora + dia, "Resumo alterado", agora + dia, new GestaoDao.Checklist()));
        visitas = GestaoDao.visitas("9101");
        verifica("checklist preservado após atualização", visitas.contains("\"notaGeral\":8.5")
                && visitas.contains("Resumo alterado"));
        // resumo: null mantém, "" limpa (a tela apaga o campo de propósito)
        verifica("atualizar com resumo null mantém", GestaoDao.visitaAtualizar(idVisita, null, null, null, null, agora + dia, null)
                && GestaoDao.visitas("9101").contains("Resumo alterado"));
        verifica("atualizar com resumo vazio limpa", GestaoDao.visitaAtualizar(idVisita, null, null, null, "", agora + dia, null)
                && !GestaoDao.visitas("9101").contains("Resumo alterado")
                && GestaoDao.visitas("9101").contains("\"notaGeral\":8.5"));

        // evolução entre visitas: a anterior (sem nota) + esta (8,5) aparecem no histórico
        verifica("duas visitas realizadas no histórico", ocorrencias(visitas, "\"REALIZADA\"") >= 2);

        // foto da visita: restrita ao Master
        br.com.bb.atlasestilo.dao.FotoDao.inserir("foto-teste-1", "9101", null, "INTERNA",
                "Sala Estilo", "foto-teste-1.jpg", "image/jpeg", "F3548926", agora, idVisita, true);
        br.com.bb.atlasestilo.dao.FotoDao.inserir("foto-teste-2", "9101", null, "FACHADA",
                "Fachada", "foto-teste-2.jpg", "image/jpeg", "F3548926", agora);
        String fotosMaster = br.com.bb.atlasestilo.dao.FotoDao.listar("9101", true, true);
        String fotosModerador = br.com.bb.atlasestilo.dao.FotoDao.listar("9101", true, false);
        String fotosColega = br.com.bb.atlasestilo.dao.FotoDao.listar("9101", false, false);
        verifica("master vê a foto restrita", fotosMaster.contains("foto-teste-1")
                && fotosMaster.contains("\"restrita\":true") && fotosMaster.contains("foto-teste-2"));
        verifica("moderador não vê foto restrita", !fotosModerador.contains("foto-teste-1")
                && fotosModerador.contains("foto-teste-2"));
        verifica("colega não vê foto restrita", !fotosColega.contains("foto-teste-1"));
        String[] meta = br.com.bb.atlasestilo.dao.FotoDao.obter("foto-teste-1");
        verifica("foto restrita marcada p/ o servlet", meta != null && "1".equals(meta[3]));
        verifica("fotos da visita", br.com.bb.atlasestilo.dao.FotoDao.daVisita(idVisita).contains("foto-teste-1"));

        // ação ligada à visita, com dono, prazo e prioridade
        long idAcao = GestaoDao.pontoCriar("9101", "Trocar letreiro da fachada", agora - 2 * dia,
                "F3548926", agora, idVisita, "Ger. Geral", "ALTA");
        verifica("ação criada", idAcao > 0);
        String acoes = GestaoDao.acoes("9101", "PENDENTES", null, null, null, agora);
        verifica("ação com dono/prioridade/visita", acoes.contains("\"prioridade\":\"ALTA\"")
                && acoes.contains("\"responsavel\":\"Ger. Geral\"")
                && acoes.contains("\"visitaId\":" + idVisita)
                && acoes.contains("\"vencida\":true"));
        verifica("filtro de vencidas", GestaoDao.acoes(null, "PENDENTES", "VENCIDAS", null, null, agora)
                .contains("Trocar letreiro"));
        verifica("filtro de prioridade", !GestaoDao.acoes("9101", "PENDENTES", null, null, "BAIXA", agora)
                .contains("Trocar letreiro"));
        verifica("prioridade inválida vira MEDIA", "MEDIA".equals(GestaoDao.prioridadeValida("URGENTE")));

        // follow-up: retorno + mudança de status na linha do tempo
        long idRet = GestaoDao.pontoComentar(idAcao, "Gerente orçou com fornecedor", "EM_TRATATIVA",
                "F3548926", agora + 1000);
        verifica("retorno registrado", idRet > 0);
        String timeline = GestaoDao.atualizacoes(idAcao);
        verifica("linha do tempo da ação", timeline.contains("orçou com fornecedor")
                && timeline.contains("\"statusNovo\":\"EM_TRATATIVA\""));
        acoes = GestaoDao.acoes("9101", "EM_TRATATIVA", null, null, null, agora);
        verifica("status mudou pelo retorno", acoes.contains("Trocar letreiro")
                && acoes.contains("\"atualizacoes\":1"));
        verifica("editar dono e prioridade", GestaoDao.pontoAtualizar(idAcao, null, null, agora + 10 * dia,
                agora, "Ger. Adm", "MEDIA", null));
        acoes = GestaoDao.acoes("9101", "PENDENTES", "30DIAS", null, null, agora);
        verifica("ação reprogramada vence em 30 dias", acoes.contains("\"responsavel\":\"Ger. Adm\"")
                && acoes.contains("\"vencida\":false"));

        // ------------------------------------ cadência de cobrança e ações paradas
        GestaoDao.Cadencia cad = GestaoDao.cadencia();
        verifica("cadência padrão", cad.alta == 7 && cad.media == 15 && cad.baixa == 30 && cad.parada == 14
                && cad.json().contains("\"ALTA\":7"));
        long idCob = GestaoDao.pontoCriar("9102", "Trocar ar-condicionado da sala Estilo", agora + 20 * dia,
                "F3548926", agora - 10 * dia, null, "Ger. Adm", "ALTA");
        String cob = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idCob + ",");
        verifica("ALTA sem retorno há 10 dias entra em Cobrar hoje", cob.contains("\"semRetornoDias\":10")
                && cob.contains("\"cobrarHoje\":true") && cob.contains("\"parada\":false")
                && cob.contains("\"vencida\":false") && cob.contains("\"cadenciaDias\":7"));
        verifica("filtro COBRAR traz a ação", GestaoDao.acoes(null, null, "COBRAR", null, null, agora)
                .contains("\"id\":" + idCob + ","));
        long idRetCob = GestaoDao.pontoComentar(idCob, "Cobrei o gerente por telefone", null, "F3548926", agora,
                "COBRANCA", 7);
        verifica("cobrança registrada", idRetCob > 0
                && GestaoDao.atualizacoes(idCob).contains("\"tipo\":\"COBRANCA\""));
        cob = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idCob + ",");
        verifica("cobrança adia a próxima e não zera o sem retorno", cob.contains("\"cobrarHoje\":false")
                && cob.contains("\"cobrancas\":1") && cob.contains("\"cobradaHaDias\":0")
                && cob.contains("\"semRetornoDias\":10") && cob.contains("\"proximaCobranca\":" + (agora + 7 * dia)));
        verifica("filtro COBRAR não traz mais a ação", !GestaoDao.acoes(null, null, "COBRAR", null, null, agora)
                .contains("\"id\":" + idCob + ","));
        GestaoDao.pontoComentar(idCob, "Gerente respondeu: orçamento aprovado", null, "F3548926", agora + 1000);
        cob = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora + 2000), "\"id\":" + idCob + ",");
        verifica("retorno reinicia o relógio pela cadência", cob.contains("\"semRetornoDias\":0")
                && cob.contains("\"cobrarHoje\":false") && cob.contains("\"proximaCobranca\":" + (agora + 1000 + 7 * dia)));
        long idParada = GestaoDao.pontoCriar("9102", "Repor cadeiras da sala de espera", null,
                "F3548926", agora - 20 * dia, null, null, "MEDIA");
        String par = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idParada + ",");
        verifica("MÉDIA sem retorno há 20 dias está parada e a cobrar", par.contains("\"parada\":true")
                && par.contains("\"cobrarHoje\":true"));
        verifica("filtro PARADAS", GestaoDao.acoes(null, null, "PARADAS", null, null, agora).contains("\"id\":" + idParada + ",")
                && !GestaoDao.acoes(null, null, "PARADAS", null, null, agora).contains("\"id\":" + idCob + ","));
        GestaoDao.cadenciaDefinir(3, 5, 10, 30, "F3548926", agora);
        cad = GestaoDao.cadencia();
        verifica("cadência ajustável no admin", cad.alta == 3 && cad.media == 5 && cad.baixa == 10 && cad.parada == 30);
        par = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idParada + ",");
        verifica("limite de parada maior tira a ação de paradas", par.contains("\"parada\":false")
                && par.contains("\"cobrarHoje\":true"));
        GestaoDao.cadenciaDefinir(7, 15, 30, 14, "F3548926", agora);
        verifica("cadência restaurada", GestaoDao.cadencia().alta == 7);
        verifica("tipo de atualização inválido vira RETORNO", "RETORNO".equals(GestaoDao.tipoAtualizacaoValido("X"))
                && "STATUS".equals(GestaoDao.tipoAtualizacaoValido("STATUS")));

        // -------------------------- fechamento comprovado: informou que fez + conferência
        long idVer = GestaoDao.pontoCriar("9102", "Consertar porta giratória", agora - dia,
                "F3548926", agora - 5 * dia, null, "Ger. Geral", "ALTA");
        String ver = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idVer + ",");
        verifica("ação vencida antes do aviso", ver.contains("\"vencida\":true"));
        GestaoDao.pontoComentar(idVer, "Gerente avisou que a porta foi consertada", GestaoDao.ST_AGUARDANDO,
                "F3548926", agora, "RETORNO", null);
        br.com.bb.atlasestilo.dao.FotoDao.inserir("foto-acao-1", "9102", null, "ACAO", "Depois · porta",
                "foto-acao-1.jpg", "image/jpeg", "F3548926", agora, null, true, idVer, "DEPOIS");
        ver = objetoDe(GestaoDao.acoes("9102", GestaoDao.ST_AGUARDANDO, null, null, null, agora), "\"id\":" + idVer + ",");
        verifica("aguardando conferência não conta como vencida", ver.contains("\"aguardando\":true")
                && ver.contains("\"vencida\":false") && ver.contains("\"informadoEm\":" + agora)
                && ver.contains("\"cobrarHoje\":false"));
        verifica("foto do depois ligada à ação", ver.contains("\"fotosDepois\":1")
                && ver.contains("\"momento\":\"DEPOIS\"") && ver.contains("foto-acao-1"));
        verifica("VENCIDAS ignora quem aguarda conferência", !GestaoDao.acoes("9102", null, "VENCIDAS", null, null, agora)
                .contains("\"id\":" + idVer + ","));
        verifica("não estava feito reabre e conta", GestaoDao.pontoVerificar(idVer, false, null, null, "F3548926", agora + 10));
        ver = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora + 10), "\"id\":" + idVer + ",");
        verifica("ação reaberta volta a vencida", ver.contains("\"status\":\"ABERTO\"") && ver.contains("\"reaberturas\":1")
                && ver.contains("\"vencida\":true") && ver.contains("\"informadoEm\":null"));
        GestaoDao.pontoComentar(idVer, "Agora foi mesmo", GestaoDao.ST_AGUARDANDO, "F3548926", agora + 20, "RETORNO", null);
        verifica("confirmei conclui com a visita como prova", GestaoDao.pontoVerificar(idVer, true, idVisitaAnterior,
                "Conferi na visita: porta funcionando", "F3548926", agora + 30));
        ver = objetoDe(GestaoDao.acoes("9102", "RESOLVIDO", null, null, null, agora + 40), "\"id\":" + idVer + ",");
        verifica("ação verificada está concluída e comprovada", ver.contains("\"verificadoEm\":" + (agora + 30))
                && ver.contains("\"verificadoVisitaId\":" + idVisitaAnterior) && ver.contains("\"comprovada\":true")
                && ver.contains("porta funcionando"));
        verifica("linha do tempo registra a conferência", GestaoDao.atualizacoes(idVer).contains("\"tipo\":\"VERIFICACAO\""));
        verifica("conferir de novo não duplica (só aguardando confere)",
                !GestaoDao.pontoVerificar(idVer, true, idVisitaAnterior, null, "F3548926", agora + 35)
                && ocorrencias(GestaoDao.atualizacoes(idVer), "\"tipo\":\"VERIFICACAO\"") == 2);
        List<String> arqAcao = br.com.bb.atlasestilo.dao.FotoDao.excluirDaAcao(idVer);
        verifica("fotos da ação removíveis", arqAcao.size() == 1 && arqAcao.get(0).equals("foto-acao-1.jpg"));
        // "sem prova": conferida in loco (sem foto) NÃO entra; concluída só com texto entra
        String semProva = GestaoDao.acoes(null, "RESOLVIDO", null, null, null, "SEM", agora + 40);
        verifica("filtro sem prova respeita a conferência in loco", semProva.contains("Teste de ponto")
                && !semProva.contains("\"id\":" + idVer + ","));
        // reabrir apaga a prova antiga; concluir só com texto fica sem prova
        GestaoDao.pontoComentar(idVer, "Voltou a dar problema", "ABERTO", "F3548926", agora + 50, "STATUS", null);
        ver = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora + 50), "\"id\":" + idVer + ",");
        verifica("reabrir limpa verificado/resolvido", ver.contains("\"verificadoEm\":null")
                && ver.contains("\"verificadoVisitaId\":null") && ver.contains("\"resolvidoEm\":null"));
        GestaoDao.pontoAtualizar(idVer, "RESOLVIDO", "fechada só com texto", null, agora + 60);
        ver = objetoDe(GestaoDao.acoes("9102", "RESOLVIDO", null, null, null, agora + 60), "\"id\":" + idVer + ",");
        verifica("concluída sem nova prova não é comprovada", ver.contains("\"comprovada\":false")
                && ver.contains("\"verificadoEm\":null"));
        verifica("…e aparece em sem prova", GestaoDao.acoes(null, "RESOLVIDO", null, null, null, "SEM", agora + 60)
                .contains("\"id\":" + idVer + ","));

        // prazo por dia-calendário: no dia do prazo ainda não venceu; na véspera faltam dias inteiros
        long hoje0 = GestaoDao.inicioDia(agora);
        long idHoje = GestaoDao.pontoCriar("9102", "Vence hoje ao meio-dia", hoje0 + 12 * 3600000L, "F3548926", agora, null, null, "MEDIA");
        long idOntem = GestaoDao.pontoCriar("9102", "Venceu ontem", hoje0 - 1, "F3548926", agora, null, null, "MEDIA");
        String aHoje = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idHoje + ",");
        String aOntem = objetoDe(GestaoDao.acoes("9102", "PENDENTES", null, null, null, agora), "\"id\":" + idOntem + ",");
        verifica("prazo de hoje não está vencido e faltam 0 dias", aHoje.contains("\"vencida\":false") && aHoje.contains("\"diasParaPrazo\":0"));
        verifica("prazo de ontem está vencido", aOntem.contains("\"vencida\":true") && aOntem.contains("\"diasParaPrazo\":-1"));
        verifica("VENCIDAS e 7DIAS separam hoje de ontem",
                !GestaoDao.acoes("9102", null, "VENCIDAS", null, null, agora).contains("\"id\":" + idHoje + ",")
                && GestaoDao.acoes("9102", null, "VENCIDAS", null, null, agora).contains("\"id\":" + idOntem + ",")
                && GestaoDao.acoes("9102", null, "7DIAS", null, null, agora).contains("\"id\":" + idHoje + ",")
                && !GestaoDao.acoes("9102", null, "7DIAS", null, null, agora).contains("\"id\":" + idOntem + ","));
        verifica("visita planejada ontem está atrasada; hoje não",
                GestaoDao.visitaAtrasada(hoje0 - 1, agora) && !GestaoDao.visitaAtrasada(hoje0 + 1000, agora));
        GestaoDao.pontoExcluir(idHoje); GestaoDao.pontoExcluir(idOntem);

        long idVisHoje = GestaoDao.visitaCriar("9101", "PLANEJADA", agora, null, "Visita de hoje", "F3548926", agora);
        long idVisAtras = GestaoDao.visitaCriar("9102", "PLANEJADA", agora - 3 * dia, null, "Visita atrasada", "F3548926", agora);
        String dia1 = GestaoDao.resumoDoDia(agora);
        verifica("resumo do dia conta visitas de hoje e atrasadas", dia1.contains("\"cobrarHoje\":")
                && dia1.matches("(?s).*\"paradas\":[1-9].*") && dia1.contains("\"aConferir\":0")
                && dia1.contains("\"visitasHoje\":1") && dia1.matches("(?s).*\"visitasAtrasadas\":[1-9].*"));
        String planHoje = GestaoDao.planejamento(agora);
        verifica("planejamento marca a visita atrasada e a de hoje na semana",
                objetoDe(planHoje, "\"id\":" + idVisAtras + ",").contains("\"atrasada\":true")
                && objetoDe(planHoje, "\"id\":" + idVisHoje + ",").contains("\"estaSemana\":true"));
        GestaoDao.visitaExcluir(idVisHoje); GestaoDao.visitaExcluir(idVisAtras);
        verifica("masters conhecidos", br.com.bb.atlasestilo.dao.ConfigDao.ehMaster("F3548926")
                && !br.com.bb.atlasestilo.dao.ConfigDao.ehMaster("F0000002"));

        // planejamento: KPIs, agenda e evolução
        String plan = GestaoDao.planejamento(agora);
        verifica("planejamento traz KPIs", plan.contains("\"kpis\":{") && plan.contains("\"acoesAbertas\":")
                && plan.contains("\"acoesVencidas\":") && plan.contains("\"notaMedia\":")
                && plan.contains("\"agendaSemana\":"));
        verifica("planejamento traz evolução, frias e vencendo", plan.contains("\"evolucao\":[")
                && plan.contains("\"frias\":[") && plan.contains("\"acoesVencendo\":["));
        verifica("planejamento traz cobrar hoje, a conferir e fechamento comprovado",
                plan.contains("\"cobrarHoje\":[") && plan.contains("\"aConferir\":[")
                && plan.contains("\"fechamentoComprovadoPct\":") && plan.contains("\"paradas\":")
                && plan.contains("\"cadencia\":{"));

        // exports CSV
        String csvV = GestaoDao.csvVisitas();
        verifica("CSV de visitas", csvV.startsWith("prefixo;agencia;") && csvV.contains("\n9101;")
                && csvV.contains("Fachada | Sala Estilo") && csvV.contains("CHEIA"));
        String csvA = GestaoDao.csvAcoes(agora);
        verifica("CSV de ações", csvA.startsWith("id;prefixo;") && csvA.contains("Trocar letreiro")
                && csvA.contains("no prazo") && csvA.contains(";Ger. Adm;")
                && csvA.contains(";registros;cobrancas;") && csvA.contains(";fotos_depois;") && csvA.contains("· cobrar"));
        verifica("CSV por agência filtra", GestaoDao.csvAcoes(agora, "9101").contains("Trocar letreiro")
                && !GestaoDao.csvAcoes(agora, "9105").contains("Trocar letreiro")
                && GestaoDao.csvVisitas("9101").contains("\n9101;") && !GestaoDao.csvVisitas("9105").contains("\n9101;"));

        // privacidade: só o Master enxerga visitas/ações nos agregados
        String mapaMaster = AgenciaDao.mapa(master);
        String mapaModerador = AgenciaDao.mapa(moderador);
        verifica("mapa do master marca a visitada e conta ações", mapaMaster.contains("\"visitada\":true")
                && mapaMaster.matches("(?s).*\"pontosAbertos\":[1-9].*"));
        verifica("mapa do moderador não revela visitas nem ações",
                !mapaModerador.contains("\"visitada\":true") && !mapaModerador.contains("\"planejada\":true")
                && !mapaModerador.contains("\"visitadas\":1") && !mapaModerador.matches("(?s).*\"pontosAbertos\":[1-9].*"));
        String resumoMaster = MetricaDao.resumo(master, Selecao.de(master, "SP", null, null, null), agora);
        String resumoModerador = MetricaDao.resumo(moderador, Selecao.de(moderador, "SP", null, null, null), agora);
        verifica("resumo do master conta visitas", !resumoMaster.contains("\"visitadas\":0"));
        verifica("resumo do moderador zera visitas e ações", resumoModerador.contains("\"visitadas\":0")
                && resumoModerador.contains("\"pontosAbertos\":0"));
        String listaColega = MetricaDao.lista(colega, Selecao.de(colega, "SP", null, null, null), "agencias", agora);
        verifica("lista de agências do colega sem visitadas", !listaColega.contains("\"visitada\":true"));

        // mapa: agência cuja única foto é restrita não aparece "com foto" para quem não a vê
        br.com.bb.atlasestilo.dao.FotoDao.inserir("foto-teste-3", "9105", null, "INTERNA",
                "Só da visita", "foto-teste-3.jpg", "image/jpeg", "F3548926", agora, idVisitaAnterior, true);
        String agMaster = objetoDe(AgenciaDao.mapa(master), "\"prefixo\":\"9105\"");
        String agModerador = objetoDe(AgenciaDao.mapa(moderador), "\"prefixo\":\"9105\"");
        verifica("temFoto não revela foto restrita", agMaster.contains("\"temFoto\":true")
                && agModerador.contains("\"temFoto\":false"));
        verifica("normalização de caminho do filtro",
                "/api/mapa".equals(br.com.bb.atlasestilo.web.AuthFilter.normalizar("/css/../api/mapa"))
                && br.com.bb.atlasestilo.web.AuthFilter.normalizar("/../x") == null
                && "/css/a.css".equals(br.com.bb.atlasestilo.web.AuthFilter.normalizar("/css/./a.css"))
                && "/js/atlas.js".equals(br.com.bb.atlasestilo.web.AuthFilter.normalizar("/js/atlas.js"))
                && br.com.bb.atlasestilo.web.AuthFilter.normalizar("/css/..;/api/x") == null
                && br.com.bb.atlasestilo.web.AuthFilter.normalizar("/css/%2e%2e/api/x") == null);

        // excluir a visita apaga as fotos dela e solta a ação (que não some)
        List<String> arquivos = br.com.bb.atlasestilo.dao.FotoDao.excluirDaVisita(idVisita);
        verifica("fotos da visita excluídas junto", arquivos.size() == 1 && arquivos.get(0).equals("foto-teste-1.jpg")
                && contar("SELECT COUNT(*) FROM foto WHERE id='foto-teste-2'") == 1);
        verifica("excluir visita", GestaoDao.visitaExcluir(idVisita));
        acoes = GestaoDao.acoes("9101", "PENDENTES", null, null, null, agora);
        verifica("ação sobrevive à visita excluída", acoes.contains("Trocar letreiro")
                && acoes.contains("\"visitaId\":null"));
        verifica("excluir ação apaga linha do tempo", GestaoDao.pontoExcluir(idAcao)
                && contar("SELECT COUNT(*) FROM acao_atualizacao WHERE ponto_id=" + idAcao) == 0);
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

    /** Objeto JSON (plano) que contém o trecho: do '{' anterior ao '}' seguinte. */
    private static String objetoDe(String json, String trecho) {
        int i = json.indexOf(trecho);
        if (i < 0) return "";
        return json.substring(json.lastIndexOf('{', i), json.indexOf('}', i) + 1);
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
