/* Atlas Estilo — tela de administração (Master) */
(function () {
  'use strict';
  var CTX = window.ATLAS_CTX || '';

  function $(s, raiz) { return (raiz || document).querySelector(s); }
  function $$(s, raiz) { return [].slice.call((raiz || document).querySelectorAll(s)); }
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function toast(msg) {
    var t = $('#toast');
    t.textContent = msg;
    t.classList.add('visivel');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.classList.remove('visivel'); }, 3000);
  }
  function api(rota, opts) {
    opts = opts || {};
    opts.headers = Object.assign({ 'X-Atlas': '1' }, opts.headers || {});
    return fetch(CTX + '/api/' + rota, opts).then(function (r) {
      return r.json().catch(function () { return { erro: 'Resposta inválida.' }; })
        .then(function (j) {
          if (!r.ok || j.erro) throw new Error(j.erro || ('HTTP ' + r.status));
          return j;
        });
    });
  }
  function post(rota, dados) {
    var corpo = new URLSearchParams();
    Object.keys(dados || {}).forEach(function (k) {
      if (dados[k] != null) corpo.append(k, dados[k]);
    });
    return api(rota, { method: 'POST', body: corpo });
  }
  function fmtData(e) { return e ? new Date(e).toLocaleString('pt-BR') : '—'; }

  // ---------------------------------------------------------------- imports

  var IMPORTS = [
    { tipo: 'agencias',  titulo: 'Agências e endereços',
      desc: 'prefixo, nome, UF, município, endereço, lat/lng, regional' },
    { tipo: 'funcis',    titulo: 'Quadro de funcis',
      desc: 'matrícula, nome, prefixo, cargo, função, carteira, posses' },
    { tipo: 'carteiras', titulo: 'Carteiras',
      desc: 'prefixo, código, nome, gerente, quantidade de clientes' },
    { tipo: 'pdg',       titulo: 'Resultado PDG por semestre',
      desc: 'prefixo, semestre, atingiu (S/N), pontuação' },
    { tipo: 'metas',     titulo: 'Metas e projeções',
      desc: 'prefixo, período, indicador, meta, realizado, projeção' }
  ];

  function montarImports() {
    var grade = $('#grade-imports');
    grade.innerHTML = IMPORTS.map(function (im) {
      return '<div class="cartao"><div class="cartao-corpo" data-import="' + im.tipo + '">' +
        '<h3 style="font-size:17px;margin-bottom:2px">' + im.titulo + '</h3>' +
        '<p class="rotulo" style="margin:0 0 10px">' + im.desc + '</p>' +
        '<div class="zona-arquivo">clique ou arraste o CSV/XLSX aqui' +
        '<input type="file" accept=".csv,.xlsx" style="display:none"></div>' +
        '<div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;align-items:center">' +
        '<a class="botao claro" href="' + CTX + '/api/admin/modelo/' + im.tipo + '">Baixar modelo</a>' +
        '<button class="botao primario" data-acao="confirmar" type="button" disabled>Confirmar import</button>' +
        '</div><div class="previa-import" style="display:none"></div>' +
        '</div></div>';
    }).join('');

    $$('[data-import]', grade).forEach(function (card) {
      var tipo = card.getAttribute('data-import');
      var zona = $('.zona-arquivo', card);
      var input = $('input[type=file]', card);
      var btnConfirmar = $('[data-acao=confirmar]', card);
      var previa = $('.previa-import', card);
      var arquivo = null;

      zona.addEventListener('click', function () { input.click(); });
      zona.addEventListener('dragover', function (e) { e.preventDefault(); zona.classList.add('sobre'); });
      zona.addEventListener('dragleave', function () { zona.classList.remove('sobre'); });
      zona.addEventListener('drop', function (e) {
        e.preventDefault(); zona.classList.remove('sobre');
        if (e.dataTransfer.files.length) { input.files = e.dataTransfer.files; aoEscolher(); }
      });
      input.addEventListener('change', aoEscolher);

      function aoEscolher() {
        arquivo = input.files[0] || null;
        if (!arquivo) return;
        zona.textContent = '📄 ' + arquivo.name + ' — analisando…';
        enviar(false);
      }

      function enviar(confirmar) {
        if (!arquivo) return;
        var fd = new FormData();
        fd.append('arquivo', arquivo);
        fd.append('confirmar', confirmar ? '1' : '0');
        btnConfirmar.disabled = true;
        api('admin/import/' + tipo, { method: 'POST', body: fd }).then(function (r) {
          previa.style.display = 'block';
          var erros = (r.erros || []);
          var san = r.saneamento || {};
          previa.innerHTML =
            (confirmar ? '<strong>✓ Importado.</strong> ' : '<strong>Prévia</strong> (nada gravado): ') +
            r.inseridos + ' novo(s), ' + r.atualizados + ' atualizado(s), ' +
            r.ignorados + ' ignorado(s) de ' + r.linhas + ' linha(s).' +
            (r.encoding ? '<br><small class="rotulo">' + esc(r.encoding) + ' · separador "' +
              esc(r.separador === '\t' ? 'TAB' : r.separador) + '" · ' +
              (san.totalCorrecoes || 0) + ' correção(ões) automática(s) · ' +
              (san.totalRejeitadas || 0) + ' linha(s) rejeitada(s)</small>' : '') +
            (erros.length ? '<ul>' + erros.map(function (e) {
              return '<li>' + esc(e) + '</li>';
            }).join('') + '</ul>' : '') +
            ((san.correcoes || []).length ? '<details><summary class="rotulo" style="cursor:pointer">ver correções</summary>' +
              tabelaCorrecoes(san.correcoes) + '</details>' : '');
          zona.textContent = '📄 ' + arquivo.name +
            (confirmar ? ' — importado ✓ (clique para trocar)' : ' — analisado (clique para trocar)');
          btnConfirmar.disabled = confirmar;
          if (confirmar) { toast('Import de ' + tipo + ' concluído.'); carregarLog(); carregarAgencias(); }
        }).catch(function (e) {
          previa.style.display = 'block';
          previa.innerHTML = '<strong style="color:var(--wine)">Erro:</strong> ' + esc(e.message);
          zona.textContent = '📄 ' + (arquivo ? arquivo.name : '') + ' — corrija e tente de novo';
          btnConfirmar.disabled = true;
        });
      }
      btnConfirmar.addEventListener('click', function () { enviar(true); });
    });
  }

  // --------------------------------------------------- pasta de CSV (fontes)

  var TIPO_NOME = { conexao: 'Conexão', indicadores: 'Indicadores', agencias: 'Agências',
    funcis: 'Funcis', carteiras: 'Carteiras', pdg: 'PDG', metas: 'Metas' };
  var FONTES = [];

  function fmtBytes(n) {
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }

  function carregarPasta() {
    return api('admin/pasta').then(renderPasta).catch(function (e) {
      $('#pasta-situacao').textContent = e.message;
    });
  }

  // ------------------------------------------------- cadência de cobrança

  function renderCadencia(c) {
    $('#cad-alta').value = c.ALTA; $('#cad-media').value = c.MEDIA; $('#cad-baixa').value = c.BAIXA; $('#cad-parada').value = c.parada;
    $('#cad-situacao').textContent = 'Hoje: alta a cada ' + c.ALTA + ' dias · média ' + c.MEDIA + ' · baixa ' + c.BAIXA +
      ' · parada após ' + c.parada + ' dias sem retorno. Um retorno do responsável reinicia o relógio; "cobrei" marca a próxima cobrança.';
  }

  function carregarCadencia() {
    return api('admin/cadencia').then(renderCadencia).catch(function (e) { $('#cad-situacao').textContent = e.message; });
  }

  function montarCadencia() {
    $('#cad-salvar').addEventListener('click', function () {
      post('admin/cadencia', { alta: $('#cad-alta').value, media: $('#cad-media').value,
        baixa: $('#cad-baixa').value, parada: $('#cad-parada').value })
        .then(function (c) { renderCadencia(c); toast('Cadência salva.'); })
        .catch(function (e) { toast(e.message); });
    });
  }

  function renderPasta(p) {
    if (document.activeElement !== $('#pasta-caminho')) $('#pasta-caminho').value = p.pasta || '';
    $('#pasta-minutos').value = p.monitorMinutos;
    $('#pasta-estrito').value = p.estrito ? '1' : '0';
    var sit = !p.existe ? '✗ a pasta não existe no servidor — crie-a ou corrija o caminho'
      : !p.ehPasta ? '✗ o caminho não é uma pasta'
      : !p.legivel ? '✗ a conta do Tomcat não tem leitura nesta pasta'
      : '✓ pasta acessível · ' + p.total + ' arquivo(s) csv/txt/xlsx' +
        (p.pasta !== p.pastaPadrao ? ' · padrão do web.xml: ' + p.pastaPadrao : '');
    $('#pasta-situacao').textContent = sit;
    $('#pasta-arquivos').innerHTML = (p.arquivos || []).length
      ? '<div class="lista-arquivos">' + p.arquivos.map(function (a) {
          return '<span class="chip">📄 ' + esc(a.nome) + '<small>' + fmtBytes(a.tamanho) + ' · ' +
            fmtData(a.modificadoEm) + '</small></span>';
        }).join('') + '</div>'
      : '<div class="vazio">Nenhum arquivo .csv/.txt/.xlsx na pasta.</div>';
    var m = p.monitor || {};
    $('#monitor-estado').textContent = !m.ativo
      ? 'monitor desligado'
      : 'monitor a cada ' + m.minutos + ' min · última varredura ' + fmtData(m.ultimaExecucao) +
        ' · próxima ' + fmtData(m.proximaExecucao);
  }

  function montarPasta() {
    $('#pasta-salvar').addEventListener('click', function () {
      post('admin/pasta', { pasta: $('#pasta-caminho').value.trim(),
        minutos: $('#pasta-minutos').value, estrito: $('#pasta-estrito').value })
        .then(function (p) { renderPasta(p); toast('Pasta salva.'); })
        .catch(function (e) { toast(e.message); });
    });
    $('#monitor-rodar').addEventListener('click', function () {
      $('#monitor-estado').textContent = 'importando…';
      post('admin/monitor/rodar', {}).then(function (r) {
        toast(r.importadas + ' fonte(s) importada(s).');
        carregarPasta(); carregarFontes(); carregarLog(); carregarAgencias();
      }).catch(function (e) { toast(e.message); carregarPasta(); });
    });
  }

  // ------------------------------------------------------------------ fontes

  function carregarFontes() {
    return api('admin/fontes').then(function (fs) {
      FONTES = fs;
      var corpo = $('#corpo-fontes');
      corpo.innerHTML = fs.length ? fs.map(function (f) {
        var st = (f.ultimoStatus || '—');
        var cls = st === 'OK' ? 'ok' : st === 'AVISOS' ? 'avisos' : st === '—' ? '' : 'erro';
        return '<tr><td><strong>' + esc(f.nome) + '</strong>' + (f.ativo ? '' : ' <small>(inativa)</small>') +
          (f.automatico ? '' : '<br><small>sem monitor</small>') + '</td>' +
          '<td>' + esc(TIPO_NOME[f.tipo] || f.tipo) + '</td>' +
          '<td><code>' + esc(f.arquivo) + '</code>' +
          (f.ultimoArquivo && f.ultimoArquivo !== f.arquivo ? '<br><small>→ ' + esc(f.ultimoArquivo) + '</small>' : '') + '</td>' +
          '<td>' + fmtData(f.ultimaLeituraEm) + '</td>' +
          '<td><span class="farol ' + cls + '"></span>' + esc(st) +
          (f.ultimoResumo ? '<br><small>' + esc(f.ultimoResumo) + '</small>' : '') + '</td>' +
          '<td><div class="acoes-linha">' +
          '<button class="botao mini claro" data-f-analisar="' + f.id + '">analisar</button>' +
          '<button class="botao mini primario" data-f-importar="' + f.id + '">importar</button>' +
          '<button class="botao mini claro" data-f-upload="' + f.id + '">enviar arquivo</button>' +
          '<button class="botao mini claro" data-f-relatorio="' + f.id + '">relatório</button>' +
          '<button class="botao mini claro" data-f-editar="' + f.id + '">editar</button>' +
          '<button class="botao mini perigo" data-f-excluir="' + f.id + '">remover</button>' +
          '</div></td></tr>';
      }).join('') : '<tr><td colspan="6" class="vazio">Nenhuma fonte ainda — cadastre abaixo.</td></tr>';

      $$('[data-f-analisar]', corpo).forEach(function (b) {
        b.addEventListener('click', function () { rodarFonte(b.getAttribute('data-f-analisar'), 'analisar'); });
      });
      $$('[data-f-importar]', corpo).forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('Importar agora e gravar os dados desta fonte?')) return;
          rodarFonte(b.getAttribute('data-f-importar'), 'importar');
        });
      });
      $$('[data-f-upload]', corpo).forEach(function (b) {
        b.addEventListener('click', function () { uploadFonte(b.getAttribute('data-f-upload')); });
      });
      $$('[data-f-relatorio]', corpo).forEach(function (b) {
        b.addEventListener('click', function () {
          var id = b.getAttribute('data-f-relatorio');
          api('admin/fonte/' + id + '/relatorio').then(function (r) {
            if (!r) { toast('Esta fonte ainda não foi lida.'); return; }
            renderRelatorio(r);
          }).catch(function (e) { toast(e.message); });
        });
      });
      $$('[data-f-editar]', corpo).forEach(function (b) {
        b.addEventListener('click', function () {
          var f = FONTES.filter(function (x) { return String(x.id) === b.getAttribute('data-f-editar'); })[0];
          if (f) editarFonte(f);
        });
      });
      $$('[data-f-excluir]', corpo).forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('Remover a fonte? Os valores de indicadores importados por ela e as visões ligadas a ela também saem.')) return;
          post('admin/fonte/' + b.getAttribute('data-f-excluir') + '/excluir', {})
            .then(function () { toast('Fonte removida.'); carregarFontes(); carregarVisoes(); })
            .catch(function (e) { toast(e.message); });
        });
      });
      preencherFontesVisao();
    });
  }

  function rodarFonte(id, acao) {
    var alvo = $('#relatorio-fonte');
    alvo.style.display = 'block';
    alvo.innerHTML = '<div class="carregando">' + (acao === 'importar' ? 'Importando' : 'Analisando') + ' a fonte…</div>';
    post('admin/fonte/' + id + '/' + acao, {}).then(function (r) {
      renderRelatorio(r);
      if (acao === 'importar') { toast(r.confirmado ? 'Fonte importada.' : 'Nada gravado — veja o relatório.'); carregarLog(); carregarAgencias(); }
      carregarFontes();
    }).catch(function (e) {
      alvo.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>';
      carregarFontes();
    });
  }

  function uploadFonte(id) {
    var input = document.createElement('input');
    input.type = 'file'; input.accept = '.csv,.txt,.xlsx';
    input.addEventListener('change', function () {
      if (!input.files[0]) return;
      var fd = new FormData();
      fd.append('arquivo', input.files[0]);
      fd.append('confirmar', '0');
      var alvo = $('#relatorio-fonte');
      alvo.style.display = 'block';
      alvo.innerHTML = '<div class="carregando">Analisando ' + esc(input.files[0].name) + '…</div>';
      api('admin/fonte/' + id + '/upload', { method: 'POST', body: fd }).then(function (r) {
        renderRelatorio(r, function () {
          var fd2 = new FormData();
          fd2.append('arquivo', input.files[0]);
          fd2.append('confirmar', '1');
          alvo.innerHTML = '<div class="carregando">Gravando…</div>';
          api('admin/fonte/' + id + '/upload', { method: 'POST', body: fd2 }).then(function (r2) {
            renderRelatorio(r2); toast(r2.confirmado ? 'Arquivo importado.' : 'Nada gravado — veja o relatório.');
            carregarFontes(); carregarLog(); carregarAgencias();
          }).catch(function (e) { alvo.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>'; });
        });
        carregarFontes();
      }).catch(function (e) { alvo.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>'; });
    });
    input.click();
  }

  function tabelaCorrecoes(cs) {
    return '<table class="tabela"><thead><tr><th>Linha</th><th>Coluna</th><th>Veio</th><th>Ficou</th><th>Regra</th></tr></thead><tbody>' +
      cs.map(function (c) {
        return '<tr><td>' + c.linha + '</td><td>' + esc(c.coluna) + '</td><td><code>' + esc(c.de) +
          '</code></td><td><code>' + esc(c.para) + '</code></td><td>' + esc(c.regra) + '</td></tr>';
      }).join('') + '</tbody></table>';
  }

  /** Relatório de leitura de uma fonte (análise, import ou upload). */
  function renderRelatorio(r, aoConfirmar) {
    var alvo = $('#relatorio-fonte');
    alvo.style.display = 'block';
    if (r.erro && !r.saneamento) {
      alvo.innerHTML = '<div class="aviso">' + esc(r.erro) + '</div>';
      return;
    }
    var san = r.saneamento || { correcoes: [], rejeitadas: [], porRegra: {} };
    var cls = r.status === 'OK' ? 'ok' : r.status === 'AVISOS' ? 'avisos' : 'erro';
    var h = '<div class="relatorio">';
    h += '<div class="painel-titulo"><h3 style="margin:0"><span class="farol ' + cls + '"></span>' +
      esc(r.fonte ? r.fonte.nome : '') + ' · ' + esc(r.status || '') + '</h3>' +
      '<span class="rotulo">' + esc(r.arquivo || '') + (r.tamanho ? ' · ' + fmtBytes(r.tamanho) : '') +
      (r.modificadoEm ? ' · modificado ' + fmtData(r.modificadoEm) : '') + '</span></div>';
    h += '<p style="margin:6px 0">' + esc(r.resumo || r.erro || '') + '</p>';
    h += '<div class="resumo-saneamento">' +
      tileMini(r.linhas, 'linhas lidas') + tileMini(r.validas, 'válidas') +
      tileMini(san.totalCorrecoes || 0, 'correções automáticas') +
      tileMini(san.totalRejeitadas || 0, 'linhas rejeitadas') +
      tileMini(san.celulasInvalidas || 0, 'células inválidas → vazias') +
      (r.confirmado ? tileMini(r.inseridos + ' / ' + r.atualizados, 'novas / atualizadas') : '') +
      '</div>';
    h += '<p class="rotulo">' + esc(r.encoding || '') + ' · separador "' + esc(r.separador || '') +
      '" · cabeçalho na linha ' + (r.linhaCabecalho || 1) +
      (r.competencias && r.competencias.length ? ' · competência(s): ' + r.competencias.map(esc).join(', ') : '') +
      (r.derivadas ? ' · ' + r.derivadas + ' nota(s) de agência derivada(s) das carteiras' : '') + '</p>';
    if (r.mapeamento) {
      var pares = Object.keys(r.mapeamento).map(function (k) { return '<code>' + esc(k) + '</code> ← ' + esc(r.mapeamento[k]); });
      if (pares.length) h += '<p style="margin:4px 0"><span class="rotulo">colunas casadas:</span> ' + pares.join(' · ') + '</p>';
    }
    if (r.colunasIndicadores && r.colunasIndicadores.length) {
      h += '<p style="margin:4px 0"><span class="rotulo">indicadores encontrados:</span> ' +
        r.colunasIndicadores.map(function (c) { return '<code>' + esc(c) + '</code>'; }).join(' ') +
        (r.colunasIgnoradas && r.colunasIgnoradas.length ? ' <span class="rotulo">· texto ignorado:</span> ' + r.colunasIgnoradas.map(esc).join(', ') : '') + '</p>';
    }
    if ((r.avisos || []).length) {
      h += '<h4>Avisos</h4><ul class="lista-avisos">' + r.avisos.map(function (a) { return '<li>' + esc(a) + '</li>'; }).join('') + '</ul>';
    }
    var regras = Object.keys(san.porRegra || {});
    if (regras.length) {
      h += '<h4>O que foi corrigido sozinho</h4><div class="chips">' + regras.map(function (k) {
        return '<span class="chip">' + esc(k) + ' · ' + san.porRegra[k] + '</span>';
      }).join('') + '</div>';
      h += '<details><summary class="rotulo" style="cursor:pointer">ver as ' + san.correcoes.length +
        ' primeiras correções</summary>' + tabelaCorrecoes(san.correcoes) + '</details>';
    }
    if ((san.rejeitadas || []).length) {
      h += '<h4>Linhas rejeitadas <a class="botao mini claro" href="' + CTX + '/api/admin/fonte/' +
        (r.fonte ? r.fonte.id : '') + '/rejeitadas">baixar CSV para corrigir</a></h4>' +
        '<table class="tabela"><thead><tr><th>Linha</th><th>Motivo</th><th>Conteúdo</th></tr></thead><tbody>' +
        san.rejeitadas.slice(0, 60).map(function (x) {
          return '<tr><td>' + x.linha + '</td><td>' + esc(x.motivo) + '</td><td><code>' + esc(x.conteudo) + '</code></td></tr>';
        }).join('') + '</tbody></table>' +
        (san.rejeitadas.length > 60 ? '<p class="rotulo">… e mais ' + (san.rejeitadas.length - 60) + ' — baixe o CSV completo.</p>' : '');
    }
    if (aoConfirmar && !r.confirmado && r.status !== 'ERRO') {
      h += '<div style="margin-top:12px"><button class="botao primario" id="rel-confirmar" type="button">Confirmar e gravar</button></div>';
    }
    h += '</div>';
    alvo.innerHTML = h;
    var bt = $('#rel-confirmar');
    if (bt) bt.addEventListener('click', aoConfirmar);
    alvo.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function tileMini(valor, rotulo) {
    return '<div class="tile" style="cursor:default"><span class="valor">' + esc(String(valor == null ? '—' : valor)) +
      '</span><span class="rotulo">' + rotulo + '</span></div>';
  }

  function editarFonte(f) {
    $('#fonte-form-titulo').textContent = 'Editar fonte · ' + f.nome;
    $('#fonte-id').value = f.id;
    $('#fonte-nome').value = f.nome;
    $('#fonte-tipo').value = f.tipo;
    $('#fonte-arquivo').value = f.arquivo;
    $('#fonte-auto').value = f.automatico ? '1' : '0';
    $('#fonte-mapeamento').value = Object.keys(f.mapeamento || {}).map(function (k) {
      return k + '=' + f.mapeamento[k]; }).join('\n');
    $('#fonte-cancelar').style.display = '';
    atualizarCamposTipo();
    $('#fonte-nome').scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  function limparFonte() {
    $('#fonte-form-titulo').textContent = 'Nova fonte';
    $('#fonte-id').value = '';
    $('#fonte-nome').value = ''; $('#fonte-arquivo').value = ''; $('#fonte-mapeamento').value = '';
    $('#fonte-auto').value = '1';
    $('#fonte-cancelar').style.display = 'none';
  }

  function atualizarCamposTipo() {
    var tipo = $('#fonte-tipo').value;
    $('#fonte-modelo').href = CTX + '/api/admin/modelo/' + tipo;
    api('admin/campos/' + tipo).then(function (cs) {
      $('#fonte-campos-dica').textContent = cs.filter(function (c) { return c.obrigatorio; })
        .map(function (c) { return c.campo; }).join(', ') + ' obrigatório(s)';
      $('#fonte-campos').innerHTML = cs.map(function (c) {
        return '<span class="chip" title="aceita: ' + esc(c.sinonimos.join(', ')) + '">' +
          esc(c.campo) + (c.obrigatorio ? ' *' : '') + '</span>';
      }).join('') + (tipo === 'indicadores'
        ? '<span class="rotulo" style="align-self:center">+ toda coluna numérica vira indicador</span>' : '');
    }).catch(function () { /* sem dica */ });
  }

  function montarFontes() {
    $('#fonte-tipo').addEventListener('change', atualizarCamposTipo);
    atualizarCamposTipo();
    $('#fonte-cancelar').addEventListener('click', limparFonte);
    $('#fonte-salvar').addEventListener('click', function () {
      var dados = { id: $('#fonte-id').value || null, nome: $('#fonte-nome').value.trim(),
        tipo: $('#fonte-tipo').value, arquivo: $('#fonte-arquivo').value.trim(),
        automatico: $('#fonte-auto').value, mapeamento: $('#fonte-mapeamento').value };
      if (!dados.nome || !dados.arquivo) { toast('Informe o nome e o arquivo.'); return; }
      post('admin/fonte', dados).then(function () {
        toast('Fonte salva.'); limparFonte(); carregarFontes();
      }).catch(function (e) { toast(e.message); });
    });
  }

  // ------------------------------------------------------------------ visões

  var VISOES = [];

  function preencherFontesVisao() {
    var sel = $('#visao-fonte');
    var atual = sel.value;
    var elegiveis = FONTES.filter(function (f) { return f.tipo === 'indicadores' || f.tipo === 'conexao'; });
    sel.innerHTML = elegiveis.length ? elegiveis.map(function (f) {
      return '<option value="' + f.id + '">' + esc(f.nome) + ' (' + esc(TIPO_NOME[f.tipo]) + ')</option>';
    }).join('') : '<option value="">— cadastre uma fonte Conexão ou Indicadores —</option>';
    if (atual) sel.value = atual;
    carregarColunasVisao();
  }

  function carregarColunasVisao(colunaSel, colunaMetaSel) {
    var id = $('#visao-fonte').value;
    var sc = $('#visao-coluna'), sm = $('#visao-coluna-meta');
    if (!id) { sc.innerHTML = ''; sm.innerHTML = '<option value="">—</option>'; return Promise.resolve(); }
    return api('admin/fonte/' + id + '/colunas').then(function (cols) {
      var ops = cols.map(function (c) {
        return '<option value="' + esc(c.coluna) + '">' + esc(c.coluna) +
          (c.competencias ? ' · ' + c.competencias + ' competência(s)' : '') + '</option>';
      }).join('');
      sc.innerHTML = ops || '<option value="">(importe a fonte primeiro para ver as colunas)</option>';
      sm.innerHTML = '<option value="">—</option>' + ops;
      if (colunaSel) sc.value = colunaSel;
      if (colunaMetaSel) sm.value = colunaMetaSel;
    }).catch(function () { sc.innerHTML = ''; });
  }

  function carregarVisoes() {
    return api('admin/visoes').then(function (vs) {
      VISOES = vs;
      $('#corpo-visoes').innerHTML = vs.length ? vs.map(function (v) {
        return '<tr' + (v.ativo ? '' : ' style="opacity:.55"') + '><td>' + v.ordem + '</td><td><strong>' + esc(v.titulo) + '</strong></td>' +
          '<td>' + esc(v.fonteNome || '?') + ' · <code>' + esc(v.coluna) + '</code></td>' +
          '<td>' + esc(v.agregacao) + '</td><td>' + esc(v.formato) + (v.casas ? ' (' + v.casas + ')' : '') + '</td>' +
          '<td>' + (v.meta != null ? v.meta.toLocaleString('pt-BR') : v.colunaMeta ? 'coluna ' + esc(v.colunaMeta) : '—') +
          ' · ' + (v.melhor === 'MENOR' ? '↓ melhor' : '↑ melhor') + '</td>' +
          '<td>' + esc(v.perfilMinimo) + '</td>' +
          '<td><div class="acoes-linha"><button class="botao mini claro" data-v-editar="' + v.id + '">editar</button>' +
          '<button class="botao mini perigo" data-v-excluir="' + v.id + '">remover</button></div></td></tr>';
      }).join('') : '<tr><td colspan="8" class="vazio">Nenhuma visão ainda. Importe uma fonte e crie a primeira abaixo.</td></tr>';
      $$('[data-v-editar]').forEach(function (b) {
        b.addEventListener('click', function () {
          var v = VISOES.filter(function (x) { return String(x.id) === b.getAttribute('data-v-editar'); })[0];
          if (v) editarVisao(v);
        });
      });
      $$('[data-v-excluir]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('Remover esta visão do dashboard?')) return;
          post('admin/visao/' + b.getAttribute('data-v-excluir') + '/excluir', {})
            .then(function () { toast('Visão removida.'); carregarVisoes(); })
            .catch(function (e) { toast(e.message); });
        });
      });
    });
  }

  function editarVisao(v) {
    $('#visao-form-titulo').textContent = 'Editar visão · ' + v.titulo;
    $('#visao-id').value = v.id;
    $('#visao-titulo').value = v.titulo;
    $('#visao-fonte').value = v.fonteId;
    carregarColunasVisao(v.coluna, v.colunaMeta || '');
    $('#visao-agregacao').value = v.agregacao;
    $('#visao-formato').value = v.formato;
    $('#visao-casas').value = v.casas;
    $('#visao-meta').value = v.meta != null ? String(v.meta).replace('.', ',') : '';
    $('#visao-melhor').value = v.melhor;
    $('#visao-minimo').value = v.minimo != null ? String(v.minimo).replace('.', ',') : '';
    $('#visao-maximo').value = v.maximo != null ? String(v.maximo).replace('.', ',') : '';
    $('#visao-perfil').value = v.perfilMinimo;
    $('#visao-ordem').value = v.ordem;
    $('#visao-cancelar').style.display = '';
    $('#visao-titulo').scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  function limparVisao() {
    $('#visao-form-titulo').textContent = 'Nova visão';
    $('#visao-id').value = ''; $('#visao-titulo').value = ''; $('#visao-meta').value = '';
    $('#visao-minimo').value = ''; $('#visao-maximo').value = ''; $('#visao-ordem').value = '0';
    $('#visao-casas').value = '0';
    $('#visao-cancelar').style.display = 'none';
  }

  function montarVisoes() {
    $('#visao-fonte').addEventListener('change', function () { carregarColunasVisao(); });
    $('#visao-cancelar').addEventListener('click', limparVisao);
    $('#visao-salvar').addEventListener('click', function () {
      var dados = { id: $('#visao-id').value || null, titulo: $('#visao-titulo').value.trim(),
        fonteId: $('#visao-fonte').value, coluna: $('#visao-coluna').value,
        agregacao: $('#visao-agregacao').value, formato: $('#visao-formato').value,
        casas: $('#visao-casas').value, meta: $('#visao-meta').value.trim() || null,
        colunaMeta: $('#visao-coluna-meta').value || null, melhor: $('#visao-melhor').value,
        minimo: $('#visao-minimo').value.trim() || null, maximo: $('#visao-maximo').value.trim() || null,
        perfilMinimo: $('#visao-perfil').value, ordem: $('#visao-ordem').value };
      if (!dados.titulo || !dados.fonteId || !dados.coluna) { toast('Título, fonte e coluna são obrigatórios.'); return; }
      post('admin/visao', dados).then(function () {
        toast('Visão salva — já aparece no dashboard.'); limparVisao(); carregarVisoes();
      }).catch(function (e) { toast(e.message); });
    });
  }

  // ------------------------------------------------------------------ fotos

  function carregarAgencias() {
    api('regiao/lista?tipo=agencias').then(function (ags) {
      var sel = $('#foto-prefixo');
      sel.innerHTML = '<option value="">— escolha a agência —</option>' +
        ags.map(function (a) {
          return '<option value="' + esc(a.prefixo) + '">' + esc(a.prefixo) + ' · ' +
            esc(a.nome) + ' (' + esc(a.uf || '') + ')</option>';
        }).join('');
    }).catch(function () { /* mantém o placeholder */ });
  }

  function montarFotos() {
    var zona = $('#zona-foto');
    var input = $('#foto-arquivos');
    zona.addEventListener('click', function () { input.click(); });
    zona.addEventListener('dragover', function (e) { e.preventDefault(); zona.classList.add('sobre'); });
    zona.addEventListener('dragleave', function () { zona.classList.remove('sobre'); });
    zona.addEventListener('drop', function (e) {
      e.preventDefault(); zona.classList.remove('sobre');
      input.files = e.dataTransfer.files;
      aoEscolher();
    });
    input.addEventListener('change', aoEscolher);
    function aoEscolher() {
      zona.textContent = input.files.length
        ? '📸 ' + input.files.length + ' arquivo(s) pronto(s) — clique em Enviar'
        : 'arraste as fotos aqui ou clique para escolher';
    }
    $('#foto-tipo').addEventListener('change', function () {
      $('#campo-matricula').style.display = this.value === 'PESSOA' ? '' : 'none';
    });
    $('#foto-enviar').addEventListener('click', function () {
      var prefixo = $('#foto-prefixo').value;
      if (!prefixo) { toast('Escolha a agência.'); return; }
      if (!input.files.length) { toast('Escolha ao menos uma foto.'); return; }
      var fd = new FormData();
      fd.append('prefixo', prefixo);
      fd.append('tipo', $('#foto-tipo').value);
      fd.append('legenda', $('#foto-legenda').value);
      fd.append('matricula', $('#foto-matricula').value);
      [].forEach.call(input.files, function (f) { fd.append('arquivo', f); });
      $('#foto-status').textContent = 'enviando…';
      api('admin/foto', { method: 'POST', body: fd }).then(function (r) {
        $('#foto-status').textContent = r.gravadas + ' foto(s) gravada(s) ✓';
        toast('Fotos enviadas.');
        input.value = '';
        zona.textContent = 'arraste as fotos aqui ou clique para escolher';
      }).catch(function (e) {
        $('#foto-status').textContent = '';
        toast(e.message);
      });
    });
  }

  // --------------------------------------------------------- masters & flags

  function carregarMasters() {
    api('admin/masters').then(function (ms) {
      $('#lista-masters').innerHTML = ms.map(function (m) {
        return '<div class="nota"><span class="acoes-nota">' +
          '<button class="botao mini perigo" data-master="' + esc(m.matricula) +
          '">remover</button></span><strong>' + esc(m.matricula) + '</strong>' +
          (m.incluidoPor ? '<br><small>incluído por ' + esc(m.incluidoPor) + '</small>' : '') +
          '</div>';
      }).join('');
      $$('[data-master]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('Remover ' + b.getAttribute('data-master') + ' dos masters?')) return;
          post('admin/master', { matricula: b.getAttribute('data-master'), acao: 'remover' })
            .then(carregarMasters).catch(function (e) { toast(e.message); });
        });
      });
    });
  }

  function carregarFlags() {
    api('admin/flags').then(function (fs) {
      $('#lista-flags').innerHTML = fs.length ? fs.map(function (f) {
        return '<div class="nota"><span class="acoes-nota">' +
          '<button class="botao mini claro" data-soltar="' + esc(f.matricula) +
          '">liberar</button></span><strong>' + esc(f.matricula) + '</strong> · ' +
          esc(f.flag) + '</div>';
      }).join('') : '<div class="vazio">Nenhuma restrição ativa.</div>';
      $$('[data-soltar]').forEach(function (b) {
        b.addEventListener('click', function () {
          post('admin/flag', { matricula: b.getAttribute('data-soltar'), flag: '' })
            .then(carregarFlags).catch(function (e) { toast(e.message); });
        });
      });
    });
  }

  function carregarLog() {
    api('admin/importlog').then(function (ls) {
      $('#corpo-importlog').innerHTML = ls.length ? ls.map(function (l) {
        return '<tr><td>' + fmtData(l.criadoEm) + '</td><td>' + esc(l.tipo) + '</td>' +
          '<td>' + esc(l.arquivo || '—') + '</td><td>' + l.inseridos + '</td>' +
          '<td>' + l.atualizados + '</td><td>' + l.ignorados + '</td>' +
          '<td>' + esc(l.criadoPor || '—') + '</td></tr>';
      }).join('') : '<tr><td colspan="7" class="vazio">Nenhum import ainda.</td></tr>';
    });
  }

  // ------------------------------------------------------------------- boot

  document.addEventListener('DOMContentLoaded', function () {
    montarPasta();
    montarFontes();
    montarVisoes();
    montarCadencia();
    carregarCadencia();
    carregarPasta();
    carregarFontes().then(carregarVisoes);
    montarImports();
    montarFotos();
    carregarAgencias();
    carregarMasters();
    carregarFlags();
    carregarLog();

    $('#incluir-master').addEventListener('click', function () {
      var m = $('#novo-master').value.trim();
      if (!m) return;
      post('admin/master', { matricula: m, acao: 'incluir' })
        .then(function () { $('#novo-master').value = ''; toast('Master incluído.'); carregarMasters(); })
        .catch(function (e) { toast(e.message); });
    });
    $('#aplicar-flag').addEventListener('click', function () {
      var m = $('#flag-matricula').value.trim();
      if (!m) return;
      post('admin/flag', { matricula: m, flag: $('#flag-valor').value })
        .then(function () { $('#flag-matricula').value = ''; toast('Flag aplicada.'); carregarFlags(); })
        .catch(function (e) { toast(e.message); });
    });
    $('#exemplo-limpar').addEventListener('click', function () {
      if (!confirm('Apagar TODOS os dados de exemplo (origem EXEMPLO)?')) return;
      post('admin/exemplo', { acao: 'limpar' })
        .then(function () { toast('Dados de exemplo removidos.'); carregarAgencias(); })
        .catch(function (e) { toast(e.message); });
    });
    $('#exemplo-recarregar').addEventListener('click', function () {
      post('admin/exemplo', { acao: 'recarregar' })
        .then(function () { toast('Dados de exemplo recarregados.'); carregarAgencias(); })
        .catch(function (e) { toast(e.message); });
    });
  });
})();
