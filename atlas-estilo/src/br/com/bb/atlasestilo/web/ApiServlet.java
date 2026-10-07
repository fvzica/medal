package br.com.bb.atlasestilo.web;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import br.com.bb.atlasestilo.core.DadosExemplo;
import br.com.bb.atlasestilo.core.FonteService;
import br.com.bb.atlasestilo.core.ImportService;
import br.com.bb.atlasestilo.core.MonitorCsv;
import br.com.bb.atlasestilo.dao.AgenciaDao;
import br.com.bb.atlasestilo.dao.ConfigDao;
import br.com.bb.atlasestilo.dao.FonteDao;
import br.com.bb.atlasestilo.dao.FotoDao;
import br.com.bb.atlasestilo.dao.GestaoDao;
import br.com.bb.atlasestilo.dao.MetricaDao;
import br.com.bb.atlasestilo.dao.ResultadoDao;
import br.com.bb.atlasestilo.dao.Selecao;
import br.com.bb.atlasestilo.db.Db;
import br.com.bb.atlasestilo.util.Http;
import br.com.bb.atlasestilo.util.Json;
import br.com.bb.atlasestilo.util.Texto;

/** APIs JSON da ferramenta. Autorização revalidada AQUI, a cada chamada. */
public class ApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    // ------------------------------------------------------------------- GET

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        Sessao s = Sessao.de(req);
        if (s == null) { Http.erro(resp, 403, "Acesso negado."); return; } // defesa em profundidade
        String[] cam = Http.caminho(req);
        long agora = System.currentTimeMillis();
        try {
            if (cam.length == 0) { Http.erro(resp, 404, "Rota ausente."); return; }
            switch (cam[0]) {
                case "contexto": {
                    boolean maps = Boolean.TRUE.equals(
                        getServletContext().getAttribute(AppListener.ATTR_MAPS_ATIVO));
                    Http.json(resp, Json.obj()
                        .put("matricula", s.matricula)
                        .put("nome", s.nome)
                        .put("prefixo", s.prefixo)
                        .put("perfil", s.perfil)
                        .put("regionalJurisdicao", s.regionalJurisdicao)
                        .put("veTudo", s.veTudo())
                        .put("master", s.master())
                        .put("mapsAtivo", maps)
                        .put("somenteLeitura", s.somenteLeitura)
                        .fim());
                    return;
                }
                case "mapa":
                    Http.json(resp, AgenciaDao.mapa(s));
                    return;
                case "municipios":
                    Http.json(resp, AgenciaDao.municipios(s, Http.param(req, "uf", null)));
                    return;
                case "regiao": {
                    Selecao sel = Selecao.de(s,
                        Http.param(req, "uf", null),
                        Http.param(req, "municipio", null),
                        Http.param(req, "prefixos", null),
                        Http.param(req, "regional", null));
                    if (cam.length > 1 && cam[1].equals("lista")) {
                        String tipo = Http.param(req, "tipo", "agencias");
                        boolean pessoas = tipo.equals("funcis") || tipo.equals("gerentes")
                                       || tipo.equals("assistentes");
                        if (pessoas && !s.veTudo()) {
                            Http.erro(resp, 403, "Seu perfil vê apenas os grandes números.");
                            return;
                        }
                        Http.json(resp, tipo.equals("conexao") ? FonteDao.conexaoLista(sel)
                                                               : MetricaDao.lista(s, sel, tipo, agora));
                    } else {
                        // grandes números + Conexão + visões configuradas no admin
                        String resumo = MetricaDao.resumo(s, sel, agora);
                        Http.json(resp, resumo.substring(0, resumo.length() - 1) +
                            ",\"conexao\":" + FonteDao.conexaoResumo(sel) +
                            ",\"visoes\":" + FonteDao.visoesCalculadas(s, sel) + "}");
                    }
                    return;
                }
                case "agencia": {
                    if (cam.length < 2) { Http.erro(resp, 404, "Prefixo ausente."); return; }
                    String prefixo = Texto.prefixo(cam[1]);
                    String cab = AgenciaDao.cabecalho(s, prefixo);
                    if (cab == null) {
                        Http.erro(resp, 404, "Agência não encontrada na sua jurisdição.");
                        return;
                    }
                    Selecao sel = Selecao.de(s, null, null, prefixo, null);
                    Json.Obj o = Json.obj()
                        .putRaw("agencia", cab)
                        .putRaw("resumo", MetricaDao.resumo(s, sel, agora))
                        .putRaw("pdgHistorico", ResultadoDao.pdgHistorico(prefixo))
                        .putRaw("conexao", FonteDao.conexaoAgencia(prefixo, s.veTudo()))
                        .putRaw("visoes", FonteDao.visoesCalculadas(s, sel))
                        .putRaw("fotos", FotoDao.listar(prefixo, s.veTudo(), s.master()));
                    if (s.veTudo()) {
                        o.putRaw("equipe", MetricaDao.lista(s, sel, "funcis", agora))
                         .putRaw("carteiras", MetricaDao.lista(s, sel, "carteiras", agora))
                         .putRaw("metas", ResultadoDao.metas(prefixo));
                    }
                    // tudo que o Master registra (visitas, notas, fotos de visita, anotações,
                    // ações) é exclusivo do Master — nem o Moderador recebe
                    if (s.master()) {
                        o.putRaw("visitas", GestaoDao.visitas(prefixo))
                         .putRaw("anotacoes", GestaoDao.anotacoes(prefixo))
                         .putRaw("pontos", GestaoDao.acoes(prefixo, null, null, null, null, agora));
                    }
                    Http.json(resp, o.fim());
                    return;
                }
                case "planejamento":
                    if (!exigir(resp, s.master())) return;
                    Http.json(resp, GestaoDao.planejamento(agora));
                    return;
                case "pontos":
                case "acoes":
                    if (!exigir(resp, s.master())) return;
                    Http.json(resp, GestaoDao.acoes(
                        Texto.prefixo(Http.param(req, "prefixo", "")),
                        Http.param(req, "status", null), Http.param(req, "prazo", null),
                        Http.param(req, "regional", null), Http.param(req, "prioridade", null), agora));
                    return;
                case "ponto": {
                    if (!exigir(resp, s.master())) return;
                    if (cam.length >= 3 && cam[2].equals("atualizacoes")) {
                        Http.json(resp, GestaoDao.atualizacoes(Long.parseLong(cam[1])));
                        return;
                    }
                    Http.erro(resp, 404, "Use /ponto/{id}/atualizacoes.");
                    return;
                }
                case "export": {
                    if (!exigir(resp, s.master())) return;
                    String oque = cam.length > 1 ? cam[1] : "";
                    if (oque.equals("visitas")) Http.download(resp, "visitas-atlas-estilo.csv", GestaoDao.csvVisitas());
                    else if (oque.equals("acoes")) Http.download(resp, "acoes-atlas-estilo.csv", GestaoDao.csvAcoes(agora));
                    else Http.erro(resp, 404, "Export desconhecido (visitas | acoes).");
                    return;
                }
                case "admin":
                    doGetAdmin(req, resp, s, cam);
                    return;
                default:
                    Http.erro(resp, 404, "Rota desconhecida: " + cam[0]);
            }
        } catch (SQLException e) {
            log("Erro de banco em GET /" + String.join("/", cam), e);
            Http.erro(resp, 500, "Erro interno de banco de dados.");
        }
    }

    private void doGetAdmin(HttpServletRequest req, HttpServletResponse resp,
                            Sessao s, String[] cam) throws IOException, SQLException {
        if (!exigir(resp, s.master())) return;
        String sub = cam.length > 1 ? cam[1] : "";
        switch (sub) {
            case "modelo": {
                String tipo = cam.length > 2 ? cam[2] : "";
                String modelo = FonteDao.tipoValido(tipo) ? FonteService.modelo(tipo) : null;
                if (modelo == null) { Http.erro(resp, 404, "Tipo de modelo desconhecido."); return; }
                Http.download(resp, "modelo-" + tipo + ".csv", modelo);
                return;
            }
            case "masters":   Http.json(resp, ConfigDao.masters()); return;
            case "flags":     Http.json(resp, ConfigDao.flags()); return;
            case "importlog": Http.json(resp, ConfigDao.importLogs()); return;
            // ---- fontes de dados em CSV na pasta do servidor
            case "pasta": {
                String pasta = FonteService.varrerPasta();
                Http.json(resp, pasta.substring(0, pasta.length() - 1) +
                    ",\"monitor\":" + MonitorCsv.estado() + "}");
                return;
            }
            case "fontes":    Http.json(resp, FonteDao.fontesJson()); return;
            case "visoes":    Http.json(resp, FonteDao.visoesJson()); return;
            case "monitor":   Http.json(resp, MonitorCsv.estado()); return;
            case "campos": {
                String tipo = cam.length > 2 ? cam[2] : "";
                if (!FonteDao.tipoValido(tipo)) { Http.erro(resp, 404, "Tipo desconhecido."); return; }
                Http.json(resp, FonteService.camposJson(tipo));
                return;
            }
            case "fonte": {
                if (cam.length < 4) { Http.erro(resp, 404, "Use /admin/fonte/{id}/relatorio|rejeitadas|colunas."); return; }
                long id = Long.parseLong(cam[2]);
                switch (cam[3]) {
                    case "relatorio": {
                        String r = FonteDao.fonteRelatorio(id);
                        Http.json(resp, r == null ? "null" : r);
                        return;
                    }
                    case "rejeitadas": {
                        String csv = FonteDao.fonteRejeitadas(id);
                        if (csv == null) { Http.erro(resp, 404, "Esta fonte não tem linhas rejeitadas na última leitura."); return; }
                        Http.download(resp, "rejeitadas-fonte-" + id + ".csv", csv);
                        return;
                    }
                    case "colunas": Http.json(resp, FonteDao.colunasDaFonte(id)); return;
                    default: Http.erro(resp, 404, "Rota de fonte desconhecida.");
                }
                return;
            }
            default: Http.erro(resp, 404, "Rota admin desconhecida.");
        }
    }

    // ------------------------------------------------------------------ POST

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        Sessao s = Sessao.de(req);
        if (s == null) { Http.erro(resp, 403, "Acesso negado."); return; }
        String[] cam = Http.caminho(req);
        long agora = System.currentTimeMillis();
        try {
            if (cam.length == 0) { Http.erro(resp, 404, "Rota ausente."); return; }
            // tudo que escreve é do Master
            if (!exigir(resp, s.master())) return;
            switch (cam[0]) {
                case "visita":   postVisita(req, resp, s, cam, agora); return;
                case "anotacao": postAnotacao(req, resp, s, cam, agora); return;
                case "ponto":    postPonto(req, resp, s, cam, agora); return;
                case "agencia":  postAgencia(req, resp, cam, agora); return;
                case "admin":    postAdmin(req, resp, s, cam, agora); return;
                default: Http.erro(resp, 404, "Rota desconhecida: " + cam[0]);
            }
        } catch (NumberFormatException e) {
            Http.erro(resp, 400, "Identificador inválido na rota.");
        } catch (SQLException e) {
            log("Erro de banco em POST /" + String.join("/", cam), e);
            Http.erro(resp, 500, "Erro interno de banco de dados.");
        }
    }

    private void postVisita(HttpServletRequest req, HttpServletResponse resp, Sessao s,
                            String[] cam, long agora) throws IOException, ServletException, SQLException {
        if (cam.length >= 3 && cam[2].equals("excluir")) {
            long visitaId = Long.parseLong(cam[1]);
            // as fotos da visita vão junto (registro e arquivo); as ações ficam, soltas da visita
            for (String arquivo : FotoDao.excluirDaVisita(visitaId)) {
                java.io.File f = new java.io.File(dirFotos(), arquivo);
                if (f.exists() && !f.delete()) f.deleteOnExit();
            }
            boolean ok = GestaoDao.visitaExcluir(visitaId);
            Http.json(resp, Json.obj().put("ok", ok).fim());
            return;
        }
        // /api/visita/{id}/foto — fotos da visita, sempre restritas ao Master
        if (cam.length >= 3 && cam[2].equals("foto")) {
            long visitaId = Long.parseLong(cam[1]);
            String prefixo = GestaoDao.visitaPrefixo(visitaId);
            if (prefixo == null) { Http.erro(resp, 404, "Visita não encontrada."); return; }
            int gravadas = 0;
            for (Part p : req.getParts()) {
                if (!"arquivo".equals(p.getName()) || p.getSize() == 0) continue;
                String ext = extensaoImagem(p.getContentType());
                if (ext == null) continue;
                String id = UUID.randomUUID().toString().replace("-", "");
                java.io.File destino = new java.io.File(dirFotos(), id + ext);
                java.nio.file.Files.copy(p.getInputStream(), destino.toPath());
                FotoDao.inserir(id, prefixo, null, "VISITA", req.getParameter("legenda"), id + ext,
                        p.getContentType(), s.matricula, agora, visitaId, true);
                gravadas++;
            }
            Http.json(resp, Json.obj().put("ok", gravadas > 0).put("gravadas", gravadas).fim());
            return;
        }
        String status = Http.param(req, "status", cam.length >= 2 ? null : "PLANEJADA");
        if (status != null && !status.equals("PLANEJADA") && !status.equals("REALIZADA")
                && !status.equals("CANCELADA")) {
            Http.erro(resp, 400, "Status de visita inválido.");
            return;
        }
        Long dataPlanejada = lerData(req, "dataPlanejada");
        Long dataRealizada = lerData(req, "dataRealizada");
        String resumo = Http.param(req, "resumo", null);
        GestaoDao.Checklist ck = lerChecklist(req);
        if (cam.length >= 2) {
            boolean ok = GestaoDao.visitaAtualizar(Long.parseLong(cam[1]), status,
                    dataPlanejada, dataRealizada, resumo, agora, ck);
            Http.json(resp, Json.obj().put("ok", ok).fim());
        } else {
            String prefixo = Texto.prefixo(Http.param(req, "prefixo", ""));
            if (prefixo.isEmpty()) { Http.erro(resp, 400, "Prefixo obrigatório."); return; }
            long id = GestaoDao.visitaCriar(prefixo, status == null ? "PLANEJADA" : status,
                    dataPlanejada, dataRealizada, resumo, s.matricula, agora, ck);
            Http.json(resp, Json.obj().put("ok", true).put("id", id).fim());
        }
    }

    /** Campos do checklist (todos opcionais; ausentes não sobrescrevem). */
    private static GestaoDao.Checklist lerChecklist(HttpServletRequest req) {
        GestaoDao.Checklist k = new GestaoDao.Checklist();
        k.ambiencia = nota5(req, "ambiencia");
        k.atendimento = nota5(req, "atendimento");
        k.organizacao = nota5(req, "organizacao");
        k.equipe = nota5(req, "equipe");
        String mov = Http.param(req, "movimento", null);
        if (mov != null) k.movimento = mov.equals("VAZIA") || mov.equals("CHEIA") ? mov : "NORMAL";
        String claros = req.getParameter("claros");
        if (!Texto.vazio(claros)) { Integer c = Texto.inteiro(claros); if (c != null) k.claros = Math.max(0, Math.min(99, c)); }
        String nota = req.getParameter("notaGeral");
        if (!Texto.vazio(nota)) { Double n = Texto.decimal(nota); if (n != null) k.notaGeral = Math.max(0, Math.min(10, n)); }
        String mel = req.getParameter("melhorias");
        if (mel != null) k.melhorias = mel.replace("\n", "|").trim();
        String perc = req.getParameter("percepcao");
        if (perc != null) k.percepcao = perc.trim();
        return k;
    }

    private static Integer nota5(HttpServletRequest req, String nome) {
        String v = req.getParameter(nome);
        if (Texto.vazio(v)) return null;
        Integer n = Texto.inteiro(v);
        return n == null ? null : Math.max(1, Math.min(5, n));
    }

    private void postAnotacao(HttpServletRequest req, HttpServletResponse resp, Sessao s,
                              String[] cam, long agora) throws IOException, SQLException {
        if (cam.length >= 3 && cam[2].equals("excluir")) {
            boolean ok = GestaoDao.anotacaoExcluir(Long.parseLong(cam[1]), agora);
            Http.json(resp, Json.obj().put("ok", ok).fim());
            return;
        }
        if (cam.length >= 2) {
            String texto = req.getParameter("texto");
            String fixadaParam = req.getParameter("fixada");
            Boolean fixada = fixadaParam == null ? null : fixadaParam.equals("1");
            boolean ok = GestaoDao.anotacaoAtualizar(Long.parseLong(cam[1]),
                    Texto.vazio(texto) ? null : texto, fixada, agora);
            Http.json(resp, Json.obj().put("ok", ok).fim());
        } else {
            String texto = Http.param(req, "texto", null);
            if (texto == null) { Http.erro(resp, 400, "Texto obrigatório."); return; }
            String prefixo = Texto.prefixo(Http.param(req, "prefixo", ""));
            long id = GestaoDao.anotacaoCriar(prefixo.isEmpty() ? null : prefixo,
                    texto, s.matricula, agora);
            Http.json(resp, Json.obj().put("ok", true).put("id", id).fim());
        }
    }

    private void postPonto(HttpServletRequest req, HttpServletResponse resp, Sessao s,
                           String[] cam, long agora) throws IOException, SQLException {
        if (cam.length >= 3 && cam[2].equals("excluir")) {
            boolean ok = GestaoDao.pontoExcluir(Long.parseLong(cam[1]));
            Http.json(resp, Json.obj().put("ok", ok).fim());
            return;
        }
        String status = Http.param(req, "status", null);
        if (status != null && !status.equals("ABERTO") && !status.equals("EM_TRATATIVA")
                && !status.equals("RESOLVIDO")) {
            Http.erro(resp, 400, "Status inválido.");
            return;
        }
        // /api/ponto/{id}/comentar — retorno na linha do tempo (com ou sem mudança de status)
        if (cam.length >= 3 && cam[2].equals("comentar")) {
            String texto = Http.param(req, "texto", null);
            if (texto == null && status == null) { Http.erro(resp, 400, "Escreva o retorno ou mude o status."); return; }
            long id = GestaoDao.pontoComentar(Long.parseLong(cam[1]), texto, status, s.matricula, agora);
            Http.json(resp, Json.obj().put("ok", true).put("id", id).fim());
            return;
        }
        if (cam.length >= 2) {
            boolean ok = GestaoDao.pontoAtualizar(Long.parseLong(cam[1]), status,
                    req.getParameter("solucao"), lerData(req, "previsao"), agora,
                    req.getParameter("responsavel"), req.getParameter("prioridade"),
                    req.getParameter("descricao"));
            Http.json(resp, Json.obj().put("ok", ok).fim());
        } else {
            String prefixo = Texto.prefixo(Http.param(req, "prefixo", ""));
            String descricao = Http.param(req, "descricao", null);
            if (prefixo.isEmpty() || descricao == null) {
                Http.erro(resp, 400, "Prefixo e descrição são obrigatórios.");
                return;
            }
            String visitaParam = req.getParameter("visitaId");
            Long visitaId = Texto.vazio(visitaParam) ? null : Long.valueOf(visitaParam.trim());
            long id = GestaoDao.pontoCriar(prefixo, descricao, lerData(req, "previsao"),
                    s.matricula, agora, visitaId, req.getParameter("responsavel"),
                    Http.param(req, "prioridade", "MEDIA"));
            Http.json(resp, Json.obj().put("ok", true).put("id", id).fim());
        }
    }

    private void postAgencia(HttpServletRequest req, HttpServletResponse resp,
                             String[] cam, long agora) throws IOException, SQLException {
        // /api/agencia/{prefixo}/gmaps — Master cola a URL de embed do Maps/Street View
        if (cam.length >= 3 && cam[2].equals("gmaps")) {
            String prefixo = Texto.prefixo(cam[1]);
            String url = Texto.aparar(Http.param(req, "url", ""), 1000);
            if (!url.isEmpty() && !url.startsWith("https://")) {
                Http.erro(resp, 400, "A URL do Maps deve começar com https://");
                return;
            }
            try (Connection c = Db.conexao(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE agencia SET gmaps_url = ?, atualizado_em = ? WHERE prefixo = ?")) {
                ps.setString(1, url.isEmpty() ? null : url);
                ps.setLong(2, agora);
                ps.setString(3, prefixo);
                Http.json(resp, Json.obj().put("ok", ps.executeUpdate() > 0).fim());
            }
            return;
        }
        Http.erro(resp, 404, "Rota de agência desconhecida.");
    }

    private void postAdmin(HttpServletRequest req, HttpServletResponse resp, Sessao s,
                           String[] cam, long agora)
            throws IOException, ServletException, SQLException {
        String sub = cam.length > 1 ? cam[1] : "";
        switch (sub) {
            case "import": {
                String tipo = cam.length > 2 ? cam[2] : "";
                boolean tipoOk = false;
                for (String t : ImportService.TIPOS) if (t.equals(tipo)) tipoOk = true;
                if (!tipoOk) { Http.erro(resp, 400, "Tipo de import desconhecido."); return; }
                Part parte = parteArquivo(req);
                if (parte == null) { Http.erro(resp, 400, "Envie o arquivo CSV ou XLSX."); return; }
                boolean confirmar = "1".equals(req.getParameter("confirmar"));
                String nome = nomeArquivo(parte);
                try (InputStream in = parte.getInputStream()) {
                    Http.json(resp, ImportService.processar(tipo, nome, in, confirmar,
                            s.matricula, agora));
                }
                return;
            }
            case "foto": {
                if (cam.length >= 4 && cam[3].equals("excluir")) {
                    String arquivo = FotoDao.excluir(cam[2]);
                    if (arquivo != null) {
                        java.io.File f = new java.io.File(dirFotos(), arquivo);
                        if (!f.delete()) f.deleteOnExit();
                    }
                    Http.json(resp, Json.obj().put("ok", arquivo != null).fim());
                    return;
                }
                String prefixo = Texto.prefixo(Http.param(req, "prefixo", ""));
                if (prefixo.isEmpty()) { Http.erro(resp, 400, "Prefixo obrigatório."); return; }
                String tipo = Http.param(req, "tipo", "INTERNA");
                if (!tipo.equals("FACHADA") && !tipo.equals("INTERNA")
                        && !tipo.equals("PESSOA") && !tipo.equals("OUTRA")) {
                    Http.erro(resp, 400, "Tipo de foto inválido.");
                    return;
                }
                int gravadas = 0;
                for (Part p : req.getParts()) {
                    if (!"arquivo".equals(p.getName()) || p.getSize() == 0) continue;
                    String mime = p.getContentType();
                    String ext = extensaoImagem(mime);
                    if (ext == null) continue;
                    String id = UUID.randomUUID().toString().replace("-", "");
                    String fisico = id + ext;
                    java.io.File destino = new java.io.File(dirFotos(), fisico);
                    java.nio.file.Files.copy(p.getInputStream(), destino.toPath());
                    FotoDao.inserir(id, prefixo, req.getParameter("matricula"), tipo,
                            req.getParameter("legenda"), fisico, mime, s.matricula, agora);
                    gravadas++;
                }
                Http.json(resp, Json.obj().put("ok", gravadas > 0)
                        .put("gravadas", gravadas).fim());
                return;
            }
            case "master": {
                String matricula = Http.param(req, "matricula", "");
                String acao = Http.param(req, "acao", "incluir");
                if (Texto.matricula(matricula).isEmpty()) {
                    Http.erro(resp, 400, "Matrícula obrigatória.");
                    return;
                }
                if (acao.equals("remover")) {
                    boolean ok = ConfigDao.masterRemover(matricula);
                    if (!ok) { Http.erro(resp, 400, "Não é possível remover o último master."); return; }
                } else {
                    ConfigDao.masterIncluir(matricula, s.matricula, agora);
                }
                AuthFilter.invalidarPerfis(); // quem perdeu/ganhou master sente na próxima requisição
                Http.json(resp, Json.obj().put("ok", true).fim());
                return;
            }
            case "flag": {
                String matricula = Http.param(req, "matricula", "");
                String flag = Http.param(req, "flag", "");
                if (!flag.isEmpty() && !flag.equals("SOMENTE_LEITURA") && !flag.equals("BLOQUEADO")) {
                    Http.erro(resp, 400, "Flag inválida.");
                    return;
                }
                ConfigDao.flagDefinir(matricula, flag, s.matricula, agora);
                AuthFilter.invalidarPerfis(); // BLOQUEADO/SOMENTE_LEITURA valem já
                Http.json(resp, Json.obj().put("ok", true).fim());
                return;
            }
            case "exemplo": {
                String acao = Http.param(req, "acao", "");
                if (acao.equals("limpar")) {
                    ConfigDao.limparExemplo();
                } else if (acao.equals("recarregar")) {
                    ConfigDao.limparExemplo();
                    DadosExemplo.semear();
                } else {
                    Http.erro(resp, 400, "Ação inválida.");
                    return;
                }
                Http.json(resp, Json.obj().put("ok", true).fim());
                return;
            }
            // ---- fontes de dados em CSV na pasta do servidor
            case "pasta": {
                String pasta = Http.param(req, "pasta", null);
                if (pasta != null) {
                    java.io.File dir = new java.io.File(pasta);
                    if (!dir.isAbsolute()) {
                        Http.erro(resp, 400, "Informe o caminho completo da pasta no servidor (ex.: D:\\dados\\atlasestilo\\csv).");
                        return;
                    }
                    FonteDao.paramDefinir(FonteDao.P_PASTA, dir.getPath(), s.matricula, agora);
                }
                String minutos = req.getParameter("minutos");
                if (minutos != null) {
                    int m = Http.paramInt(req, "minutos", 10);
                    FonteDao.paramDefinir(FonteDao.P_MINUTOS, String.valueOf(Math.max(0, Math.min(1440, m))), s.matricula, agora);
                }
                String estrito = req.getParameter("estrito");
                if (estrito != null) FonteDao.paramDefinir(FonteDao.P_ESTRITO, "1".equals(estrito) ? "1" : "0", s.matricula, agora);
                String varredura = FonteService.varrerPasta();
                Http.json(resp, varredura.substring(0, varredura.length() - 1) +
                    ",\"monitor\":" + MonitorCsv.estado() + "}");
                return;
            }
            case "fonte": postFonte(req, resp, s, cam, agora); return;
            case "visao": postVisao(req, resp, s, cam, agora); return;
            case "monitor": {
                if (cam.length >= 3 && cam[2].equals("rodar")) {
                    Http.json(resp, MonitorCsv.rodar(agora, true));
                    return;
                }
                Http.erro(resp, 404, "Use /admin/monitor/rodar.");
                return;
            }
            default: Http.erro(resp, 404, "Rota admin desconhecida.");
        }
    }

    private void postFonte(HttpServletRequest req, HttpServletResponse resp, Sessao s,
                           String[] cam, long agora) throws IOException, ServletException, SQLException {
        // /admin/fonte/{id}/analisar | importar | upload | excluir
        if (cam.length >= 4) {
            long id = Long.parseLong(cam[2]);
            FonteDao.Fonte f = FonteDao.fonte(id);
            if (f == null) { Http.erro(resp, 404, "Fonte não encontrada."); return; }
            switch (cam[3]) {
                case "analisar": Http.json(resp, FonteService.analisarFonte(id, false, s.matricula, agora)); return;
                case "importar": Http.json(resp, FonteService.analisarFonte(id, true, s.matricula, agora)); return;
                case "upload": {
                    Part parte = parteArquivo(req);
                    if (parte == null) { Http.erro(resp, 400, "Envie o arquivo CSV ou XLSX."); return; }
                    boolean confirmar = "1".equals(req.getParameter("confirmar"));
                    byte[] dados;
                    try (InputStream in = parte.getInputStream()) { dados = ImportService.lerTudo(in); }
                    Http.json(resp, FonteService.processarFonte(f, nomeArquivo(parte), dados, null,
                        confirmar, s.matricula, agora));
                    return;
                }
                case "excluir": Http.json(resp, Json.obj().put("ok", FonteDao.fonteExcluir(id)).fim()); return;
                default: Http.erro(resp, 404, "Ação de fonte desconhecida.");
            }
            return;
        }
        // criar / editar
        FonteDao.Fonte f = new FonteDao.Fonte();
        f.id = Http.paramLong(req, "id", 0);
        f.nome = Texto.aparar(Http.param(req, "nome", ""), 120);
        f.tipo = Http.param(req, "tipo", "");
        f.arquivo = Texto.aparar(Http.param(req, "arquivo", ""), 200);
        f.ativo = !"0".equals(req.getParameter("ativo"));
        f.automatico = !"0".equals(req.getParameter("automatico"));
        if (f.nome.isEmpty() || f.arquivo.isEmpty()) { Http.erro(resp, 400, "Nome e arquivo são obrigatórios."); return; }
        if (!FonteDao.tipoValido(f.tipo)) { Http.erro(resp, 400, "Tipo de fonte inválido."); return; }
        if (f.arquivo.indexOf('/') >= 0 || f.arquivo.indexOf('\\') >= 0 || f.arquivo.contains("..")) {
            Http.erro(resp, 400, "Informe só o nome (ou padrão) do arquivo dentro da pasta, sem caminho.");
            return;
        }
        String map = req.getParameter("mapeamento");
        if (map != null) {
            for (String par : map.split("[;\n]")) {
                int i = par.indexOf('=');
                if (i > 0) f.mapeamento.put(par.substring(0, i).trim().toLowerCase(), par.substring(i + 1).trim());
            }
        }
        long id = FonteDao.fonteSalvar(f, s.matricula, agora);
        Http.json(resp, FonteDao.fonte(id).json());
    }

    private void postVisao(HttpServletRequest req, HttpServletResponse resp, Sessao s,
                           String[] cam, long agora) throws IOException, SQLException {
        if (cam.length >= 4 && cam[3].equals("excluir")) {
            Http.json(resp, Json.obj().put("ok", FonteDao.visaoExcluir(Long.parseLong(cam[2]))).fim());
            return;
        }
        FonteDao.Visao v = new FonteDao.Visao();
        v.id = Http.paramLong(req, "id", 0);
        v.titulo = Texto.aparar(Http.param(req, "titulo", ""), 80);
        v.fonteId = Http.paramLong(req, "fonteId", 0);
        v.coluna = ImportService.chaveColuna(Http.param(req, "coluna", ""));
        if (v.titulo.isEmpty() || v.fonteId <= 0 || v.coluna.isEmpty()) {
            Http.erro(resp, 400, "Título, fonte e coluna são obrigatórios.");
            return;
        }
        if (FonteDao.fonte(v.fonteId) == null) { Http.erro(resp, 400, "Fonte inexistente."); return; }
        v.agregacao = escolha(Http.param(req, "agregacao", "MEDIA"), "MEDIA", "SOMA", "MIN", "MAX");
        v.formato = escolha(Http.param(req, "formato", "INTEIRO"), "INTEIRO", "DECIMAL", "PERCENTUAL", "MOEDA");
        v.casas = Math.max(0, Math.min(4, Http.paramInt(req, "casas", 0)));
        v.meta = Texto.decimal(req.getParameter("meta"));
        v.colunaMeta = Texto.vazio(req.getParameter("colunaMeta")) ? null
                     : ImportService.chaveColuna(req.getParameter("colunaMeta"));
        v.melhor = escolha(Http.param(req, "melhor", "MAIOR"), "MAIOR", "MENOR");
        v.minimo = Texto.decimal(req.getParameter("minimo"));
        v.maximo = Texto.decimal(req.getParameter("maximo"));
        v.perfilMinimo = escolha(Http.param(req, "perfilMinimo", "COLEGA"), "COLEGA", "MODERADOR", "MASTER");
        v.ordem = Http.paramInt(req, "ordem", 0);
        v.ativo = !"0".equals(req.getParameter("ativo"));
        FonteDao.visaoSalvar(v, s.matricula, agora);
        Http.json(resp, Json.obj().put("ok", true).fim());
    }

    private static String escolha(String v, String... opcoes) {
        for (String o : opcoes) if (o.equals(v)) return v;
        return opcoes[0];
    }

    // ----------------------------------------------------------------- apoio

    private boolean exigir(HttpServletResponse resp, boolean condicao) throws IOException {
        if (!condicao) Http.erro(resp, 403, "Seu perfil não tem acesso a esta função.");
        return condicao;
    }

    private static Long lerData(HttpServletRequest req, String nome) {
        String v = req.getParameter(nome);
        if (Texto.vazio(v)) return null;
        if (v.matches("\\d{13}")) return Long.parseLong(v);
        return Texto.data(v);
    }

    private Part parteArquivo(HttpServletRequest req) throws IOException, ServletException {
        for (Part p : req.getParts()) {
            if ("arquivo".equals(p.getName()) && p.getSize() > 0) return p;
        }
        return null;
    }

    /** Nome do arquivo via content-disposition (compatível com Servlet 3.0). */
    private static String nomeArquivo(Part p) {
        String cd = p.getHeader("content-disposition");
        if (cd == null) return null;
        for (String trecho : cd.split(";")) {
            String t = trecho.trim();
            if (t.startsWith("filename=")) {
                String nome = t.substring(9).replace("\"", "");
                int barra = Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\'));
                return barra >= 0 ? nome.substring(barra + 1) : nome;
            }
        }
        return null;
    }

    private static String extensaoImagem(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case "image/png":  return ".png";
            case "image/jpeg": return ".jpg";
            case "image/gif":  return ".gif";
            case "image/webp": return ".webp";
            default: return null;
        }
    }

    private java.io.File dirFotos() {
        return new java.io.File(String.valueOf(
            getServletContext().getAttribute(AppListener.ATTR_FOTO_DIR)));
    }
}
