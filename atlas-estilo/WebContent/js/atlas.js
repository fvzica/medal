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

  function isoLocal(ms) { // epoch -> AAAA-MM-DD no fuso do usuário (p/ input date)
    var d = new Date(ms), m = d.getMonth() + 1, dia = d.getDate();
    return d.getFullYear() + '-' + (m < 10 ? '0' : '') + m + '-' + (dia < 10 ? '0' : '') + dia;
  }

  // ------------------------------------------- visões configuradas / Conexão

  var MESES_CURTOS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
  function compHumana(c) { // "2026-09" -> "set/2026"
    if (!c) return '';
    var p = c.split('-');
    return p.length === 2 && +p[1] >= 1 && +p[1] <= 12 ? MESES_CURTOS[+p[1] - 1] + '/' + p[0] : c;
  }

  function fmtVisao(v, valor) {
    if (valor == null) return '—';
    var casas = v.casas || 0;
    var op = { minimumFractionDigits: casas, maximumFractionDigits: casas };
    if (v.formato === 'MOEDA') return 'R$ ' + valor.toLocaleString('pt-BR', op);
    if (v.formato === 'PERCENTUAL') return valor.toLocaleString('pt-BR', { maximumFractionDigits: casas }) + '%';
    if (v.formato === 'DECIMAL') return valor.toLocaleString('pt-BR', op);
    return Math.round(valor).toLocaleString('pt-BR');
  }

  function tendHtml(delta, textoFmt, ref) {
    if (delta == null) return ref ? '<span class="tend">' + esc(ref) + '</span>' : '';
    return '<span class="tend ' + (delta >= 0 ? 'sobe' : 'desce') + '"><b>' +
      (delta >= 0 ? '▲ ' : '▼ ') + textoFmt + '</b>' + (ref ? ' vs ' + esc(ref) : '') + '</span>';
  }

  /** Card de uma visão configurada no admin (valor, meta, farol, tendência). */
  function tileVisao(v) {
    var titulo = (v.fonte || '') + (v.meta != null
      ? ' · meta ' + fmtVisao(v, v.meta) + (v.pct != null ? ' (' + Math.round(v.pct) + '%)' : '') : '') +
      (v.agenciasComDado != null ? ' · ' + v.agenciasComDado + ' agência(s) com dado' : '');
    var meta = v.meta != null
      ? '<span class="meta-pct"><i style="width:' + Math.min(100, Math.round(v.pct || 0)) + '%"></i></span>' : '';
    return '<button class="tile' + (v.status ? ' ' + v.status : '') + '" disabled title="' + esc(titulo) + '">' +
      '<span class="valor">' + fmtVisao(v, v.valor) + '</span>' +
      '<span class="rotulo">' + esc(v.titulo) + '</span>' +
      tendHtml(v.delta, fmtVisao(v, v.delta == null ? null : Math.abs(v.delta)),
        v.delta != null ? compHumana(v.competenciaAnterior) : compHumana(v.competencia)) + meta + '</button>';
  }

  function visoesHtml(visoes, rotulo) {
    if (!visoes || !visoes.length) return '';
    return '<p class="rotulo" style="margin:14px 0 2px">' + rotulo + '</p>' +
      '<div class="grade-tiles" style="margin-top:6px">' + visoes.map(tileVisao).join('') + '</div>';
  }

  /** Tile de Conexão média da seleção (abre a lista por agência). */
  function tileConexao(c) {
    if (!c) return '';
    return '<button class="tile destaque" type="button" data-lista="conexao" title="' +
      esc(c.agencias + ' agência(s) com Conexão em ' + compHumana(c.competencia)) + '">' +
      '<span class="valor">' + fmtInt(c.media) + '</span>' +
      '<span class="rotulo">Conexão média · ' + esc(compHumana(c.competencia)) + '</span>' +
      tendHtml(c.delta, fmtInt(c.delta == null ? 0 : Math.abs(c.delta)), c.delta != null ? 'mês anterior' : '') +
      '</button>';
  }

  function sparklineSvg(hist) {
    if (!hist || hist.length < 2) return '';
    var W = 220, H = 46, vals = hist.map(function (h) { return h.pontos; });
    var max = Math.max.apply(null, vals), min = Math.min.apply(null, vals);
    var faixa = Math.max(40, max - min);
    var pts = vals.map(function (v, i) {
      return [10 + i * (W - 20) / (vals.length - 1), H - 8 - (v - min) / faixa * (H - 18)];
    });
    var linha = pts.map(function (p, i) { return (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1); }).join('');
    var area = linha + 'L' + pts[pts.length - 1][0] + ' ' + (H - 4) + 'L' + pts[0][0] + ' ' + (H - 4) + 'Z';
    var fim = pts[pts.length - 1];
    return '<svg class="sparkline" viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="xMidYMid meet" aria-hidden="true">' +
      '<path class="area" d="' + area + '"/><path class="tracado" d="' + linha + '"/>' +
      '<circle class="ponto-fim" cx="' + fim[0] + '" cy="' + fim[1] + '" r="3.5"/>' +
      '<text x="10" y="' + H + '">' + esc(compHumana(hist[0].competencia)) + '</text>' +
      '<text x="' + (W - 10) + '" y="' + H + '" text-anchor="end">' + esc(compHumana(hist[hist.length - 1].competencia)) + '</text></svg>';
  }

  var FAIXA_NOME = { excelencia: 'Excelência', forte: 'Forte', atencao: 'Atenção', critico: 'Crítico' };

  /** Card de Conexão da agência: placar, faixa, histórico e carteiras. */
  function conexaoAgenciaHtml(k) {
    if (!k) return '';
    var h = '<div class="conexao-card">' +
      '<div class="placar">' + fmtInt(k.pontos) + '<small>CONEXÃO · DE 1000</small></div>' +
      '<div class="lado"><span class="faixa ' + esc(k.faixa) + '">' + (FAIXA_NOME[k.faixa] || k.faixa) + '</span> ' +
      (k.delta != null ? '<span class="delta ' + (k.delta >= 0 ? 'sobe' : 'desce') + '">' +
        (k.delta >= 0 ? '▲' : '▼') + Math.abs(k.delta) + ' vs mês anterior</span>' : '') +
      ' <span class="rotulo">' + esc(compHumana(k.competencia)) + '</span>' +
      sparklineSvg(k.historico) + '</div></div>';
    if ((k.carteiras || []).length) {
      h += '<table class="tabela"><thead><tr><th>Carteira</th><th>Gerente</th><th>Conexão</th></tr></thead><tbody>';
      k.carteiras.forEach(function (c) {
        h += '<tr><td><strong>' + esc(c.carteira) + '</strong></td><td>' +
          esc(c.gerenteNome || c.gerenteMatricula || (App.contexto.veTudo ? '—' : 'reservado')) + '</td>' +
          '<td><span class="conexao-barra"><span class="tr"><span class="ch" style="width:' +
          Math.min(100, Math.round(c.pontos / 10)) + '%"></span></span><span class="v">' + fmtInt(c.pontos) + '</span>' +
          (c.delta != null ? '<span class="delta ' + (c.delta >= 0 ? 'sobe' : 'desce') + '">' +
            (c.delta >= 0 ? '▲' : '▼') + Math.abs(c.delta) + '</span>' : '') + '</span></td></tr>';
      });
      h += '</tbody></table>';
    }
    return h;
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
            resumo.agencias + ' agência(s) · ' + resumo.funcis + ' funcis' +
            (App.contexto.master ? ' · ' + resumo.visitadas + ' visitada(s)' : '');
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

  /**
   * Agências praticamente no mesmo ponto (mesma cidade grande) viram uma
   * roseta: cada uma recebe um deslocamento em círculo para não se cobrirem.
   */
  function espalharPins(itens, raio) {
    var grupos = [];
    itens.forEach(function (it) {
      var g = grupos.filter(function (gr) {
        return Math.abs(gr.cx - it.p[0]) < raio * 1.2 && Math.abs(gr.cy - it.p[1]) < raio * 1.2;
      })[0];
      if (!g) { g = { cx: it.p[0], cy: it.p[1], itens: [] }; grupos.push(g); }
      g.itens.push(it);
    });
    grupos.forEach(function (gr) {
      if (gr.itens.length < 2) return;
      var n = gr.itens.length, r = raio * (n > 4 ? 1.6 : 1.15);
      gr.itens.forEach(function (it, i) {
        var ang = -Math.PI / 2 + (2 * Math.PI * i) / n;
        it.p = [gr.cx + r * Math.cos(ang), gr.cy + r * Math.sin(ang)];
        it.agrupado = true; // sem rótulo: o tooltip identifica cada pin da roseta
      });
    });
  }

  function desenharPins(uf, municipio) {
    var NS = 'http://www.w3.org/2000/svg';
    var g = $('#g-pins');
    g.innerHTML = '';
    var rotulados = []; // posições com rótulo, p/ suprimir colisões
    var escalaPin = Math.max(App.vb.w / 1000, .22);
    var itens = App.mapa.agencias.filter(function (a) {
      return a.uf === uf && a.lat != null && a.lng != null;
    }).map(function (a) { return { a: a, p: App.proj(a.lng, a.lat) }; });
    espalharPins(itens, 4.5 * escalaPin * 3);
    itens.forEach(function (it) {
      var a = it.a, p = it.p;
      var apagada = municipio && a.municipio !== municipio;
      var pin = document.createElementNS(NS, 'g');
      pin.setAttribute('class', 'pin' + (a.visitada ? ' visitada' : '') + (a.planejada ? ' planejada' : ''));
      pin.setAttribute('transform', 'translate(' + p[0] + ',' + p[1] + ')');
      if (apagada) pin.setAttribute('opacity', '.25');
      var escala = Math.max(App.vb.w / 1000, .22);
      // rótulo só quando não colide com outro já desenhado (o tooltip cobre o resto)
      var minDist = 34 * escala * 3;
      var cabeRotulo = !apagada && !it.agrupado && !rotulados.some(function (q) {
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
          '<br>' + a.funcis + ' funcis' +
          (App.contexto.master
            ? ' · ' + (a.visitada ? '✓ visitada' : 'ainda não visitada') +
              (a.planejada ? ' · 📅 visita planejada' : '') +
              (a.pontosAbertos ? ' · ' + a.pontosAbertos + ' ação(ões) em aberto' : '')
            : ''));
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
      ['agencias', 'carteiras', 'pdg', 'conexao'].indexOf(tipoLista) >= 0);
    return '<button class="tile' + (destaque ? ' destaque' : '') + '" type="button" ' +
      (podeAbrir ? 'data-lista="' + tipoLista + '"' : 'disabled') + '>' +
      '<span class="valor">' + valor + (sufixo || '') + '</span>' +
      '<span class="rotulo">' + rotulo + '</span></button>';
  }

  function renderPainel(f, resumo, agencias, municipios) {
    App.listaAberta = null; // o painel novo nasce sem lista de detalhe aberta
    var titulo = f.municipio ? f.municipio
      : f.uf ? (UF_NOMES[f.uf] || f.uf)
      : (App.contexto.regionalJurisdicao || 'Brasil');
    var h = '<div class="cartao"><div class="cartao-corpo">';
    h += '<div class="painel-titulo"><h2>' + esc(titulo) + '</h2>' +
      '<span class="rotulo">' + fmtInt(resumo.agencias) + ' agência(s)</span></div>';

    h += '<div class="grade-tiles">';
    h += tileConexao(resumo.conexao);
    h += tile('Funcis', fmtInt(resumo.funcis), 'funcis');
    h += tile('Gerentes', fmtInt(resumo.gerentes), 'gerentes');
    h += tile('Assistentes', fmtInt(resumo.assistentes), 'assistentes');
    h += tile('Carteiras', fmtInt(resumo.carteiras), 'carteiras');
    h += tile('Média de funcis por agência', resumo.mediaFuncisPorAgencia != null
      ? resumo.mediaFuncisPorAgencia.toLocaleString('pt-BR') : '—', null);
    h += tile('Tempo médio no cargo', fmtMeses(resumo.tempoMedioCargoMeses), null);
    h += tile('Tempo médio na função', fmtMeses(resumo.tempoMedioFuncaoMeses), null);
    h += tile('Semestres com PDG', fmtInt(resumo.pdgGanhos), 'pdg', !resumo.conexao);
    h += '</div>';
    h += visoesHtml(resumo.visoes, 'Visões configuradas no admin');

    if (App.contexto.master) {
      h += '<div class="chips so-master" style="margin-top:2px">' +
        '<span class="badge verde">✓ ' + fmtInt(resumo.visitadas) + ' visitada(s)</span>' +
        '<span class="badge ' + (resumo.pontosAbertos ? 'vinho' : 'neutro') + '">' +
        (resumo.pontosAbertos ? '⚑ ' : '') + fmtInt(resumo.pontosAbertos) +
        ' ação(ões) em aberto</span></div>';
    }

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
        (App.contexto.master
          ? '<span class="selo-visita" title="' + (a.visitada ? 'visitada' : 'não visitada') + '"></span>'
          : '<span class="selo-visita neutro" title="agência Estilo"></span>') +
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
    pdg: 'PDG por agência', conexao: 'Conexão por agência' };

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
        } else if (tipo === 'conexao') {
          h += '<table class="tabela"><thead><tr><th>Agência</th><th>Regional</th>' +
            '<th>Conexão · ' + esc(itens.length ? compHumana(itens[0].competencia) : '') + '</th></tr></thead><tbody>';
          itens.forEach(function (k) {
            h += '<tr><td><button class="botao mini claro" type="button" data-prefixo="' + esc(k.prefixo) + '">' +
              esc(k.agencia) + '</button></td><td>' + esc(k.regional || '') + '</td>' +
              '<td><span class="conexao-barra"><span class="tr"><span class="ch" style="width:' +
              Math.min(100, Math.round(k.pontos / 10)) + '%"></span></span><span class="v">' + fmtInt(k.pontos) +
              '</span> <span class="faixa ' + esc(k.faixa) + '">' + (FAIXA_NOME[k.faixa] || k.faixa) + '</span></span></td></tr>';
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
        $$('[data-prefixo]', zona).forEach(function (b) {
          b.addEventListener('click', function () { abrirAgencia(b.getAttribute('data-prefixo')); });
        });
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

  /**
   * Abre (ou recarrega) o drawer da agência. `estado` (opcional) devolve o
   * usuário à mesma aba e à mesma rolagem depois de salvar algo.
   */
  function abrirAgencia(prefixo, estado) {
    var drawer = $('#drawer-agencia');
    $('#veu').classList.add('aberto');
    drawer.classList.add('aberto');
    if (!estado) $('#ag-corpo').innerHTML = '<div class="carregando">Abrindo a agência…</div>';
    api('agencia/' + prefixo).then(function (d) {
      App.agencia = d;
      renderAgencia(d);
      if (estado) {
        if (estado.aba) ativarAba(estado.aba);
        drawer.scrollTop = estado.rolagem || 0;
      } else {
        drawer.scrollTop = 0;
      }
    }).catch(function (e) {
      $('#ag-corpo').innerHTML = '<div class="aviso">' + esc(e.message) + '</div>';
    });
  }

  function ativarAba(nome) {
    var botao = $('#ag-abas button[data-aba="' + nome + '"]');
    if (!botao) return;
    $$('#ag-abas button').forEach(function (x) { x.classList.toggle('ativa', x === botao); });
    $$('.aba-corpo', $('#ag-corpo')).forEach(function (c) {
      c.classList.toggle('ativa', c.getAttribute('data-corpo') === nome);
    });
  }

  /** Estado atual do drawer (aba ativa + rolagem) para restaurar após recarregar. */
  function estadoDrawer() {
    var ativa = $('#ag-abas button.ativa');
    return { aba: ativa ? ativa.getAttribute('data-aba') : null, rolagem: $('#drawer-agencia').scrollTop };
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
    // briefing "antes de ir" (só Master; vazio para os demais)
    h += briefingHtml(d);

    var abertas = (d.pontos || []).filter(function (p) { return p.status !== 'RESOLVIDO'; });
    var vencidas = abertas.filter(function (p) { return p.vencida; });
    h += '<div class="cartao"><div class="cartao-corpo">';
    h += '<div class="abas" id="ag-abas">';
    h += '<button data-aba="geral" class="ativa">Visão geral</button>';
    if (veTudo) {
      h += '<button data-aba="equipe">Equipe · ' + (d.equipe || []).length + '</button>';
      h += '<button data-aba="carteiras">Carteiras · ' + (d.carteiras || []).length + '</button>';
    }
    h += '<button data-aba="fotos">Fotos · ' + (d.fotos || []).length + '</button>';
    if (App.contexto.master) {
      h += '<button data-aba="visitas">Visitas · ' + realizadas(d).length + '</button>';
      h += '<button data-aba="acoes">Ações · ' + abertas.length +
        (vencidas.length ? ' <span class="badge vinho mini">' + vencidas.length + ' vencida(s)</span>' : '') +
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
    if (d.conexao) {
      h += '<p class="rotulo" style="margin:8px 0 0">Conexão</p>' + conexaoAgenciaHtml(d.conexao);
    }
    h += visoesHtml(d.visoes, 'Visões configuradas no admin');
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
      var restritas = d.fotos.filter(function (f) { return f.restrita; }).length;
      if (restritas) {
        h += '<p class="rotulo" style="margin:12px 0 0">' + restritas +
          ' foto(s) de visita · <span class="badge ouro mini">restritas aos Masters</span></p>';
      }
      h += '<div class="galeria" style="margin-top:14px">';
      d.fotos.forEach(function (f) {
        h += '<figure' + (f.restrita ? ' class="restrita" title="visível só para Masters"' : '') + '>' +
          '<img loading="lazy" src="' + CTX + '/foto/' + esc(f.id) +
          '" alt="" onclick="window.open(this.src)">' +
          '<figcaption>' + (f.restrita ? '🔒 ' : '') + esc(f.legenda || f.tipo) + '</figcaption></figure>';
      });
      h += '</div>';
    } else {
      h += '<div class="vazio">Nenhuma foto ainda' +
        (App.contexto.master ? ' — registre uma visita e anexe fotos, ou suba as institucionais na tela Admin.' : '.') + '</div>';
    }
    h += '</div>';

    // --- visitas (checklist, fotos, evolução, anotações) e ações (Master)
    if (App.contexto.master) {
      h += '<div class="aba-corpo" data-corpo="visitas">' + visitasHtml(d) + '</div>';
      h += '<div class="aba-corpo" data-corpo="acoes">' + acoesHtml(d) + '</div>';
    }

    h += '</div></div>';
    $('#ag-corpo').innerHTML = h;

    $('#botao-porta').addEventListener('click', function () { abrirDash(d); });
    $$('#ag-abas button').forEach(function (b) {
      b.addEventListener('click', function () { ativarAba(b.getAttribute('data-aba')); });
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

  // ------------------------------------------------ visitas (checklist)
  // Tudo desta seção é exclusivo do Master: a API só devolve visitas,
  // anotações, fotos de visita e ações para master(), e o front só renderiza
  // quando App.contexto.master.

  var CRITERIOS = [['ambiencia', 'Ambiência'], ['atendimento', 'Atendimento'],
    ['organizacao', 'Organização'], ['equipe', 'Equipe']];
  var MELHORIAS = ['Fachada e letreiro', 'Sala Estilo', 'Climatização', 'Fila no prioritário',
    'Sinalização interna', 'Autoatendimento', 'Carteiras desbalanceadas',
    'Treinamento do portfólio', 'Sala de reunião', 'Iluminação', 'Limpeza', 'Estacionamento'];
  var MOVIMENTO = { VAZIA: 'vazia', NORMAL: 'movimento normal', CHEIA: 'cheia' };
  var STATUS_ACAO = { ABERTO: 'aberta', EM_TRATATIVA: 'em tratativa', AGUARDANDO_VERIFICACAO: 'aguardando conferência', RESOLVIDO: 'concluída' };
  var TIPO_ATU = { RETORNO: ['retorno', 'neutro'], COBRANCA: ['cobrança', 'ouro'], VERIFICACAO: ['conferência', 'verde'], STATUS: [null, null] };

  /** Reduz a foto no navegador (lado maior 1600 px, JPEG 0,82); se não der, manda o original. */
  function reduzirImagem(file, maxLado) {
    maxLado = maxLado || 1600;
    return new Promise(function (resolve) {
      if (!file || !/^image\//.test(file.type) || /gif|svg/.test(file.type) || !window.HTMLCanvasElement) { resolve(file); return; }
      var url = URL.createObjectURL(file), img = new Image();
      var original = function () { URL.revokeObjectURL(url); resolve(file); };
      img.onload = function () {
        try {
          var w = img.naturalWidth, h = img.naturalHeight, k = Math.min(1, maxLado / Math.max(w, h));
          if (k >= 1 && file.size < 900 * 1024) { original(); return; }
          var cv = document.createElement('canvas');
          cv.width = Math.max(1, Math.round(w * k)); cv.height = Math.max(1, Math.round(h * k));
          cv.getContext('2d').drawImage(img, 0, 0, cv.width, cv.height);
          cv.toBlob(function (blob) {
            URL.revokeObjectURL(url);
            resolve(blob && blob.size < file.size ? blob : file);
          }, 'image/jpeg', 0.82);
        } catch (e) { original(); }
      };
      img.onerror = original;
      img.src = url;
    });
  }

  /** Envia fotos (reduzidas) para uma rota multipart; `extra` são campos de formulário. */
  function enviarFotos(rota, arquivos, extra) {
    var lista = [].slice.call(arquivos || []);
    if (!lista.length) return Promise.resolve({ ok: false, gravadas: 0 });
    return Promise.all(lista.map(function (f) { return reduzirImagem(f); })).then(function (blobs) {
      var fd = new FormData();
      Object.keys(extra || {}).forEach(function (k) { if (extra[k] != null) fd.append(k, extra[k]); });
      blobs.forEach(function (b, i) {
        var nome = (lista[i].name || 'foto').replace(/\.\w+$/, '') + (b.type === 'image/jpeg' ? '.jpg' : '');
        fd.append('arquivo', b, nome);
      });
      return api(rota, { method: 'POST', body: fd });
    });
  }

  /** Abre o seletor de arquivos e devolve os escolhidos (Promise). */
  function escolherFotos(inputId) {
    return new Promise(function (resolve) {
      var input = $('#' + inputId);
      input.onchange = function () { var fs = [].slice.call(this.files); this.value = ''; resolve(fs); };
      input.click();
    });
  }

  function realizadas(d) {
    return (d.visitas || []).filter(function (v) { return v.status === 'REALIZADA'; })
      .sort(function (a, b) { return (b.dataRealizada || 0) - (a.dataRealizada || 0); });
  }

  /** "Antes de ir": o que observar nesta agência, montado dos dados já carregados. */
  function briefingHtml(d) {
    if (!App.contexto.master) return '';
    var itens = [];
    var ult = realizadas(d)[0];
    var planejada = (d.visitas || []).filter(function (v) { return v.status === 'PLANEJADA'; })
      .sort(function (a, b) { return (a.dataPlanejada || 9e15) - (b.dataPlanejada || 9e15); })[0];
    var abertas = (d.pontos || []).filter(function (p) { return p.status !== 'RESOLVIDO'; });
    var vencidas = abertas.filter(function (p) { return p.vencida; });
    if (planejada) itens.push('Visita <b>planejada para ' + fmtData(planejada.dataPlanejada) + '</b>' +
      (planejada.dataPlanejada && planejada.dataPlanejada < Date.now() - 864e5 ? ' <span class="badge vinho">atrasada</span>' : ''));
    if (ult) {
      itens.push('Última visita em <b>' + fmtData(ult.dataRealizada) + '</b>' +
        (ult.notaGeral != null ? ' · nota <b>' + ult.notaGeral.toLocaleString('pt-BR') + '</b>' : '') +
        (ult.claros ? ' · <b>' + ult.claros + ' claro(s)</b> no quadro' : ''));
      if ((ult.melhorias || []).length) itens.push('Conferir o que estava pendente: <b>' + ult.melhorias.map(esc).join('</b>, <b>') + '</b>');
      if (ult.percepcao) itens.push('Você escreveu: <i>“' + esc(ult.percepcao.length > 160 ? ult.percepcao.slice(0, 160) + '…' : ult.percepcao) + '”</i>');
    } else {
      itens.push('<b>Primeira visita</b> — registre a impressão inicial e as fotos da fachada e da sala Estilo.');
    }
    if (vencidas.length) itens.push('<b>' + vencidas.length + ' ação(ões) com prazo vencido</b> — cobrar retorno: ' +
      vencidas.slice(0, 3).map(function (p) { return esc(p.descricao); }).join('; ') + (vencidas.length > 3 ? '…' : ''));
    else if (abertas.length) itens.push('<b>' + abertas.length + ' ação(ões) em aberto</b> para acompanhar na conversa.');
    if (d.conexao) {
      if (d.conexao.delta != null && d.conexao.delta < 0) itens.push('Conexão <b>caiu ' + Math.abs(d.conexao.delta) + ' pontos</b> no mês (' + fmtInt(d.conexao.pontos) + ') — perguntar o que mudou.');
      else if (d.conexao.faixa === 'critico' || d.conexao.faixa === 'atencao') itens.push('Conexão em <b>' + fmtInt(d.conexao.pontos) + '</b> (' + FAIXA_NOME[d.conexao.faixa].toLowerCase() + ').');
      var piores = (d.conexao.carteiras || []).filter(function (c) { return c.faixa === 'critico'; });
      if (piores.length) itens.push('Carteira(s) crítica(s): <b>' + piores.map(function (c) { return esc(c.gerenteNome || c.carteira); }).join('</b>, <b>') + '</b>');
    }
    var metas = d.metas || [];
    if (metas.length) {
      var periodo = metas[0].periodo;
      var abaixo = metas.filter(function (m) { return m.periodo === periodo && m.meta && (m.realizado || 0) / m.meta < .7; });
      if (abaixo.length) itens.push('Abaixo de 70% da meta: <b>' + abaixo.map(function (m) { return esc(m.indicador); }).join('</b>, <b>') + '</b>');
    }
    if (itens.length <= 1 && !vencidas.length && !abertas.length) itens.push('<span class="ok">✓ Nada pendente — visita de relacionamento.</span>');
    return '<div class="cartao briefing"><div class="cartao-corpo">' +
      '<h3><span class="pulso"></span>Antes de ir · briefing automático</h3>' +
      '<ul>' + itens.map(function (i) { return '<li>' + i + '</li>'; }).join('') + '</ul>' +
      '</div></div>';
  }

  function visitasHtml(d) {
    var reals = realizadas(d);
    var h = '<div class="painel-titulo" style="margin-top:14px"><h3 style="margin:0">Registrar visita</h3>' +
      '<span class="rotulo" id="fv-modo">nova visita</span></div>';
    h += checklistFormHtml(null);

    h += '<div class="painel-titulo" style="margin-top:22px"><h3 style="margin:0">Histórico · ' + reals.length +
      ' visita(s) realizada(s)</h3><a class="botao mini claro" href="' + CTX + '/api/export/visitas">Exportar CSV</a></div>';
    if (reals.length > 1) h += evolucaoHtml(reals);
    (d.visitas || []).forEach(function (v, i) {
      var anterior = v.status === 'REALIZADA' ? reals[reals.indexOf(v) + 1] : null;
      var acoesDaVisita = (d.pontos || []).filter(function (p) { return p.visitaId === v.id; });
      h += visitaCardHtml(v, anterior, acoesDaVisita);
    });
    if (!(d.visitas || []).length) h += '<div class="vazio">Nenhuma visita registrada ainda.</div>';

    h += '<p class="rotulo" style="margin:20px 0 6px">Anotações da agência</p>';
    h += '<div style="display:flex;gap:8px"><input type="text" id="a-texto" style="flex:1" ' +
      'class="campo-input" placeholder="anotar algo sobre esta agência…">' +
      '<button class="botao claro" id="a-salvar" type="button">Anotar</button></div>';
    h += '<div id="a-lista" style="margin-top:10px;display:flex;flex-direction:column;gap:8px">';
    (d.anotacoes || []).forEach(function (a) { h += notaHtml(a); });
    h += '</div>';
    return h;
  }

  /** Formulário do checklist (vazio = nova visita; v = edição). */
  function checklistFormHtml(v) {
    v = v || {};
    var h = '<div class="cartao" id="fv-form" data-id="' + (v.id || '') + '"><div class="cartao-corpo">';
    h += '<div class="linha-campos">' +
      '<div class="campo"><label>Situação</label><select id="fv-status">' +
      '<option value="REALIZADA"' + (v.status !== 'PLANEJADA' ? ' selected' : '') + '>Realizada (preencher checklist)</option>' +
      '<option value="PLANEJADA"' + (v.status === 'PLANEJADA' ? ' selected' : '') + '>Só agendar</option></select></div>' +
      '<div class="campo"><label>Data</label><input type="date" id="fv-data" value="' +
      isoLocal(v.dataRealizada || v.dataPlanejada || Date.now()) + '"></div>' +
      '<div class="campo" style="flex:2"><label>Resumo da visita</label><input type="text" id="fv-resumo" value="' + esc(v.resumo || '') +
      '" placeholder="como encontrei a agência e o que combinei"></div></div>';

    h += '<div id="fv-checklist"' + (v.status === 'PLANEJADA' ? ' hidden' : '') + '>';
    h += '<div class="checklist">';
    CRITERIOS.forEach(function (c) {
      var val = v[c[0]] || 0;
      h += '<div class="criterio"><div class="cab"><span class="nome">' + c[1] + '</span>' +
        '<span class="lido" id="fv-' + c[0] + '-lido">' + (val ? val + ' de 5' : 'toque para avaliar') + '</span></div>' +
        '<div class="pontos-toque" data-criterio="' + c[0] + '">';
      for (var i = 1; i <= 5; i++) {
        h += '<button type="button" data-v="' + i + '" class="' + (val === i ? 'sel' : val > i ? 'marcado' : '') + '">' + i + '</button>';
      }
      h += '</div></div>';
    });
    h += '</div>';
    h += '<div class="linha-campos">' +
      '<div class="campo"><label>Movimento</label><div class="segmentado" id="fv-movimento">' +
      ['VAZIA', 'NORMAL', 'CHEIA'].map(function (m) {
        return '<button type="button" data-v="' + m + '" class="' + ((v.movimento || 'NORMAL') === m ? 'ativo' : '') + '">' +
          m.charAt(0) + m.slice(1).toLowerCase() + '</button>';
      }).join('') + '</div></div>' +
      '<div class="campo"><label>Claros no quadro</label><div class="stepper">' +
      '<button type="button" id="fv-claros-menos">−</button><span class="valor" id="fv-claros">' + (v.claros || 0) + '</span>' +
      '<button type="button" id="fv-claros-mais">+</button></div></div>' +
      '<div class="campo" style="flex:2"><label>Nota geral · 0 a 10</label><div class="nota-geral">' +
      '<span class="numzona" id="fv-nota-num">' + (v.notaGeral != null ? v.notaGeral.toLocaleString('pt-BR') : '7') + '</span>' +
      '<input type="range" id="fv-nota" min="0" max="10" step="0.5" value="' + (v.notaGeral != null ? v.notaGeral : 7) + '"></div></div></div>';
    // chips padrão + as melhorias livres já gravadas nesta visita (para poder desmarcar)
    var chipsMelhorias = MELHORIAS.concat((v.melhorias || []).filter(function (m) { return MELHORIAS.indexOf(m) < 0; }));
    h += '<div class="campo"><label>O que precisa melhorar · toque para marcar</label><div class="chips" id="fv-melhorias" style="margin:0">' +
      chipsMelhorias.map(function (m) {
        return '<button type="button" class="chip' + ((v.melhorias || []).indexOf(m) >= 0 ? ' ativo' : '') + '" data-m="' + esc(m) + '">' + esc(m) + '</button>';
      }).join('') + '<input type="text" id="fv-melhoria-outra" class="campo-input" placeholder="outro ponto… (Enter)" style="min-height:30px;padding:4px 10px;font-size:12.5px"></div></div>';
    h += '<div class="campo"><label>Minha percepção</label><textarea id="fv-percepcao" placeholder="o que vi, o que combinei, o que observar na próxima visita…">' + esc(v.percepcao || '') + '</textarea></div>';
    // conferir in loco o que o responsável disse ter feito (ações aguardando verificação desta agência)
    var aConferir = ((App.agencia && App.agencia.pontos) || []).filter(function (p) { return p.status === 'AGUARDANDO_VERIFICACAO'; });
    if (aConferir.length) {
      h += '<div class="campo"><label>Conferir · o responsável disse que fez; o que você viu?</label><div class="conferir" id="fv-conferir">' +
        aConferir.map(function (p) {
          return '<div class="item" data-conferir="' + p.id + '"><div><div class="desc">' + esc(p.descricao) + '</div><small>' +
            (p.responsavel ? esc(p.responsavel) + ' · ' : '') + 'informou em ' + fmtData(p.informadoEm) +
            (p.fotosDepois ? ' · 📷 ' + p.fotosDepois + ' foto(s) do depois' : '') + '</small></div>' +
            '<div class="tri"><button type="button" data-r="CONFIRMADO">confirmei</button>' +
            '<button type="button" data-r="NAO_FEITO">não estava feito</button>' +
            '<button type="button" data-r="" class="sel">não conferi</button></div></div>';
        }).join('') + '</div></div>';
    }
    h += '<div class="campo"><label>Ações para dar retorno · caem na aba Ações com prazo e responsável</label>' +
      '<div class="linha-campos" style="gap:8px"><input type="text" id="fv-acao" class="campo-input" style="flex:2;min-width:180px" placeholder="ex.: cobrar engenharia sobre a fachada">' +
      '<input type="text" id="fv-acao-resp" class="campo-input" style="flex:1;min-width:120px" placeholder="responsável">' +
      '<input type="date" id="fv-acao-prazo" class="campo-input" title="prazo">' +
      '<select id="fv-acao-prio" class="campo-input"><option value="MEDIA">Média</option><option value="ALTA">Alta</option><option value="BAIXA">Baixa</option></select>' +
      '<button class="botao claro" id="fv-acao-add" type="button">+ incluir</button></div>' +
      '<div id="fv-acoes" style="display:flex;flex-direction:column;gap:6px;margin-top:6px"></div></div>';
    h += '<div class="campo"><label>Fotos da visita · só Masters veem</label><div class="fotos-grade" id="fv-fotos">' +
      '<button class="foto-add" id="fv-foto-add" type="button" title="Adicionar fotos">+</button></div></div>';
    h += '</div>'; // fv-checklist
    h += '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">' +
      '<button class="botao primario" id="fv-salvar" type="button">' + (v.id ? 'Atualizar visita' : 'Salvar visita') + '</button>' +
      (v.id ? '<button class="botao claro" id="fv-cancelar" type="button">Cancelar edição</button>' : '') + '</div>';
    h += '</div></div>';
    return h;
  }

  function evolucaoHtml(reals) {
    var crono = reals.slice().reverse().filter(function (v) { return v.notaGeral != null; });
    if (crono.length < 2) return '';
    var h = '<div class="cartao" style="margin-top:8px"><div class="cartao-corpo">' +
      '<div class="painel-titulo"><h3 style="margin:0">Evolução entre visitas</h3>' +
      '<span class="rotulo">nota geral · as ações estão funcionando?</span></div><div class="evolucao">';
    crono.forEach(function (v, i) {
      h += '<div class="barra' + (i === crono.length - 1 ? ' ultima' : '') + '" title="' + fmtData(v.dataRealizada) + ' · nota ' + v.notaGeral + '">' +
        '<i style="height:' + Math.max(6, v.notaGeral * 4.6) + 'px"></i><small>' + v.notaGeral.toLocaleString('pt-BR') + '</small></div>';
    });
    var delta = crono[crono.length - 1].notaGeral - crono[0].notaGeral;
    h += '</div><span class="badge ' + (delta >= 0 ? 'verde' : 'vinho') + '">' + (delta >= 0 ? '▲' : '▼') + ' ' +
      Math.abs(delta).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' desde a primeira visita</span></div></div>';
    return h;
  }

  function deltaHtml(atual, anterior) {
    if (atual == null || anterior == null || atual === anterior) return '';
    var d = atual - anterior;
    return '<span class="delta ' + (d > 0 ? 'sobe' : 'desce') + '">' + (d > 0 ? '▲' : '▼') + Math.abs(d).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + '</span>';
  }

  function visitaCardHtml(v, anterior, acoesDaVisita) {
    var realizada = v.status === 'REALIZADA';
    var badge = realizada ? '<span class="badge verde">realizada</span>'
      : v.status === 'PLANEJADA' ? '<span class="badge info">planejada</span>' : '<span class="badge neutro">cancelada</span>';
    var h = '<div class="visita-card" data-visita="' + v.id + '">';
    h += '<div class="cab">' + badge + '<span class="quando">' + fmtData(realizada ? v.dataRealizada : v.dataPlanejada) + '</span>' +
      (v.notaGeral != null ? '<span class="badge ' + (v.notaGeral >= 7.5 ? 'verde' : v.notaGeral >= 6 ? 'ambar' : 'vinho') + '">nota ' + v.notaGeral.toLocaleString('pt-BR') + '</span>' : '') +
      (v.movimento ? '<span class="badge neutro">' + MOVIMENTO[v.movimento] + '</span>' : '') +
      (v.claros ? '<span class="badge ambar">' + v.claros + ' claro(s)</span>' : '') +
      '<span class="acoes-nota">' +
      (v.status === 'PLANEJADA' ? '<button class="botao mini primario" data-acao="realizar">registrar agora</button>' : '') +
      '<button class="botao mini claro" data-acao="editar-visita">editar</button>' +
      '<button class="botao mini perigo" data-acao="excluir-visita">excluir</button></span></div>';
    if (v.resumo) h += '<p class="percepcao" style="margin:8px 0 0"><b>' + esc(v.resumo) + '</b></p>';
    if (realizada && (v.ambiencia || v.atendimento || v.organizacao || v.equipe)) {
      h += '<div class="notas">' + CRITERIOS.map(function (c) {
        return '<div class="n"><small>' + c[1] + '</small><b>' + (v[c[0]] != null ? v[c[0]] + '<span style="font-size:11px;color:var(--texto-3)">/5</span>' : '—') +
          (anterior ? deltaHtml(v[c[0]], anterior[c[0]]) : '') + '</b></div>';
      }).join('') + '</div>';
    }
    if ((v.melhorias || []).length) h += '<div class="chips" style="margin:6px 0">' + v.melhorias.map(function (m) { return '<span class="badge vinho">' + esc(m) + '</span>'; }).join('') + '</div>';
    if (v.percepcao) h += '<p class="percepcao">“' + esc(v.percepcao) + '”</p>';
    if (acoesDaVisita.length) {
      h += '<div class="rotulo" style="margin-top:8px">Ações desta visita</div>';
      acoesDaVisita.forEach(function (p) { h += acaoCardHtml(p, { compacta: true }); });
    }
    if (realizada) {
      h += '<div class="rotulo" style="margin-top:10px">Fotos · restritas aos Masters</div><div class="fotos-grade">' +
        (v.fotos || []).map(function (f) {
          return '<span class="foto-mini"><img loading="lazy" src="' + CTX + '/foto/' + esc(f.id) + '" alt="" onclick="window.open(this.src)"></span>';
        }).join('') + '<button class="foto-add" data-acao="foto-visita" type="button" title="Adicionar fotos">+</button></div>';
    }
    return h + '</div>';
  }

  // ---------------------------------------------------------------- ações

  function acoesHtml(d) {
    var pend = (d.pontos || []).filter(function (p) { return p.status !== 'RESOLVIDO'; });
    var feitas = (d.pontos || []).filter(function (p) { return p.status === 'RESOLVIDO'; });
    var h = '<div class="cartao" style="margin-top:14px"><div class="cartao-corpo"><h3>Nova ação</h3>' +
      '<div class="linha-campos" style="gap:8px">' +
      '<input type="text" id="p-desc" class="campo-input" style="flex:2;min-width:200px" placeholder="o que precisa ser feito">' +
      '<input type="text" id="p-resp" class="campo-input" style="flex:1;min-width:130px" placeholder="responsável">' +
      '<input type="date" id="p-prev" class="campo-input" title="prazo">' +
      '<select id="p-prio" class="campo-input"><option value="MEDIA">Média</option><option value="ALTA">Alta</option><option value="BAIXA">Baixa</option></select>' +
      '<button class="botao primario" id="p-salvar" type="button">Criar</button></div></div></div>';
    h += '<div class="painel-titulo" style="margin-top:16px"><h3 style="margin:0">Pendentes <span class="badge ' + (pend.length ? 'ambar' : 'verde') + '">' + pend.length + '</span></h3>' +
      '<a class="botao mini claro" href="' + CTX + '/api/export/acoes">Exportar CSV</a></div>';
    if (!pend.length) h += '<div class="vazio">Nada pendente nesta agência. ✓</div>';
    pend.forEach(function (p) { h += acaoCardHtml(p, {}); });
    if (feitas.length) {
      h += '<div class="painel-titulo" style="margin-top:16px"><h3 style="margin:0">Concluídas <span class="badge verde">' + feitas.length + '</span></h3></div>';
      feitas.forEach(function (p) { h += acaoCardHtml(p, { compacta: true }); });
    }
    return h;
  }

  function prazoBadge(p) {
    if (p.status === 'RESOLVIDO') return p.resolvidoEm ? '<span class="badge verde">concluída em ' + fmtData(p.resolvidoEm) + '</span>' : '';
    if (p.status === 'AGUARDANDO_VERIFICACAO') return '<span class="badge info">informou que fez' + (p.informadoEm ? ' em ' + fmtData(p.informadoEm) : '') + '</span>';
    if (!p.previsao) return '<span class="badge neutro">sem prazo</span>';
    if (p.vencida) return '<span class="badge vinho">venceu em ' + fmtData(p.previsao) + '</span>';
    if (p.diasParaPrazo != null && p.diasParaPrazo <= 7) return '<span class="badge ambar">vence em ' + Math.max(0, Math.round(p.diasParaPrazo)) + ' dia(s)</span>';
    return '<span class="badge neutro">até ' + fmtData(p.previsao) + '</span>';
  }

  /** Card de ação (na agência, na vista Ações e no planejamento). */
  function fotosMiniHtml(p, momento) {
    var fs = (p.fotos || []).filter(function (f) { return f.momento === momento; });
    return fs.length
      ? fs.map(function (f) { return '<img loading="lazy" src="' + CTX + '/foto/' + esc(f.id) + '" alt="" onclick="window.open(this.src)">'; }).join('')
      : '<span class="vazia" title="sem foto">📷</span>';
  }

  function acaoCardHtml(p, opts) {
    opts = opts || {};
    var aberta = p.status !== 'RESOLVIDO';
    var aguardando = p.status === 'AGUARDANDO_VERIFICACAO';
    var cad = (App.contexto && App.contexto.cadencia) || {};
    var h = '<div class="acao-card' + (p.vencida ? ' vencida' : '') + (aberta ? '' : ' resolvida') +
      (aguardando ? ' aguardando' : '') + (p.parada ? ' parada' : '') + (p.cobrarHoje ? ' cobrar' : '') +
      (opts.compacta ? ' compacta' : '') + '" data-ponto="' + p.id + '" data-prefixo-acao="' + esc(p.prefixo || '') + '">';
    h += '<span class="prio ' + esc(p.prioridade || 'MEDIA') + '" title="prioridade ' + esc((p.prioridade || 'MEDIA').toLowerCase()) + '"></span>';
    h += '<div><div class="desc">' + esc(p.descricao) + '</div><div class="meta">' +
      (opts.comAgencia ? '<button class="botao mini claro" data-prefixo="' + esc(p.prefixo) + '">' + esc(p.agencia || p.prefixo) + '</button>' : '') +
      '<span class="badge ' + (p.status === 'RESOLVIDO' ? 'verde' : aguardando ? 'info' : p.status === 'EM_TRATATIVA' ? 'info' : 'ambar') + '">' + STATUS_ACAO[p.status] + '</span>' +
      prazoBadge(p) +
      (p.responsavel ? '<span>👤 ' + esc(p.responsavel) + '</span>' : '') +
      (aberta && !aguardando && p.semRetornoDias != null && p.semRetornoDias >= 1
        ? '<span class="' + (p.parada ? 'ambar' : '') + '">· sem retorno há ' + p.semRetornoDias + ' dia(s)' + (p.parada ? ' · parada' : '') + '</span>' : '') +
      (p.cobradaHaDias != null && aberta ? '<span>· cobrada ' + (p.cobradaHaDias === 0 ? 'hoje' : 'há ' + p.cobradaHaDias + ' dia(s)') + (p.cobrancas > 1 ? ' (' + p.cobrancas + '×)' : '') + '</span>' : '') +
      (p.reaberturas ? '<span class="badge vinho">reaberta ' + p.reaberturas + '×</span>' : '') +
      (!aberta ? (p.comprovada ? '<span class="badge verde">comprovada' + (p.verificadoEm ? ' na visita' : ' com foto') + '</span>' : '<span class="badge neutro">sem prova</span>') : '') +
      (opts.compacta && (p.fotosAntes || p.fotosDepois) ? '<span>· 📷 antes ' + (p.fotosAntes || 0) + ' · depois ' + (p.fotosDepois || 0) + '</span>' : '') +
      (p.visitaId ? '<span>· origem: visita</span>' : '') +
      (p.atualizacoes ? '<span>· ' + p.atualizacoes + ' registro(s)</span>' : '') +
      (p.solucao ? '<span>· solução: ' + esc(p.solucao) + '</span>' : '') + '</div></div>';
    h += '<span class="acoes-nota">' +
      (aguardando
        ? '<button class="botao mini primario" data-acao="confirmar" title="vi na agência: está feito">confirmei</button>' +
          '<button class="botao mini perigo" data-acao="nao-feito" title="vi na agência: não estava feito">não estava feito</button>'
        : aberta
          ? (p.status === 'ABERTO' ? '<button class="botao mini claro" data-acao="tratar">em tratativa</button>' : '') +
            '<button class="botao mini claro" data-acao="informou" title="o responsável avisou que fez; conferir na próxima visita">informou que fez</button>' +
            '<button class="botao mini primario" data-acao="resolver">concluir</button>'
          : '<button class="botao mini claro" data-acao="reabrir">reabrir</button>') +
      (p.atualizacoes ? '<button class="botao mini claro" data-acao="historico">histórico</button>' : '') +
      '<button class="botao mini perigo" data-acao="excluir-ponto">excluir</button></span>';
    if (!opts.compacta) {
      h += '<div class="fotos-par">' +
        '<div class="lado"><div class="rotulo">Antes' + (aberta ? '<button class="botao mini claro" data-acao="foto-antes" type="button">+ foto</button>' : '') + '</div><div class="miniaturas">' + fotosMiniHtml(p, 'ANTES') + '</div></div>' +
        '<div class="lado depois"><div class="rotulo">Depois<button class="botao mini claro" data-acao="foto-depois" type="button">+ foto</button></div><div class="miniaturas">' + fotosMiniHtml(p, 'DEPOIS') + '</div></div></div>';
    }
    if (aberta && !aguardando && !opts.compacta) {
      var dias = p.cadenciaDias || cad[p.prioridade] || 15, opcoes = [3, 7, 15, 30];
      if (opcoes.indexOf(dias) < 0) { opcoes.push(dias); opcoes.sort(function (a, b) { return a - b; }); }
      h += '<div class="retorno"><input type="text" placeholder="retorno do responsável ou o que você cobrou…" data-campo="retorno">' +
        '<select data-campo="status-novo"><option value="">manter status</option><option value="EM_TRATATIVA">→ em tratativa</option>' +
        '<option value="AGUARDANDO_VERIFICACAO">→ informou que fez</option><option value="RESOLVIDO">→ concluída</option></select>' +
        '<button class="botao mini claro" data-acao="retorno-enviar" type="button" title="registrar retorno do responsável">retorno</button>' +
        '<span class="cobranca"><select data-campo="adiar" title="quando cobrar de novo">' +
        opcoes.map(function (d) { return '<option value="' + d + '"' + (d === dias ? ' selected' : '') + '>cobrar de novo em ' + d + ' dias</option>'; }).join('') +
        '</select><button class="botao mini primario" data-acao="cobrei" type="button" title="registra a cobrança sem mexer no prazo">cobrei</button></span></div>';
    }
    h += '<div class="mini-form" data-mini hidden></div>';
    h += '<div class="timeline" data-timeline hidden></div>';
    return h + '</div>';
  }

  // ------------------------------------------------------ estado do form

  var formVisita = { criterios: {}, melhorias: [], fotos: [], acoes: [], editando: null };

  function ligarGestao(d) {
    var prefixo = d.agencia.prefixo;
    var raiz = $('#ag-corpo');

    var salvarGmaps = $('#salvar-gmaps', raiz);
    if (salvarGmaps) salvarGmaps.addEventListener('click', function () {
      post('agencia/' + prefixo + '/gmaps', { url: $('#ag-gmaps').value.trim() })
        .then(function () { toast('Link do Maps salvo.'); abrirAgencia(prefixo); })
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
      if (!desc) { toast('Descreva a ação.'); return; }
      post('ponto', { prefixo: prefixo, descricao: desc, previsao: dataParaEpoch($('#p-prev').value),
        responsavel: $('#p-resp').value.trim(), prioridade: $('#p-prio').value })
        .then(function () { toast('Ação criada.'); recarregar(prefixo); atualizarContadorAcoes(); })
        .catch(function (e) { toast(e.message); });
    });

    ligarFormVisita(d, null);
  }

  function ligarFormVisita(d, v) {
    var prefixo = d.agencia.prefixo;
    var form = $('#fv-form');
    if (!form) return;
    formVisita = { criterios: {}, melhorias: (v && v.melhorias ? v.melhorias.slice() : []), fotos: [], acoes: [], conferencias: {}, editando: v ? v.id : null };
    CRITERIOS.forEach(function (c) { formVisita.criterios[c[0]] = v && v[c[0]] ? v[c[0]] : 0; });
    $$('#fv-conferir .item', form).forEach(function (item) {
      var pid = item.getAttribute('data-conferir');
      $$('.tri button', item).forEach(function (b) {
        b.addEventListener('click', function () {
          $$('.tri button', item).forEach(function (x) { x.classList.toggle('sel', x === b); });
          var r = b.getAttribute('data-r');
          if (r) formVisita.conferencias[pid] = r; else delete formVisita.conferencias[pid];
        });
      });
    });
    $('#fv-modo').textContent = v ? 'editando a visita de ' + fmtData(v.dataRealizada || v.dataPlanejada) : 'nova visita';

    $('#fv-status', form).addEventListener('change', function () {
      $('#fv-checklist', form).hidden = this.value === 'PLANEJADA';
    });
    $$('.pontos-toque', form).forEach(function (grupo) {
      var crit = grupo.getAttribute('data-criterio');
      $$('button', grupo).forEach(function (b) {
        b.addEventListener('click', function () {
          var val = +b.getAttribute('data-v');
          formVisita.criterios[crit] = val;
          $$('button', grupo).forEach(function (x) {
            var xv = +x.getAttribute('data-v');
            x.className = xv === val ? 'sel' : xv < val ? 'marcado' : '';
          });
          $('#fv-' + crit + '-lido', form).textContent = val + ' de 5';
        });
      });
    });
    $$('#fv-movimento button', form).forEach(function (b) {
      b.addEventListener('click', function () {
        $$('#fv-movimento button', form).forEach(function (x) { x.classList.remove('ativo'); });
        b.classList.add('ativo');
      });
    });
    function claros() { return +$('#fv-claros', form).textContent; }
    $('#fv-claros-menos', form).addEventListener('click', function () { $('#fv-claros', form).textContent = Math.max(0, claros() - 1); });
    $('#fv-claros-mais', form).addEventListener('click', function () { $('#fv-claros', form).textContent = Math.min(99, claros() + 1); });
    $('#fv-nota', form).addEventListener('input', function () { $('#fv-nota-num', form).textContent = (+this.value).toLocaleString('pt-BR'); });
    $$('#fv-melhorias .chip', form).forEach(function (b) {
      b.addEventListener('click', function () {
        var m = b.getAttribute('data-m'), i = formVisita.melhorias.indexOf(m);
        if (i >= 0) formVisita.melhorias.splice(i, 1); else formVisita.melhorias.push(m);
        b.classList.toggle('ativo');
      });
    });
    $('#fv-melhoria-outra', form).addEventListener('keydown', function (ev) {
      if (ev.key !== 'Enter') return;
      ev.preventDefault();
      var m = this.value.trim();
      if (!m) return;
      if (formVisita.melhorias.indexOf(m) < 0) formVisita.melhorias.push(m);
      var chip = document.createElement('button');
      chip.type = 'button'; chip.className = 'chip ativo'; chip.setAttribute('data-m', m); chip.textContent = m;
      chip.addEventListener('click', function () {
        var i = formVisita.melhorias.indexOf(m);
        if (i >= 0) formVisita.melhorias.splice(i, 1); else formVisita.melhorias.push(m);
        chip.classList.toggle('ativo');
      });
      this.parentNode.insertBefore(chip, this);
      this.value = '';
    });
    function renderAcoesForm() {
      $('#fv-acoes', form).innerHTML = formVisita.acoes.map(function (a, i) {
        return '<div class="nota" style="padding:6px 10px;font-size:12.5px"><span class="acoes-nota">' +
          '<button class="botao mini perigo" type="button" data-fv-acao-tirar="' + i + '">×</button></span>⚑ ' + esc(a.texto) +
          (a.responsavel ? ' · ' + esc(a.responsavel) : '') + (a.prazo ? ' · até ' + fmtData(a.prazo) : '') +
          ' <span class="badge ' + (a.prioridade === 'ALTA' ? 'vinho' : a.prioridade === 'BAIXA' ? 'info' : 'ambar') + '">' + a.prioridade.toLowerCase() + '</span></div>';
      }).join('');
      $$('[data-fv-acao-tirar]', form).forEach(function (b) {
        b.addEventListener('click', function () { formVisita.acoes.splice(+b.getAttribute('data-fv-acao-tirar'), 1); renderAcoesForm(); });
      });
    }
    $('#fv-acao-add', form).addEventListener('click', function () {
      var tx = $('#fv-acao', form).value.trim();
      if (!tx) { toast('Descreva a ação.'); return; }
      formVisita.acoes.push({ texto: tx, responsavel: $('#fv-acao-resp', form).value.trim(),
        prazo: dataParaEpoch($('#fv-acao-prazo', form).value), prioridade: $('#fv-acao-prio', form).value });
      $('#fv-acao', form).value = ''; $('#fv-acao-resp', form).value = ''; $('#fv-acao-prazo', form).value = '';
      renderAcoesForm();
    });
    function renderFotosForm() {
      var grade = $('#fv-fotos', form);
      $$('.foto-mini', grade).forEach(function (x) { x.remove(); });
      formVisita.fotos.forEach(function (f, i) {
        var mini = document.createElement('span');
        mini.className = 'foto-mini';
        mini.innerHTML = '<img alt=""><button class="botao mini perigo" type="button" style="position:absolute;top:4px;right:4px;padding:2px 6px" data-fv-foto-tirar="' + i + '">×</button>';
        mini.querySelector('img').src = URL.createObjectURL(f);
        mini.querySelector('button').addEventListener('click', function () { formVisita.fotos.splice(i, 1); renderFotosForm(); });
        grade.insertBefore(mini, $('#fv-foto-add', grade));
      });
    }
    $('#fv-foto-add', form).addEventListener('click', function () {
      var input = $('#foto-visita-input');
      input.onchange = function () {
        [].slice.call(this.files).forEach(function (f) { if (formVisita.fotos.length < 12) formVisita.fotos.push(f); });
        this.value = '';
        renderFotosForm();
      };
      input.click();
    });
    var cancelar = $('#fv-cancelar', form);
    if (cancelar) cancelar.addEventListener('click', function () { recarregar(prefixo); });

    $('#fv-salvar', form).addEventListener('click', function () {
      var status = $('#fv-status', form).value;
      var data = dataParaEpoch($('#fv-data', form).value);
      // resumo vazio vai como '' (e não null) para poder limpar o campo na edição
      var dados = { prefixo: prefixo, status: status, resumo: $('#fv-resumo', form).value.trim() };
      if (status === 'PLANEJADA') dados.dataPlanejada = data || Date.now();
      else {
        dados.dataRealizada = data || Date.now();
        var faltando = CRITERIOS.filter(function (c) { return !formVisita.criterios[c[0]]; });
        if (faltando.length) { toast('Avalie ' + faltando[0][1].toLowerCase() + ' antes de salvar.'); return; }
        CRITERIOS.forEach(function (c) { dados[c[0]] = formVisita.criterios[c[0]]; });
        var mov = $('#fv-movimento .ativo', form);
        dados.movimento = mov ? mov.getAttribute('data-v') : 'NORMAL';
        dados.claros = claros();
        dados.notaGeral = $('#fv-nota', form).value;
        dados.melhorias = formVisita.melhorias.join('|');
        dados.percepcao = $('#fv-percepcao', form).value.trim();
      }
      var btn = this; btn.disabled = true;
      var pedido = formVisita.editando ? post('visita/' + formVisita.editando, dados) : post('visita', dados);
      pedido.then(function (r) {
        var visitaId = formVisita.editando || r.id;
        // a visita já existe: se uma ação/foto falhar, o novo clique atualiza em vez de duplicar
        formVisita.editando = visitaId;
        var depois = [];
        formVisita.acoes.forEach(function (a) {
          depois.push(post('ponto', { prefixo: prefixo, descricao: a.texto, previsao: a.prazo,
            responsavel: a.responsavel, prioridade: a.prioridade, visitaId: visitaId }));
        });
        var conferidas = 0;
        if (status !== 'PLANEJADA') {
          Object.keys(formVisita.conferencias).forEach(function (pid) {
            conferidas++;
            depois.push(post('ponto/' + pid + '/verificar', { resultado: formVisita.conferencias[pid], visitaId: visitaId }));
          });
        }
        if (formVisita.fotos.length && status !== 'PLANEJADA') {
          depois.push(enviarFotos('visita/' + visitaId + '/foto', formVisita.fotos));
        }
        return Promise.all(depois).then(function () {
          toast(status === 'PLANEJADA' ? 'Visita agendada.' : 'Visita registrada ✓' +
            (formVisita.acoes.length ? ' · ' + formVisita.acoes.length + ' ação(ões) na sua fila' : '') +
            (conferidas ? ' · ' + conferidas + ' conferida(s)' : ''));
          recarregar(prefixo); atualizarContadorAcoes();
        });
      }).catch(function (e) { btn.disabled = false; toast(e.message); });
    });
  }

  /**
   * Delegação ÚNICA (registrada no boot) das ações de gestão dentro do drawer
   * da agência — o corpo é re-renderizado a cada abertura, então o listener
   * fica no contêiner fixo para não acumular.
   */
  function aoClicarGestaoAgencia(ev) {
    var b = ev.target.closest('[data-acao]');
    if (!b || !App.agencia) return;
    var prefixo = App.agencia.agencia.prefixo;
    var acao = b.getAttribute('data-acao');
    var cartaoVisita = b.closest('[data-visita]'), cartaoAcao = b.closest('[data-ponto]'), nota = b.closest('.nota');
    var falha = function (e) { toast(e.message); };
    var visitaDe = function () {
      var id = +cartaoVisita.getAttribute('data-visita');
      return (App.agencia.visitas || []).filter(function (v) { return v.id === id; })[0];
    };
    if (acao === 'realizar' || acao === 'editar-visita') {
      var v = visitaDe();
      if (!v) return;
      if (acao === 'realizar') { v = Object.assign({}, v, { status: 'REALIZADA', dataRealizada: Date.now() }); }
      var atual = $('#fv-form');
      var novo = document.createElement('div');
      novo.innerHTML = checklistFormHtml(v);
      atual.parentNode.replaceChild(novo.firstChild, atual);
      ligarFormVisita(App.agencia, v);
      $('#fv-form').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else if (acao === 'excluir-visita') {
      if (!confirm('Excluir esta visita e suas notas?')) return;
      post('visita/' + cartaoVisita.getAttribute('data-visita') + '/excluir', {})
        .then(function () { recarregar(prefixo); }).catch(falha);
    } else if (acao === 'foto-visita') {
      escolherFotos('foto-visita-input').then(function (fs) {
        if (!fs.length) return;
        toast('Enviando foto(s)…');
        return enviarFotos('visita/' + cartaoVisita.getAttribute('data-visita') + '/foto', fs)
          .then(function (r) { toast(r.gravadas + ' foto(s) guardada(s).'); recarregar(prefixo); });
      }).catch(falha);
    } else if (acao === 'fixar') {
      post('anotacao/' + nota.getAttribute('data-anotacao'), { fixada: nota.classList.contains('fixada') ? '0' : '1' })
        .then(function () { recarregar(prefixo); }).catch(falha);
    } else if (acao === 'excluir-anotacao') {
      post('anotacao/' + nota.getAttribute('data-anotacao') + '/excluir', {})
        .then(function () { recarregar(prefixo); }).catch(falha);
    } else if (cartaoAcao) {
      tratarAcaoCard(acao, cartaoAcao, function () { recarregar(prefixo); atualizarContadorAcoes(); });
    }
  }

  /** Botões de um card de ação (compartilhado pela agência e pela vista Ações). */
  /** Mini-formulário dentro do card (concluir com prova / informou que fez). */
  function abrirMiniForm(cartao, modo) {
    var mini = $('[data-mini]', cartao);
    cartao._fotosMini = [];
    var concluir = modo === 'resolver';
    mini.innerHTML =
      '<label class="rotulo" style="margin:0">' + (concluir ? 'O que foi feito / solução' : 'O que o responsável informou') + '</label>' +
      '<textarea data-campo="mini-texto" placeholder="' + (concluir ? 'ex.: letreiro trocado pela engenharia em 12/10' : 'ex.: gerente avisou que a fachada foi pintada') + '"></textarea>' +
      '<div class="linha"><button class="botao mini claro" type="button" data-acao="mini-foto">📷 foto do depois</button>' +
      '<span class="foto-pendente" data-mini-fotos></span><span style="flex:1"></span>' +
      '<button class="botao mini claro" type="button" data-acao="mini-cancelar">cancelar</button>' +
      '<button class="botao mini primario" type="button" data-acao="' + (concluir ? 'resolver-enviar' : 'informou-enviar') + '">' +
      (concluir ? 'Concluir' : 'Registrar · conferir na próxima visita') + '</button></div>';
    mini.hidden = false;
    $('[data-campo="mini-texto"]', mini).focus();
  }

  function renderFotosMini(cartao) {
    var alvo = $('[data-mini-fotos]', cartao);
    if (!alvo) return;
    alvo.innerHTML = (cartao._fotosMini || []).map(function (f) {
      return '<img alt="" src="' + URL.createObjectURL(f) + '">';
    }).join('') + ((cartao._fotosMini || []).length ? '<span>' + cartao._fotosMini.length + ' foto(s)</span>' : '<span>opcional, mas é a prova</span>');
  }

  /** Botões de um card de ação (compartilhado pela agência, pela vista Ações e pelo planejamento). */
  function tratarAcaoCard(acao, cartao, depois) {
    var id = cartao.getAttribute('data-ponto');
    var falha = function (e) { toast(e.message); };
    var subirFotosMini = function (momento) {
      return (cartao._fotosMini || []).length ? enviarFotos('ponto/' + id + '/foto', cartao._fotosMini, { momento: momento }) : Promise.resolve();
    };
    if (acao === 'tratar') {
      post('ponto/' + id + '/comentar', { status: 'EM_TRATATIVA', texto: 'Tratativa iniciada', tipo: 'STATUS' }).then(depois).catch(falha);
    } else if (acao === 'reabrir') {
      post('ponto/' + id + '/comentar', { status: 'ABERTO', texto: 'Reaberta', tipo: 'STATUS' }).then(depois).catch(falha);
    } else if (acao === 'resolver' || acao === 'informou') {
      abrirMiniForm(cartao, acao);
    } else if (acao === 'mini-cancelar') {
      $('[data-mini]', cartao).hidden = true;
    } else if (acao === 'mini-foto') {
      escolherFotos('foto-acao-input').then(function (fs) {
        cartao._fotosMini = (cartao._fotosMini || []).concat(fs).slice(0, 6);
        renderFotosMini(cartao);
      });
    } else if (acao === 'resolver-enviar') {
      var sol = $('[data-campo="mini-texto"]', cartao).value.trim();
      if (!sol) { toast('Diga o que foi feito.'); return; }
      post('ponto/' + id, { status: 'RESOLVIDO', solucao: sol }).then(function () {
        return post('ponto/' + id + '/comentar', { texto: 'Concluída: ' + sol, tipo: 'STATUS' });
      }).then(function () { return subirFotosMini('DEPOIS'); })
        .then(function () { toast('Ação concluída ✓'); depois(); }).catch(falha);
    } else if (acao === 'informou-enviar') {
      var inf = $('[data-campo="mini-texto"]', cartao).value.trim();
      post('ponto/' + id + '/comentar', { status: 'AGUARDANDO_VERIFICACAO', texto: inf || 'Responsável informou que fez', tipo: 'RETORNO' })
        .then(function () { return subirFotosMini('DEPOIS'); })
        .then(function () { toast('Anotado · entra em "a conferir" na próxima visita.'); depois(); }).catch(falha);
    } else if (acao === 'confirmar' || acao === 'nao-feito') {
      post('ponto/' + id + '/verificar', { resultado: acao === 'confirmar' ? 'CONFIRMADO' : 'NAO_FEITO' })
        .then(function () { toast(acao === 'confirmar' ? 'Conferida e concluída ✓' : 'Reaberta: não estava feito.'); depois(); }).catch(falha);
    } else if (acao === 'foto-antes' || acao === 'foto-depois') {
      var momento = acao === 'foto-antes' ? 'ANTES' : 'DEPOIS';
      escolherFotos('foto-acao-input').then(function (fs) {
        if (!fs.length) return;
        toast('Enviando foto(s)…');
        return enviarFotos('ponto/' + id + '/foto', fs, { momento: momento })
          .then(function (r) { toast(r.gravadas + ' foto(s) do ' + momento.toLowerCase() + ' guardada(s).'); depois(); });
      }).catch(falha);
    } else if (acao === 'cobrei') {
      var txtC = $('[data-campo="retorno"]', cartao).value.trim();
      var adiar = $('[data-campo="adiar"]', cartao).value;
      post('ponto/' + id + '/comentar', { tipo: 'COBRANCA', texto: txtC || 'Cobrança feita', adiar: adiar })
        .then(function () { toast('Cobrança registrada · próxima em ' + adiar + ' dias.'); depois(); }).catch(falha);
    } else if (acao === 'excluir-ponto') {
      if (!confirm('Excluir esta ação, seu histórico e suas fotos?')) return;
      post('ponto/' + id + '/excluir', {}).then(depois).catch(falha);
    } else if (acao === 'retorno-enviar') {
      var txt = $('[data-campo="retorno"]', cartao).value.trim();
      var st = $('[data-campo="status-novo"]', cartao).value;
      if (!txt && !st) { toast('Escreva o retorno ou escolha o novo status.'); return; }
      post('ponto/' + id + '/comentar', { texto: txt || null, status: st || null, tipo: 'RETORNO' })
        .then(function () { toast('Retorno registrado.'); depois(); }).catch(falha);
    } else if (acao === 'historico') {
      var tl = $('[data-timeline]', cartao);
      if (!tl.hidden) { tl.hidden = true; return; }
      tl.hidden = false;
      tl.innerHTML = '<div class="ev">carregando…</div>';
      api('ponto/' + id + '/atualizacoes').then(function (lista) {
        tl.innerHTML = lista.length ? lista.map(function (u) {
          var t = TIPO_ATU[u.tipo] || TIPO_ATU.RETORNO;
          return '<div class="ev"><small>' + fmtData(u.criadoEm) + ' · ' + esc(u.criadoPor || '') + '</small>' +
            (t[0] ? '<span class="badge ' + (u.tipo === 'VERIFICACAO' && u.statusNovo === 'ABERTO' ? 'vinho' : t[1]) + '" style="margin-right:6px">' + t[0] + '</span>' : '') +
            (u.statusNovo ? '<span class="badge ' + (u.statusNovo === 'RESOLVIDO' ? 'verde' : u.statusNovo === 'ABERTO' ? 'ambar' : 'info') + '" style="margin-right:6px">' + STATUS_ACAO[u.statusNovo] + '</span>' : '') +
            esc(u.texto || '') + '</div>';
        }).join('') : '<div class="ev">Sem registros.</div>';
      }).catch(falha);
    }
  }

  /** Contador do menu e do filtro "Cobrar hoje" (o que pede ação sua agora). */
  function atualizarContadorAcoes() {
    var alvo = $('#nav-acoes-n');
    if (!alvo || !App.contexto || !App.contexto.master) return;
    api('hoje').then(function (h) {
      App.hoje = h;
      alvo.textContent = h.cobrarHoje;
      alvo.hidden = !h.cobrarHoje;
      alvo.title = h.cobrarHoje + ' ação(ões) para cobrar hoje · ' + h.vencidas + ' vencida(s) · ' + h.aConferir + ' a conferir';
      var seg = $('#acoes-n-cobrar');
      if (seg) { seg.textContent = h.cobrarHoje; seg.hidden = !h.cobrarHoje; }
    }).catch(function () { /* silencioso */ });
  }

  function notaHtml(a) {
    var acoes = App.contexto.master
      ? '<span class="acoes-nota">' +
        '<button class="botao mini claro" data-acao="fixar">' + (a.fixada ? 'solta' : 'fixa') + '</button>' +
        '<button class="botao mini perigo" data-acao="excluir-anotacao">excluir</button></span>'
      : '';
    return '<div class="nota' + (a.fixada ? ' fixada' : '') + '" data-anotacao="' + a.id + '">' +
      acoes + esc(a.texto) + '<br><small>' + fmtData(a.criadoEm) + '</small></div>';
  }

  /** Recarrega a agência mantendo aba e rolagem; atualiza pins/painel por trás. */
  function recarregar(prefixo) {
    api('mapa').then(function (m) {
      App.mapa = m;
      if (App.sel.uf) desenharPins(App.sel.uf, App.sel.municipio);
    }).catch(function () { /* o drawer já mostra o erro se a API caiu */ });
    abrirAgencia(prefixo, estadoDrawer());
  }

  // -------------------------------------------------------------- dashboard

  function abrirDash(d) {
    $('#dash-nome').textContent = d.agencia.nome;
    $('#painel-dash').classList.add('aberto');
    $('#veu').classList.add('aberto');
    var corpo = $('#dash-corpo');

    var r = d.resumo;
    var h = '<div class="dash-grade">';

    // coluna 1: metas do período + visões configuradas
    h += '<div style="display:flex;flex-direction:column;gap:18px">';
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

    if ((d.visoes || []).length) {
      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3 style="font-size:19px">Visões configuradas</h3>' +
        '<p class="rotulo" style="margin:0 0 6px">cards definidos pelo Master a partir dos CSV da pasta</p>' +
        '<div class="grade-tiles">' + d.visoes.map(tileVisao).join('') + '</div></div></div>';
    }
    h += '</div>';

    // coluna 2: Conexão + histórico + equipe + situação
    h += '<div style="display:flex;flex-direction:column;gap:18px">';
    if (d.conexao) {
      h += '<div class="cartao"><div class="cartao-corpo">' +
        '<h3 style="font-size:19px">Conexão</h3>' + conexaoAgenciaHtml(d.conexao) + '</div></div>';
    }
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
        abertos.length + ' ação(ões) em aberto</span>' +
        (ultima && ultima.notaGeral != null
          ? ' <span class="badge ouro">nota ' + ultima.notaGeral.toLocaleString('pt-BR') + '</span>' : '') + '</p>';
      abertos.slice(0, 4).forEach(function (p) {
        h += '<div class="nota" style="margin-top:8px">' + esc(p.descricao) +
          (p.responsavel ? ' <small>· ' + esc(p.responsavel) + '</small>' : '') +
          (p.previsao ? '<br><small>prazo ' + fmtData(p.previsao) +
            (p.vencida ? ' · <span class="badge vinho mini">vencida</span>' : '') + '</small>' : '') + '</div>';
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
      var k = p.kpis || {};
      $('#plan-resumo').textContent = k.visitadas + ' de ' + k.total + ' visitadas · ' +
        p.planejadas.length + ' planejada(s) · ' + k.acoesVencidas + ' ação(ões) vencida(s)';
      var h = '';

      var cad = p.cadencia || {};
      h += '<div class="cartao largo"><div class="cartao-corpo"><div class="kpis">' +
        kpi(k.visitadas + '/' + k.total, 'agências visitadas', 'destaque') +
        kpi(k.visitas90, 'visitas · 90 dias') +
        kpi(k.notaMedia != null ? k.notaMedia.toLocaleString('pt-BR', { maximumFractionDigits: 1 }) : '—', 'nota média das visitas') +
        kpi(k.agendaSemana, 'planejadas esta semana') +
        kpi(k.cobrarHoje || 0, 'cobrar hoje', k.cobrarHoje ? 'critico' : 'ok') +
        kpi(k.acoesVencidas, 'ações vencidas', k.acoesVencidas ? 'critico' : 'ok') +
        kpi(k.paradas || 0, 'paradas · sem retorno há ' + (cad.parada || 14) + '+ dias', k.paradas ? 'atencao' : 'ok') +
        kpi(k.aguardando || 0, 'a conferir na próxima visita') +
        kpi(k.fechamentoComprovadoPct != null ? k.fechamentoComprovadoPct + '%' : '—',
          'fechamento comprovado · ' + (k.comprovadas180 || 0) + ' de ' + (k.concluidas180 || 0) + ' em 180 dias',
          k.fechamentoComprovadoPct == null ? '' : k.fechamentoComprovadoPct >= 70 ? 'ok' : k.fechamentoComprovadoPct >= 40 ? 'atencao' : 'critico') +
        '</div></div></div>';

      // esta semana + atrasadas
      var semana = p.planejadas.filter(function (v) { return v.estaSemana || v.atrasada; });
      h += '<div class="cartao"><div class="cartao-corpo"><h3>Esta semana <span class="badge info">' + semana.length + '</span></h3><div class="fila">';
      if (!semana.length) h += '<div class="vazio">Nada planejado para os próximos 7 dias.</div>';
      semana.forEach(function (v) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(v.prefixo) + '">' +
          '<span class="selo-visita"></span><span><span class="nome">' + esc(v.nome) + '</span><small>' +
          esc(v.municipio || '') + '/' + esc(v.uf || '') + (v.pontosAbertos ? ' · ⚑ ' + v.pontosAbertos + ' ação(ões) aberta(s)' : '') + '</small></span>' +
          '<span class="numeros">' + (v.atrasada ? '<span class="badge vinho">atrasada</span><br>' : '') + fmtData(v.dataPlanejada) + '</span></button>';
      });
      h += '</div></div></div>';

      // cobrar hoje: lista única priorizada (vencidas, paradas, cadência vencida, por prioridade)
      var cobrar = p.cobrarHoje || [];
      h += '<div class="cartao"><div class="cartao-corpo"><h3>Cobrar hoje <span class="badge ' + (cobrar.length ? 'vinho' : 'verde') + '">' + cobrar.length + '</span></h3>' +
        '<p class="rotulo" style="margin:-4px 0 8px">vencidas · paradas · cadência (alta ' + (cad.ALTA || 7) + ' · média ' + (cad.MEDIA || 15) + ' · baixa ' + (cad.BAIXA || 30) + ' dias)</p><div class="fila">';
      if (!cobrar.length) h += '<div class="vazio">Nada para cobrar hoje. ✓</div>';
      cobrar.forEach(function (a) { h += acaoCardHtml(a, { compacta: true, comAgencia: true }); });
      h += '</div></div></div>';

      // a conferir na próxima visita
      var conferir = p.aConferir || [];
      h += '<div class="cartao"><div class="cartao-corpo"><h3>A conferir na próxima visita <span class="badge ' + (conferir.length ? 'info' : 'neutro') + '">' + conferir.length + '</span></h3>' +
        '<p class="rotulo" style="margin:-4px 0 8px">o responsável disse que fez · confirme na agência ou pela foto</p><div class="fila">';
      if (!conferir.length) h += '<div class="vazio">Nada aguardando conferência.</div>';
      conferir.forEach(function (a) { h += acaoCardHtml(a, { compacta: true, comAgencia: true }); });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo"><h3>Fila de visitas <span class="badge ouro">' + p.naoVisitadas.length + '</span></h3>' +
        '<p class="rotulo" style="margin:-4px 0 8px">nunca visitadas · menor Conexão primeiro</p><div class="fila">';
      if (!p.naoVisitadas.length) h += '<div class="vazio">Tudo visitado. 🏆</div>';
      p.naoVisitadas.forEach(function (a) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(a.prefixo) + '">' +
          '<span class="selo-visita"></span><span><span class="nome">' + esc(a.nome) + '</span><small>' +
          esc(a.municipio || '') + '/' + esc(a.uf || '') + ' · ' + esc(a.regional || '') + '</small></span>' +
          '<span class="numeros">' + (a.conexao != null ? 'Conexão ' + fmtInt(a.conexao) : '') +
          (a.pontosAbertos ? '<br>⚑ ' + a.pontosAbertos : '') + '</span></button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo"><h3>Agências frias <span class="badge ' + (p.frias.length ? 'ambar' : 'neutro') + '">' + p.frias.length + '</span></h3>' +
        '<p class="rotulo" style="margin:-4px 0 8px">sem visita há mais de 120 dias</p><div class="fila">';
      if (!p.frias.length) h += '<div class="vazio">Nenhuma agência esfriou. ✓</div>';
      p.frias.forEach(function (a) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(a.prefixo) + '">' +
          '<span class="selo-visita"></span><span><span class="nome">' + esc(a.nome) + '</span><small>' +
          esc(a.municipio || '') + '/' + esc(a.uf || '') + '</small></span>' +
          '<span class="numeros">' + a.dias + ' dias<br><small>' + fmtData(a.ultimaVisita) + '</small></span></button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo"><h3>Evolução entre visitas</h3>' +
        '<p class="rotulo" style="margin:-4px 0 8px">nota geral: anterior → última</p>';
      var evol = (p.evolucao || []).filter(function (e) { return e.anterior != null; });
      if (!evol.length) h += '<div class="vazio">Registre a segunda visita de uma agência para comparar.</div>';
      else {
        evol.sort(function (a, b) { return (a.delta || 0) - (b.delta || 0); });
        h += '<table class="tabela cartoes"><thead><tr><th>Agência</th><th>Anterior</th><th>Última</th><th>Variação</th></tr></thead><tbody>';
        evol.forEach(function (e) {
          h += '<tr><td data-th="Agência"><button class="botao mini claro" data-prefixo="' + esc(e.prefixo) + '">' + esc(e.nome) + '</button></td>' +
            '<td data-th="Anterior">' + e.anterior.toLocaleString('pt-BR') + '</td><td data-th="Última"><b>' + e.ultima.toLocaleString('pt-BR') + '</b></td>' +
            '<td data-th="Variação"><span class="badge ' + (e.delta > 0 ? 'verde' : e.delta < 0 ? 'vinho' : 'neutro') + '">' +
            (e.delta > 0 ? '▲ ' : e.delta < 0 ? '▼ ' : '') + Math.abs(e.delta).toLocaleString('pt-BR') + '</span></td></tr>';
        });
        h += '</tbody></table>';
      }
      h += '</div></div>';

      h += '<div class="cartao"><div class="cartao-corpo"><h3>Fotos pendentes <span class="badge neutro">' + p.semFoto.length + '</span></h3><div class="fila">';
      if (!p.semFoto.length) h += '<div class="vazio">Todas as agências têm foto. 📸</div>';
      p.semFoto.forEach(function (a) {
        h += '<button class="item-agencia" type="button" data-prefixo="' + esc(a.prefixo) + '">' +
          '<span><span class="nome">' + esc(a.nome) + '</span><small>' + esc(a.uf || '') + '</small></span></button>';
      });
      h += '</div></div></div>';

      h += '<div class="cartao"><div class="cartao-corpo"><h3>Minhas anotações</h3>' +
        '<div style="display:flex;gap:8px;margin-bottom:10px">' +
        '<input type="text" id="plan-anotacao" class="campo-input" style="flex:1" placeholder="anotar um lembrete geral…">' +
        '<button class="botao claro" id="plan-anotar" type="button">Anotar</button></div>' +
        '<div class="fila" id="plan-notas">';
      (p.anotacoesGerais || []).forEach(function (a) { h += notaHtml(a); });
      if (!(p.anotacoesGerais || []).length) h += '<div class="vazio">Sem lembretes gerais.</div>';
      h += '</div></div></div>';

      alvo.innerHTML = h;
      animarContadores(alvo);
      $$('[data-prefixo]', alvo).forEach(function (b) {
        b.addEventListener('click', function () { abrirAgencia(b.getAttribute('data-prefixo')); });
      });
      var anotar = $('#plan-anotar');
      if (anotar) anotar.addEventListener('click', function () {
        var t = $('#plan-anotacao').value.trim();
        if (!t) return;
        post('anotacao', { texto: t })
          .then(function () { toast('Anotado.'); carregarPlanejamento(); })
          .catch(function (e) { toast(e.message); });
      });
    }).catch(function (e) {
      alvo.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>';
    });
  }

  function kpi(valor, rotulo, cls) {
    var n = typeof valor === 'number' ? valor : null;
    return '<div class="tile' + (cls ? ' ' + cls : '') + '" style="cursor:default"><span class="valor"' +
      (n != null ? ' data-n="' + n + '"' : '') + '>' + esc(String(valor)) + '</span><span class="rotulo">' + rotulo + '</span></div>';
  }

  /** Números sobem até o valor final (toque futurista, respeita reduced-motion). */
  function animarContadores(raiz) {
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    $$('.valor[data-n]', raiz).forEach(function (el) {
      var fim = +el.getAttribute('data-n'), texto = el.textContent, t0 = performance.now(), dur = 650;
      if (!isFinite(fim) || fim === 0) return;
      function passo(t) {
        var k = Math.min(1, (t - t0) / dur); k = 1 - Math.pow(1 - k, 3);
        el.textContent = fmtInt(Math.round(fim * k));
        if (k < 1) requestAnimationFrame(passo); else el.textContent = texto;
      }
      requestAnimationFrame(passo);
    });
  }

  // ------------------------------------------------------------------ ações

  var filtroAcoes = { chave: 'PENDENTES', prioridade: '', regional: '', texto: '' };

  function carregarAcoes() {
    var alvo = $('#grade-acoes');
    alvo.innerHTML = '<div class="carregando">Carregando…</div>';
    var q = [], ch = filtroAcoes.chave;
    if (ch === 'PENDENTES' || ch === 'RESOLVIDO') q.push('status=' + ch);
    if (ch === 'VENCIDAS' || ch === '7DIAS' || ch === 'COBRAR' || ch === 'PARADAS') q.push('prazo=' + ch);
    if (ch === 'AGUARDANDO') q.push('status=AGUARDANDO_VERIFICACAO');
    if (ch === 'SEMPROVA') { q.push('status=RESOLVIDO'); q.push('prova=SEM'); }
    if (filtroAcoes.prioridade) q.push('prioridade=' + filtroAcoes.prioridade);
    if (filtroAcoes.regional) q.push('regional=' + encodeURIComponent(filtroAcoes.regional));
    api('acoes' + (q.length ? '?' + q.join('&') : '')).then(function (lista) {
      var t = filtroAcoes.texto.toLowerCase();
      if (t) lista = lista.filter(function (a) {
        return ((a.descricao || '') + ' ' + (a.agencia || '') + ' ' + (a.responsavel || '') + ' ' + (a.solucao || '')).toLowerCase().indexOf(t) >= 0;
      });
      var vencidas = lista.filter(function (a) { return a.vencida; }).length;
      var paradas = lista.filter(function (a) { return a.parada; }).length;
      var cobrar = lista.filter(function (a) { return a.cobrarHoje; }).length;
      $('#acoes-resumo').textContent = lista.length + ' ação(ões)' + (vencidas ? ' · ' + vencidas + ' vencida(s)' : '') +
        (paradas ? ' · ' + paradas + ' parada(s)' : '') + (cobrar && ch !== 'COBRAR' ? ' · ' + cobrar + ' para cobrar hoje' : '');
      if (!lista.length) {
        alvo.innerHTML = '<div class="vazio">' + (ch === 'COBRAR' ? 'Nada para cobrar hoje. ✓' : ch === 'AGUARDANDO' ? 'Nada aguardando conferência.'
          : ch === 'SEMPROVA' ? 'Toda conclusão tem prova. ✓' : 'Nenhuma ação neste filtro.') + '</div>';
        return;
      }
      // agrupa por agência para a cobrança ficar organizada
      var grupos = {};
      lista.forEach(function (a) { (grupos[a.prefixo] = grupos[a.prefixo] || { nome: a.agencia, itens: [] }).itens.push(a); });
      var h = '';
      Object.keys(grupos).forEach(function (pfx) {
        var g = grupos[pfx];
        h += '<div class="painel-titulo" style="margin-top:14px"><h3 style="margin:0"><button class="botao mini claro" data-prefixo="' + esc(pfx) + '">' +
          esc(g.nome || pfx) + '</button> <span class="badge neutro">' + g.itens.length + '</span></h3></div>';
        g.itens.forEach(function (a) { h += acaoCardHtml(a, {}); });
      });
      alvo.innerHTML = h;
      $$('[data-prefixo]', alvo).forEach(function (b) {
        b.addEventListener('click', function () { abrirAgencia(b.getAttribute('data-prefixo')); });
      });
    }).catch(function (e) { alvo.innerHTML = '<div class="aviso">' + esc(e.message) + '</div>'; });
  }

  function ligarAcoes() {
    var vista = $('#vista-acoes');
    if (!vista) return;
    $$('#acoes-status button').forEach(function (b) {
      b.addEventListener('click', function () {
        $$('#acoes-status button').forEach(function (x) { x.classList.remove('ativo'); });
        b.classList.add('ativo');
        filtroAcoes.chave = b.getAttribute('data-v');
        carregarAcoes();
      });
    });
    $('#acoes-prioridade').addEventListener('change', function () { filtroAcoes.prioridade = this.value; carregarAcoes(); });
    $('#acoes-regional').addEventListener('change', function () { filtroAcoes.regional = this.value; carregarAcoes(); });
    var timer;
    $('#acoes-busca').addEventListener('input', function () {
      clearTimeout(timer); var v = this.value;
      timer = setTimeout(function () { filtroAcoes.texto = v.trim(); carregarAcoes(); }, 250);
    });
    // regionais do mapa já carregado
    var regs = {};
    (App.mapa.agencias || []).forEach(function (a) { if (a.regional) regs[a.regional] = 1; });
    $('#acoes-regional').innerHTML = '<option value="">Todas as regionais</option>' +
      Object.keys(regs).sort().map(function (r) { return '<option value="' + esc(r) + '">' + esc(r) + '</option>'; }).join('');
    // delegação dos botões dos cards
    $('#grade-acoes').addEventListener('click', function (ev) {
      var b = ev.target.closest('[data-acao]');
      var cartao = b && b.closest('[data-ponto]');
      if (!b || !cartao) return;
      tratarAcaoCard(b.getAttribute('data-acao'), cartao, function () { carregarAcoes(); atualizarContadorAcoes(); });
    });
  }

  // ------------------------------------------------------------------ busca

  function ligarBusca() {
    var input = $('#busca-input'), res = $('#busca-res');
    if (!input) return;
    function fechar() { res.hidden = true; }
    function render() {
      var q = input.value.trim().toLowerCase();
      if (!q) { fechar(); return; }
      var itens = (App.mapa.agencias || []).filter(function (a) {
        return (a.nome + ' ' + a.prefixo + ' ' + (a.municipio || '') + ' ' + (a.uf || '') + ' ' + (a.regional || ''))
          .toLowerCase().indexOf(q) >= 0;
      }).slice(0, 10);
      res.innerHTML = itens.length ? itens.map(function (a) {
        return '<button type="button" data-prefixo="' + esc(a.prefixo) + '"><span class="tipo">' + esc(a.uf || 'ag') + '</span>' +
          '<span><b>' + esc(a.nome) + '</b><small>' + esc(a.prefixo) + ' · ' + esc(a.municipio || '') + ' · ' + esc(a.regional || '') + '</small></span></button>';
      }).join('') : '<div class="vazio" style="border:none;padding:12px">Nada encontrado para “' + esc(q) + '”.</div>';
      res.hidden = false;
      $$('[data-prefixo]', res).forEach(function (b) {
        b.addEventListener('click', function () { fechar(); input.value = ''; abrirAgencia(b.getAttribute('data-prefixo')); });
      });
    }
    input.addEventListener('input', render);
    input.addEventListener('focus', render);
    input.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') { fechar(); input.blur(); }
      if (ev.key === 'Enter') { var p = $('[data-prefixo]', res); if (p) p.click(); }
    });
    document.addEventListener('click', function (ev) { if (!ev.target.closest('.busca-topo')) fechar(); });
    document.addEventListener('keydown', function (ev) {
      if (ev.key === '/' && !/input|textarea|select/i.test(document.activeElement.tagName)) { ev.preventDefault(); input.focus(); }
    });
  }

  // ---------------------------------------------------------------- vistas

  var TITULOS = { mapa: 'Atlas', planejamento: 'Minha gestão', acoes: 'Ações para dar retorno' };

  function trocarVista() {
    var h = (location.hash || '#mapa').replace('#', '');
    if ((h === 'planejamento' || h === 'acoes') && !(App.contexto && App.contexto.master)) h = 'mapa';
    if (!TITULOS[h]) h = 'mapa';
    // trocar de vista fecha o que estava sobreposto (drawer da agência, dashboard da porta)
    if ($('#painel-dash').classList.contains('aberto')) fecharDash();
    if ($('#drawer-agencia').classList.contains('aberto')) fecharAgencia();
    $('#vista-mapa').classList.toggle('ativa', h === 'mapa');
    $('#vista-planejamento').classList.toggle('ativa', h === 'planejamento');
    $('#vista-acoes').classList.toggle('ativa', h === 'acoes');
    $$('#nav-lateral a').forEach(function (a) {
      a.classList.toggle('ativa', a.getAttribute('data-nav') === h);
    });
    $('#topo-titulo').textContent = TITULOS[h];
    $('#conteudo').scrollTop = 0;
    if (h === 'planejamento') carregarPlanejamento();
    if (h === 'acoes') carregarAcoes();
  }

  function atualizarSubtitulo(texto) {
    var el = $('#topo-sub');
    if (el) el.innerHTML = '<span class="pulso"></span>' + esc(texto);
  }

  /** Vista Ações já no filtro "Cobrar hoje". */
  function irParaCobrancas() {
    filtroAcoes.chave = 'COBRAR';
    $$('#acoes-status button').forEach(function (x) { x.classList.toggle('ativo', x.getAttribute('data-v') === 'COBRAR'); });
    if ((location.hash || '#mapa') === '#acoes') carregarAcoes(); else location.hash = '#acoes';
  }

  /** Aviso do dia na abertura (só Master): o que pede sua ação hoje. Fecha por hoje. */
  function mostrarAvisoDoDia() {
    if (!App.contexto || !App.contexto.master) return;
    var chave = 'atlas.aviso.' + isoLocal(Date.now()), fechado = null;
    try { fechado = localStorage.getItem(chave); } catch (e) { /* sem storage */ }
    if (fechado) return;
    api('hoje').then(function (h) {
      App.hoje = h;
      var itens = [];
      if (h.cobrarHoje) itens.push('<b>' + h.cobrarHoje + '</b> cobrança(s) para hoje');
      if (h.vencidas) itens.push('<b>' + h.vencidas + '</b> vencida(s)');
      if (h.paradas) itens.push('<b>' + h.paradas + '</b> parada(s) sem retorno');
      if (h.aConferir) itens.push('<b>' + h.aConferir + '</b> a conferir na próxima visita');
      if (h.visitasHoje) itens.push('<b>' + h.visitasHoje + '</b> visita(s) planejada(s) para hoje');
      if (h.visitasAtrasadas) itens.push('<b>' + h.visitasAtrasadas + '</b> visita(s) planejada(s) atrasada(s)');
      if (!itens.length) return;
      var vista = $('#vista-mapa');
      if (!vista || $('#aviso-dia')) return;
      var el = document.createElement('div');
      el.className = 'aviso-dia'; el.id = 'aviso-dia';
      el.innerHTML = '<span class="pulso"></span><div class="itens"><b>Hoje</b>' +
        itens.map(function (i) { return '<span>' + i + '</span>'; }).join('') + '</div>' +
        '<div class="acoes">' + (h.cobrarHoje ? '<button class="botao mini claro" type="button" data-ir="cobrar">Ver cobranças</button>' : '') +
        '<button class="botao mini claro" type="button" data-ir="planejamento">Minha gestão</button>' +
        '<button class="fechar" type="button" title="fechar por hoje" aria-label="fechar">×</button></div>';
      vista.insertBefore(el, vista.firstChild);
      el.addEventListener('click', function (ev) {
        var b = ev.target.closest('button');
        if (!b) return;
        if (b.classList.contains('fechar')) {
          try { localStorage.setItem(chave, '1'); } catch (e) { /* sem storage */ }
          el.remove();
          return;
        }
        if (b.getAttribute('data-ir') === 'cobrar') irParaCobrancas(); else location.hash = '#planejamento';
      });
    }).catch(function () { /* silencioso */ });
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
      document.documentElement.classList.toggle('master', !!App.contexto.master);
      desenharMapa();
      carregarPainel({});
      ligarBusca();
      if (App.contexto.master) { ligarAcoes(); atualizarContadorAcoes(); mostrarAvisoDoDia(); }
      trocarVista();
      atualizarSubtitulo((App.contexto.regionalJurisdicao || 'Super Nacional Estilo') + ' · ' +
        App.mapa.agencias.length + ' agência(s)' + (App.contexto.master ? '' : ' · visão ' + App.contexto.perfil.toLowerCase()));
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
    // delegações únicas: os corpos são re-renderizados, os listeners não
    $('#ag-corpo').addEventListener('click', aoClicarGestaoAgencia);
    $('#grade-planejamento').addEventListener('click', function (ev) {
      var b = ev.target.closest('[data-acao]');
      if (!b) return;
      var nota = b.closest('.nota'), cartao = b.closest('[data-ponto]');
      var acao = b.getAttribute('data-acao');
      var falha = function (e) { toast(e.message); };
      if (cartao) { tratarAcaoCard(acao, cartao, function () { carregarPlanejamento(); atualizarContadorAcoes(); }); return; }
      if (!nota) return;
      if (acao === 'fixar') {
        post('anotacao/' + nota.getAttribute('data-anotacao'),
          { fixada: nota.classList.contains('fixada') ? '0' : '1' })
          .then(carregarPlanejamento).catch(falha);
      } else if (acao === 'excluir-anotacao') {
        post('anotacao/' + nota.getAttribute('data-anotacao') + '/excluir', {})
          .then(carregarPlanejamento).catch(falha);
      }
    });
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
