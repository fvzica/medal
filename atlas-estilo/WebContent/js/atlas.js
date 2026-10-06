/* Atlas Estilo — mapa imersivo das agências (SUPER PF1 · BB) */
(function () {
  'use strict';

  var CTX = window.ATLAS_CTX || '';
  var App = {
    contexto: null,       // /api/contexto
    mapa: null,           // /api/mapa (ufs + agências)
    geo: null,            // geojson das UFs
    proj: null,           // função de projeção lon/lat -> x/y
    vb: { x: 0, y: 0, w: 1000, h: 1000 },
    vbBrasil: null,
    sel: { uf: null, municipio: null },
    agencia: null,        // detalhe aberto no drawer
    listaAberta: null
  };

  var UF_NOMES = { AC:'Acre', AL:'Alagoas', AP:'Amapá', AM:'Amazonas', BA:'Bahia',
    CE:'Ceará', DF:'Distrito Federal', ES:'Espírito Santo', GO:'Goiás',
    MA:'Maranhão', MT:'Mato Grosso', MS:'Mato Grosso do Sul', MG:'Minas Gerais',
    PA:'Pará', PB:'Paraíba', PR:'Paraná', PE:'Pernambuco', PI:'Piauí',
    RJ:'Rio de Janeiro', RN:'Rio Grande do Norte', RS:'Rio Grande do Sul',
    RO:'Rondônia', RR:'Roraima', SC:'Santa Catarina', SP:'São Paulo',
    SE:'Sergipe', TO:'Tocantins' };

  // ------------------------------------------------------------ utilidades

  function $(s, raiz) { return (raiz || document).querySelector(s); }
  function $$(s, raiz) { return [].slice.call((raiz || document).querySelectorAll(s)); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function api(rota, opts) {
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

  function toast(msg) {
    var t = $('#toast');
    t.textContent = msg;
    t.classList.add('visivel');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.classList.remove('visivel'); }, 2600);
  }

  function fmtInt(n) { return (n == null ? 0 : n).toLocaleString('pt-BR'); }

  function fmtValor(n) {
    if (n == null) return '—';
    if (Math.abs(n) >= 1e6) return (n / 1e6).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' mi';
    if (Math.abs(n) >= 1e3) return (n / 1e3).toLocaleString('pt-BR', { maximumFractionDigits: 0 }) + ' mil';
    return n.toLocaleString('pt-BR', { maximumFractionDigits: 1 });
  }

  function fmtMeses(m) {
    if (m == null) return '—';
    if (m < 12) return m.toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' m';
    return (m / 12).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' anos';
  }

  function fmtData(epoch) {
    if (!epoch) return '—';
    return new Date(epoch).toLocaleDateString('pt-BR');
  }

  function dataParaEpoch(valor) { // input date -> epoch millis (meio-dia local)
    if (!valor) return null;
    var p = valor.split('-');
    return new Date(+p[0], +p[1] - 1, +p[2], 12).getTime();
  }

  // --------------------------------------------------------------- projeção

  function criarProjecao(geo) {
    var lonMin = 180, lonMax = -180, latMin = 90, latMax = -90;
    geo.features.forEach(function (f) {
      cadaCoord(f.geometry, function (lon, lat) {
        if (lon < lonMin) lonMin = lon;
        if (lon > lonMax) lonMax = lon;
        if (lat < latMin) latMin = lat;
        if (lat > latMax) latMax = lat;
      });
    });
    var cos = Math.cos((latMin + latMax) / 2 * Math.PI / 180);
    var margem = 28;
    var kx = (1000 - margem * 2) / ((lonMax - lonMin) * cos);
    var ky = (1000 - margem * 2) / (latMax - latMin);
    var k = Math.min(kx, ky);
    var largura = (lonMax - lonMin) * cos * k, altura = (latMax - latMin) * k;
    var dx = (1000 - largura) / 2, dy = (1000 - altura) / 2;
    return function (lon, lat) {
      return [dx + (lon - lonMin) * cos * k, dy + (latMax - lat) * k];
    };
  }

  function cadaCoord(geom, fn) {
    var polys = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates;
    polys.forEach(function (poly) {
      poly.forEach(function (anel) {
        anel.forEach(function (c) { fn(c[0], c[1]); });
      });
    });
  }

  function pathDe(geom, proj) {
    var d = [];
    var polys = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates;
    polys.forEach(function (poly) {
      poly.forEach(function (anel) {
        anel.forEach(function (c, i) {
          var p = proj(c[0], c[1]);
          d.push((i === 0 ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1));
        });
        d.push('Z');
      });
    });
    return d.join('');
  }

  function bboxDe(geom, proj) {
    var xMin = 1e9, xMax = -1e9, yMin = 1e9, yMax = -1e9;
    cadaCoord(geom, function (lon, lat) {
      var p = proj(lon, lat);
      if (p[0] < xMin) xMin = p[0];
      if (p[0] > xMax) xMax = p[0];
      if (p[1] < yMin) yMin = p[1];
      if (p[1] > yMax) yMax = p[1];
    });
    return { x: xMin, y: yMin, w: xMax - xMin, h: yMax - yMin };
  }

  // ------------------------------------------------------------------ mapa

  function desenharMapa() {
    var svg = $('#svg-mapa');
    svg.innerHTML = '';
    var NS = 'http://www.w3.org/2000/svg';
    var porUf = {};
    (App.mapa.ufs || []).forEach(function (u) { porUf[u.uf] = u; });

    var gExtrusao = document.createElementNS(NS, 'g');
    var gFaces = document.createElementNS(NS, 'g');
    svg.appendChild(gExtrusao);
    svg.appendChild(gFaces);

    App.geo.features.forEach(function (f) {
      var uf = f.properties.uf;
      var d = pathDe(f.geometry, App.proj);
      var resumo = porUf[uf];

      var ex = document.createElementNS(NS, 'path');
      ex.setAttribute('d', d);
      ex.setAttribute('class', 'uf-extrusao');
      ex.setAttribute('transform', 'translate(0,7)');
      gExtrusao.appendChild(ex);

      var g = document.createElementNS(NS, 'g');
      g.setAttribute('class', 'uf ' + (resumo ? 'tem-agencia' : 'sem-agencia'));
      g.setAttribute('data-uf', uf);
      var face = document.createElementNS(NS, 'path');
      face.setAttribute('d', d);
      face.setAttribute('class', 'uf-face');
      g.appendChild(face);
      gFaces.appendChild(g);

      var bb = bboxDe(f.geometry, App.proj);
      f._bbox = bb;
      if (resumo) {
        var tx = document.createElementNS(NS, 'text');
        tx.setAttribute('x', bb.x + bb.w / 2);
        tx.setAttribute('y', bb.y + bb.h / 2);
        tx.setAttribute('class', 'rotulo-uf');
        tx.textContent = uf;
        g.appendChild(tx);
        var ct = document.createElementNS(NS, 'text');
        ct.setAttribute('x', bb.x + bb.w / 2);
        ct.setAttribute('y', bb.y + bb.h / 2 + 13);
        ct.setAttribute('class', 'contagem-uf');
        ct.textContent = resumo.agencias + (resumo.agencias === 1 ? ' ag' : ' ags');
        g.appendChild(ct);
      }

      g.addEventListener('click', function () {
        if (resumo && App.sel.uf !== uf) focarUf(uf);
      });
      g.addEventListener('mousemove', function (ev) {
        var html;
        if (resumo) {
          html = '<strong>' + esc(UF_NOMES[uf] || uf) + '</strong>' +
            resumo.agencias + ' agência(s) · ' + resumo.funcis + ' funcis · ' +
            resumo.visitadas + ' visitada(s)';
        } else {
          html = '<strong>' + esc(UF_NOMES[uf] || uf) + '</strong>sem agência Estilo na jurisdição';
        }
        mostrarDica(ev, html);
      });
      g.addEventListener('mouseleave', esconderDica);
    });

    var gPins = document.createElementNS(NS, 'g');
    gPins.setAttribute('id', 'g-pins');
    svg.appendChild(gPins);

    App.vbBrasil = { x: 0, y: 0, w: 1000, h: 1000 };
    aplicarViewBox(App.vbBrasil);
  }

  function desenharPins(uf, municipio) {
    var NS = 'http://www.w3.org/2000/svg';
    var g = $('#g-pins');
    g.innerHTML = '';
    var rotulados = []; // posições com rótulo, p/ suprimir colisões
    App.mapa.agencias.forEach(function (a) {
      if (a.uf !== uf || a.lat == null || a.lng == null) return;
      var p = App.proj(a.lng, a.lat);
      var apagada = municipio && a.municipio !== municipio;
      var pin = document.createElementNS(NS, 'g');
      pin.setAttribute('class', 'pin' + (a.visitada ? ' visitada' : ''));
      pin.setAttribute('transform', 'translate(' + p[0] + ',' + p[1] + ')');
      if (apagada) pin.setAttribute('opacity', '.25');
      var escala = Math.max(App.vb.w / 1000, .22);
      // rótulo só quando não colide com outro já desenhado (o tooltip cobre o resto)
      var minDist = 34 * escala * 3;
      var cabeRotulo = !apagada && !rotulados.some(function (q) {
        return Math.abs(q[0] - p[0]) < minDist * 2.4 && Math.abs(q[1] - p[1]) < minDist * .55;
      });
      if (cabeRotulo) rotulados.push(p);
      pin.innerHTML =
        '<circle class="pulso" r="3"></circle>' +
        '<circle class="nucleo" r="' + (3.2 * escala * 3).toFixed(2) + '"></circle>' +
        (cabeRotulo
          ? '<text y="' + (-5 * escala * 3).toFixed(1) + '" style="font-size:' +
            (7.5 * escala * 2.4).toFixed(1) + 'px">' +
            esc(a.nome.replace(/^Estilo /, '')) + '</text>'
          : '');
      pin.addEventListener('click', function (ev) {
        ev.stopPropagation();
        abrirAgencia(a.prefixo);
      });
      pin.addEventListener('mousemove', function (ev) {
        mostrarDica(ev, '<strong>' + esc(a.nome) + '</strong>' +
          'Prefixo ' + esc(a.prefixo) + ' · ' + esc(a.municipio || '') +
          '<br>' + a.funcis + ' funcis · ' +
          (a.visitada ? '✓ visitada' : 'ainda não visitada') +
          (a.pontosAbertos ? ' · ' + a.pontosAbertos + ' ponto(s) aberto(s)' : ''));
      });
      pin.addEventListener('mouseleave', esconderDica);
      g.appendChild(pin);
    });
  }

  function aplicarViewBox(vb) {
    App.vb = vb;
    $('#svg-mapa').setAttribute('viewBox',
      vb.x.toFixed(1) + ' ' + vb.y.toFixed(1) + ' ' +
      vb.w.toFixed(1) + ' ' + vb.h.toFixed(1));
  }

  function animarViewBox(destino, aoFim) {
    var inicio = { x: App.vb.x, y: App.vb.y, w: App.vb.w, h: App.vb.h };
    var t0 = performance.now(), dur = 650;
    function passo(t) {
      var k = Math.min(1, (t - t0) / dur);
      k = 1 - Math.pow(1 - k, 3); // easeOutCubic
      aplicarViewBox({
        x: inicio.x + (destino.x - inicio.x) * k,
        y: inicio.y + (destino.y - inicio.y) * k,
        w: inicio.w + (destino.w - inicio.w) * k,
        h: inicio.h + (destino.h - inicio.h) * k
      });
      if (k < 1) requestAnimationFrame(passo);
      else if (aoFim) aoFim();
    }
    requestAnimationFrame(passo);
  }

  function focarUf(uf) {
    var feat = null;
    App.geo.features.forEach(function (f) { if (f.properties.uf === uf) feat = f; });
    if (!feat) return;
    App.sel.uf = uf;
    App.sel.municipio = null;

    var svg = $('#svg-mapa');
    svg.classList.add('mapa-focado');
    $$('.uf', svg).forEach(function (g) {
      g.classList.toggle('selecionada', g.getAttribute('data-uf') === uf);
    });
    $('#palco-mapa').classList.add('plano');

    var bb = feat._bbox, m = Math.max(bb.w, bb.h) * .18;
    var vb = { x: bb.x - m, y: bb.y - m, w: bb.w + m * 2, h: bb.h + m * 2 };
    animarViewBox(vb, function () { desenharPins(uf, null); });
    $('#lenda-dica').textContent = 'clique num pin para abrir a agência · Esc volta';

    montarMigalhas();
    carregarPainel({ uf: uf });
  }

  function voltarBrasil() {
    App.sel.uf = null;
    App.sel.municipio = null;
    var svg = $('#svg-mapa');
    svg.classList.remove('mapa-focado');
    $$('.uf', svg).forEach(function (g) { g.classList.remove('selecionada'); });
    $('#palco-mapa').classList.remove('plano');
    $('#g-pins').innerHTML = '';
    animarViewBox(App.vbBrasil);
    $('#lenda-dica').textContent = 'clique num estado para mergulhar';
    montarMigalhas();
    carregarPainel({});
  }

  function selecionarMunicipio(m) {
    App.sel.municipio = m;
    desenharPins(App.sel.uf, m);
    montarMigalhas();
    carregarPainel({ uf: App.sel.uf, municipio: m });
  }

  function montarMigalhas() {
    var el = $('#migalhas');
    var h = '<button type="button" data-nivel="brasil">Brasil</button>';
    if (App.sel.uf) {
      h += '<span class="seta">›</span>';
      h += App.sel.municipio
        ? '<button type="button" data-nivel="uf">' + esc(UF_NOMES[App.sel.uf] || App.sel.uf) + '</button>'
        : '<span class="atual">' + esc(UF_NOMES[App.sel.uf] || App.sel.uf) + '</span>';
    }
    if (App.sel.municipio) {
      h += '<span class="seta">›</span><span class="atual">' + esc(App.sel.municipio) + '</span>';
    }
    el.innerHTML = h;
    $$('button', el).forEach(function (b) {
      b.addEventListener('click', function () {
        if (b.getAttribute('data-nivel') === 'brasil') voltarBrasil();
        else { App.sel.municipio = null; desenharPins(App.sel.uf, null);
               montarMigalhas(); carregarPainel({ uf: App.sel.uf }); }
      });
    });
  }

  var dicaEl;
  function mostrarDica(ev, html) {
    dicaEl = dicaEl || $('#dica-mapa');
    dicaEl.innerHTML = html;
    dicaEl.style.left = Math.min(ev.clientX + 14, window.innerWidth - 270) + 'px';
    dicaEl.style.top = (ev.clientY + 16) + 'px';
    dicaEl.classList.add('visivel');
  }
  function esconderDica() { if (dicaEl) dicaEl.classList.remove('visivel'); }

  // ------------------------------------------------------- painel da região

  function filtrosAtuais(extra) {
    var f = extra || {};
    var q = [];
    if (f.uf) q.push('uf=' + encodeURIComponent(f.uf));
    if (f.municipio) q.push('municipio=' + encodeURIComponent(f.municipio));
    if (f.prefixos) q.push('prefixos=' + encodeURIComponent(f.prefixos));
    return q.length ? '?' + q.join('&') : '';
  }

  function carregarPainel(f) {
    var alvo = $('#painel-regiao');
    alvo.innerHTML = '<div class="cartao"><div class="cartao-corpo">' +
      '<div class="carregando">Somando a região…</div></div></div>';
    var pedidos = [
      api('regiao' + filtrosAtuais(f)),
      api('regiao/lista' + filtrosAtuais(f) + (filtrosAtuais(f) ? '&' : '?') + 'tipo=agencias')
    ];
    if (f.uf && !f.municipio) pedidos.push(api('municipios?uf=' + encodeURIComponent(f.uf)));
    Promise.all(pedidos).then(function (r) {
      renderPainel(f, r[0], r[1], r[2] || null);
    }).catch(function (e) {
      alvo.innerHTML = '<div class="cartao"><div class="cartao-corpo">' +
        '<div class="aviso">' + esc(e.message) + '</div></div></div>';
    });
  }

  function tile(rotulo, valor, tipoLista, destaque, sufixo) {
    var podeAbrir = tipoLista && (App.contexto.veTudo ||
      ['agencias', 'carteiras', 'pdg'].indexOf(tipoLista) >= 0);
    return '<button class="tile' + (destaque ? ' destaque' : '') + '" type="button" ' +
      (podeAbrir ? 'data-lista="' + tipoLista + '"' : 'disabled') + '>' +
      '<span class="valor">' + valor + (sufixo || '') + '</span>' +
      '<span class="rotulo">' + rotulo + '</span></button>';
  }

  function renderPainel(f, resumo, agencias, municipios) {
    var titulo = f.municipio ? f.municipio
      : f.uf ? (UF_NOMES[f.uf] || f.uf)
      : (App.contexto.regionalJurisdicao || 'Brasil');
    var h = '<div class="cartao"><div class="cartao-corpo">';
    h += '<div class="painel-titulo"><h2>' + esc(titulo) + '</h2>' +
      '<span class="rotulo">' + fmtInt(resumo.agencias) + ' agência(s)</span></div>';

    h += '<div class="grade-tiles">';
    h += tile('Funcis', fmtInt(resumo.funcis), 'funcis');
    h += tile('Gerentes', fmtInt(resumo.gerentes), 'gerentes');
    h += tile('Assistentes', fmtInt(resumo.assistentes), 'assistentes');
    h += tile('Carteiras', fmtInt(resumo.carteiras), 'carteiras');
    h += tile('Média de funcis por agência', resumo.mediaFuncisPorAgencia != null
      ? resumo.mediaFuncisPorAgencia.toLocaleString('pt-BR') : '—', null);
    h += tile('Tempo médio no cargo', fmtMeses(resumo.tempoMedioCargoMeses), null);
    h += tile('Tempo médio na função', fmtMeses(resumo.tempoMedioFuncaoMeses), null);
    h += tile('Semestres com PDG', fmtInt(resumo.pdgGanhos), 'pdg', true);
    h += '</div>';

    h += '<div class="chips" style="margin-top:2px">' +
      '<span class="badge verde">✓ ' + fmtInt(resumo.visitadas) + ' visitada(s)</span>' +
      '<span class="badge ' + (resumo.pontosAbertos ? 'vinho' : 'neutro') + '">' +
      (resumo.pontosAbertos ? '⚑ ' : '') + fmtInt(resumo.pontosAbertos) +
      ' ponto(s) de melhoria aberto(s)</span></div>';

    if (municipios && municipios.length > 1) {
      h += '<p class="rotulo" style="margin:14px 0 4px">Municípios</p><div class="chips">';
      municipios.forEach(function (m) {
        h += '<button class="chip" type="button" data-municipio="' + esc(m.municipio) + '">' +
          esc(m.municipio) + ' · ' + m.agencias + '</button>';
      });
      h += '</div>';
    }

    h += '<div id="zona-lista"></div>';

    h += '<p class="rotulo" style="margin:16px 0 4px">Agências' +
      (f.municipio ? ' de ' + esc(f.municipio) : '') + '</p>';
    h += '<div class="lista-agencias">';
    if (!agencias.length) h += '<div class="vazio">Nenhuma agência na seleção.</div>';
    agencias.forEach(function (a) {
      h += '<button class="item-agencia' + (a.visitada ? ' visitada' : '') +
        '" type="button" data-prefixo="' + esc(a.prefixo) + '">' +
        '<span class="selo-visita" title="' + (a.visitada ? 'visitada' : 'não visitada') + '"></span>' +
        '<span><span class="nome">' + esc(a.nome) + '</span>' +
        '<small>' + esc(a.prefixo) + ' · ' + esc(a.municipio || '') + '/' + esc(a.uf || '') + '</small></span>' +
        '<span class="numeros">' + a.funcis + ' funcis<br>' + a.pdgGanhos + '× PDG</span>' +
        '</button>';
    });
    h += '</div></div></div>';

    var alvo = $('#painel-regiao');
    alvo.innerHTML = h;

    $$('[data-lista]', alvo).forEach(function (b) {
      b.addEventListener('click', function () {
        abrirLista(f, b.getAttribute('data-lista'), b);
      });
    });
    $$('[data-municipio]', alvo).forEach(function (b) {
      b.addEventListener('click', function () {
        selecionarMunicipio(b.getAttribute('data-municipio'));
      });
    });
    $$('[data-prefixo]', alvo).forEach(function (b) {
      b.addEventListener('click', function () {
        abrirAgencia(b.getAttribute('data-prefixo'));
      });
    });
  }

  var ROTULO_LISTA = { funcis: 'Funcis da seleção', gerentes: 'Gerentes da seleção',
    assistentes: 'Assistentes da seleção', carteiras: 'Carteiras da seleção',
    pdg: 'PDG por agência' };

  function abrirLista(f, tipo, botao) {
    var zona = $('#zona-lista');
    if (App.listaAberta === tipo) { zona.innerHTML = ''; App.listaAberta = null; return; }
    App.listaAberta = tipo;
    zona.innerHTML = '<div class="carregando">Buscando…</div>';
    api('regiao/lista' + filtrosAtuais(f) + (filtrosAtuais(f) ? '&' : '?') + 'tipo=' + tipo)
      .then(function (itens) {
        var h = '<p class="rotulo" style="margin:16px 0 4px">' +
          (ROTULO_LISTA[tipo] || tipo) + ' · ' + itens.length + '</p>';
        if (tipo === 'carteiras') {
          h += '<table class="tabela"><thead><tr><th>Carteira</th><th>Agência</th>' +
            '<th>Gerente</th><th>Clientes</th></tr></thead><tbody>';
          itens.forEach(function (c) {
            h += '<tr><td><strong>' + esc(c.codigo) + '</strong><br><small>' +
              esc(c.nome || '') + '</small></td><td>' + esc(c.agencia) + '</td><td>' +
              esc(c.gerenteNome || c.gerenteMatricula || '—') + '</td><td>' +
              fmtInt(c.qtdClientes) + '</td></tr>';
          });
          h += '</tbody></table>';
        } else if (tipo === 'pdg') {
          h += '<table class="tabela"><thead><tr><th>Agência</th><th>Semestres</th>' +
            '<th>Ganhos</th></tr></thead><tbody>';
          itens.forEach(function (p) {
            h += '<tr><td>' + esc(p.agencia) + ' <small>(' + esc(p.prefixo) + ')</small></td>' +
              '<td>' + p.semestres + '</td><td><span class="badge ' +
              (p.ganhos > 0 ? 'verde' : 'neutro') + '">' + p.ganhos + '×</span></td></tr>';
          });
          h += '</tbody></table>';
        } else {
          h += '<table class="tabela"><thead><tr><th>Funci</th><th>Agência</th>' +
            '<th>Cargo / Função</th><th>No cargo</th></tr></thead><tbody>';
          itens.forEach(function (p) {
            h += '<tr><td>' + pessoaHtml(p) + '</td><td>' + esc(p.agencia) +
              '</td><td>' + esc(p.cargo || '—') +
              (p.funcao ? '<br><small>' + esc(p.funcao) + '</small>' : '') +
              '</td><td>' + fmtMeses(p.mesesCargo) + '</td></tr>';
          });
          h += '</tbody></table>';
        }
        zona.innerHTML = h;
        zona.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      })
      .catch(function (e) { zona.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>'; });
  }

  function pessoaHtml(p) {
    var ini = esc((p.nome || '?').trim().charAt(0).toUpperCase());
    return '<span class="pessoa">' +
      '<img class="avatar" alt="" src="https://humanograma.intranet.bb.com.br/avatar/' +
      esc(p.matricula) + '" onerror="this.style.display=\'none\';' +
      'this.nextElementSibling.style.display=\'flex\'">' +
      '<span class="avatar avatar-iniciais" style="display:none">' + ini + '</span>' +
      '<span><span class="nome">' + esc(p.nome) + '</span>' +
      '<small>' + esc(p.matricula) + (p.carteira ? ' · ' + esc(p.carteira) : '') +
      '</small></span></span>';
  }

  // -------------------------------------------------------- drawer agência

  function abrirAgencia(prefixo) {
    $('#veu').classList.add('aberto');
    $('#drawer-agencia').classList.add('aberto');
    $('#ag-corpo').innerHTML = '<div class="carregando">Abrindo a agência…</div>';
    api('agencia/' + prefixo).then(function (d) {
      App.agencia = d;
      renderAgencia(d);
    }).catch(function (e) {
      $('#ag-corpo').innerHTML = '<div class="aviso">' + esc(e.message) + '</div>';
    });
  }

  function fecharAgencia() {
    $('#drawer-agencia').classList.remove('aberto');
    if (!$('#painel-dash').classList.contains('aberto')) $('#veu').classList.remove('aberto');
    App.agencia = null;
  }

  function fachadaHtml(d) {
    var ag = d.agencia;
    var fachada = (d.fotos || []).filter(function (f) { return f.tipo === 'FACHADA'; })[0];
    var h = '<div class="fachada">';
    if (fachada) {
      h += '<img class="foto-fachada" alt="Fachada" src="' + CTX + '/foto/' + esc(fachada.id) + '">';
    } else if (ag.gmapsUrl) {
      h += '<iframe src="' + esc(ag.gmapsUrl) + '" loading="lazy" ' +
        'referrerpolicy="no-referrer-when-downgrade" allowfullscreen></iframe>';
    } else if (App.contexto.mapsAtivo && ag.lat != null && ag.lng != null) {
      h += '<iframe src="https://maps.google.com/maps?q=' + ag.lat + ',' + ag.lng +
        '&z=18&t=k&output=embed" loading="lazy"></iframe>';
    } else {
      h += '<div class="sem-imagem"><div><p style="font-size:40px;margin:0">🏛</p>' +
        '<p>Sem imagem da fachada ainda.<br><small>O Master pode subir uma foto na tela Admin' +
        ' ou colar um link de embed do Google Maps.</small></p></div></div>';
    }
    h += '<button class="porta" id="botao-porta" type="button" ' +
      'title="Entrar na agência">Entrar · dashboard</button></div>';
    return h;
  }

  function renderAgencia(d) {
    var ag = d.agencia, r = d.resumo;
    $('#ag-rotulo').textContent = 'Prefixo ' + ag.prefixo + ' · ' + (ag.regional || '');
    $('#ag-nome').textContent = ag.nome;

    var veTudo = App.contexto.veTudo;
    var h = fachadaHtml(d);

    h += '<div class="cartao"><div class="cartao-corpo">';
    h += '<div class="abas" id="ag-abas">';
    h += '<button data-aba="geral" class="ativa">Visão geral</button>';
    if (veTudo) {
      h += '<button data-aba="equipe">Equipe · ' + (d.equipe || []).length + '</button>';
      h += '<button data-aba="carteiras">Carteiras · ' + (d.carteiras || []).length + '</button>';
    }
    h += '<button data-aba="fotos">Fotos · ' + (d.fotos || []).length + '</button>';
    if (App.contexto.master) {
      h += '<button data-aba="visitas">Visitas &amp; anotações</button>';
      h += '<button data-aba="pontos">Pontos · ' +
        (d.pontos || []).filter(function (p) { return p.status !== 'RESOLVIDO'; }).length +
        '</button>';
    }
    h += '</div>';

    // --- aba geral
    h += '<div class="aba-corpo ativa" data-corpo="geral">';
    h += '<p style="margin:14px 0 2px"><strong>' + esc(ag.endereco || 'Endereço não informado') +
      '</strong><br><span class="rotulo">' + esc(ag.municipio || '') + '/' + esc(ag.uf || '') +
      (ag.cep ? ' · CEP ' + esc(ag.cep) : '') + '</span></p>';
    h += '<div class="grade-tiles">';
    h += '<button class="tile" disabled><span class="valor">' + fmtInt(r.funcis) +
      '</span><span class="rotulo">Funcis</span></button>';
    h += '<button class="tile" disabled><span class="valor">' + fmtInt(r.gerentes) +
      '</span><span class="rotulo">Gerentes</span></button>';
    h += '<button class="tile" disabled><span class="valor">' + fmtInt(r.carteiras) +
      '</span><span class="rotulo">Carteiras</span></button>';
    h += '<button class="tile destaque" disabled><span class="valor">' + fmtInt(r.pdgGanhos) +
      '</span><span class="rotulo">Semestres com PDG</span></button>';
    h += '</div>';
    h += '<p class="rotulo" style="margin:8px 0 4px">Histórico de PDG</p>';
    h += pdgTimelineHtml(d.pdgHistorico || []);
    if (App.contexto.master) {
      h += '<div class="campo" style="margin-top:16px"><label>Link de embed do Google Maps / Street View (opcional)</label>' +
        '<div style="display:flex;gap:8px"><input id="ag-gmaps" type="url" style="flex:1" ' +
        'placeholder="https://www.google.com/maps/embed?pb=…" value="' + esc(ag.gmapsUrl || '') + '">' +
        '<button class="botao claro" id="salvar-gmaps" type="button">Salvar</button></div></div>';
    }
    h += '</div>';

    // --- equipe
    if (veTudo) {
      h += '<div class="aba-corpo" data-corpo="equipe">';
      h += '<table class="tabela"><thead><tr><th>Funci</th><th>Cargo / Função</th>' +
        '<th>No cargo</th><th>Na função</th></tr></thead><tbody>';
      (d.equipe || []).forEach(function (p) {
        h += '<tr><td>' + pessoaHtml(p) + '</td><td>' + esc(p.cargo || '—') +
          (p.funcao ? '<br><small>' + esc(p.funcao) + '</small>' : '') + '</td><td>' +
          fmtMeses(p.mesesCargo) + '</td><td>' + fmtMeses(p.mesesFuncao) + '</td></tr>';
      });
      h += '</tbody></table></div>';

      h += '<div class="aba-corpo" data-corpo="carteiras">';
      h += '<table class="tabela"><thead><tr><th>Carteira</th><th>Gerente</th>' +
        '<th>Clientes</th></tr></thead><tbody>';
      (d.carteiras || []).forEach(function (c) {
        h += '<tr><td><strong>' + esc(c.codigo) + '</strong><br><small>' + esc(c.nome || '') +
          '</small></td><td>' + esc(c.gerenteNome || c.gerenteMatricula || '—') + '</td>' +
          '<td>' + fmtInt(c.qtdClientes) + '</td></tr>';
      });
      h += '</tbody></table></div>';
    }

    // --- fotos
    h += '<div class="aba-corpo" data-corpo="fotos">';
    if ((d.fotos || []).length) {
      h += '<div class="galeria" style="margin-top:14px">';
      d.fotos.forEach(function (f) {
        h += '<figure><img loading="lazy" src="' + CTX + '/foto/' + esc(f.id) +
          '" alt="" onclick="window.open(this.src)">' +
          '<figcaption>' + esc(f.legenda || f.tipo) + '</figcaption></figure>';
      });
      h += '</div>';
    } else {
      h += '<div class="vazio">Nenhuma foto ainda' +
        (App.contexto.master ? ' — suba as suas na tela Admin.' : '.') + '</div>';
    }
    h += '</div>';

    // --- visitas & anotações (Master)
    if (App.contexto.master) {
      h += '<div class="aba-corpo" data-corpo="visitas">' + visitasHtml(d) + '</div>';
      h += '<div class="aba-corpo" data-corpo="pontos">' + pontosHtml(d) + '</div>';
    }

    h += '</div></div>';
    $('#ag-corpo').innerHTML = h;

    $('#botao-porta').addEventListener('click', function () { abrirDash(d); });
    $$('#ag-abas button').forEach(function (b) {
      b.addEventListener('click', function () {
        $$('#ag-abas button').forEach(function (x) { x.classList.remove('ativa'); });
        b.classList.add('ativa');
        $$('.aba-corpo', $('#ag-corpo')).forEach(function (c) {
          c.classList.toggle('ativa', c.getAttribute('data-corpo') === b.getAttribute('data-aba'));
        });
      });
    });
    if (App.contexto.master) ligarGestao(d);
  }

  function pdgTimelineHtml(hist) {
    if (!hist.length) return '<div class="vazio">Sem histórico de PDG importado.</div>';
    var h = '<div class="timeline-pdg">';
    hist.forEach(function (s) {
      h += '<div class="semestre-pdg ' + (s.atingiu ? 'ganhou' : 'perdeu') + '">' +
        '<span class="resultado">' + (s.atingiu ? '✓' : '✗') + '</span>' +
        '<span class="rotulo">' + esc(s.semestre) + '</span>' +
        (s.pontuacao != null ? '<span class="pontos">' +
          s.pontuacao.toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' pts</span>' : '') +
        '</div>';
    });
    return h + '</div>';
  }

  function visitasHtml(d) {
    var h = '<div class="linha-campos" style="margin-top:14px">' +
      '<div class="campo"><label>Status</label><select id="v-status">' +
      '<option value="PLANEJADA">Planejada</option>' +
      '<option value="REALIZADA">Realizada</option></select></div>' +
      '<div class="campo"><label>Data</label><input type="date" id="v-data"></div>' +
      '<div class="campo" style="flex:2"><label>Resumo</label>' +
      '<input type="text" id="v-resumo" placeholder="como foi / o que combinar"></div>' +
      '<div class="campo"><label>&nbsp;</label>' +
      '<button class="botao primario" id="v-salvar" type="button">Registrar</button></div></div>';
    h += '<div id="v-lista">';
    (d.visitas || []).forEach(function (v) { h += visitaItemHtml(v); });
    if (!(d.visitas || []).length) h += '<div class="vazio">Nenhuma visita registrada.</div>';
    h += '</div>';
    h += '<p class="rotulo" style="margin:18px 0 6px">Anotações da agência</p>';
    h += '<div style="display:flex;gap:8px"><input type="text" id="a-texto" style="flex:1" ' +
      'class="campo-input" placeholder="anotar algo sobre esta agência…">' +
      '<button class="botao claro" id="a-salvar" type="button">Anotar</button></div>';
    h += '<div id="a-lista" style="margin-top:10px;display:flex;flex-direction:column;gap:8px">';
    (d.anotacoes || []).forEach(function (a) { h += notaHtml(a); });
    h += '</div>';
    return h;
  }

  function visitaItemHtml(v) {
    var badge = v.status === 'REALIZADA' ? '<span class="badge verde">✓ realizada</span>'
      : v.status === 'PLANEJADA' ? '<span class="badge ouro">planejada</span>'
      : '<span class="badge neutro">cancelada</span>';
    var data = v.status === 'REALIZADA' ? fmtData(v.dataRealizada) : fmtData(v.dataPlanejada);
    return '<div class="nota" data-visita="' + v.id + '">' +
      '<span class="acoes-nota">' +
      (v.status === 'PLANEJADA'
        ? '<button class="botao mini primario" data-acao="realizar">feita hoje</button>' : '') +
      '<button class="botao mini perigo" data-acao="excluir-visita">excluir</button></span>' +
      badge + ' <strong>' + data + '</strong>' +
      (v.resumo ? '<br>' + esc(v.resumo) : '') + '</div>';
  }

  function notaHtml(a) {
    return '<div class="nota' + (a.fixada ? ' fixada' : '') + '" data-anotacao="' + a.id + '">' +
      '<span class="acoes-nota">' +
      '<button class="botao mini claro" data-acao="fixar">' + (a.fixada ? 'solta' : 'fixa') + '</button>' +
      '<button class="botao mini perigo" data-acao="excluir-anotacao">excluir</button></span>' +
      esc(a.texto) + '<br><small>' + fmtData(a.criadoEm) + '</small></div>';
  }

  function pontosHtml(d) {
    var h = '<div class="linha-campos" style="margin-top:14px">' +
      '<div class="campo" style="flex:2"><label>Novo ponto de melhoria</label>' +
      '<input type="text" id="p-desc" placeholder="o que precisa melhorar"></div>' +
      '<div class="campo"><label>Previsão</label><input type="date" id="p-prev"></div>' +
      '<div class="campo"><label>&nbsp;</label>' +
      '<button class="botao primario" id="p-salvar" type="button">Abrir ponto</button></div></div>';
    h += '<div id="p-lista">';
    (d.pontos || []).forEach(function (p) { h += pontoItemHtml(p); });
    if (!(d.pontos || []).length) h += '<div class="vazio">Nenhum ponto registrado.</div>';
    return h + '</div>';
  }

  function pontoItemHtml(p) {
    var badge = p.status === 'RESOLVIDO' ? '<span class="badge verde">✓ resolvido</span>'
      : p.status === 'EM_TRATATIVA' ? '<span class="badge ouro">em tratativa</span>'
      : '<span class="badge vinho">aberto</span>';
    var atrasado = p.status !== 'RESOLVIDO' && p.previsao && p.previsao < Date.now();
    return '<div class="nota" data-ponto="' + p.id + '">' +
      '<span class="acoes-nota">' +
      (p.status === 'ABERTO'
        ? '<button class="botao mini claro" data-acao="tratar">tratar</button>' : '') +
      (p.status !== 'RESOLVIDO'
        ? '<button class="botao mini primario" data-acao="resolver">resolver</button>' : '') +
      '<button class="botao mini perigo" data-acao="excluir-ponto">excluir</button></span>' +
      badge + (p.previsao ? ' <span class="badge ' + (atrasado ? 'vinho' : 'neutro') + '">prev. ' +
        fmtData(p.previsao) + (atrasado ? ' ⚠' : '') + '</span>' : '') +
      '<br><strong>' + esc(p.descricao) + '</strong>' +
      (p.solucao ? '<br><small>Solução: ' + esc(p.solucao) + '</small>' : '') + '</div>';
  }

  function ligarGestao(d) {
    var prefixo = d.agencia.prefixo;
    var raiz = $('#ag-corpo');

    var salvarGmaps = $('#salvar-gmaps', raiz);
    if (salvarGmaps) salvarGmaps.addEventListener('click', function () {
      post('agencia/' + prefixo + '/gmaps', { url: $('#ag-gmaps').value.trim() })
        .then(function () { toast('Link do Maps salvo.'); abrirAgencia(prefixo); })
        .catch(function (e) { toast(e.message); });
    });

    var vSalvar = $('#v-salvar', raiz);
    if (vSalvar) vSalvar.addEventListener('click', function () {
      var status = $('#v-status').value;
      var data = dataParaEpoch($('#v-data').value);
      post('visita', {
        prefixo: prefixo, status: status,
        dataPlanejada: status === 'PLANEJADA' ? data : null,
        dataRealizada: status === 'REALIZADA' ? (data || Date.now()) : null,
        resumo: $('#v-resumo').value
      }).then(function () { toast('Visita registrada.'); recarregar(prefixo); })
        .catch(function (e) { toast(e.message); });
    });

    var aSalvar = $('#a-salvar', raiz);
    if (aSalvar) aSalvar.addEventListener('click', function () {
      var texto = $('#a-texto').value.trim();
      if (!texto) return;
      post('anotacao', { prefixo: prefixo, texto: texto })
        .then(function () { toast('Anotação salva.'); recarregar(prefixo); })
        .catch(function (e) { toast(e.message); });
    });

    var pSalvar = $('#p-salvar', raiz);
    if (pSalvar) pSalvar.addEventListener('click', function () {
      var desc = $('#p-desc').value.trim();
      if (!desc) return;
      post('ponto', { prefixo: prefixo, descricao: desc,
        previsao: dataParaEpoch($('#p-prev').value) })
        .then(function () { toast('Ponto aberto.'); recarregar(prefixo); })
        .catch(function (e) { toast(e.message); });
    });

    raiz.addEventListener('click', function (ev) {
      var b = ev.target.closest('[data-acao]');
      if (!b) return;
      var acao = b.getAttribute('data-acao');
      var nota = b.closest('.nota');
      var p;
      if (acao === 'realizar') {
        post('visita/' + nota.getAttribute('data-visita'),
          { status: 'REALIZADA', dataRealizada: Date.now() })
          .then(function () { toast('Visita concluída. 🎉'); recarregar(prefixo); });
      } else if (acao === 'excluir-visita') {
        post('visita/' + nota.getAttribute('data-visita') + '/excluir', {})
          .then(function () { recarregar(prefixo); });
      } else if (acao === 'fixar') {
        var fixada = nota.classList.contains('fixada') ? '0' : '1';
        post('anotacao/' + nota.getAttribute('data-anotacao'), { fixada: fixada })
          .then(function () { recarregar(prefixo); });
      } else if (acao === 'excluir-anotacao') {
        post('anotacao/' + nota.getAttribute('data-anotacao') + '/excluir', {})
          .then(function () { recarregar(prefixo); });
      } else if (acao === 'tratar') {
        post('ponto/' + nota.getAttribute('data-ponto'), { status: 'EM_TRATATIVA' })
          .then(function () { recarregar(prefixo); });
      } else if (acao === 'resolver') {
        p = prompt('Qual foi a solução aplicada?');
        if (p == null) return;
        post('ponto/' + nota.getAttribute('data-ponto'), { status: 'RESOLVIDO', solucao: p })
          .then(function () { toast('Ponto resolvido. ✓'); recarregar(prefixo); });
      } else if (acao === 'excluir-ponto') {
        post('ponto/' + nota.getAttribute('data-ponto') + '/excluir', {})
          .then(function () { recarregar(prefixo); });
      }
    });
  }

  function recarregar(prefixo) {
    api('mapa').then(function (m) { App.mapa = m; });
    abrirAgencia(prefixo);
  }

  // -------------------------------------------------------------- dashboard

  function abrirDash(d) {
    $('#dash-nome').textContent = d.agencia.nome;
    $('#painel-dash').classList.add('aberto');
    $('#veu').classList.add('aberto');
    var corpo = $('#dash-corpo');

    var r = d.resumo;
    var h = '<div class="dash-grade">';

    // coluna 1: metas do período
    h += '<div class="cartao"><div class="cartao-corpo">';
    h += '<h3 style="font-size:19px">Metas do semestre</h3>';
    var metas = d.metas || [];
    var periodo = metas.length ? metas[0].periodo : null;
    var doPeriodo = metas.filter(function (m) { return m.periodo === periodo; });
    if (!doPeriodo.length) {
      h += '<div class="vazio">Sem metas importadas' +
        (App.contexto.master ? ' — use o import "Metas" na tela Admin.' : '.') + '</div>';
    } else {
      h += '<p class="rotulo" style="margin:0 0 6px">' + esc(periodo) +
        ' · realizado × meta, com marca de projeção</p>';
      doPeriodo.forEach(function (m) {
        var pct = m.meta ? Math.round((m.realizado || 0) / m.meta * 100) : null;
        var pctProj = m.meta && m.projecao ? Math.min(100, (m.projecao / m.meta) * 100) : null;
        var largura = pct == null ? 0 : Math.min(100, pct);
        h += '<div class="bullet">' +
          '<div class="linha-topo"><span class="indicador">' + esc(m.indicador) + '</span>' +
          '<span class="valores">' + fmtValor(m.realizado) + ' de ' + fmtValor(m.meta) +
          (m.projecao != null ? ' · proj. ' + fmtValor(m.projecao) : '') + '</span></div>' +
          '<div style="display:flex;align-items:center">' +
          '<div class="trilho" style="flex:1" title="' + esc(m.indicador) + ': ' +
          (pct == null ? 'sem meta' : pct + '% da meta') + '">' +
          '<div class="preenchido" style="width:' + largura + '%"></div>' +
          (pctProj != null ? '<div class="marca-projecao" style="left:' + pctProj + '%"></div>' : '') +
          '</div><span class="pct">' + (pct == null ? '—' : pct + '%') + '</span></div></div>';
      });
    }
    h += '</div></div>';

    // coluna 2: histórico + equipe + situação
    h += '<div style="display:flex;flex-direction:column;gap:18px">';
    h += '<div class="cartao"><div class="cartao-corpo">' +
      '<h3 style="font-size:19px">PDG · histórico</h3>' +
      pdgTimelineHtml(d.pdgHistorico || []) + '</div></div>';
    h += '<div class="cartao"><div class="cartao-corpo">' +
      '<h3 style="font-size:19px">A agência em números</h3>' +
      '<div class="grade-tiles">' +
      '<button class="tile" disabled><span class="valor">' + fmtInt(r.funcis) +
      '</span><span class="rotulo">Funcis</span></button>' +
      '<button class="tile" disabled><span class="valor">' + fmtInt(r.carteiras) +
      '</span><span class="rotulo">Carteiras</span></button>' +
      '<button class="tile" disabled><span class="valor">' + fmtMeses(r.tempoMedioCargoMeses) +
      '</span><span class="rotulo">Tempo médio no cargo</span></button>' +
      '<button class="tile destaque" disabled><span class="valor">' + fmtInt(r.pdgGanhos) + '/' +
      fmtInt(r.pdgSemestres) + '</span><span class="rotulo">PDG ganho / disputado</span></button>' +
      '</div></div></div>';
    if (App.contexto.master) {
      var abertos = (d.pontos || []).filter(function (p) { return p.status !== 'RESOLVIDO'; });
      var ultima = (d.visitas || []).filter(function (v) { return v.status === 'REALIZADA'; })[0];
      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3 style="font-size:19px">Atendimento</h3>' +
        '<p style="margin:4px 0">' + (ultima
          ? '<span class="badge verde">✓ última visita ' + fmtData(ultima.dataRealizada) + '</span>'
          : '<span class="badge vinho">ainda não visitada</span>') + ' ' +
        '<span class="badge ' + (abertos.length ? 'vinho' : 'neutro') + '">' +
        abertos.length + ' ponto(s) aberto(s)</span></p>';
      abertos.slice(0, 4).forEach(function (p) {
        h += '<div class="nota" style="margin-top:8px">' + esc(p.descricao) +
          (p.previsao ? '<br><small>previsão ' + fmtData(p.previsao) + '</small>' : '') + '</div>';
      });
      h += '</div></div>';
    }
    h += '</div></div>';
    corpo.innerHTML = h;
  }

  function fecharDash() {
    $('#painel-dash').classList.remove('aberto');
    if (!$('#drawer-agencia').classList.contains('aberto')) $('#veu').classList.remove('aberto');
  }

  // ----------------------------------------------------------- planejamento

  function carregarPlanejamento() {
    var alvo = $('#grade-planejamento');
    alvo.innerHTML = '<div class="carregando">Carregando…</div>';
    api('planejamento').then(function (p) {
      $('#plan-resumo').textContent = p.naoVisitadas.length + ' a visitar · ' +
        p.planejadas.length + ' planejada(s) · ' + p.pontosEstourados.length +
        ' prazo(s) estourado(s)';
      var h = '';

      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3>Fila de visitas <span class="badge ouro">' + p.naoVisitadas.length + '</span></h3>' +
        '<div class="fila">';
      if (!p.naoVisitadas.length) h += '<div class="vazio">Tudo visitado. 🏆</div>';
      p.naoVisitadas.forEach(function (a) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(a.prefixo) + '">' +
          '<span class="selo-visita"></span>' +
          '<span><span class="nome">' + esc(a.nome) + '</span><small>' +
          esc(a.municipio || '') + '/' + esc(a.uf || '') + ' · ' + esc(a.regional || '') +
          '</small></span>' +
          (a.pontosAbertos ? '<span class="numeros">⚑ ' + a.pontosAbertos + '</span>' : '') +
          '</button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3>Próximas planejadas <span class="badge ouro">' + p.planejadas.length + '</span></h3>' +
        '<div class="fila">';
      if (!p.planejadas.length) h += '<div class="vazio">Nada no radar — planeje pela agência.</div>';
      p.planejadas.forEach(function (v) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(v.prefixo) + '">' +
          '<span class="selo-visita"></span>' +
          '<span><span class="nome">' + esc(v.nome) + '</span><small>' +
          esc(v.municipio || '') + '/' + esc(v.uf || '') + '</small></span>' +
          '<span class="numeros">' + fmtData(v.dataPlanejada) + '</span></button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3>Prazos estourados <span class="badge ' +
        (p.pontosEstourados.length ? 'vinho' : 'neutro') + '">' +
        p.pontosEstourados.length + '</span></h3><div class="fila">';
      if (!p.pontosEstourados.length) h += '<div class="vazio">Nenhum prazo vencido. ✓</div>';
      p.pontosEstourados.forEach(function (pt) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(pt.prefixo) + '">' +
          '<span><span class="nome">' + esc(pt.descricao) + '</span><small>' +
          esc(pt.nome) + ' · previsto para ' + fmtData(pt.previsao) + '</small></span></button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3>Fotos pendentes <span class="badge neutro">' + p.semFoto.length + '</span></h3>' +
        '<div class="fila">';
      if (!p.semFoto.length) h += '<div class="vazio">Todas as agências têm foto. 📸</div>';
      p.semFoto.forEach(function (a) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(a.prefixo) + '">' +
          '<span><span class="nome">' + esc(a.nome) + '</span><small>' + esc(a.uf || '') +
          '</small></span></button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3>Minhas anotações</h3>' +
        '<div style="display:flex;gap:8px;margin-bottom:10px">' +
        '<input type="text" id="plan-anotacao" style="flex:1" placeholder="anotar um lembrete geral…">' +
        '<button class="botao claro" id="plan-anotar" type="button">Anotar</button></div>' +
        '<div class="fila" id="plan-notas">';
      (p.anotacoesGerais || []).forEach(function (a) { h += notaHtml(a); });
      h += '</div></div></div>';

      alvo.innerHTML = h;
      $$('[data-prefixo]', alvo).forEach(function (b) {
        b.addEventListener('click', function () {
          abrirAgencia(b.getAttribute('data-prefixo'));
        });
      });
      $('#plan-anotar').addEventListener('click', function () {
        var t = $('#plan-anotacao').value.trim();
        if (!t) return;
        post('anotacao', { texto: t })
          .then(function () { toast('Anotado.'); carregarPlanejamento(); })
          .catch(function (e) { toast(e.message); });
      });
      alvo.addEventListener('click', function (ev) {
        var b = ev.target.closest('[data-acao]');
        if (!b) return;
        var nota = b.closest('.nota');
        var acao = b.getAttribute('data-acao');
        if (acao === 'fixar') {
          post('anotacao/' + nota.getAttribute('data-anotacao'),
            { fixada: nota.classList.contains('fixada') ? '0' : '1' })
            .then(carregarPlanejamento);
        } else if (acao === 'excluir-anotacao') {
          post('anotacao/' + nota.getAttribute('data-anotacao') + '/excluir', {})
            .then(carregarPlanejamento);
        }
      });
    }).catch(function (e) {
      alvo.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>';
    });
  }

  // ---------------------------------------------------------------- vistas

  function trocarVista() {
    var plan = location.hash === '#planejamento' && App.contexto && App.contexto.veTudo;
    $('#vista-mapa').classList.toggle('ativa', !plan);
    $('#vista-planejamento').classList.toggle('ativa', !!plan);
    $$('.nav-principal a').forEach(function (a) {
      var n = a.getAttribute('data-nav');
      a.classList.toggle('ativa', plan ? n === 'planejamento' : n === 'mapa');
    });
    if (plan) carregarPlanejamento();
  }

  // ------------------------------------------------------------------ boot

  function iniciar() {
    Promise.all([
      api('contexto'),
      api('mapa'),
      fetch(CTX + '/js/br-uf.min.geojson').then(function (r) { return r.json(); })
    ]).then(function (r) {
      App.contexto = r[0];
      App.mapa = r[1];
      App.geo = r[2];
      App.proj = criarProjecao(App.geo);
      desenharMapa();
      carregarPainel({});
      trocarVista();
      if (!App.mapa.agencias.length) {
        toast(App.contexto.master
          ? 'Nenhuma agência cadastrada — importe a planilha na tela Admin.'
          : 'Nenhuma agência na sua jurisdição ainda.');
      }
    }).catch(function (e) {
      $('#painel-regiao').innerHTML =
        '<div class="cartao"><div class="cartao-corpo"><div class="aviso">' +
        esc(e.message) + '</div></div></div>';
    });

    $('#fechar-agencia').addEventListener('click', fecharAgencia);
    $('#fechar-dash').addEventListener('click', fecharDash);
    $('#veu').addEventListener('click', function () { fecharDash(); fecharAgencia(); });
    document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Escape') return;
      if ($('#painel-dash').classList.contains('aberto')) fecharDash();
      else if ($('#drawer-agencia').classList.contains('aberto')) fecharAgencia();
      else if (App.sel.uf) voltarBrasil();
    });
    window.addEventListener('hashchange', trocarVista);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', iniciar);
  } else {
    iniciar();
  }
})();
