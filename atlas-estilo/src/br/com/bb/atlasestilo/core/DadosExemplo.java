package br.com.bb.atlasestilo.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;

import br.com.bb.atlasestilo.db.Db;

/**
 * Dados simulados para navegar na experiência antes dos imports reais.
 * Tudo entra com origem = 'EXEMPLO' e pode ser removido pela tela Admin.
 * Gerador determinístico (semente fixa) — o mesmo cenário em toda subida.
 */
public final class DadosExemplo {

    private DadosExemplo() { }

    private static final String[][] AGENCIAS = {
        // prefixo, nome, uf, municipio, endereco, lat, lng, regional
        { "9101", "Estilo Paulista",        "SP", "São Paulo",            "Av. Paulista, 1230 — 3º andar",        "-23.5629", "-46.6544", "ESTILO SP CAPITAL" },
        { "9102", "Estilo Jardins",         "SP", "São Paulo",            "R. Haddock Lobo, 585",                 "-23.5577", "-46.6668", "ESTILO SP CAPITAL" },
        { "9103", "Estilo Berrini",         "SP", "São Paulo",            "Av. Eng. Luís C. Berrini, 1140",       "-23.6103", "-46.6946", "ESTILO SP CAPITAL" },
        { "9104", "Estilo Tatuapé",         "SP", "São Paulo",            "R. Tuiuti, 2100",                      "-23.5402", "-46.5760", "ESTILO SP CAPITAL" },
        { "9105", "Estilo Alphaville",      "SP", "Barueri",              "Al. Rio Negro, 503",                   "-23.5049", "-46.8529", "ESTILO SP INTERIOR" },
        { "9106", "Estilo Campinas Cambuí", "SP", "Campinas",             "R. Cel. Quirino, 1470",                "-22.8982", "-47.0520", "ESTILO SP INTERIOR" },
        { "9107", "Estilo Santos Gonzaga",  "SP", "Santos",               "Av. Ana Costa, 318",                   "-23.9646", "-46.3331", "ESTILO SP INTERIOR" },
        { "9108", "Estilo Ribeirão Preto",  "SP", "Ribeirão Preto",       "Av. Pres. Vargas, 850",                "-21.2122", "-47.8103", "ESTILO SP INTERIOR" },
        { "9111", "Estilo Ipanema",         "RJ", "Rio de Janeiro",       "R. Visconde de Pirajá, 414",           "-22.9846", "-43.2030", "ESTILO RIO" },
        { "9112", "Estilo Barra da Tijuca", "RJ", "Rio de Janeiro",       "Av. das Américas, 3500",               "-23.0030", "-43.3186", "ESTILO RIO" },
        { "9113", "Estilo Niterói Icaraí",  "RJ", "Niterói",              "R. Gavião Peixoto, 124",               "-22.9035", "-43.1056", "ESTILO RIO" },
        { "9121", "Estilo Savassi",         "MG", "Belo Horizonte",       "R. Pernambuco, 1322",                  "-19.9352", "-43.9345", "ESTILO MINAS" },
        { "9122", "Estilo Uberlândia",      "MG", "Uberlândia",           "Av. Rondon Pacheco, 2345",             "-18.9240", "-48.2650", "ESTILO MINAS" },
        { "9131", "Estilo Batel",           "PR", "Curitiba",             "Av. do Batel, 1230",                   "-25.4425", "-49.2846", "ESTILO SUL" },
        { "9132", "Estilo Londrina",        "PR", "Londrina",             "Av. Higienópolis, 790",                "-23.3105", "-51.1593", "ESTILO SUL" },
        { "9133", "Estilo Beira-Mar Norte", "SC", "Florianópolis",        "Av. Rubens de Arruda Ramos, 1870",     "-27.5879", "-48.5577", "ESTILO SUL" },
        { "9134", "Estilo Moinhos",         "RS", "Porto Alegre",         "R. Padre Chagas, 415",                 "-30.0253", "-51.2040", "ESTILO SUL" },
        { "9141", "Estilo Salvador Barra",  "BA", "Salvador",             "Av. Centenário, 2992",                 "-12.9985", "-38.5181", "ESTILO NORDESTE" },
        { "9142", "Estilo Boa Viagem",      "PE", "Recife",               "Av. Conselheiro Aguiar, 2333",         "-8.1137",  "-34.8961", "ESTILO NORDESTE" },
        { "9143", "Estilo Aldeota",         "CE", "Fortaleza",            "Av. Santos Dumont, 2626",              "-3.7336",  "-38.4993", "ESTILO NORDESTE" },
        { "9151", "Estilo Brasília Sul",    "DF", "Brasília",             "SCS Qd. 2, Bl. A, Ed. Nações Unidas",  "-15.7975", "-47.8919", "ESTILO CENTRO-OESTE" },
        { "9152", "Estilo Lago Norte",      "DF", "Brasília",             "SHIN CA 1, Lt. A",                     "-15.7320", "-47.8880", "ESTILO CENTRO-OESTE" },
        { "9153", "Estilo Goiânia Bueno",   "GO", "Goiânia",              "Av. T-63, 984",                        "-16.7080", "-49.2730", "ESTILO CENTRO-OESTE" },
        { "9161", "Estilo Praia do Canto",  "ES", "Vitória",              "R. Joaquim Lírio, 300",                "-20.2945", "-40.2936", "ESTILO MINAS" },
        { "9162", "Estilo Manaus Vieiralves","AM","Manaus",               "R. Rio Içá, 300",                      "-3.0972",  "-60.0280", "ESTILO NORTE" },
        { "9163", "Estilo Belém Nazaré",    "PA", "Belém",                "Av. Nazaré, 540",                      "-1.4520",  "-48.4890", "ESTILO NORTE" },
    };

