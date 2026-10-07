# Atlas Estilo — SUPER PF1 · Banco do Brasil

Atlas interativo das agências Estilo: mapa do Brasil com zoom por
estado/município, métricas filtráveis da região (funcis, gerentes, assistentes,
carteiras, tempos médios, PDG, Conexão, visões de CSV), página da agência com
fachada (foto ou Google Maps) e o botão **“Entrar · dashboard”** na porta, e —
só para o Master — o ciclo completo da visita: briefing “antes de ir”,
checklist com notas e fotos, ações com dono/prazo/prioridade e cobrança, agenda
e evolução entre visitas.

Stack: Java 8 · JSP/Servlets puros · SQLite (WAR) · Tomcat 8.5 (Windows x86) ·
SSO OAuth2 do BB · front-end “dashboard” (sidebar navy + dourado, claro/escuro,
responsivo até celular).

## Perfis

| Perfil | Quem | O que vê |
|---|---|---|
| **Colega** | qualquer matrícula no SSO | grandes números dos prefixos da **sua regional** (sem pessoas, sem fotos de pessoas, sem nada de gestão) |
| **Moderador** | prefixo 9007 | tudo em leitura (todas as regiões, equipes, carteiras, Conexão por gerente) — mas **não** o que o Master registra |
| **Master** | tabela `config_master` | tudo + gestão (visitas, checklist, fotos de visita, anotações, ações, imports, fotos, acessos) |

Carga inicial de masters: `F3548926`, `F3191837`, `F6323371`.

**Privacidade do que o Master registra.** Visitas (planejadas e realizadas,
com checklist, notas e percepção), anotações, ações e seus retornos, fotos
anexadas a visitas (`foto.restrita = 1`) e os agregados derivados (agência
“visitada”, “visita planejada”, contagem de ações em aberto, KPIs de “Minha
gestão”, exports CSV) são **devolvidos pela API somente quando
`Sessao.master()` é verdadeiro**. Colega e Moderador recebem `visitada=false`,
`pontosAbertos=0`, nenhuma visita/anotação/ação no JSON da agência, e `/foto/{id}`
responde 403 para foto restrita. O front-end apenas esconde o que já não vem
(classe `html.master` ↔ `.so-master`).

## Build

```
./build.sh        # produção -> dist/atlasestilo.war
./build.sh dev    # teste local SEM SSO (usuário simulado Master) -> dist/atlasestilo-dev.war
```

Compila com `--release 8`, roda o SelfTest (164 verificações, sem rede —
inclui o que cada perfil pode ou não ver) e empacota. O WAR leva o driver
SQLite em `WEB-INF/lib`. No WAR **dev**, `?perfil=COLEGA`, `?perfil=MODERADOR`
ou `?perfil=MASTER` na URL troca o usuário simulado (fica na sessão) para
conferir a visão de cada um.

Esquema do banco: `WEB-INF/sql/schema.sql` roda a cada subida
(`CREATE TABLE IF NOT EXISTS`) e `db/Migracoes.java` acrescenta as colunas
novas em bancos já existentes (idempotente) — atualizar o WAR em produção não
exige mexer no banco.

### Antes do deploy de produção (uma vez)

1. **Copiar os binários do SSO do BB** (de uma ferramenta existente, ex.
   boaspraticas) para `terceiros/sso/`:
   - `br/com/bb/sso/filter/FilterOauth2.class`
   - `br/com/bb/sso/bean/Usuario.class`
   - `json-20230618.jar`
   O build inclui tudo automaticamente quando presente (e avisa quando falta).
2. **Registrar o redirect_uri** `https://super-pf1.intranet.bb.com.br/atlasestilo`
   para o client_id `SUPERPF1` no servidor OAuth2 do BB (idêntico, sem barra
   final) — senão o login devolve `redirect_uri_mismatch`.

## Deploy (Tomcat 8.5 · Windows)

