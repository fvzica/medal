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
          previa.innerHTML =
            (confirmar ? '<strong>✓ Importado.</strong> ' : '<strong>Prévia</strong> (nada gravado): ') +
            r.inseridos + ' novo(s), ' + r.atualizados + ' atualizado(s), ' +
            r.ignorados + ' ignorado(s) de ' + r.linhas + ' linha(s).' +
            (erros.length ? '<ul>' + erros.map(function (e) {
              return '<li>' + esc(e) + '</li>';
            }).join('') + '</ul>' : '');
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