    private static final String[] NOMES = {
        "Ana", "Bruno", "Camila", "Diego", "Elisa", "Fábio", "Gabriela", "Heitor",
        "Isabela", "João", "Karina", "Leonardo", "Mariana", "Nelson", "Olívia",
        "Paulo", "Raquel", "Samuel", "Tatiana", "Vinícius", "Beatriz", "Caio",
        "Daniela", "Eduardo", "Fernanda", "Gustavo", "Helena", "Igor", "Júlia", "Marcos"
    };
    private static final String[] SOBRENOMES = {
        "Almeida", "Barbosa", "Cardoso", "Dias", "Esteves", "Ferreira", "Gomes",
        "Lima", "Martins", "Nogueira", "Oliveira", "Pereira", "Queiroz", "Ribeiro",
        "Santos", "Teixeira", "Vieira", "Moraes", "Castro", "Duarte"
    };
    private static final String[] INDICADORES = {
        "Captação", "Crédito", "Seguridade", "Investimentos", "Encarteiramento"
    };
    private static final String[] PONTOS = {
        "Letreiro da fachada com lâmpadas queimadas",
        "Sala Estilo precisa de pintura e mobiliário renovado",
        "Fila acima de 15 min no atendimento prioritário",
        "Climatização deficiente no salão principal",
        "Sinalização interna desatualizada (novo padrão Estilo)",
        "Espaço de autoatendimento com 2 terminais inoperantes",
        "Carteiras desbalanceadas entre gerentes",
        "Ausência de sala de reunião reservada para clientes",
        "Equipe sem treinamento no novo portfólio de investimentos",
        "Iluminação externa insuficiente no estacionamento",
    };

