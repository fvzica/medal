<%@ page pageEncoding="UTF-8" %><%
    br.com.bb.atlasestilo.web.Sessao s =
        (br.com.bb.atlasestilo.web.Sessao) request.getAttribute("sessao");
    if (s == null || !s.master()) {
        request.getRequestDispatcher("/negado.jsp").forward(request, response);
        return;
    }
%><!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Admin · Atlas Estilo</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,600;0,9..144,800;0,9..144,900;1,9..144,400&family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@300;400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="css/atlas.css">
</head>
<body>
<%@ include file="/WEB-INF/jspf/header.jspf" %>

<main class="palco">
  <div class="painel-titulo" style="margin-bottom:14px">
    <h2 style="font-size:26px">Administração</h2>
    <span class="rotulo">imports de planilhas · fotos · acessos</span>
  </div>

  <h3 style="margin:8px 0 10px">Planilhas de dados</h3>
  <p class="rotulo" style="margin:-6px 0 12px">
    Anexe CSV ou Excel (.xlsx). O arquivo é analisado primeiro — nada é gravado
    sem a sua confirmação. Baixe o modelo para conferir as colunas.
  </p>
  <div class="grade-admin" id="grade-imports"></div>

  <h3 style="margin:28px 0 10px">Fotos das agências e das pessoas</h3>
  <div class="cartao"><div class="cartao-corpo">
    <div class="linha-campos">
      <div class="campo" style="flex:2"><label>Agência</label>
        <select id="foto-prefixo"><option value="">Carregando…</option></select></div>
      <div class="campo"><label>Tipo</label>
        <select id="foto-tipo">
          <option value="FACHADA">Fachada</option>
          <option value="INTERNA">Interna</option>
          <option value="PESSOA">Pessoa (restrita)</option>
          <option value="OUTRA">Outra</option>
        </select></div>
      <div class="campo" id="campo-matricula" style="display:none"><label>Matrícula (foto de pessoa)</label>
        <input type="text" id="foto-matricula" placeholder="F1234567"></div>
      <div class="campo" style="flex:2"><label>Legenda</label>
        <input type="text" id="foto-legenda" placeholder="ex.: fachada após a reforma"></div>
    </div>
    <div class="zona-arquivo" id="zona-foto">
      arraste as fotos aqui ou clique para escolher (PNG/JPG/WEBP, até 8 MB cada)
      <input type="file" id="foto-arquivos" accept="image/*" multiple style="display:none">
    </div>
    <div style="margin-top:12px;display:flex;gap:10px;align-items:center">
      <button class="botao primario" id="foto-enviar" type="button">Enviar fotos</button>
      <span class="rotulo" id="foto-status"></span>
    </div>
  </div></div>

  <div class="grade-admin" style="margin-top:28px">
    <div class="cartao"><div class="cartao-corpo">
      <h3>Masters</h3>
      <p class="rotulo" style="margin:0 0 10px">quem enxerga e administra tudo</p>
      <div id="lista-masters" class="fila"></div>
      <div style="display:flex;gap:8px;margin-top:12px">
        <input type="text" id="novo-master" placeholder="F1234567" style="flex:1"
               class="campo-input">
        <button class="botao claro" id="incluir-master" type="button">Incluir</button>
      </div>
    </div></div>

    <div class="cartao"><div class="cartao-corpo">
      <h3>Restrições de matrícula</h3>
      <p class="rotulo" style="margin:0 0 10px">somente leitura ou bloqueio total</p>
      <div id="lista-flags" class="fila"></div>
      <div class="linha-campos" style="margin-top:12px">
        <div class="campo" style="flex:1"><label>Matrícula</label>
          <input type="text" id="flag-matricula" placeholder="F1234567"></div>
        <div class="campo"><label>Flag</label>
          <select id="flag-valor">
            <option value="SOMENTE_LEITURA">Somente leitura</option>
            <option value="BLOQUEADO">Bloqueado</option>
            <option value="">(remover flag)</option>
          </select></div>
        <div class="campo"><label>&nbsp;</label>
          <button class="botao claro" id="aplicar-flag" type="button">Aplicar</button></div>
      </div>
    </div></div>

    <div class="cartao"><div class="cartao-corpo">
      <h3>Dados de exemplo</h3>
      <p class="rotulo" style="margin:0 0 10px">
        agências fictícias para demonstração (origem EXEMPLO)
      </p>
      <div style="display:flex;gap:10px;flex-wrap:wrap">
        <button class="botao claro" id="exemplo-recarregar" type="button">Recarregar exemplo</button>
        <button class="botao perigo" id="exemplo-limpar" type="button">Limpar dados de exemplo</button>
      </div>
      <p class="rotulo" style="margin-top:12px">
        Os imports reais substituem os registros de mesmo prefixo/matrícula
        automaticamente — limpe o exemplo quando a base real estiver completa.
      </p>
    </div></div>
  </div>

  <h3 style="margin:28px 0 10px">Histórico de imports</h3>
  <div class="cartao"><div class="cartao-corpo">
    <table class="tabela"><thead><tr>
      <th>Quando</th><th>Tipo</th><th>Arquivo</th><th>Inseridos</th>
      <th>Atualizados</th><th>Ignorados</th><th>Por</th>
    </tr></thead><tbody id="corpo-importlog"></tbody></table>
  </div></div>
</main>

<div class="toast" id="toast"></div>
<script>window.ATLAS_CTX = '<%= request.getContextPath() %>';</script>
<script src="js/admin.js"></script>
</body>
</html>
