<%@ page pageEncoding="UTF-8" %><!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Atlas Estilo</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b1324'/%3E%3Ccircle cx='16' cy='16' r='7' fill='%23f5c518'/%3E%3C/svg%3E">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Space+Grotesk:wght@500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="css/atlas.css">
</head>
<body>
<%@ include file="/WEB-INF/jspf/header.jspf" %>

<header class="topo">
  <button class="botao-icone so-mobile" id="abrir-menu" type="button" aria-label="Menu">
    <svg class="ico" viewBox="0 0 24 24"><path d="M4 7h16M4 12h16M4 17h16"/></svg>
  </button>
  <div class="topo-titulo">
    <h1 id="topo-titulo">Atlas</h1>
    <small id="topo-sub"><span class="pulso"></span>carregando…</small>
  </div>
  <div class="topo-acoes">
    <div class="busca-topo" id="busca-topo">
      <svg class="ico" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.8-3.8"/></svg>
      <input type="search" id="busca-input" autocomplete="off"
             placeholder="Buscar agência, prefixo, município…" aria-label="Buscar">
      <div class="busca-res" id="busca-res" hidden></div>
    </div>
  </div>
</header>

<main class="conteudo" id="conteudo">

  <!-- ============================================================= MAPA -->
  <section id="vista-mapa" class="vista ativa">
    <div class="grade-mapa">
      <div class="cartao cartao-mapa">
        <div class="migalhas" id="migalhas">
          <button type="button" data-nivel="brasil">Brasil</button>
        </div>
        <div class="cena-mapa">
          <div class="palco-mapa" id="palco-mapa">
            <svg id="svg-mapa" viewBox="0 0 1000 1000" role="img"
                 aria-label="Mapa do Brasil com as agências Estilo"></svg>
          </div>
        </div>
        <div class="lenda-mapa">
          <span class="lenda-item"><span class="bolinha papel"></span> estado com agência</span>
          <span class="lenda-item"><span class="bolinha ouro"></span> agência</span>
          <span class="lenda-item so-master"><span class="bolinha verde"></span> visitada</span>
          <span class="lenda-item" id="lenda-dica">toque num estado para mergulhar</span>
        </div>
      </div>
      <aside id="painel-regiao"><div class="carregando">Carregando o atlas…</div></aside>
    </div>
  </section>

  <!-- ===================================================== MINHA GESTÃO -->
  <section id="vista-planejamento" class="vista">
    <div class="painel-titulo pagina">
      <div><h2>Minha gestão</h2><span class="rotulo" id="plan-resumo"></span></div>
      <div class="acoes-pagina">
        <a class="botao claro" href="api/export/visitas">Exportar visitas</a>
        <a class="botao claro" href="api/export/acoes">Exportar ações</a>
      </div>
    </div>
    <div class="grade-planejamento" id="grade-planejamento">
      <div class="carregando">Carregando…</div>
    </div>
  </section>

  <!-- ============================================================ AÇÕES -->
  <section id="vista-acoes" class="vista">
    <div class="painel-titulo pagina">
      <div><h2>Ações para dar retorno</h2><span class="rotulo" id="acoes-resumo"></span></div>
      <div class="acoes-pagina"><a class="botao claro" href="api/export/acoes">Exportar CSV</a></div>
    </div>
    <div class="cartao"><div class="cartao-corpo">
      <div class="toolbar" id="acoes-toolbar">
        <div class="segmentado" id="acoes-status">
          <button type="button" data-v="PENDENTES" class="ativo">Pendentes</button>
          <button type="button" data-v="VENCIDAS">Vencidas</button>
          <button type="button" data-v="7DIAS">Vencem em 7 dias</button>
          <button type="button" data-v="RESOLVIDO">Concluídas</button>
          <button type="button" data-v="">Todas</button>
        </div>
        <select id="acoes-prioridade"><option value="">Toda prioridade</option>
          <option value="ALTA">Alta</option><option value="MEDIA">Média</option><option value="BAIXA">Baixa</option></select>
        <select id="acoes-regional"><option value="">Todas as regionais</option></select>
        <input type="search" id="acoes-busca" placeholder="filtrar por texto, agência ou responsável">
      </div>
      <div id="grade-acoes"><div class="carregando">Carregando…</div></div>
    </div></div>
  </section>
</main>
</div><!-- /principal -->
</div><!-- /app -->

<!-- ============================================================ AGÊNCIA -->
<div class="veu" id="veu"></div>
<aside class="drawer" id="drawer-agencia" aria-label="Detalhe da agência">
  <div class="drawer-topo">
    <div>
      <span class="rotulo" id="ag-rotulo">Agência</span>
      <h2 id="ag-nome">—</h2>
    </div>
    <button class="fechar" id="fechar-agencia" title="Fechar (Esc)">&times;</button>
  </div>
  <div class="drawer-corpo" id="ag-corpo">
    <div class="carregando">Abrindo a agência…</div>
  </div>
</aside>

<!-- ========================================================== DASHBOARD -->
<div class="painel-dash" id="painel-dash" aria-label="Dashboard da agência">
  <div class="drawer-topo">
    <div>
      <span class="rotulo">Dashboard completo</span>
      <h2 id="dash-nome">—</h2>
    </div>
    <button class="fechar" id="fechar-dash" title="Fechar (Esc)">&times;</button>
  </div>
  <div id="dash-corpo"><div class="carregando">Calculando…</div></div>
</div>

<input type="file" id="foto-visita-input" accept="image/*" multiple hidden>
<div class="dica-mapa" id="dica-mapa"></div>
<div class="toast" id="toast"></div>

<script>window.ATLAS_CTX = '<%= request.getContextPath() %>';</script>
<script src="js/atlas.js"></script>
</body>
</html>
