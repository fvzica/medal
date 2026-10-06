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
import br.com.bb.atlasestilo.core.ImportService;
import br.com.bb.atlasestilo.dao.AgenciaDao;
import br.com.bb.atlasestilo.dao.ConfigDao;
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
                        Http.json(resp, MetricaDao.lista(s, sel, tipo, agora));
                    } else {
                        Http.json(resp, MetricaDao.resumo(s, sel, agora));
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
                        .putRaw("fotos", FotoDao.listar(prefixo, s.veTudo()));
                    if (s.veTudo()) {
                        o.putRaw("equipe", MetricaDao.lista(s, sel, "funcis", agora))
                         .putRaw("carteiras", MetricaDao.lista(s, sel, "carteiras", agora))
                         .putRaw("metas", ResultadoDao.metas(prefixo))
                         .putRaw("visitas", GestaoDao.visitas(prefixo))
                         .putRaw("anotacoes", GestaoDao.anotacoes(prefixo))
                         .putRaw("pontos", GestaoDao.pontos(prefixo, null));
                    }
                    Http.json(resp, o.fim());
                    return;
                }
                case "planejamento":
                    if (!exigir(resp, s.veTudo())) return;
                    Http.json(resp, GestaoDao.planejamento(agora));
                    return;
                case "pontos":
                    if (!exigir(resp, s.veTudo())) return;
                    Http.json(resp, GestaoDao.pontos(
                        Texto.prefixo(Http.param(req, "prefixo", "")),
                        Http.param(req, "status", null)));
                    return;
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
                String modelo = ImportService.modelo(tipo);
                if (modelo == null) { Http.erro(resp, 404, "Tipo de modelo desconhecido."); return; }
                Http.download(resp, "modelo-" + tipo + ".csv", modelo);
                return;
            }
            case "masters":   Http.json(resp, ConfigDao.masters()); return;
            case "flags":     Http.json(resp, ConfigDao.flags()); return;
            case "importlog": Http.json(resp, ConfigDao.importLogs()); return;
            default: Http.erro(resp, 404, "Rota admin desconhecida.");
        }
    }

    // ------------------------------------------------------------------ POST

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        Sessao s = Sessao.de(req);
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
                            String[] cam, long agora) throws IOException, SQLException {
        if (cam.length >= 3 && cam[2].equals("excluir")) {
            boolean ok = GestaoDao.visitaExcluir(Long.parseLong(cam[1]));
            Http.json(resp, Json.obj().put("ok", ok).fim());
            return;
        }
        String status = Http.param(req, "status", "PLANEJADA");
        if (!status.equals("PLANEJADA") && !status.equals("REALIZADA")
                && !status.equals("CANCELADA")) {
            Http.erro(resp, 400, "Status de visita inválido.");
            return;
        }
        Long dataPlanejada = lerData(req, "dataPlanejada");
        Long dataRealizada = lerData(req, "dataRealizada");
        String resumo = Http.param(req, "resumo", null);
        if (cam.length >= 2) {
            boolean ok = GestaoDao.visitaAtualizar(Long.parseLong(cam[1]), status,
                    dataPlanejada, dataRealizada, resumo, agora);
            Http.json(resp, Json.obj().put("ok", ok).fim());
        } else {
            String prefixo = Texto.prefixo(Http.param(req, "prefixo", ""));
            if (prefixo.isEmpty()) { Http.erro(resp, 400, "Prefixo obrigatório."); return; }
            long id = GestaoDao.visitaCriar(prefixo, status, dataPlanejada, dataRealizada,
                    resumo, s.matricula, agora);
            Http.json(resp, Json.obj().put("ok", true).put("id", id).fim());
        }
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
        if (cam.length >= 2) {
            String status = Http.param(req, "status", null);
            if (status != null && !status.equals("ABERTO") && !status.equals("EM_TRATATIVA")
                    && !status.equals("RESOLVIDO")) {
                Http.erro(resp, 400, "Status inválido.");
                return;
            }
            boolean ok = GestaoDao.pontoAtualizar(Long.parseLong(cam[1]), status,
                    req.getParameter("solucao"), lerData(req, "previsao"), agora);
            Http.json(resp, Json.obj().put("ok", ok).fim());
        } else {
            String prefixo = Texto.prefixo(Http.param(req, "prefixo", ""));
            String descricao = Http.param(req, "descricao", null);
            if (prefixo.isEmpty() || descricao == null) {
                Http.erro(resp, 400, "Prefixo e descrição são obrigatórios.");
                return;
            }
            long id = GestaoDao.pontoCriar(prefixo, descricao, lerData(req, "previsao"),
                    s.matricula, agora);
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
            default: Http.erro(resp, 404, "Rota admin desconhecida.");
        }
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
