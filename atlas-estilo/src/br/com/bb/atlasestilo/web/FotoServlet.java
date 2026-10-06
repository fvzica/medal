package br.com.bb.atlasestilo.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import br.com.bb.atlasestilo.dao.FotoDao;
import br.com.bb.atlasestilo.util.Http;

/**
 * Serve as fotos por id (/foto/{id}), sempre autenticado e nunca pelo nome
 * original. Fotos de PESSOA só para Master/Moderador (LGPD).
 */
public class FotoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        Sessao s = Sessao.de(req);
        String[] cam = Http.caminho(req);
        if (cam.length == 0 || !cam[0].matches("[0-9a-f]{32}")) {
            Http.erro(resp, 404, "Foto não encontrada.");
            return;
        }
        String[] meta;
        try {
            meta = FotoDao.obter(cam[0]);
        } catch (SQLException e) {
            Http.erro(resp, 500, "Erro interno de banco de dados.");
            return;
        }
        if (meta == null) { Http.erro(resp, 404, "Foto não encontrada."); return; }
        if ("PESSOA".equals(meta[2]) && !s.veTudo()) {
            Http.erro(resp, 403, "Seu perfil não visualiza fotos de pessoas.");
            return;
        }
        File dir = new File(String.valueOf(
            getServletContext().getAttribute(AppListener.ATTR_FOTO_DIR)));
        File arquivo = new File(dir, meta[0]);
        // nome físico é UUID+extensão gerado por nós; ainda assim, nega traversal
        if (!arquivo.getCanonicalPath().startsWith(dir.getCanonicalPath())
                || !arquivo.isFile()) {
            Http.erro(resp, 404, "Arquivo da foto ausente.");
            return;
        }
        resp.setContentType(meta[1] == null ? "image/jpeg" : meta[1]);
        resp.setContentLengthLong(arquivo.length());
        resp.setHeader("Cache-Control", "private, max-age=86400");
        try (InputStream in = new FileInputStream(arquivo);
             OutputStream out = resp.getOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