1. Copiar `dist/atlasestilo.war` para `webapps\` — e **deixar o .war lá**
   (removê-lo desfaz o deploy).
2. Os dados ficam em `<Tomcat>\dados\atlasestilo\` (banco + fotos), criados na
   1ª subida. Se o log acusar falta de escrita:
   `icacls "<Tomcat>\dados" /grant "NETWORK SERVICE":(OI)(CI)M /T`
   (ajuste a conta de serviço real do Tomcat).
3. Se o contexto sumir sozinho, verifique a quarentena do antivírus
   (o sqlite-jdbc tem DLLs nativas).

## Dados

Na primeira subida com banco vazio a ferramenta **semeia dados de exemplo**
(26 agências fictícias em 14 UFs) para a experiência ser navegável de cara.
Na tela **Admin** o Master:

- importa as planilhas reais (CSV/XLSX, com modelo para baixar, prévia e
  confirmação): **agências/endereços**, **funcis**, **carteiras**, **PDG por
  semestre**, **metas/projeções** — os imports substituem os registros de
  exemplo de mesma chave;
- sobe **fotos** (fachada/interna/pessoa — foto de pessoa só aparece para
  Master/Moderador);
- administra masters, flags (`SOMENTE_LEITURA`/`BLOQUEADO`) e pode limpar ou
  recarregar o exemplo.

### Fontes de dados em CSV na pasta do servidor

Além do upload manual, o Master aponta na tela **Admin › Pasta de CSV** uma
pasta do servidor (padrão `<Tomcat>\dados\atlasestilo\csv`, parâmetro
`atlas.csv.dir`; o caminho salvo no Admin prevalece). Cada **fonte** liga um
arquivo dessa pasta (nome exato ou padrão como `conexao_*.csv` — vale o mais
recente) a um tipo de dado:

| Tipo | O que alimenta |
|---|---|
| `agencias`, `funcis`, `carteiras`, `pdg`, `metas` | os mesmos imports tipados do upload |
| `conexao` | Conexão por competência (`AAAA-MM`) da agência e de cada carteira/gerente; agência sem nota própria recebe a média das carteiras (origem `DERIVADO`) |
| `indicadores` | qualquer CSV com `prefixo` (+ `competencia` opcional): toda coluna numérica vira um indicador disponível para as **visões** |

**Visões do dashboard** (Admin › Visões): cada visão escolhe fonte, coluna,
agregação (média/soma/mín/máx), formato (inteiro/decimal/%/R$), meta fixa ou
coluna de meta, direção (maior/menor é melhor), faixa plausível e **perfil
mínimo** (Colega/Moderador/Master). Elas viram cards no painel da região, na
agência e no dashboard da porta, com farol (ok/atenção/crítico) e variação
contra a competência anterior.

**Saneamento automático** (`util/Saneador`): encoding (BOM, UTF-8, ANSI),
delimitador (`;` `,` TAB `|`), preâmbulo e cabeçalhos repetidos, colunas
vazias sobrando, números com vírgula/ponto trocados, milhar, `R$`, `%`,
parênteses negativos, erros do Excel (`#N/D`), letras no lugar de dígitos
(`O`→`0`, `l`/`I`→`1`), competências em vários formatos. Tudo que foi
corrigido aparece no **relatório** da fonte (regra, linha, veio/ficou); as
linhas rejeitadas podem ser baixadas em CSV para correção. O **modo estrito**
impede qualquer gravação quando há linha rejeitada ou célula inválida.

**Monitor**: a cada N minutos (Admin; 0 desliga; `atlas.csv.monitor=false`
no web.xml desativa de vez) o servidor reimporta as fontes automáticas cujo
arquivo mudou (data de modificação). "Importar agora o que mudou" roda a
varredura na hora.

Modelos CSV: `/api/admin/modelo/conexao` e `/api/admin/modelo/indicadores`.

### Visitas, checklist, fotos e ações (Master)

Tudo na própria página da agência (drawer), em abas:

- **Antes de ir · briefing automático** (topo do drawer): última visita e
  nota, claros no quadro, o que ficou pendente, a percepção escrita na visita
  anterior, ações vencidas/em aberto, Conexão que caiu ou carteiras críticas e
  metas abaixo de 70% — montado dos dados já carregados, sem configuração.
- **Visitas**: formulário padronizado da visita — situação (planejada /
  realizada), data, resumo, **checklist** (ambiência, atendimento,
  organização, equipe: 1 a 5; movimento; nº de claros; **nota geral 0–10**),
  chips do que precisa melhorar (+ livre), percepção, **ações desta visita**
  (descrição, responsável, prazo, prioridade) e **fotos da visita** (várias
  de uma vez; sempre restritas ao Master). Histórico com cards por visita,
  **evolução** entre as duas últimas notas, edição/“realizar” de visita
  planejada e anotações da agência.
- **Ações**: criação rápida, pendentes × concluídas, botões “em tratativa” /
  “informou que fez” / “concluir” (mini-formulário com solução e foto do
  depois) / “reabrir”, campo de **retorno** com mudança de status, botão
  **“cobrei”** (registra a cobrança e marca quando cobrar de novo) e
  **histórico** (linha do tempo em `acao_atualizacao`, com tipo: retorno,
  cobrança, mudança de status, conferência).
- Excluir uma visita apaga as fotos dela (registro e arquivo) e **solta** as
  ações (continuam na fila, sem origem). Excluir uma ação apaga seu histórico
  e suas fotos.

