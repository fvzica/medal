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
    <h1>Administração</h1>
    <small>fontes CSV do servidor · visões do dashboard · fotos · acessos</small>
  </div>
</header>

<main class="conteudo">

  <!-- ============================================ CADÊNCIA DE COBRANÇA -->
  <h3 style="margin:8px 0 10px">Cadência de cobrança das ações</h3>
  <p class="rotulo" style="margin:-6px 0 12px">
    De quantos em quantos dias cada prioridade entra em "Cobrar hoje" quando não
    há retorno, e a partir de quantos dias sem retorno a ação conta como parada.
  </p>
  <div class="cartao"><div class="cartao-corpo">
    <div class="linha-campos">
      <div class="campo"><label>Alta · dias</label><input type="number" id="cad-alta" min="1" max="365" value="7"></div>
      <div class="campo"><label>Média · dias</label><input type="number" id="cad-media" min="1" max="365" value="15"></div>
      <div class="campo"><label>Baixa · dias</label><input type="number" id="cad-baixa" min="1" max="365" value="30"></div>
      <div class="campo"><label>Parada após · dias sem retorno</label><input type="number" id="cad-parada" min="1" max="365" value="14"></div>
      <div class="campo"><label>&nbsp;</label><button class="botao primario" id="cad-salvar" type="button">Salvar cadência</button></div>
    </div>
    <div id="cad-situacao" class="rotulo" style="margin:4px 0 0"></div>
  </div></div>

  <!-- ================================================ PASTA DE CSV -->
  <h3 style="margin:8px 0 10px">Pasta de CSV no servidor</h3>
  <p class="rotulo" style="margin:-6px 0 12px">
    Aponte a pasta onde os arquivos ficam. O servidor lê de lá, corrige o que
    der (vírgula × ponto, letras no lugar de dígitos, separador, encoding) e
    mostra o relatório antes de gravar.
  </p>
  <div class="cartao"><div class="cartao-corpo">
    <div class="linha-campos">
      <div class="campo" style="flex:3"><label>Caminho completo da pasta</label>
        <input type="text" id="pasta-caminho" placeholder="D:\dados\atlasestilo\csv"></div>
      <div class="campo"><label>Monitor (minutos · 0 desliga)</label>
        <input type="number" id="pasta-minutos" min="0" max="1440" value="10"></div>
      <div class="campo"><label>Modo estrito</label>
        <select id="pasta-estrito">
          <option value="0">Tolerante — grava o que validou</option>
          <option value="1">Estrito — nada grava se houver erro</option>
        </select></div>
      <div class="campo"><label>&nbsp;</label>
        <button class="botao primario" id="pasta-salvar" type="button">Salvar e varrer</button></div>
    </div>
    <div id="pasta-situacao" class="rotulo" style="margin:4px 0 10px"></div>
    <div id="pasta-arquivos"></div>
    <div style="display:flex;gap:10px;align-items:center;margin-top:12px;flex-wrap:wrap">
      <button class="botao claro" id="monitor-rodar" type="button">Importar agora o que mudou</button>
      <span class="rotulo" id="monitor-estado"></span>
    </div>
  </div></div>

  <!-- ================================================ FONTES DE DADOS -->
  <h3 style="margin:28px 0 10px">Fontes de dados</h3>
  <p class="rotulo" style="margin:-6px 0 12px">
    Cada fonte liga um arquivo da pasta (nome exato ou padrão com *, vale o mais
    recente) a um tipo de dado. Analise sem gravar, importe, ou envie o arquivo
    direto. Baixe o modelo para ver as colunas aceitas.
  </p>
  <div class="cartao"><div class="cartao-corpo">
    <table class="tabela" id="tabela-fontes"><thead><tr>
      <th>Fonte</th><th>Tipo</th><th>Arquivo na pasta</th><th>Última leitura</th>
      <th>Situação</th><th></th>
    </tr></thead><tbody id="corpo-fontes"></tbody></table>
    <div id="relatorio-fonte" style="display:none;margin-top:16px"></div>
  </div></div>

  <div class="cartao" style="margin-top:14px"><div class="cartao-corpo">
    <h3 id="fonte-form-titulo">Nova fonte</h3>
    <input type="hidden" id="fonte-id" value="">
    <div class="linha-campos">
      <div class="campo" style="flex:2"><label>Nome</label>
        <input type="text" id="fonte-nome" placeholder="ex.: Conexão mensal"></div>
      <div class="campo"><label>Tipo</label>
        <select id="fonte-tipo">
          <option value="conexao">Conexão (agência e gerentes)</option>
          <option value="indicadores">Indicadores livres (vira visões)</option>
          <option value="agencias">Agências e endereços</option>
          <option value="funcis">Quadro de funcis</option>
          <option value="carteiras">Carteiras</option>
          <option value="pdg">PDG por semestre</option>
          <option value="metas">Metas e projeções</option>
        </select></div>
      <div class="campo" style="flex:2"><label>Arquivo (nome ou padrão, ex.: conexao_*.csv)</label>
        <input type="text" id="fonte-arquivo" placeholder="conexao_*.csv"></div>
      <div class="campo"><label>Monitor automático</label>
        <select id="fonte-auto"><option value="1">Sim</option><option value="0">Não</option></select></div>
    </div>
    <details style="margin:6px 0 10px">
      <summary class="rotulo" style="cursor:pointer">De-para de colunas (opcional) · <span id="fonte-campos-dica"></span></summary>
      <p class="rotulo" style="margin:8px 0">Só preencha se o cabeçalho do arquivo não bater com os
        nomes aceitos. Formato: <code>campo=Nome da coluna no arquivo</code>, um por linha.</p>
      <div id="fonte-campos" class="chips"></div>
      <textarea id="fonte-mapeamento" class="campo-input" rows="3"
        style="width:100%;font-family:var(--ff-mono);font-size:12px"
        placeholder="prefixo=Cód. Dependência&#10;pontos=Nota Conexão"></textarea>
    </details>
    <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
      <button class="botao primario" id="fonte-salvar" type="button">Salvar fonte</button>
      <button class="botao claro" id="fonte-cancelar" type="button" style="display:none">Cancelar edição</button>
      <a class="botao claro" id="fonte-modelo" href="#">Baixar modelo do tipo</a>
    </div>
  </div></div>

  <!-- ============================================== VISÕES DO DASHBOARD -->
  <h3 style="margin:28px 0 10px">Visões do dashboard</h3>
  <p class="rotulo" style="margin:-6px 0 12px">
    Cada visão vira um card no painel da região, na agência e no dashboard da
    porta: escolha a fonte, a coluna, como agregar e a meta. Colega só vê as
    visões marcadas para Colega.
  </p>
  <div class="cartao"><div class="cartao-corpo">
    <table class="tabela"><thead><tr>
      <th>Ordem</th><th>Título</th><th>Fonte · coluna</th><th>Agregação</th>
      <th>Formato</th><th>Meta</th><th>Perfil</th><th></th>
    </tr></thead><tbody id="corpo-visoes"></tbody></table>
  </div></div>
  <div class="cartao" style="margin-top:14px"><div class="cartao-corpo">
    <h3 id="visao-form-titulo">Nova visão</h3>
    <input type="hidden" id="visao-id" value="">
    <div class="linha-campos">
      <div class="campo" style="flex:2"><label>Título do card</label>
        <input type="text" id="visao-titulo" placeholder="ex.: Captação do mês"></div>
      <div class="campo" style="flex:2"><label>Fonte</label>
        <select id="visao-fonte"></select></div>
      <div class="campo" style="flex:2"><label>Coluna</label>
        <select id="visao-coluna"></select></div>
      <div class="campo"><label>Agregação</label>
        <select id="visao-agregacao">
          <option value="MEDIA">Média</option><option value="SOMA">Soma</option>
          <option value="MIN">Mínimo</option><option value="MAX">Máximo</option>
        </select></div>
    </div>
    <div class="linha-campos">
      <div class="campo"><label>Formato</label>
        <select id="visao-formato">
          <option value="INTEIRO">Inteiro</option><option value="DECIMAL">Decimal</option>
          <option value="PERCENTUAL">Percentual</option><option value="MOEDA">Moeda (R$)</option>
        </select></div>
      <div class="campo"><label>Casas</label>
        <input type="number" id="visao-casas" min="0" max="4" value="0"></div>
      <div class="campo"><label>Meta fixa (vale para o agregado da seleção)</label>
        <input type="text" id="visao-meta" placeholder="ex.: 800"></div>
      <div class="campo"><label>ou coluna da meta</label>
        <select id="visao-coluna-meta"><option value="">—</option></select></div>
      <div class="campo"><label>Melhor</label>
        <select id="visao-melhor"><option value="MAIOR">Quanto maior</option><option value="MENOR">Quanto menor</option></select></div>
    </div>
    <div class="linha-campos">
      <div class="campo"><label>Mínimo plausível</label><input type="text" id="visao-minimo" placeholder="opcional"></div>
      <div class="campo"><label>Máximo plausível</label><input type="text" id="visao-maximo" placeholder="opcional"></div>
      <div class="campo"><label>Perfil mínimo</label>
        <select id="visao-perfil">
          <option value="COLEGA">Colega (todos)</option><option value="MODERADOR">Moderador</option>
          <option value="MASTER">Master</option>
        </select></div>
      <div class="campo"><label>Ordem</label><input type="number" id="visao-ordem" value="0"></div>
      <div class="campo"><label>&nbsp;</label>
        <div style="display:flex;gap:8px">
          <button class="botao primario" id="visao-salvar" type="button">Salvar visão</button>
          <button class="botao claro" id="visao-cancelar" type="button" style="display:none">Cancelar</button>
        </div></div>
    </div>
  </div></div>

  <!-- ================================================ UPLOAD MANUAL -->
  <h3 style="margin:28px 0 10px">Envio manual de planilhas</h3>
  <p class="rotulo" style="margin:-6px 0 12px">
    Alternativa à pasta: anexe CSV ou Excel (.xlsx). O arquivo é analisado
    primeiro — nada é gravado sem a sua confirmação.
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
</div><!-- /principal -->
</div><!-- /app -->

<div class="toast" id="toast"></div>
<script>window.ATLAS_CTX = '<%= request.getContextPath() %>';</script>
<script src="js/admin.js"></script>
</body>
</html>
