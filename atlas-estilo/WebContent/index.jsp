<%@ page pageEncoding="UTF-8" %><!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Atlas Estilo</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,600;0,9..144,800;0,9..144,900;1,9..144,400&family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@300;400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="css/atlas.css">
</head>
<body>
<%@ include file="/WEB-INF/jspf/header.jspf" %>

<main class="palco">

  <!-- ============================================================= MAPA -->
  <section id="vista-mapa" class="vista ativa">
    <div class="grade-mapa">
      <div class="cartao">
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
          <span class="lenda-item"><span class="bolinha ouro"></span> agência a visitar</span>
          <span class="lenda-item"><span class="bolinha verde"></span> agência visitada</span>
          <span class="lenda-item" id="lenda-dica">clique num estado para mergulhar</span>
        </div>
      </div>
      <aside id="painel-regiao"><div class="carregando">Carregando o atlas…</div></aside>
    </div>
  </section>

  <!-- ===================================================== PLANEJAMENTO -->
  <section id="vista-planejamento" class="vista">
    <div class="painel-titulo" style="margin-bottom:12px">
      <h2 style="font-size:26px">Planejamento de visitas</h2>
      <span class="rotulo" id="plan-resumo"></span>
    </div>
    <div class="grade-planejamento" id="grade-planejamento">
      <div class="carregando">Carregando…</div>
    </div>
  </section>
</main>

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

<div class="dica-mapa" id="dica-mapa"></div>
<div class="toast" id="toast"></div>

<script>window.ATLAS_CTX = '<%= request.getContextPath() %>';</script>
<script src="js/atlas.js"></script>
</body>
</html>