    /** Semeia tudo. Chamado quando o banco está vazio (ou pelo Admin). */
    public static void semear() {
        long agora = System.currentTimeMillis();
        long dia = 86400000L;
        Random rnd = new Random(42);
        try (Connection c = Db.conexao()) {
            c.setAutoCommit(false);
            try {
                PreparedStatement agIns = c.prepareStatement(
                    "INSERT OR IGNORE INTO agencia (prefixo,nome,segmento,uf,municipio," +
                    "endereco,cep,lat,lng,regional,super_regional,origem,atualizado_em) " +
                    "VALUES (?,?,'ESTILO',?,?,?,?,?,?,?,?, 'EXEMPLO',?)");
                PreparedStatement fuIns = c.prepareStatement(
                    "INSERT OR IGNORE INTO funci (matricula,nome,prefixo,cargo,funcao,tipo," +
                    "carteira,posse_cargo,posse_funcao,origem,atualizado_em) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,'EXEMPLO',?)");
                PreparedStatement caIns = c.prepareStatement(
                    "INSERT OR IGNORE INTO carteira (prefixo,codigo,nome,tipo," +
                    "gerente_matricula,qtd_clientes,origem) VALUES (?,?,?,?,?,?,'EXEMPLO')");
                PreparedStatement pdIns = c.prepareStatement(
                    "INSERT OR IGNORE INTO pdg (prefixo,semestre,atingiu,pontuacao,origem) " +
                    "VALUES (?,?,?,?,'EXEMPLO')");
                PreparedStatement meIns = c.prepareStatement(
                    "INSERT OR IGNORE INTO meta (prefixo,periodo,indicador,meta,realizado," +
                    "projecao,origem,atualizado_em) VALUES (?,?,?,?,?,?,'EXEMPLO',?)");
                PreparedStatement viIns = c.prepareStatement(
                    "INSERT INTO visita (prefixo,status,data_planejada,data_realizada,resumo," +
                    "criado_por,criado_em,atualizado_em) VALUES (?,?,?,?,?,'EXEMPLO',?,?)");
                PreparedStatement anIns = c.prepareStatement(
                    "INSERT INTO anotacao (prefixo,texto,fixada,criado_por,criado_em," +
                    "atualizado_em) VALUES (?,?,?,'EXEMPLO',?,?)");
                PreparedStatement poIns = c.prepareStatement(
                    "INSERT INTO ponto_melhoria (prefixo,descricao,status,solucao,previsao," +
                    "resolvido_em,criado_por,criado_em,atualizado_em) " +
                    "VALUES (?,?,?,?,?,?,'EXEMPLO',?,?)");

                int seqMatricula = 1;
                int idx = 0;
                for (String[] ag : AGENCIAS) {
                    String prefixo = ag[0];
                    agIns.setString(1, prefixo);
                    agIns.setString(2, ag[1]);
                    agIns.setString(3, ag[2]);
                    agIns.setString(4, ag[3]);
                    agIns.setString(5, ag[4]);
                    agIns.setString(6, String.format("%05d-%03d", 10000 + rnd.nextInt(80000), rnd.nextInt(999)));
                    agIns.setDouble(7, Double.parseDouble(ag[5]));
                    agIns.setDouble(8, Double.parseDouble(ag[6]));
                    agIns.setString(9, ag[7]);
                    agIns.setString(10, "DIRETORIA ESTILO");
                    agIns.setLong(11, agora);
                    agIns.executeUpdate();

                    // equipe: 1 gerente geral + carteiras com gerente + assistentes + apoio
                    int qtdCarteiras = 3 + rnd.nextInt(5);     // 3..7
                    int qtdAssist = 2 + rnd.nextInt(3);        // 2..4
                    int qtdApoio = 2 + rnd.nextInt(4);         // 2..5

                    String mGG = matricula(seqMatricula++);
                    inserirFunci(fuIns, mGG, nome(rnd), prefixo, "Gerente Geral",
                            "Gerência Geral Estilo", "GERENTE", null, rnd, agora, dia);
                    for (int i = 1; i <= qtdCarteiras; i++) {
                        String cod = String.format("EST-%02d", i);
                        String mG = matricula(seqMatricula++);
                        inserirFunci(fuIns, mG, nome(rnd), prefixo,
                                "Gerente de Relacionamento", "Gerente Estilo",
                                "GERENTE", cod, rnd, agora, dia);
                        caIns.setString(1, prefixo);
                        caIns.setString(2, cod);
                        caIns.setString(3, "Estilo " + ag[3] + " " + i);
                        caIns.setString(4, "ESTILO");
                        caIns.setString(5, mG);
                        caIns.setInt(6, 180 + rnd.nextInt(260));
                        caIns.executeUpdate();
                    }
                    for (int i = 0; i < qtdAssist; i++) {
                        inserirFunci(fuIns, matricula(seqMatricula++), nome(rnd), prefixo,
                                "Assistente de Negócios", "Assistente Estilo",
                                "ASSISTENTE", null, rnd, agora, dia);
                    }
                    for (int i = 0; i < qtdApoio; i++) {
                        inserirFunci(fuIns, matricula(seqMatricula++), nome(rnd), prefixo,
                                i % 2 == 0 ? "Escriturário" : "Caixa Executivo",
                                null, "OUTRO", null, rnd, agora, dia);
                    }

                    // PDG: 2023-1 .. 2026-1 (7 semestres)
                    String[] semestres = { "2023-1", "2023-2", "2024-1", "2024-2",
                                           "2025-1", "2025-2", "2026-1" };
                    for (String sem : semestres) {
                        boolean atingiu = rnd.nextInt(100) < 55;
                        pdIns.setString(1, prefixo);
                        pdIns.setString(2, sem);
                        pdIns.setInt(3, atingiu ? 1 : 0);
                        pdIns.setDouble(4, Math.round((atingiu ? 100 + rnd.nextInt(18)
                                                               : 72 + rnd.nextInt(26)) * 10
                                                      + rnd.nextInt(10)) / 10.0);
                        pdIns.executeUpdate();
                    }

                    // Metas do semestre atual
                    for (String ind : INDICADORES) {
                        double meta = (500 + rnd.nextInt(4500)) * 1000.0;
                        double frac = 0.45 + rnd.nextDouble() * 0.6;   // 45%..105%
                        double realizado = Math.round(meta * frac);
                        double proj = Math.round(realizado * (1.25 + rnd.nextDouble() * 0.35));
                        meIns.setString(1, prefixo);
                        meIns.setString(2, "2026-2");
                        meIns.setString(3, ind);
                        meIns.setDouble(4, meta);
                        meIns.setDouble(5, realizado);
                        meIns.setDouble(6, proj);
                        meIns.setLong(7, agora);
                        meIns.executeUpdate();
                    }

                    // Visitas: ~40% realizadas, algumas planejadas
                    if (idx % 5 < 2) {
                        long quando = agora - (10 + rnd.nextInt(80)) * dia;
                        viIns.setString(1, prefixo);
                        viIns.setString(2, "REALIZADA");
                        viIns.setNull(3, java.sql.Types.BIGINT);
                        viIns.setLong(4, quando);
                        viIns.setString(5, "Visita de relacionamento: equipe engajada, "
                                + "estrutura revisada. Itens de melhoria registrados.");
                        viIns.setLong(6, quando);
                        viIns.setLong(7, quando);
                        viIns.executeUpdate();

                        anIns.setString(1, prefixo);
                        anIns.setString(2, "Gerente geral pediu apoio para destravar reforma "
                                + "da sala Estilo. Voltar a falar com engenharia regional.");
                        anIns.setInt(3, rnd.nextInt(3) == 0 ? 1 : 0);
                        anIns.setLong(4, quando + dia);
                        anIns.setLong(5, quando + dia);
                        anIns.executeUpdate();
                    } else if (idx % 5 == 2) {
                        long quando = agora + (3 + rnd.nextInt(25)) * dia;
                        viIns.setString(1, prefixo);
                        viIns.setString(2, "PLANEJADA");
                        viIns.setLong(3, quando);
                        viIns.setNull(4, java.sql.Types.BIGINT);
                        viIns.setString(5, "Primeira visita do semestre.");
                        viIns.setLong(6, agora);
                        viIns.setLong(7, agora);
                        viIns.executeUpdate();
                    }

                    // Pontos de melhoria: 0..2 por agência
                    int qtdPontos = rnd.nextInt(3);
                    for (int i = 0; i < qtdPontos; i++) {
                        String desc = PONTOS[rnd.nextInt(PONTOS.length)];
                        int sorte = rnd.nextInt(3);
                        String status = sorte == 0 ? "ABERTO"
                                      : sorte == 1 ? "EM_TRATATIVA" : "RESOLVIDO";
                        long criado = agora - (20 + rnd.nextInt(120)) * dia;
                        Long previsao = status.equals("RESOLVIDO") ? null
                                : criado + (30 + rnd.nextInt(90)) * dia;
                        poIns.setString(1, prefixo);
                        poIns.setString(2, desc);
                        poIns.setString(3, status);
                        poIns.setString(4, status.equals("RESOLVIDO")
                                ? "Tratado com a regional; serviço concluído e validado em visita."
                                : null);
                        if (previsao == null) poIns.setNull(5, java.sql.Types.BIGINT);
                        else poIns.setLong(5, previsao);
                        if (status.equals("RESOLVIDO")) poIns.setLong(6, criado + 45 * dia);
                        else poIns.setNull(6, java.sql.Types.BIGINT);
                        poIns.setLong(7, criado);
                        poIns.setLong(8, criado);
                        poIns.executeUpdate();
                    }
                    idx++;
                }

                // anotações gerais (planejador)
                anIns.setNull(1, java.sql.Types.VARCHAR);
                anIns.setString(2, "Roteiro sugerido para outubro: fechar o interior de SP "
                        + "(Campinas, Ribeirão, Santos) numa única semana de deslocamento.");
                anIns.setInt(3, 1);
                anIns.setLong(4, agora - 3 * dia);
                anIns.setLong(5, agora - 3 * dia);
                anIns.executeUpdate();
                anIns.setNull(1, java.sql.Types.VARCHAR);
                anIns.setString(2, "Padronizar o checklist de visita: fachada, sala Estilo, "
                        + "fila prioritária, terminais, treinamento do portfólio.");
                anIns.setInt(3, 0);
                anIns.setLong(4, agora - 10 * dia);
                anIns.setLong(5, agora - 10 * dia);
                anIns.executeUpdate();

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao semear dados de exemplo: " + e.getMessage(), e);
        }
    }

    private static void inserirFunci(PreparedStatement ps, String matricula, String nome,
                                     String prefixo, String cargo, String funcao,
                                     String tipo, String carteira, Random rnd,
                                     long agora, long dia) throws SQLException {
        ps.setString(1, matricula);
        ps.setString(2, nome);
        ps.setString(3, prefixo);
        ps.setString(4, cargo);
        ps.setString(5, funcao);
        ps.setString(6, tipo);
        ps.setString(7, carteira);
        ps.setLong(8, agora - (180 + rnd.nextInt(2800)) * dia);   // posse no cargo
        ps.setLong(9, agora - (90 + rnd.nextInt(1400)) * dia);    // posse na função
        ps.setLong(10, agora);
        ps.executeUpdate();
    }

    private static String matricula(int seq) {
        return String.format("F99%05d", seq);
    }

    private static String nome(Random rnd) {
        return NOMES[rnd.nextInt(NOMES.length)] + " "
             + SOBRENOMES[rnd.nextInt(SOBRENOMES.length)] + " "
             + SOBRENOMES[rnd.nextInt(SOBRENOMES.length)];
    }
}