**Cadência de cobrança** (Admin › Cadência): cada prioridade tem um ritmo
(padrão alta 7, média 15, baixa 30 dias) e um limite de **parada** (14 dias
sem retorno). Uma ação entra em **“Cobrar hoje”** quando está vencida,
parada ou quando a próxima cobrança chegou; “cobrei” adia a próxima cobrança
(+3/+7/+15/+30 dias) sem mexer no prazo da ação e não zera o “sem retorno”;
um retorno do responsável reinicia o relógio pela cadência. O card mostra
“sem retorno há N dias”, “cobrada há N dias (N×)” e o selo *cobrar hoje*.
Na vista Ações: filtros **Cobrar hoje**, **Paradas**, **A conferir** e **Sem
prova**; o contador do menu é o “cobrar hoje”. Ao abrir a ferramenta, o
**aviso do dia** resume cobranças, vencidas, paradas, a conferir e visitas
do dia (fecha por hoje).

**Fechamento comprovado.** Cada ação aceita **fotos do antes e do depois**
(reduzidas no navegador antes de subir; restritas ao Master). Quando o
responsável avisa que fez, “informou que fez” leva a ação ao status
`AGUARDANDO_VERIFICACAO`: sai das vencidas e entra em **“A conferir na
próxima visita”** (Minha gestão, briefing e aba Ações). No checklist da visita
seguinte aparece o bloco **Conferir**: “confirmei” conclui a ação com a
visita como prova (`verificado_em`, `verificado_visita_id`); “não estava
feito” reabre e conta a reabertura. O KPI **fechamento comprovado** é a
fração das concluídas em 180 dias com conferência ou foto do depois.

**Minha gestão** (`#planejamento`): KPIs (visitadas, visitas em 90 dias, nota
média, planejadas na semana, cobrar hoje, vencidas, paradas, a conferir,
fechamento comprovado), agenda da semana, **Cobrar hoje** (lista única
priorizada: vencidas, paradas, cadência, prioridade), **A conferir na próxima
visita**, fila de nunca visitadas (menor Conexão primeiro), agências frias
(sem visita há mais de 120 dias), evolução entre visitas, fotos pendentes,
anotações gerais. **Ações** (`#acoes`): fila nacional com filtros (cobrar
hoje / pendentes / vencidas / paradas / 7 dias / a conferir / concluídas / sem
prova / todas, prioridade, regional, texto), agrupada por agência, com o
mesmo card de retorno. Exports: `/api/export/visitas` e `/api/export/acoes`
(CSV `;`, BOM, Excel; o de ações traz cobranças, próxima cobrança, dias sem
retorno, informado/verificado em, reaberturas e fotos antes/depois).

Rotas da API usadas por esses fluxos (todas exigem Master): `GET /api/hoje`,
`GET /api/acoes?status=…&prazo=VENCIDAS|7DIAS|30DIAS|SEM|COBRAR|PARADAS&prova=SEM`,
`POST /api/ponto/{id}/comentar` (`texto`, `status`, `tipo=RETORNO|COBRANCA|STATUS`,
`adiar` em dias), `POST /api/ponto/{id}/foto?momento=ANTES|DEPOIS` (multipart),
`POST /api/ponto/{id}/verificar` (`resultado=CONFIRMADO|NAO_FEITO`, `visitaId`),
`GET/POST /api/admin/cadencia`.

### Front-end

Layout “dashboard”: barra lateral navy com a navegação (Atlas · Minha gestão ·
Ações com contador · Admin), topo com busca (`/` foca), conteúdo em cards com
KPIs animados, tabelas que viram cards no celular, tema claro/escuro
(`html[data-tema]`, salvo no navegador), sidebar em gaveta abaixo de 1000px.
Fontes Inter / Space Grotesk / JetBrains Mono via Google Fonts, com fallback
de sistema quando a rede bloqueia.

### Fachada da agência (Google Maps)

A imagem da fachada segue esta ordem: **foto FACHADA do admin** → **link de
embed colado pelo Master** (campo na própria página da agência; aceita o
“Incorporar um mapa”/Street View do Google Maps) → **embed automático por
lat/lng** (satélite; exige que a estação do usuário alcance maps.google.com)
→ aviso de “sem imagem”. Se a rede bloquear o Google, defina
`atlas.maps.ativo=false` no web.xml que a ferramenta passa a usar apenas fotos.

## Integração futura

- Fotos da intranet (método a combinar) — campo `origem='INTRANET'` na tabela
  `foto` já reservado.
- Troca SQLite → DB2: a camada DAO isola o SQL (ver `db/Db.java` e os
  `ON CONFLICT` nos DAOs/imports).
