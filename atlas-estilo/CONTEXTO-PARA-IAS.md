# Atlas Estilo — contexto completo para IAs

**Para que serve este documento.** É o ponto de partida de quem vai dar manutenção no Atlas Estilo sem ter visto o código — uma IA ou uma pessoa técnica. Ele descreve o propósito, as regras que não se negociam, cada arquivo do repositório, a arquitetura, o modelo de dados, a API, o front-end, o build, o deploy com o SSO do BB, receitas para as tarefas mais comuns, as armadilhas conhecidas, o histórico e o que está pendente. Foi escrito a partir da leitura integral do código e conferido contra ele.

**Como usá-lo.** Leia a seção 2 (regras inegociáveis) antes de qualquer alteração. Use a seção 3 para localizar arquivos, a 6 e a 8 como referência de tabelas e rotas, a 12 como passo a passo e a 13 antes de "corrigir" algo que parece estranho (quase sempre é decisão deliberada). Caminhos são relativos a `atlas-estilo/`; símbolos (classes, métodos, funções, chaves, rotas) são citados com o nome exato que está no código, sem números de linha. Quando este documento e o código divergirem, o código manda — e este documento deve ser corrigido no mesmo commit.

**Estado retratado.** 2026-10-08, branch `claude/passagem-aerea-sp-orlando-0k2jhb`. A base funcional é a revisão `c1c16f9` ("Revisão: correções necessárias, WAR de produção sem segredo e injeção do SSO"); depois dela vieram `27079a8` (WAR regenerado, `README-DEPLOY.md` apontando para este arquivo) e `58e7e40` (`HEAD`: login OAuth2 **próprio** dentro do WAR em `src/br/com/bb/sso`, `build.sh` gerando sempre `dist/atlasestilo.war`, SelfTest com 199 verificações). Este documento descreve o código em `58e7e40`. Repositório git: `/home/user/medal` (monorepo público em `github.com/fvzica/medal`; esta ferramenta vive na pasta `atlas-estilo/`).

---

## 1. O que é o Atlas Estilo

O Atlas Estilo é uma ferramenta web interna da **SUPER PF1** (Superintendência de Pessoa Física 1 do Banco do Brasil, prefixo `9007`) para acompanhar as **agências Estilo** (segmento de alta renda). Ela mostra um mapa do Brasil em SVG com zoom por UF e município, os "grandes números" da seleção (funcis, gerentes, assistentes, carteiras, tempos médios de posse, semestres de PDG, nota Conexão e cards de indicadores configuráveis), e uma página por agência com fachada, equipe, carteiras, metas, fotos e um "dashboard na porta".

Para o perfil **Master** ela é também um instrumento de gestão do atendimento: planejar e registrar **visitas** com checklist padronizado (notas de 1 a 5, movimento, claros, nota geral, melhorias, percepção, fotos), abrir **ações** (pontos de melhoria) com responsável, prazo e prioridade, acompanhar a **cadência de cobrança** ("cobrar hoje", "paradas"), conferir in loco o que o responsável disse ter feito (**fechamento comprovado** com conferência ou foto do depois), manter anotações e acompanhar tudo em "Minha gestão", na vista "Ações" e no aviso do dia. Tudo o que o Master registra é visível **somente a Masters**.

Quem usa: qualquer funcionário do BB autenticado pelo SSO (perfil **Colega**, que vê os grandes números da própria regional), a equipe da SUPER PF1 (perfil **Moderador**, prefixo `9007`, leitura ampla) e os gestores cadastrados em `config_master` (perfil **Master**). Os dados cadastrais vêm de planilhas CSV/XLSX (upload no Admin ou fontes numa pasta do servidor monitorada por uma thread); em banco vazio a ferramenta semeia 26 agências fictícias para ser navegável de cara.

Onde roda: um único WAR (`/atlasestilo`) num Tomcat 8.5 Windows x86 (JRE 1.8.0_25) do servidor `super-pf1`, acessado em `https://super-pf1.intranet.bb.com.br/atlasestilo`, com banco SQLite e fotos gravados em `${catalina.base}/dados/atlasestilo/`. Não há dependências externas além do driver `sqlite-jdbc`: o login OAuth2 no SSO do BB é implementado no próprio WAR (`src/br/com/bb/sso`), com o mesmo nome e contrato da classe oficial do Banco, que pode substituí-lo se um dia for preferida.

## 2. Regras inegociáveis

1. **Privacidade Master-only.** Visitas, checklist, anotações, ações e sua linha do tempo, fotos restritas (`foto.restrita = 1`), planejamento, aviso do dia, exports e Admin só saem da API quando `Sessao.master()` é verdadeiro. Os agregados derivados (`visitada`, `planejada`, `pontosAbertos`, `visitadas`, `temFoto` com restritas) são mascarados no servidor (`AgenciaDao`, `MetricaDao`). O front apenas esconde o que já não vem (`html.master` ↔ `.so-master`). Nunca libere nada disso a Moderador ou Colega sem decisão explícita do dono.
2. **Autorização no servidor, sempre.** Todo endpoint revalida o perfil (`ApiServlet.exigir`, `FotoServlet`, gate de `admin.jsp`). "Esconder botão no front não é proteção" (comentário de `AuthFilter`).
3. **Stack fixa.** Java 8 (`javac --release 8`), JSP/Servlets puros (Servlet 3.0, `web.xml`), SQLite via `sqlite-jdbc-3.36.0.3.jar` dentro do WAR, front-end vanilla JS/CSS sem framework, bundler ou transpilação. Sem Spring, JSF, Maven, Gradle, npm, Hibernate, Jackson/Gson — JSON é montado à mão com `util/Json`.
4. **Compatível com o servidor de produção.** Tomcat 8.5 em Windows Server x86 (32 bits) com JRE 1.8.0_25: nada de API além do Java 8, heap pequeno (o teto de leitura de CSV é `min(60 MB, maxMemory/16)`), caminhos Windows, conta de serviço com permissões limitadas.
5. **SSO do BB com o contrato oficial, lido por reflexão.** O primeiro filtro em `/*` é `br.com.bb.sso.filter.FilterOauth2`, que ao autenticar põe um `br.com.bb.sso.bean.Usuario` em `HttpSession["usuario"]`. Desde `58e7e40` filtro e bean são código do projeto (`src/br/com/bb/sso`, sem `json-*.jar`), mas o resto da aplicação **não** os referencia: `Sessao.montar(Object)` continua lendo o bean por reflexão (`getChaveUsuario`, `getNomeUsuario`, `getPrefixo`, `getNomeComissao`…), para que os binários oficiais do BB possam sobrescrevê-los no WAR sem mudar nada. Mantenha essa separação: `br.com.bb.atlasestilo.*` nunca importa `br.com.bb.sso.*`.
6. **Nenhum segredo no repositório.** `terceiros/sso/oauth.properties` (com o `client_secret` real), `dist/atlasestilo.war` (que o embute), `*.class` e `json-*.jar` oficiais estão no `.gitignore` da raiz; o repositório só tem `oauth.properties.exemplo` com placeholder. O valor do `client_secret` **nunca** é escrito em documento, código, exemplo, log ou commit — use `<segredo>`. O segredo já vazou no histórico e precisa de rotação (seção 11.6).
7. **Dados fora da webapp e dentro do Tomcat.** `${catalina.base}/dados/atlasestilo/` (banco, fotos, csv), resolvido por `AppListener.resolver`; nunca dentro da pasta da webapp (some no redeploy) nem em `C:\dados` (a conta de serviço não tem acesso).
8. **Toda escrita HTTP exige o cabeçalho `X-Atlas`** (anti-CSRF em `AuthFilter`) e é recusada para matrícula `SOMENTE_LEITURA`. Formulários HTML com `method=post` não funcionam; só `fetch` via `api()`/`post()`.
9. **Esquema evolutivo e idempotente.** `WEB-INF/sql/schema.sql` roda em toda subida (`CREATE ... IF NOT EXISTS`); coluna nova em tabela que já existe em produção entra em `db/Migracoes.COLUNAS` (e índice sobre ela em `Migracoes.INDICES`); o `SelfTest` precisa continuar verde — ele é portão do `build.sh`.
10. **Desktop-first.** O layout padrão é o de mesa (sidebar 248 px + conteúdo); `@media (max-width: …)` reduz para tablet/celular. Não existe `min-width`.
11. **Identidade visual SUPER PF1.** Tema claro/escuro em `html[data-tema]` com a chave `localStorage['superpf1.tema']` compartilhada com as outras ferramentas, boot anti-flash inline no `header.jspf`, monograma BB, avatar do Humanograma com fallback, páginas `negado.jsp`/`erro.jsp` standalone.
12. **Datas em epoch millis; prazos por dia civil em `America/Sao_Paulo`** (`GestaoDao.inicioDia`). Textos em português do Brasil.

**O que nunca fazer:**

- Nunca escrever o `client_secret` em lugar nenhum; nunca versionar `terceiros/sso/oauth.properties`, `dist/atlasestilo.war`, `*.class` do BB ou `json-*.jar` (nem remover essas regras do `.gitignore`).
- Nunca implantar `dist/atlasestilo-dev.war` em produção (sem `FilterOauth2`, usuário simulado Master) nem um `atlasestilo.war` gerado sem o `terceiros/sso/oauth.properties` real (sobe, mas o filtro recusa o segredo de exemplo `TROQUE_AQUI…` e mostra a página "SSO não configurado").
- Nunca ligar `tls_ignorar_certificado=true` fora de uma medida provisória documentada; nunca registrar o `client_secret` em log (`/sso/diagnostico` e os logs do filtro já o omitem).
- Nunca inverter a ordem dos `<filter-mapping>` (`FilterOauth2` antes de `AuthFilter`) nem renomear/mover os marcadores `SSO-INICIO`/`SSO-FIM` e `DEV-SIMULAR-INICIO`/`DEV-SIMULAR-FIM` do `web.xml` (o `build.sh dev` depende deles).
- Nunca remover o `exigir(resp, s.master())` do início de `ApiServlet.doPost` nem dos `case` de gestão em `doGet`.
- Nunca devolver dados de gestão em rota aberta a Colega/Moderador, nem foto `PESSOA` a Colega, nem foto restrita a não Master.
- Nunca trocar `Http.erro` (que usa `setStatus`) por `sendError`: a `error-page` engoliria o JSON.
- Nunca adicionar coluna só no `CREATE TABLE` de uma tabela já existente em produção; nunca colocar índice sobre coluna migrada no `schema.sql`.
- Nunca editar `sql/schema.sql` esperando efeito: é cópia gerada pelo build de produção a partir de `WebContent/WEB-INF/sql/schema.sql`.
- Nunca interpolar valor da API ou do usuário em `innerHTML` sem `esc()`; nunca criar `fetch` fora de `api()` (ficaria sem `X-Atlas`).
- Nunca introduzir framework, biblioteca JS externa, dependência Maven ou API acima do Java 8.
- Nunca rodar `git add/commit/push`, builds ou Tomcat em tarefas declaradas somente leitura.
- Nunca citar nomes de modelos de IA neste documento ou no código.

## 3. Mapa do repositório

Árvore de `atlas-estilo/` (o `build/` é gerado e ignorado pelo git). Tamanhos aproximados em linhas na data deste documento.

```
atlas-estilo/
├── README-DEPLOY.md                    manual humano: perfis, build, SSO, deploy, funcionalidades (278 l.); aponta para este arquivo
├── CONTEXTO-PARA-IAS.md                este documento
├── build.sh                            compila (--release 8), roda o SelfTest, monta o WAR, injeta SSO se houver, empacota em dist/ (90 l.)
├── lib/
│   └── sqlite-jdbc-3.36.0.3.jar        driver SQLite (Xerial, ~9,7 MB) copiado para WEB-INF/lib; a API de servlet vem de ../lib/javax.servlet-api-3.1.0.jar (raiz do monorepo)
├── sql/
│   └── schema.sql                      CÓPIA DE REFERÊNCIA do schema, regenerada por ./build.sh (prod); ninguém a lê em runtime
├── dist/
│   ├── atlasestilo-dev.war             WAR de teste sem FilterOauth2, atlas.dev.simular=true (versionado; nunca em produção)
│   └── atlasestilo.war                 WAR de produção gerado por ./build.sh; IGNORADO pelo git porque embute o oauth.properties real
├── terceiros/sso/
│   ├── LEIAME.txt                      como criar o oauth.properties, o que é a implementação própria e como (opcionalmente) usar os binários oficiais
│   ├── oauth.properties.exemplo        modelo comentado (client_id=SUPERPF1, client_secret=TROQUE_AQUI…, redirect_uri, login_endpoint, cookie_sso, scopes + chaves opcionais)
│   ├── oauth.properties                (não versionado) cópia do .exemplo com o segredo real; o build o copia para WEB-INF/classes
│   ├── injetar-sso.ps1                 OPCIONAL: troca a implementação própria pelos binários oficiais do BB dentro de um WAR já gerado (PowerShell/.NET)
│   ├── injetar-sso.bat                 invólucro do .ps1 (roteiro manual se o PowerShell falhar)
│   └── injetar-sso.sh                  versão Linux/macOS (unzip/zip)
├── src/test/
│   └── SelfTest.java                   auto-teste de integração sem Tomcat (SQLite temporário, schema real, migrações, exemplo, DAOs, perfis, imports, saneador, fontes, partes puras do SSO); 199 verificações; obrigatório no build
├── src/br/com/bb/sso/                  login OAuth2 próprio, com o nome e o contrato da classe oficial do BB (nada em atlasestilo/* importa este pacote)
│   ├── filter/FilterOauth2.java        1º filtro: authorization code (state, discovery, troca do code, claims), 401 JSON para API, página de erro, /sso/diagnostico (669 l.)
│   ├── bean/Usuario.java               bean na sessão: guarda todas as claims achatadas e resolve getChaveUsuario/getNomeUsuario/getPrefixo/getNomeComissao por listas de claims candidatas (claim.*)
│   └── util/JsonLeve.java              parser JSON mínimo e estrito (substitui o json-*.jar do BB)
├── src/br/com/bb/atlasestilo/
│   ├── core/
│   │   ├── DadosExemplo.java           gerador determinístico (Random(42)) das 26 agências fictícias e tudo que as acompanha
│   │   ├── FonteService.java           fontes CSV/XLSX da pasta do servidor: pasta, resolução de padrão, análise/importação, relatório, conexao/indicadores, sinônimos SIN
│   │   ├── ImportService.java          imports tipados (agencias, funcis, carteiras, pdg, metas): sinônimos de cabeçalho, validação, upsert em transação, modelos CSV
│   │   └── MonitorCsv.java             thread daemon que reimporta fontes automáticas quando o arquivo muda
│   ├── dao/
│   │   ├── AgenciaDao.java             mapa (resumo por UF + pins), cabeçalho da agência, municípios; máscaras para não Master
│   │   ├── ConfigDao.java              masters, flags, import_log, limpeza do exemplo, marca exemplo.limpo
│   │   ├── FonteDao.java               config_parametro, fonte_csv, conexao, indicador_valor, visao_dashboard, visões calculadas
│   │   ├── FotoDao.java                metadados das fotos (o arquivo fica em disco); regras de listagem por perfil
│   │   ├── GestaoDao.java              TODA a gestão do Master: visitas, checklist, anotações, ações, linha do tempo, cadência, verificação, planejamento, aviso do dia, CSV (1121 l.)
│   │   ├── MetricaDao.java             grandes números (resumo) e listas de detalhe (lista) da seleção
│   │   ├── ResultadoDao.java           metas/projeções e histórico de PDG de uma agência
│   │   └── Selecao.java                traduz jurisdição + filtros (uf, municipio, prefixos, regional) em WHERE parametrizado sobre agencia a
│   ├── db/
│   │   ├── Db.java                     abertura do SQLite (WAL), conexão por operação (busy_timeout, foreign_keys)
│   │   └── Migracoes.java              ALTER TABLE ADD COLUMN idempotente (COLUNAS) e índices (INDICES) para bancos já existentes
│   ├── util/
│   │   ├── Csv.java                    leitor CSV antigo; só o SelfTest o usa (produção passa pelo Saneador)
│   │   ├── Http.java                   json/erro/param/paramInt/paramLong/caminho/download
│   │   ├── Json.java                   builders manuais Json.obj()/Json.arr(), str, num
│   │   ├── Saneador.java               leitor tolerante de CSV "do mundo real" (encoding, separador, cabeçalho, números, competências) com relatório de correções
│   │   ├── Texto.java                  esc, normalizar, aparar, vazio, matricula, prefixo, decimal, inteiro, data
│   │   └── Xlsx.java                   leitor mínimo de .xlsx (zip + StAX, sem POI): sharedStrings + primeira planilha; dataDeCelula
│   └── web/
│       ├── ApiServlet.java             todas as rotas /api/* (GET e POST), multipart de fotos e imports (811 l.)
│       ├── AppListener.java            subida: resolve pastas, testa escrita, Db.iniciar, schema, migrações, masters, exemplo, atributos de contexto, MonitorCsv
│       ├── AuthFilter.java             porteiro: normalização de caminho, isenções, X-Atlas, Sessao (cache 60 s), BLOQUEADO/SOMENTE_LEITURA, simulação em dev
│       ├── FotoServlet.java            GET /foto/{id} autenticado, com regras PESSOA/restrita
│       └── Sessao.java                 usuário logado: perfil, jurisdição, flags; montar(Object) por reflexão
└── WebContent/
    ├── index.jsp                       página principal: topo, três vistas (mapa, planejamento, acoes), drawer da agência, dashboard da porta, inputs de foto, toast
    ├── admin.jsp                       tela Admin (gate em scriptlet: só Master): cadência, pasta CSV, fontes, visões, imports, fotos, masters, flags, exemplo, histórico
    ├── negado.jsp                      acesso negado, standalone (session="false")
    ├── erro.jsp                        página de erro 404/500/Throwable; JSON {"erro"} quando a URI contém /api/
    ├── css/atlas.css                   folha única: tokens de tema claro/escuro, shell, cartões, tiles, drawer, cards de ação, admin, responsivo (659 l.)
    ├── js/atlas.js                     toda a lógica da página principal (2291 l.)
    ├── js/admin.js                     toda a lógica do Admin, com cópias próprias dos helpers (751 l.)
    ├── js/br-uf.min.geojson            contorno das 27 UFs (properties.uf, properties.nome), carregado pelo navegador
    └── WEB-INF/
        ├── web.xml                     context-params, filtros (FilterOauth2 → AuthFilter), listener, servlets, multipart, error-pages, sessão 480 min
        ├── jspf/header.jspf            fragmento: script do tema e do menu mobile, sidebar (marca, navegação, tema, chip do usuário), abre div.principal
        └── sql/schema.sql              DDL idempotente das 18 tabelas e índices base — É ESTE que roda no Tomcat e no SelfTest
```

Fora de `atlas-estilo/`, na raiz `/home/user/medal`: `.gitignore` (regras `build/`, `*.class`, `atlas-estilo/terceiros/sso/oauth.properties`, `atlas-estilo/terceiros/sso/*.jar`, `atlas-estilo/WebContent/WEB-INF/classes/oauth.properties`, `atlas-estilo/dist/atlasestilo.war`), `lib/javax.servlet-api-3.1.0.jar` (compartilhado) e a outra ferramenta do monorepo, "apigol", com `build.sh` e `dist/apigol.war` próprios.

## 4. Arquitetura e fluxo de uma requisição

Monólito clássico de servlets: filtros → servlet → DAO estático → SQLite. Não há injeção de dependência, pool, ORM nem camada de serviço além de `core/` (fontes e imports). Classes de `dao/`, `core/`, `util/` e `db/` são `final` com métodos estáticos.

```
navegador (Chrome/Edge da estação BB)
   │  GET /atlasestilo/…            fetch CTX+'/api/…' com 'X-Atlas: 1' (js/atlas.js api())
   ▼
Tomcat 8.5
   │
   ├─ FilterOauth2 (src/br/com/bb/sso, 1º filter-mapping /*; mesmo contrato da classe oficial do BB)
   │     sem "usuario" na sessão: /api/*, /foto/*, X-Atlas → 401 JSON; página → redirect ao
   │     authorize endpoint (state na sessão) → volta com ?code&state → troca por token →
   │     claims do userinfo/id_token → Usuario em HttpSession["usuario"] → volta à página pedida
   │     configuração em WEB-INF/classes/oauth.properties (+ OAUTH_<CHAVE> / -Doauth.<chave>)
   │
   ├─ AuthFilter (2º filter-mapping /*)
   │     UTF-8 → normalizar(caminho) (400 "Caminho inválido.") → isenções (/css/, /js/, negado.jsp, erro.jsp)
   │     → escrita sem X-Atlas = 403 → Sessao do cache "sessao.atlas" (60 s, geracaoPerfis)
   │       ou Sessao.montar(usuario) por reflexão (ou simulada() no WAR dev)
   │     → sem Sessao: /api/* 403 JSON, telas → negado.jsp
   │     → somenteLeitura && escrita = 403 → req.setAttribute("sessao", s)
   │
   ├─ ApiServlet (/api/*)        ├─ FotoServlet (/foto/{id})      ├─ index.jsp / admin.jsp
   │   Sessao.de(req)            │   Sessao.de(req)               │   header.jspf lê "sessao"
   │   Http.caminho(req) → cam[] │   id [0-9a-f]{32}              │   admin.jsp: !master → /?aviso=admin
   │   exigir(master/veTudo)     │   PESSOA→veTudo, restrita→master
   │   DAOs → Json.obj()/arr()   │   stream do arquivo (ATTR_FOTO_DIR)
   │   Http.json / Http.erro     │
   ▼
dao/*  (Selecao.de(s,…) aplica a jurisdição do Colega: a.regional = ?)
   │   Db.conexao() por operação: PRAGMA busy_timeout=5000, foreign_keys=ON; transações manuais
   ▼
SQLite  ${catalina.base}/dados/atlasestilo/atlas.db  (WAL: atlas.db-wal, atlas.db-shm)
```

**Passo a passo de um GET `/api/agencia/1881`:**

1. `FilterOauth2` garante `usuario` na `HttpSession`; sem ele, um pedido `/api/*` recebe 401 `{"erro":"Sessão expirada. Recarregue a página para entrar de novo.","login":true}` e uma página é redirecionada ao login (seção 11.3).
2. `AuthFilter.doFilter`: `caminho = "/api/agencia/1881"`; `normalizar` ok; não é isento; GET não exige `X-Atlas`; pega a `Sessao` do cache ou monta (`Sessao.montar(Object)` → getters `getChaveUsuario`/`getUid`/`getUsername`, `getNomeUsuario`/`getName`/`getDisplayName`/`getNomeGuerra`, `getPrefixo`, `getNomeComissao`/`getCargo` → `Sessao.montar(String,String,String,String)` consulta `config_master`, `usuario_flag`, `agencia.regional`); grava `req.setAttribute("sessao", s)`.
3. `ApiServlet.doGet`: `cam = ["agencia","1881"]`; `case "agencia"`: `Texto.prefixo(cam[1])`, `Selecao.de(s, null, null, prefixo, null)`; `AgenciaDao.cabecalho` (null → 404 "Agência não encontrada na sua jurisdição."); monta o objeto com `MetricaDao.resumo`, `ResultadoDao.pdgHistorico`, `FonteDao.conexaoAgencia(prefixo, s.veTudo())`, `FonteDao.visoesCalculadas`, `FotoDao.listar(prefixo, s.veTudo(), s.master())`; se `veTudo()`, `equipe`/`carteiras`/`metas`; se `master()`, `visitas`/`anotacoes`/`pontos` de `GestaoDao`.
4. `Http.json(resp, json)` → `application/json;charset=UTF-8`, `Cache-Control: no-store`.
5. No front, `abrirAgencia` recebe o JSON, `renderAgencia(d)` monta HTML por strings com `esc()` e religa listeners (ou usa a delegação de `#ag-corpo`).

**Convenções transversais:**

- **JSON à mão** (`util/Json`): `Json.obj().put(...).putNum(...).putRaw(...).fim()`, `Json.arr().add(...).fim()`. `putNum(null)` → literal `null`; `Json.num` imprime inteiros sem casas (`3.0` → `3`), `NaN`/infinito → `null`; `Json.str` escapa `"`, `\`, controles e `<` (como `\u003c`), não `>` nem `&`. Alguns JSONs são compostos por "cirurgia de string" (`substring(0, len-1) + ",\"campo\":" + outro + "}"`): `ApiServlet` casos `regiao` e `admin/pasta`, `ImportService.processar`, `FonteDao.visoesJson`.
- **Erros** sempre como `{"erro": "mensagem."}` via `Http.erro(resp, status, msg)` (usa `setStatus`, não dispara `error-page`). `ApiServlet` captura `NumberFormatException` → 400 "Identificador inválido na rota.", `SQLException` → 500 "Erro interno de banco de dados.", `IllegalStateException` em POST multipart → 413, outra `RuntimeException` → 500 "Erro interno.". `IOException`/`Error` sobem ao container → `erro.jsp` (JSON porque a URI contém `/api/`).
- **Parâmetros**: `Http.param(req, nome, padrao)` apara e trata vazio como ausente; `req.getParameter` é usado de propósito onde `""` tem significado (resumo da visita, `solucao`, `responsavel`).
- **Datas**: epoch millis (`long`) em todas as tabelas e na API; `ApiServlet.lerData` aceita 13 dígitos ou `Texto.data` (`dd/mm/aaaa`, `aaaa-mm-dd` → meia-noite em `America/Sao_Paulo`); o front manda epoch ao meio-dia local (`dataParaEpoch`).
- **Identidade**: matrícula do SSO canonizada por `Texto.matricula` (maiúsculas, sem acento e espaços) em `criado_por`/`atualizado_por`/`incluido_por`; sentinelas `'EXEMPLO'` (gerador), `'ADMIN'` (marca `exemplo.limpo`), `'MONITOR'` (imports do monitor, `FonteService.MATRICULA_MONITOR`).
- **Prefixo** canonizado por `Texto.prefixo`: só dígitos, corta no primeiro `-`, remove zeros à esquerda (`"01881-0"` → `"1881"`, `" 09007 "` → `"9007"`).
- **Páginas JSP** não têm lógica de negócio: `index.jsp` e `admin.jsp` são cascas que incluem `header.jspf`, definem `window.ATLAS_CTX` e carregam o JS; `admin.jsp` faz o gate de Master em scriptlet.

## 5. Perfis, sessão e segurança

### 5.1 Os três perfis (`web/Sessao`)

Perfis cumulativos: MASTER ⊃ MODERADOR ⊃ COLEGA. Decididos em `Sessao.montar(String matricula, String nome, String prefixo, String comissao)`:

| Perfil (`Sessao.perfil`) | Como é decidido | `regionalJurisdicao` | `veTudo()` | `master()` |
|---|---|---|---|---|
| `MASTER` (`PERFIL_MASTER`) | matrícula em `config_master` (qualquer prefixo) | `null` | true | true |
| `MODERADOR` (`PERFIL_MODERADOR`) | não é master e `"9007".equals(prefixo)` (literal no código; prefixo já canonizado por `Texto.prefixo`) | `null` | true | false |
| `COLEGA` (`PERFIL_COLEGA`) | todo o resto | `SELECT regional FROM agencia WHERE prefixo = ?` com o prefixo do usuário, ou `"NÃO MAPEADA"` se o prefixo não está cadastrado | false | false |

`Sessao.moderador()` é verdadeiro para MASTER e MODERADOR; `veTudo()` é alias de `moderador()` e é a porta dos "dados completos" (pessoas, equipe, carteiras com gerente, metas, fotos `PESSOA`, nomes de gerente na Conexão, visões com `perfil_minimo = MODERADOR`). Campos públicos finais: `matricula`, `nome` (cai para a matrícula se o SSO não der nome), `prefixo`, `comissao`, `perfil`, `regionalJurisdicao`, `somenteLeitura`. `Sessao.de(req)` devolve `(Sessao) req.getAttribute("sessao")`.

**Leitura do bean do SSO por reflexão** (`Sessao.montar(Object)`, pacote-privado): `call(alvo, metodo)` faz `getClass().getMethod(m).invoke` e devolve `null` em qualquer exceção; `primeiro(...)` pega o primeiro valor não vazio. Ordem dos getters: matrícula `getChaveUsuario`, `getUid`, `getUsername` (→ `Texto.matricula`); nome `getNomeUsuario`, `getName`, `getDisplayName`, `getNomeGuerra`; prefixo `getPrefixo` (→ `Texto.prefixo`); comissão `getNomeComissao`, `getCargo`. Se o bean deixar de expor esses getters, o sintoma é "Acesso negado"/`negado.jsp` para todos, sem stack trace. O `Usuario` próprio (`src/br/com/bb/sso/bean/Usuario.java`) implementa exatamente esses getters (e também `getUid`, `getUsername`, `getName`, `getDisplayName`, `getCargo`, `getEmail`, `getClaims`) resolvendo-os pela primeira claim presente nas listas `matriculaDe`, `nomeDe`, `prefixoDe`, `comissaoDe`… (ajustáveis por `claim.matricula`, `claim.nome`, `claim.prefixo`, `claim.comissao`, `claim.nomeGuerra`, `claim.email` no `oauth.properties`); `getChaveUsuario` já devolve em maiúsculas e `getPrefixo` corta `"9007.0"` para `"9007"`.

### 5.2 Jurisdição (`dao/Selecao`)

`Selecao.de(Sessao s, String uf, String municipio, String prefixosCsv, String regional)` acumula `conds`/`params`: (1) `a.regional = ?` com `s.regionalJurisdicao` quando não nulo; (2) `a.uf = ?` com `Texto.normalizar(uf)`; (3) `a.municipio = ?` com `municipio.trim()` — comparação **exata**, sensível a acento (o front manda o nome como veio de `/api/municipios`; o `UPPER` do SQLite não cobre acento); (4) `a.regional = ?` com `regional.trim()` (soma-se à jurisdição); (5) `a.prefixo IN (?,…)` com cada item canonizado por `Texto.prefixo`. `where()` devolve as condições unidas por ` AND ` ou `"1=1"`; `aplicar(ps, pos)` faz os `setString` e devolve a próxima posição; `qtdParams()`. Como os DAOs embutem `sel.where()` várias vezes na mesma SQL (`MetricaDao.resumo` repete a subconsulta 12 vezes, intercalando `setLong(agora)`), cada ocorrência exige um `sel.aplicar` na mesma ordem.

Consequências: o Colega só recebe agências (e tudo que deriva delas) da própria regional; `GET /api/agencia/{prefixo}` fora da jurisdição responde 404 "Agência não encontrada na sua jurisdição."; um Colega cujo prefixo não está em `agencia` enxerga justamente as agências com `regional = 'NÃO MAPEADA'` (o DEFAULT da coluna).

### 5.3 Máscaras por perfil (impostas no servidor)

| Onde | Campo | Colega | Moderador | Master |
|---|---|---|---|---|
| `AgenciaDao.mapa` | `visitada`, `planejada` | false | false | real |
| idem | `pontosAbertos` | 0 | 0 | `ponto_melhoria` com `status <> 'RESOLVIDO'` |
| idem | `temFoto` | só `restrita = 0` | idem | inclui restritas (`tem_foto_restrita`) |
| idem, `ufs[]` | `visitadas`, `pontosAbertos`, `semFoto` | derivados dos campos mascarados | idem | reais |
| `AgenciaDao.municipios` | `visitadas` | 0 | 0 | real |
| `MetricaDao.resumo` | `visitadas`, `pontosAbertos` | 0 | 0 | reais |
| `MetricaDao.lista` tipos `funcis`/`gerentes`/`assistentes` | lista inteira | 403 "Seu perfil vê apenas os grandes números." (checado em `ApiServlet` caso `regiao`) | sim | sim |
| `MetricaDao.lista` tipo `carteiras` | `gerenteMatricula`, `gerenteNome` | null | sim | sim |
| `MetricaDao.lista` padrão (agências) | `visitada` | false | false | real |
| `FonteDao.conexaoAgencia(prefixo, vePessoas)` | `carteiras[].gerenteMatricula/gerenteNome` | null | sim | sim |
| `FonteDao.visoesCalculadas` (`perfilAlcanca`) | visão inteira | só `perfil_minimo = 'COLEGA'` | `COLEGA`+`MODERADOR` | todas |
| `FotoDao.listar(prefixo, veTudo, master)` | fotos | sem `tipo = 'PESSOA'` e sem `restrita = 1` | sem `restrita = 1` | todas |
| `FotoServlet` | `/foto/{id}` | 403 em PESSOA e restrita | 403 em restrita | tudo |
| `/api/agencia/{prefixo}` | `equipe`, `carteiras`, `metas` | ausentes | presentes | presentes |
| idem | `visitas`, `anotacoes`, `pontos` | ausentes | **ausentes** | presentes |
| `/api/contexto` | `cadencia` | ausente | ausente | presente |
| `GET /api/planejamento`, `hoje`, `acoes`/`pontos`, `ponto/{id}/atualizacoes`, `export/*`, `admin/*` e **todo POST** | — | 403 "Seu perfil não tem acesso a esta função." | 403 | ok |

### 5.4 Flags de matrícula (`usuario_flag`) e somente leitura

Uma flag por matrícula (PK). `BLOQUEADO` → `Sessao.montar` devolve `null` → `AuthFilter` trata como "sem usuário" (403 JSON em `/api/*`, redirect a `negado.jsp` nas telas). `SOMENTE_LEITURA` → `Sessao.somenteLeitura = true` → `AuthFilter` recusa com 403 "Sua matrícula está em modo somente leitura." qualquer método que não seja GET/HEAD, antes de chegar ao servlet; `/api/contexto` devolve `somenteLeitura: true`, `atlas.js` liga `html.somente-leitura` (CSS esconde os controles de gravação), `post()` e `enviarFotos()` recusam localmente, e o `header.jspf` acrescenta " · somente leitura" ao chip. Administração: `POST /api/admin/flag` (`ConfigDao.flagDefinir`; flag vazia apaga a linha; recusa restringir a própria matrícula e matrícula que é master — `ConfigDao.ehMaster`). Um master que recebeu a flag **antes** de virar master continua `master()`, mas com toda escrita barrada.

### 5.5 Anti-CSRF `X-Atlas`

Em `AuthFilter.doFilter`, `escrita = método != GET && != HEAD`; se `escrita && req.getHeader("X-Atlas") == null` → 403 "Requisição sem o cabeçalho de proteção (X-Atlas)." — **antes** de resolver a sessão, em qualquer caminho não isento, inclusive POST em JSP. O valor não importa; `api()` em `js/atlas.js` e `js/admin.js` manda `'X-Atlas': '1'` em toda chamada (GET inclusive). Links de export são `<a href="api/export/…">` (GET) e por isso dispensam o cabeçalho. Testes com `curl` precisam de `-H 'X-Atlas: 1'`.

### 5.6 Cache da sessão e `invalidarPerfis`

`AuthFilter.CACHE_MS = 60_000L`. A `Sessao` pronta fica na `HttpSession` sob `"sessao.atlas"` como `Object[]{Sessao, Long momento, Long geracaoPerfis}` e é reaproveitada enquanto `agora - momento < CACHE_MS` **e** `geracao == geracaoPerfis` (`static volatile long`). `AuthFilter.invalidarPerfis()` incrementa `geracaoPerfis`; `ApiServlet.postAdmin` chama nos casos `master` e `flag`, por isso incluir/remover master e definir flag valem na requisição seguinte. Mudanças que não chamam (ex.: import de agências que troca a `regional` do prefixo de um Colega) valem em até 60 s. A `HttpSession` dura 480 min (`session-timeout`) e guarda `usuario` (SSO), `sessao.atlas` (cache) e `dev.perfil` (dev).

### 5.7 Normalização de caminho e isenções

`AuthFilter.normalizar(String)` (estático, público, coberto pelo `SelfTest`): devolve `null` se a string contém `;` ou `%`; se não há `/.` nem `//`, devolve como está; senão resolve `.`/`..` segmento a segmento (`..` que sai da raiz → `null`) preservando a barra final. Em `doFilter`, a requisição é recusada com 400 `{"erro":"Caminho inválido."}` se `normalizar(requestURI sem contextPath) == null` **ou** se `caminho = getServletPath() + getPathInfo()` contém `;` ou `%`. Isso rejeita também `;jsessionid=` (URL rewriting) e percent-encoding no caminho — valores com acento/espaço vão na query string (`encodeURIComponent` no front). Exemplos do `SelfTest`: `/css/../api/mapa` → `/api/mapa`; `/../x` → null; `/css/..;/api/x` → null; `/css/%2e%2e/api/x` → null.

Isentos de sessão e de CSRF: `caminho` igual a `/negado.jsp` ou `/erro.jsp`, ou começando com `/css/` ou `/js/` (os não-JSP recebem `Cache-Control: no-cache`, para ninguém ficar com JS antigo após um deploy). Em produção eles ainda passam pelo `FilterOauth2` (também em `/*`). Os filtros não declaram `<dispatcher>`: forwards e `error-page` não passam de novo por eles, por isso `erro.jsp`/`negado.jsp` são standalone.

### 5.8 Fotos restritas e de pessoas

`foto.restrita = 1` (fotos de visita e de ação; `FotoDao.inserir` força `restrita = restrita || pontoId != null`) só é listada e servida a Master; `foto.tipo = 'PESSOA'` exige `veTudo()` (LGPD). `FotoServlet.doGet`: `Sessao.de(req)` null → 403 "Acesso negado."; id fora de `[0-9a-f]{32}` → 404 "Foto não encontrada."; `FotoDao.obter(id)` → `[arquivo, mime, tipo, restrita]`; `PESSOA && !veTudo()` → 403 "Seu perfil não visualiza fotos de pessoas."; `"1".equals(restrita) && !master()` → 403 "Foto restrita à gestão."; caminho canônico fora de `ATTR_FOTO_DIR` ou não arquivo → 404 "Arquivo da foto ausente."; resposta com `Content-Type` do mime (ou `image/jpeg`), `Content-Length` e `Cache-Control: private, max-age=86400`.

### 5.9 Admin

`admin.jsp` (scriptlet no topo): `sessao == null` → `forward("/negado.jsp")`; `!s.master()` → `sendRedirect(ctx + "/?aviso=admin")` (o `atlas.js`, em `iniciar`, mostra o toast "A tela Admin é exclusiva dos Masters." e limpa a URL com `history.replaceState`). `header.jspf` só imprime os links "Minha gestão", "Ações" e "Admin" `if (sessao.master())`. `GET /api/admin/*` (`doGetAdmin`) e todo `POST /api/*` exigem `master()` antes de qualquer sub-rota — um Moderador recebe 403 mesmo para rota POST inexistente.

### 5.10 Simulação de perfil em desenvolvimento

Só no WAR dev (`atlas.dev.simular=true`, sem `FilterOauth2`). `AuthFilter.init` lê `ServletContext.getAttribute(AppListener.ATTR_DEV_SIMULAR)`; sem `usuario` na sessão, `doFilter` chama `AuthFilter.simulada(req)`: o parâmetro `?perfil=` (maiusculizado) é gravado em `HttpSession["dev.perfil"]` e o cache `sessao.atlas` é descartado; `COLEGA` → `Sessao.montar("F0000002", "Colega (simulado)", "9101", "DEV")` (jurisdição `ESTILO SP CAPITAL` com os dados de exemplo); `MODERADOR` → `Sessao.montar("F0000001", "Moderador (simulado)", "9007", "DEV")`; qualquer outro valor ou nenhum → `Sessao.montar("F3548926", "Desenvolvedor (simulado)", "9007", "DEV")`, Master porque `F3548926` está na carga inicial de `config_master` (removido no Admin, vira Moderador). `simulada()` só roda quando não há cache válido: `?perfil=X` é ignorado dentro da janela de 60 s do perfil anterior.

## 6. Modelo de dados

### 6.1 Convenções

Um único arquivo SQLite (`atlas.db`, `atlas.db.path`) fora da webapp. Sem pool nem ORM: `Db.conexao()` por operação em `try-with-resources`; transações manuais (`setAutoCommit(false)` … `commit()`/`rollback()`). `Db.iniciar(caminho)` carrega `org.sqlite.JDBC` (ausente → `IllegalStateException("Driver SQLite ausente do WAR (WEB-INF/lib).")`), monta `jdbc:sqlite:` + caminho e executa `PRAGMA journal_mode=WAL` (persistente: cria `atlas.db-wal` e `atlas.db-shm`); `Db.conexao()` executa em cada conexão `PRAGMA busy_timeout=5000` (espera até 5 s por lock) e `PRAGMA foreign_keys=ON` (inócuo: **nenhuma tabela declara `FOREIGN KEY`**; as cascatas são manuais, ver 6.6). O comentário de `Db` registra a intenção de troca futura por DB2: mexer ali, no dialeto do schema e nos `ON CONFLICT`.

Regras que valem para todas as tabelas: datas são epoch em milissegundos em `INTEGER`; booleanos são `INTEGER` 0/1; identidade = matrícula canônica; prefixo canônico; textos livres aparados por `Texto.aparar(s, max)`; upserts com `INSERT ... ON CONFLICT(chave) DO UPDATE SET col = excluded.col` (SQLite ≥ 3.24).

### 6.2 Tabelas

Ordem do `WebContent/WEB-INF/sql/schema.sql`. **(M)** marca colunas que não estão no `schema.sql` e são criadas por `Migracoes.COLUNAS`.

**`agencia`** — cadastro (chave de quase tudo). Índices `idx_agencia_uf (uf)`, `idx_agencia_regional (regional)`.

| Coluna | Tipo | Significado |
|---|---|---|
| `prefixo` | TEXT PK | código canônico |
| `nome` | TEXT NOT NULL | |
| `segmento` | TEXT NOT NULL DEFAULT 'ESTILO' | o import não preenche; o exemplo grava `'ESTILO'` |
| `uf` | TEXT | 2 letras maiúsculas (validado em `ImportService.validar`); pode ser NULL |
| `municipio` | TEXT | nome como veio do CSV (comparação exata em `Selecao`) |
| `endereco`, `cep` | TEXT | só exibição |
| `lat`, `lng` | REAL | pin no mapa; NULL = sem pin (ainda conta na UF) |
| `regional` | TEXT NOT NULL DEFAULT 'NÃO MAPEADA' | **é a hierarquia prefixo → regional do projeto** e a jurisdição do Colega |
| `super_regional` | TEXT | informativo (`superRegional` no cabeçalho) |
| `gmaps_url` | TEXT | embed do Maps/Street View colado pelo Master (`POST /api/agencia/{prefixo}/gmaps`, até 1000 chars, `https://`) ou do import |
| `origem` | TEXT NOT NULL DEFAULT 'IMPORT' | `IMPORT` \| `EXEMPLO` |
| `atualizado_em` | INTEGER | |

**`funci`** — funcionários. Índice `idx_funci_prefixo (prefixo)`. Colunas: `matricula` TEXT PK, `nome` NOT NULL, `prefixo` NOT NULL (lotação), `cargo`, `funcao`, `tipo` NOT NULL DEFAULT 'OUTRO' (`GERENTE` \| `ASSISTENTE` \| `OUTRO`; o import deriva de cargo+função quando vazio), `carteira` (código que o gerente atende), `posse_cargo`, `posse_funcao` (epoch; base dos tempos médios em meses de `MetricaDao`), `origem` (`IMPORT` \| `EXEMPLO`), `atualizado_em`. Dados de pessoas só para `veTudo()`.

**`carteira`** — `id` INTEGER PK AUTOINCREMENT, `prefixo` NOT NULL, `codigo` NOT NULL, `nome`, `tipo`, `gerente_matricula`, `qtd_clientes` NOT NULL DEFAULT 0, `origem`; `UNIQUE (prefixo, codigo)`; índice `idx_carteira_prefixo`. Upsert `ON CONFLICT(prefixo,codigo)`.

**`pdg`** — `prefixo`, `semestre` (`AAAA-N`, normalizado por `ImportService.normalizarSemestre`: "2026/1", "20261", "1º SEM 2026" → `2026-1`), `atingiu` INTEGER NOT NULL DEFAULT 0, `pontuacao` REAL, `origem`; PK `(prefixo, semestre)`.

**`meta`** — `prefixo`, `periodo` (`AAAA-N` quando reconhecido), `indicador` (texto livre), `meta`, `realizado`, `projecao` REAL, `origem`, `atualizado_em`; PK `(prefixo, periodo, indicador)`; índice `idx_meta_prefixo`. Só para `veTudo()` (`ResultadoDao.metas`).

**`visita`** — índice `idx_visita_prefixo`. Colunas base: `id` PK AUTOINCREMENT, `prefixo` NOT NULL, `status` NOT NULL DEFAULT 'PLANEJADA' (`PLANEJADA` \| `REALIZADA` \| `CANCELADA`), `data_planejada`, `data_realizada`, `resumo` (4000; `null` mantém, `""` limpa), `criado_por`, `criado_em`, `atualizado_em`. (M) checklist: `ambiencia`, `atendimento`, `organizacao`, `equipe` INTEGER 1..5; `movimento` TEXT (`VAZIA` \| `NORMAL` \| `CHEIA`); `claros` INTEGER 0..99; `nota_geral` REAL 0..10; `melhorias` TEXT (itens separados por `|`, 2000); `percepcao` TEXT (8000); `checklist_em` INTEGER (só muda quando algum campo do checklist veio).

**`anotacao`** — `id`, `prefixo` (**NULL = anotação geral** do planejamento), `texto` NOT NULL (8000), `fixada` 0/1, `excluida` 0/1 (**soft delete**; listagens filtram `excluida = 0`), `criado_por`, `criado_em`, `atualizado_em`; índice `idx_anotacao_prefixo`.

**`ponto_melhoria`** — uma "ação" (nome histórico da tabela). Índices `idx_ponto_prefixo` (schema), `idx_ponto_status (status, previsao)` e `idx_ponto_visita (visita_id)` (migração).

| Coluna | Origem | Significado |
|---|---|---|
| `id`, `prefixo`, `descricao` (4000) | schema | |
| `status` NOT NULL DEFAULT 'ABERTO' | schema | `ABERTO` \| `EM_TRATATIVA` \| `AGUARDANDO_VERIFICACAO` (`GestaoDao.ST_AGUARDANDO`) \| `RESOLVIDO` |
| `solucao` (4000), `previsao` (epoch), `resolvido_em` | schema | prazo e conclusão; reabrir zera `resolvido_em` |
| `criado_por`, `criado_em`, `atualizado_em` | schema | |
| `visita_id` | (M) | visita de origem (NULL = solta) |
| `responsavel` (200) | (M) | dono, texto livre |
| `prioridade` NOT NULL DEFAULT 'MEDIA' | (M) | `ALTA` \| `MEDIA` \| `BAIXA` (`GestaoDao.prioridadeValida`) |
| `tipo` NOT NULL DEFAULT 'ACAO' | (M) | só `'ACAO'` hoje |
| `proxima_cobranca_em` | (M) | marcada pelo "cobrei" |
| `informado_em` | (M) | quando o responsável "informou que fez" |
| `verificado_em`, `verificado_visita_id` | (M) | conferência in loco confirmada |
| `reaberturas` NOT NULL DEFAULT 0 | (M) | incrementado quando a conferência diz "não estava feito" |

**`acao_atualizacao`** — linha do tempo: `id`, `ponto_id` NOT NULL, `texto` (4000), `status_novo`, `criado_por`, `criado_em`; (M) `tipo` NOT NULL DEFAULT 'RETORNO' (`RETORNO` \| `COBRANCA` \| `STATUS` \| `VERIFICACAO`, `GestaoDao.tipoAtualizacaoValido`). Índices `idx_acao_atu_ponto` (schema), `idx_acao_atu_tipo (ponto_id, tipo)` (migração).

**`foto`** — metadados; o arquivo fica em `atlas.foto.dir`. Índices `idx_foto_prefixo` (schema), `idx_foto_visita`, `idx_foto_ponto` (migração). Colunas: `id` TEXT PK (UUID sem hífens, 32 hex — nome físico = `id` + `.jpg/.png/.gif/.webp`), `prefixo` NOT NULL, `matricula` (foto `PESSOA`), `tipo` NOT NULL DEFAULT 'INTERNA' (`FACHADA` \| `INTERNA` \| `PESSOA` \| `OUTRA` no Admin; `VISITA`; `ACAO`), `legenda` (500), `arquivo` NOT NULL, `mime`, `origem` NOT NULL DEFAULT 'ADMIN' (`ADMIN` \| `VISITA` \| `ACAO`, derivada em `FotoDao.inserir`; `INTRANET` reservado no README), `criado_por`, `criado_em`; (M) `visita_id`, `restrita` NOT NULL DEFAULT 0, `ponto_id`, `momento` (`ANTES` \| `DEPOIS`, só com `ponto_id`).

**`config_master`** — `matricula` TEXT PK, `incluido_por`, `criado_em` (NULL na carga inicial). Lida em `Sessao.montar` e `ConfigDao.ehMaster`.

**`usuario_flag`** — `matricula` TEXT PK, `flag` NOT NULL (`SOMENTE_LEITURA` \| `BLOQUEADO`), `criado_por`, `criado_em`.

**`import_log`** — `id`, `tipo` NOT NULL (`agencias` \| `funcis` \| `carteiras` \| `pdg` \| `metas` \| `conexao` \| `indicadores`), `arquivo` (300), `inseridos`, `atualizados`, `ignorados` NOT NULL DEFAULT 0, `criado_por` (matrícula ou `'MONITOR'`), `criado_em`. Gravada por `ConfigDao.importLog`; `ConfigDao.importLogs()` devolve os 50 últimos.

**`config_parametro`** — `chave` TEXT PK, `valor` TEXT, `atualizado_por`, `atualizado_em`. API `FonteDao.param(chave, padrao)` e `FonteDao.paramDefinir(chave, valor, por, agora)`. Chaves na seção 6.3.

**`fonte_csv`** — `id`, `nome` NOT NULL (120), `tipo` NOT NULL (`FonteDao.TIPOS`: `agencias`, `funcis`, `carteiras`, `pdg`, `metas`, `conexao`, `indicadores`), `arquivo` NOT NULL (nome ou padrão `*`/`?` dentro da pasta, sem `/`, `\`, `..`; vale o mais recente), `ativo` DEFAULT 1, `automatico` DEFAULT 1 (0 = o monitor não reimporta sozinho), `mapeamento` (de-para `campo=CABECALHO;campo2=OUTRO`), `ultima_leitura_em`, `ultimo_arquivo`, `ultimo_mtime`, `ultimo_status` (`OK` \| `AVISOS` \| `ERRO` \| `SEM ARQUIVO`, com espaço), `ultimo_resumo` (500), `ultimo_relatorio` (JSON), `ultimo_rejeitadas` (CSV `linha;motivo;conteudo`), `criado_por`, `criado_em`.

**`conexao`** — `prefixo`, `competencia` (`AAAA-MM`), `carteira` NOT NULL DEFAULT '' (**`''` = nota da agência**; outro valor = carteira/gerente), `gerente_matricula`, `gerente_nome`, `pontos` REAL NOT NULL (0–1000; faixas em `FonteDao.faixaConexao`: ≥ 900 `excelencia`, ≥ 750 `forte`, ≥ 600 `atencao`, senão `critico`), `origem` (`IMPORT` \| `EXEMPLO` \| `DERIVADO` = média das carteiras quando a agência não tem linha própria), `atualizado_em`; PK `(prefixo, competencia, carteira)`; índice `idx_conexao_comp`.

**`indicador_valor`** — `fonte_id`, `prefixo`, `competencia` NOT NULL DEFAULT '' (`''` = foto atual sem histórico), `coluna` (nome normalizado por `ImportService.chaveColuna`), `valor` REAL; PK `(fonte_id, prefixo, competencia, coluna)`; índice `idx_indicador_col (fonte_id, coluna, competencia)`.

**`visao_dashboard`** — `id`, `titulo` (80), `fonte_id`, `coluna`, `agregacao` DEFAULT 'MEDIA' (`MEDIA` \| `SOMA` \| `MIN` \| `MAX` → `AVG/SUM/MIN/MAX` em `FonteDao.agg`), `formato` DEFAULT 'INTEIRO' (`INTEIRO` \| `DECIMAL` \| `PERCENTUAL` \| `MOEDA`), `casas` 0..4, `meta` REAL, `coluna_meta`, `melhor` DEFAULT 'MAIOR' (`MAIOR` \| `MENOR`), `minimo`, `maximo` (faixa plausível → `FonteDao.foraDaFaixa`), `perfil_minimo` DEFAULT 'COLEGA' (`COLEGA` \| `MODERADOR` \| `MASTER`), `ordem`, `ativo`, `criado_por`, `criado_em`.

### 6.3 Parâmetros em `config_parametro`

| Chave (case-sensitive) | Padrão quando ausente | Quem lê | Quem grava |
|---|---|---|---|
| `csv.pasta` (`FonteDao.P_PASTA`) | `atlas.csv.dir` resolvido pelo `AppListener` (`FonteService.definirPastaPadrao`) | `FonteService.pasta()` | `POST /api/admin/pasta` (`pasta`, caminho absoluto) — **prevalece sobre o web.xml** |
| `csv.monitor.minutos` (`FonteDao.P_MINUTOS`) | `"10"` | `MonitorCsv.tique`/`estado`, `FonteService.varrerPasta` (`Integer.parseInt` direto: valor não numérico derruba o tique e faz `/api/admin/pasta` responder 500) | `POST /api/admin/pasta` (`minutos`, 0..1440; 0 desliga) |
| `csv.estrito` (`FonteDao.P_ESTRITO`) | `"0"` | `FonteService.processarFonte` | `POST /api/admin/pasta` (`estrito` `"1"`/`"0"`) |
| `cadencia.ALTA`, `cadencia.MEDIA`, `cadencia.BAIXA` | 7, 15, 30 | `GestaoDao.cadencia()` (`intParam` → `limitar` 1..365) | `GestaoDao.cadenciaDefinir` via `POST /api/admin/cadencia` |
| `cadencia.parada` | 14 | idem | idem |
| `exemplo.limpo` | ausente | `ConfigDao.exemploLimpo()` no boot | `ConfigDao.limparExemplo()` grava `'1'` com `atualizado_por = 'ADMIN'` |

### 6.4 Domínios de valores (exatos)

`agencia/funci/carteira/pdg/meta.origem`: `IMPORT`, `EXEMPLO` · `conexao.origem`: + `DERIVADO` · `funci.tipo`: `GERENTE`, `ASSISTENTE`, `OUTRO` · `visita.status`: `PLANEJADA`, `REALIZADA`, `CANCELADA` · `visita.movimento`: `VAZIA`, `NORMAL`, `CHEIA` · `ponto_melhoria.status`: `ABERTO`, `EM_TRATATIVA`, `AGUARDANDO_VERIFICACAO`, `RESOLVIDO` · `ponto_melhoria.prioridade`: `ALTA`, `MEDIA`, `BAIXA` · `ponto_melhoria.tipo`: `ACAO` · `acao_atualizacao.tipo`: `RETORNO`, `COBRANCA`, `STATUS`, `VERIFICACAO` · `foto.tipo`: `FACHADA`, `INTERNA`, `PESSOA`, `OUTRA`, `VISITA`, `ACAO` · `foto.origem`: `ADMIN`, `VISITA`, `ACAO` (+ `INTRANET` reservado) · `foto.momento`: `ANTES`, `DEPOIS`, NULL · `usuario_flag.flag`: `SOMENTE_LEITURA`, `BLOQUEADO` · `import_log.tipo`/`fonte_csv.tipo`: minúsculas, os 7 de `FonteDao.TIPOS` · `fonte_csv.ultimo_status`: `OK`, `AVISOS`, `ERRO`, `SEM ARQUIVO` · `visao_dashboard`: `MEDIA|SOMA|MIN|MAX`, `INTEIRO|DECIMAL|PERCENTUAL|MOEDA`, `MAIOR|MENOR`, `COLEGA|MODERADOR|MASTER` · sentinelas em `criado_por`/`atualizado_por`: `EXEMPLO`, `ADMIN`, `MONITOR`.

### 6.5 `schema.sql`, `Migracoes` e a ordem do boot

`AppListener.executarSchema` abre `/WEB-INF/sql/schema.sql` do WAR e chama `AppListener.executarSql(InputStream)` (público, usado também pelo `SelfTest`): lê linha a linha em UTF-8, ignora linhas vazias e linhas cujo `trim()` começa com `--`, acumula e executa com `Statement.execute` quando a linha aparada **termina em `;`**; os comandos rodam um a um, sem transação — por isso tudo é `IF NOT EXISTS`. `WebContent/WEB-INF/sql/schema.sql` e `sql/schema.sql` são idênticos hoje; só o primeiro é lido (Tomcat e `SelfTest`); o segundo é regenerado por `build.sh` em modo produção.

`db/Migracoes.aplicar()`: percorre `COLUNAS` (`String[][]` de triplas `{tabela, coluna, definição}`, 24 entradas: 10 em `visita`, 4 em `foto`, 9 em `ponto_melhoria`, 1 em `acao_atualizacao`); quando a tabela muda em relação à entrada anterior recarrega `colunasDe(st, tabela)` via `PRAGMA table_info` (nomes em minúsculas); pula a coluna se já existe, senão `ALTER TABLE t ADD COLUMN c definição`; depois executa cada string de `INDICES` (`CREATE INDEX IF NOT EXISTS idx_foto_visita ON foto(visita_id)`, `idx_foto_ponto ON foto(ponto_id)`, `idx_ponto_status ON ponto_melhoria(status, previsao)`, `idx_ponto_visita ON ponto_melhoria(visita_id)`, `idx_acao_atu_tipo ON acao_atualizacao(ponto_id, tipo)`). Idempotente; o `SelfTest` a executa duas vezes. **Convenção do projeto** (comentário acima de `acao_atualizacao` no `schema.sql` e cabeçalho de `Migracoes`): coluna nova em tabela que já existe em produção entra **só** em `Migracoes.COLUNAS` — o `CREATE TABLE` do `schema.sql` não tem as colunas migradas. Colocar nos dois não quebra (a migração pula), mas duplica a fonte da verdade. SQLite não aceita `ADD COLUMN` com `NOT NULL` sem `DEFAULT` constante, nem `PRIMARY KEY`/`UNIQUE`; escreva nomes em minúsculas.

Ordem de boot (`AppListener.contextInitialized`): `baseTomcat()` (`catalina.base` → `catalina.home` → `user.dir`) → `resolver` dos três caminhos (expande `${catalina.base}`/`${catalina.home}`, ancora relativos na base; ausentes → `base/dados/atlasestilo/{atlas.db,fotos,csv}`) → `criarPastaComEscrita` para a pasta do `.db` e a de fotos (`mkdirs` + `File.createTempFile("escrita", ".tmp", dir)`; falha → `IllegalStateException` com a dica `icacls "<dir>" /grant "NETWORK SERVICE":(OI)(CI)M /T`; a pasta de CSV só gera `ctx.log`) → `FonteService.definirPastaPadrao(csvDir)` → `Db.iniciar(dbPath)` → `executarSchema` → `Migracoes.aplicar()` → `ConfigDao.semearMastersSeVazio()` (`SQLException` → "Falha na migração/carga inicial.") → semeia `DadosExemplo.semear()` **somente se** `atlas.dados.exemplo` = `true` **e** `bancoVazio()` (`COUNT(*) FROM agencia == 0`; `SQLException` → false) **e** `!ConfigDao.exemploLimpo()` → publica `ATTR_FOTO_DIR` (`"atlas.foto.dir.resolvido"`), `ATTR_MAPS_ATIVO` (`!"false".equalsIgnoreCase(...)`, ausente = ativo) e `ATTR_DEV_SIMULAR` (`"true".equalsIgnoreCase(...)`) → `MonitorCsv.iniciar()` se `atlas.csv.monitor` ≠ `false` → `ctx.log("[atlasestilo] Iniciado. db=… fotos=… csv=…")`. Qualquer `IllegalStateException` aborta a subida (404 em tudo; motivo em `catalina.*.log`/`localhost.*.log`). `contextDestroyed` → `MonitorCsv.parar()` (`shutdownNow` + `awaitTermination(60 s)`).

`ConfigDao.semearMastersSeVazio()`: só com `config_master` vazia insere `F3548926`, `F3191837`, `F6323371` (`INSERT OR IGNORE`, sem `incluido_por`/`criado_em`); reexecutar o schema nunca ressuscita um master removido (coberto pelo `SelfTest`). `ConfigDao.masterRemover` recusa se `COUNT(*) <= 1`.

### 6.6 Cascatas manuais (não há FK)

- `GestaoDao.pontoExcluir`: `DELETE FROM acao_atualizacao WHERE ponto_id = ?` e a ação, em transação; antes, `ApiServlet.postPonto` apaga as fotos (`FotoDao.excluirDaAcao` + `File.delete`).
- `GestaoDao.visitaExcluir`: `UPDATE ponto_melhoria SET visita_id = NULL` e `SET verificado_visita_id = NULL` para a visita, depois `DELETE FROM visita`, em transação; as ações ficam soltas. As fotos são apagadas antes e fora da transação (`FotoDao.excluirDaVisita`).
- `FonteDao.fonteExcluir`: apaga `indicador_valor` e `visao_dashboard` da fonte (não toca em `conexao`, que não tem `fonte_id`).
- `ConfigDao.limparExemplo()`: transação única — coleta `foto.arquivo` das fotos de agências/ações de exemplo; apaga `funci`, `carteira`, `pdg`, `meta`, `conexao` por `origem = 'EXEMPLO'`; `visita` e `anotacao` por `criado_por = 'EXEMPLO'` **ou** prefixo de agência EXEMPLO; `acao_atualizacao` e `foto` por `ponto_id` de ação de exemplo; `ponto_melhoria` por `criado_por = 'EXEMPLO'` ou prefixo EXEMPLO; `foto` por prefixo EXEMPLO; `agencia` por `origem = 'EXEMPLO'`; grava `exemplo.limpo = '1'`; devolve a lista de arquivos que `ApiServlet` apaga de `dirFotos()`.
- Nada remove `funci`/`carteira`/`pdg`/`meta`/`conexao` quando uma agência some: registros órfãos só deixam de aparecer nos joins com `agencia`.

### 6.7 Exemplo de leitura

```sql
-- ações abertas, com a última cobrança e quantas fotos do depois existem
SELECT p.id, p.prefixo, ag.nome, p.status, p.prioridade, p.previsao,
       (SELECT MAX(criado_em) FROM acao_atualizacao u WHERE u.ponto_id = p.id AND u.tipo = 'COBRANCA') AS ultima_cobranca,
       (SELECT COUNT(*) FROM foto f WHERE f.ponto_id = p.id AND f.momento = 'DEPOIS') AS fotos_depois
FROM ponto_melhoria p JOIN agencia ag ON ag.prefixo = p.prefixo
WHERE p.status <> 'RESOLVIDO'
ORDER BY CASE p.prioridade WHEN 'ALTA' THEN 0 WHEN 'MEDIA' THEN 1 ELSE 2 END, COALESCE(p.previsao, 9e15);
```

É a forma de `GestaoDao.SQL_ACOES_BASE` (que também traz `ultimo_retorno` com `tipo IN ('RETORNO','VERIFICACAO')`, `cobrancas`, `fotos_antes`, `atualizacoes`, `ultima_atualizacao`).

## 7. Domínio funcional

### 7.1 Agências, mapa e jurisdição

- **Hierarquia prefixo → regional**: não há tabela de de-para; a regional é `agencia.regional`, carregada pelo import `agencias` (sinônimos `REGIONAL`, `SUPER REGIONAL PF`, `GERENCIA REGIONAL` em `ImportService.SINONIMOS`; vazia → `'NÃO MAPEADA'`), preservada quando a coluna não vem no arquivo, semeada pelo exemplo com `ESTILO SP CAPITAL`, `ESTILO SP INTERIOR`, `ESTILO RIO`, `ESTILO MINAS`, `ESTILO SUL`, `ESTILO NORDESTE`, `ESTILO CENTRO-OESTE`, `ESTILO NORTE` (e `super_regional = 'DIRETORIA ESTILO'`). A skill da SUPER PF1 fala em 141 prefixos em 8 regionais; o projeto **não** carrega essa lista — o Master importa o CSV real de agências.
- **`GET /api/mapa`** → `AgenciaDao.mapa(Sessao)`: uma SQL sobre `agencia a WHERE sel.where() ORDER BY a.uf, a.municipio, a.nome` com subconsultas em `visita`, `foto`, `ponto_melhoria`, `funci`; devolve `{ufs:[{uf, agencias, funcis, visitadas, semFoto, pontosAbertos}], agencias:[{prefixo, nome, uf, municipio, lat, lng, regional, visitada, planejada, temFoto, pontosAbertos, funcis}]}` com as máscaras da seção 5.3. `ufs` é acumulado num `java.util.TreeMap` — uma agência com `uf` NULL provoca `NullPointerException` (500 para todos); garanta UF no import. **`GET /api/municipios?uf=`** → `AgenciaDao.municipios` (`GROUP BY a.municipio ORDER BY agencias DESC`).
- **Front**: o contorno das UFs é `js/br-uf.min.geojson`; `criarProjecao` (equirretangular com `cos` da latitude média, encaixada em 1000×1000), `desenharMapa` (extrusão + faces, classes `tem-agencia`/`sem-agencia`), `focarUf` (anima o `viewBox` para o bbox + 18 %, `desenharPins`, `carregarPainel({uf})`), `selecionarMunicipio`, `voltarBrasil` (Esc), `espalharPins` (roseta para pins coincidentes), busca 100 % no cliente (`ligarBusca`, tecla `/`). Pins só para agências com `lat`/`lng`.
- **Cabeçalho da agência** (`AgenciaDao.cabecalho`): `prefixo, nome, segmento, uf, municipio, endereco, cep, lat, lng, regional, superRegional, gmapsUrl` — todo perfil, dentro da jurisdição. Fachada no front (`fachadaHtml`): foto `FACHADA` → `gmapsUrl` (iframe) → embed por lat/lng se `App.contexto.mapsAtivo` → "Sem imagem".

### 7.2 Métricas, fontes CSV, Saneador, visões, indicadores e Conexão

**Grandes números** — `MetricaDao.resumo(s, sel, agora)`: `agencias`, `funcis`, `gerentes` (`funci.tipo = 'GERENTE'`), `assistentes`, `carteiras`, `mediaFuncisPorAgencia`, `tempoMedioCargoMeses` e `tempoMedioFuncaoMeses` (`AVG(agora − posse)` ÷ 2 629 800 000 ms, 1 casa, `null` sem datas), `pdgGanhos` (`SUM(atingiu)`), `pdgAgencias`, `pdgSemestres`, `visitadas`/`pontosAbertos` (Master). `MetricaDao.lista(s, sel, tipo, agora)` (todas `LIMIT 500`): `funcis`/`gerentes`/`assistentes` (`matricula, nome, prefixo, agencia, cargo, funcao, tipo, carteira, mesesCargo, mesesFuncao`), `carteiras` (`prefixo, agencia, codigo, nome, tipo, qtdClientes, gerenteMatricula, gerenteNome`), `pdg` (`prefixo, agencia, semestres, ganhos`), padrão = agências (`prefixo, nome, uf, municipio, regional, funcis, gerentes, assistentes, carteiras, pdgGanhos, visitada`). `tipo=conexao` desvia para `FonteDao.conexaoLista`. `ResultadoDao.metas(prefixo)` (`periodo, indicador, meta, realizado, projecao`) e `ResultadoDao.pdgHistorico(prefixo)` (`semestre, atingiu, pontuacao`).

**Fontes CSV/XLSX na pasta do servidor** — tabela `fonte_csv`; pasta = `csv.pasta` com fallback em `atlas.csv.dir`; `FonteService.resolverArquivo(dir, padrao)` aceita nome exato ou padrão `*`/`?` (case-insensitive), recusa `/`, `\`, `..`, escolhe o mais recente por `lastModified` e não restringe extensão (só `.xlsx` vai para `Xlsx.ler`). `FonteService.analisarFonte(id, confirmar, matricula, agora)`: sem arquivo → status `SEM ARQUIVO`; acima do teto `min(60 MB, maxMemory/16)` → `ERRO`; senão `processarFonte(f, nome, bytes, mtime, confirmar, matricula, agora)`: `ImportService.lerTabela` (Saneador), relatório com `fonte, arquivo, tamanho, modificadoEm, encoding, separador, linhaCabecalho, estrito, cabecalho[]`; tipos `conexao`/`indicadores` tratados em `lerConexao`/`lerIndicadores` (bloqueio em modo estrito quando `totalRejeitadas > 0 || celulasInvalidas > 0`; gravação em transação via `FonteDao.conexaoGravar`/`indicadoresGravar` + `ConfigDao.importLog`); tipos tipados fazem dry-run `ImportService.processarTabela(..., confirmar=false)` e, se permitido, rodam de novo com `confirmar=true`. `fechar(...)` define `status` (`ERRO` \| `AVISOS` \| `OK`), `resumo` ("Importado"/"Bloqueado"/"Analisado · N linha(s), V válida(s), C correção(ões), R rejeitada(s) [· I nova(s), A atualizada(s)]") e persiste tudo com `FonteDao.fonteStatus` (relatório JSON, rejeitadas em CSV). **Monitor** (`MonitorCsv`): thread daemon `atlasestilo-monitor-csv`, `scheduleWithFixedDelay(tique, 20 s, 30 s)`; `tique` só varre quando passaram `csv.monitor.minutos` desde `ultimaExecucao` (≤ 0 desliga); `rodar(agora, forcar)` (sincronizado em `TRAVA`) processa fontes `ativo` e (`automatico` ou `forcar`), marca `SEM ARQUIVO`, pula "sem mudança" quando `ultimo_mtime`/`ultimo_arquivo` não mudaram e `ultimo_status != "ERRO"`, senão `analisarFonte(id, true, "MONITOR", agora)`; `estado()` → `{ativo, minutos, ultimaExecucao, proximaExecucao, ultimoResultado}`. Uma fonte em `ERRO` é reprocessada a cada ciclo.

**Imports tipados** (`ImportService`, `TIPOS = agencias, funcis, carteiras, pdg, metas`): `mapearCabecalho(tipo, t, manual)` casa primeiro o de-para manual e depois `SINONIMOS` contra `cabecalhoNorm`; obrigatórios (`obrigatoriosDe`): `agencias` → prefixo, nome; `funcis` → matricula, nome, prefixo; `carteiras` → prefixo, codigo; `pdg` → prefixo, semestre, atingiu; `metas` → prefixo, periodo, indicador. Por linha: `extrair`, `Saneador.codigoNumerico` em prefixo/matrícula, `Saneador.numero` nos numéricos (estilo inferido por coluna), `validar` (até 10 erros "Linha N: motivo"); **duplicatas: vale a última ocorrência**; `ignorados = linhas − registros únicos`; transação com `existe` → `inseridos`/`atualizados`, `gravar` só se `confirmar`, `commit` + `importLog` ou `rollback`. **Upsert de agências** (`gravar`, caso `agencias`): `ON CONFLICT(prefixo) DO UPDATE SET nome = excluded.nome` + **apenas as colunas presentes no arquivo** (`r.containsKey(col)` para `uf, municipio, endereco, cep, lat, lng, regional, super_regional, gmaps_url`) + `origem = 'IMPORT'` — um CSV `prefixo;nome` não apaga o link do Maps, a regional nem as coordenadas; coluna presente com célula vazia **limpa**. Demais: `funci ON CONFLICT(matricula)`, `carteira ON CONFLICT(prefixo,codigo)`, `pdg ON CONFLICT(prefixo,semestre)`, `meta ON CONFLICT(prefixo,periodo,indicador)`. `ImportService.modelo(tipo)` gera os modelos CSV (`agencias`: `prefixo;nome;uf;municipio;endereco;cep;lat;lng;regional;super_regional;gmaps_url`). Os dicionários divergem: em `ImportService.SINONIMOS`, `GERENTE` → `gerente_matricula` e `PONTOS` → `pontuacao`; em `FonteService.SIN` (conexao/indicadores), `GERENTE` → `gerente_nome` e `PONTOS` → `pontos`.

**Saneador** (`util/Saneador`): `ler(byte[])` → `Tabela` (`encoding`, `separador`, `linhaCabecalho`, `cabecalho[]`, `cabecalhoNorm[]` = MAIÚSCULO sem acento com `[^A-Z0-9%]+` → espaço, `linhas`, `numeroLinha`, `avisos`, contadores) e `Relatorio` (`correcoes` até `LIMITE_LISTA = 400`, `rejeitadas` até 2000, `celulasInvalidas`, `porRegra`). Encoding: BOM UTF-8, UTF-16LE/BE, UTF-8 estrito, senão Windows-1252 com aviso. Separador entre `;` `,` TAB `|` por moda × consistência nas 25 primeiras linhas (`;` desempata). Cabeçalho = primeira das 30 primeiras linhas com ≥ 2 campos não vazios e nem todos numéricos (antes = preâmbulo); colunas vazias no fim descartadas; nome vazio → `COLUNA_n`; repetido → `nome (2)`. Linhas: cabeçalho repetido pulado, campos a mais recolados no último, a menos completados. `limparTexto` tira NBSP, zero-width, BOM, aspas, apóstrofo e `=` iniciais do Excel. `numero(bruto, estilo, rel, linha, coluna)`: `celulaVazia` (`VAZIOS`: `-`, `N/D`, `#N/D`, `#DIV/0!`, `NULL`, `NAN`…) → null; regras registradas em `porRegra` com nomes exatos (`símbolo monetário`, `percentual` — só remove `%`, **não divide por 100**, `espaços internos`, `sinal unicode`, `parênteses = negativo`, `sinal no fim`, `letra no lugar de dígito`, `sufixo mil`/`mi`/`k`, `separador decimal trocado`, `milhar removido`, `arredondado para inteiro`); separadores: com vírgula e ponto o último é decimal; só ponto com exatamente 3 dígitos em estilo BR = milhar (`-23.586` vira −23586 — use 4+ casas ou vírgula nas coordenadas). `inferirEstilo` por coluna, empate → BR. `competencia(bruto)` → `AAAA-MM` de `dd/mm/aaaa`, `aaaa-mm[-dd…]`, `mm/aaaa`, `aaaa/mm`, `aaaamm`, `mmaaaa`, `mar/2026`, serial do Excel (`Xlsx.dataDeCelula`, 15000–80000). `deLinhas` reaproveita `ler` para o `.xlsx` (`encoding = "xlsx"`). `Xlsx.ler` lê só a planilha de menor número e `sharedStrings`.

**Conexão** — importação (`FonteService.lerConexao`): obrigatórios `prefixo` e `pontos`; competência da coluna, senão do nome do arquivo (`competenciaDoNome`: `conexao_2026-09.csv`, `conexao_092026.csv` → `2026-09`), senão o mês atual (`competenciaAtual`); fora de 0–1000 só avisa; dedupe por `prefixo|competencia|carteira`; agência sem linha própria ganha linha `DERIVADO` com a média das carteiras. Exibição: `FonteDao.conexaoResumo(sel)` (`/api/regiao`.`conexao`: duas últimas competências **presentes na seleção** → `{competencia, media, agencias, anterior, delta, faixa}` ou literal `null`); `FonteDao.conexaoLista(sel)` (competência `MAX(competencia)` **global**, `LIMIT 500`); `FonteDao.conexaoAgencia(prefixo, vePessoas)` (12 competências → `historico[]`, `competencia`, `pontos`, `anterior`, `delta`, `faixa`, `carteiras[]` com `delta` contra a competência anterior daquela carteira). `GestaoDao.planejamento` ordena a fila de nunca visitadas pela última nota (menor primeiro). Front: `tileConexao`, `conexaoAgenciaHtml`, `sparklineSvg`, `FAIXA_NOME`.

**Indicadores e visões** — `FonteService.lerIndicadores`: só `prefixo` é obrigatório; competência da coluna, do nome do arquivo ou `""` (foto atual, sem histórico); toda coluna que passa em `Saneador.colunaNumerica` (≥ 60 % numérica) vira indicador com nome `cabecalhoNorm`; `FonteDao.indicadoresGravar` upsert por célula; `FonteDao.foraDaFaixa(fonteId)` lista células da última competência fora de `minimo`/`maximo` das visões. `FonteDao.visoesCalculadas(s, sel)`: para cada visão ativa alcançável (`perfilAlcanca`), duas últimas competências **globais** da coluna, agrega dentro da seleção, meta fixa ou `coluna_meta`, `pct = valor/meta*100`, `status` `ok` (≥ 1) / `atencao` (≥ 0,9) / `critico` (razão invertida se `melhor = MENOR`), `delta`; saída `{id, titulo, formato, casas, agregacao, melhor, valor, meta, pct, competencia, competenciaAnterior, anterior, delta, status, agenciasComDado, fonte}`. Fonte `conexao` em visão usa `(SELECT prefixo, competencia, pontos AS valor FROM conexao WHERE carteira = '')`. `FonteDao.colunasDaFonte(fonteId)` alimenta os selects do Admin.

### 7.3 Visitas e checklist

Status `PLANEJADA` (padrão na criação), `REALIZADA`, `CANCELADA` (aceito pela API e com badge, mas sem botão no formulário). `GestaoDao.visitaCriar(prefixo, status, dataPlanejada, dataRealizada, resumo, por, agora, Checklist ck)` grava `resumo = Texto.aparar(resumo, 4000)` (null vira `""`), `melhorias`/`percepcao` só se não vazios, `checklist_em = agora` se `!ck.vazio()`. `GestaoDao.Checklist`: `ambiencia, atendimento, organizacao, equipe, claros` (Integer), `movimento, melhorias, percepcao` (String), `notaGeral` (Double). `ApiServlet.lerChecklist`: notas via `nota5` (clamp 1..5), `movimento` `VAZIA`/`CHEIA` senão `NORMAL`, `claros` 0..99, `notaGeral` `Texto.decimal` 0..10, `melhorias` com `\n` → `|` (o front já manda `|`), `percepcao` `trim()`. `GestaoDao.visitaAtualizar(...)`: `COALESCE(?, coluna)` em tudo — ausente mantém; `resumo`/`melhorias`/`percepcao` com `""` limpam; datas nunca são limpadas. `GestaoDao.visitas(prefixo)` → `visitaJson`: `id, prefixo, status, dataPlanejada, dataRealizada, resumo, ambiencia, atendimento, organizacao, equipe, movimento, claros, notaGeral, melhorias` (array por `split("\\|")`), `percepcao, criadoPor, criadoEm, atualizadoEm, fotos` (`FotoDao.daVisita`), ordem `COALESCE(data_realizada, data_planejada, criado_em) DESC`.

Fotos da visita: `POST /api/visita/{id}/foto` → `GestaoDao.visitaPrefixo(id)` (404 "Visita não encontrada.") → `FotoDao.inserir(..., "VISITA", ..., visitaId, restrita=true)` → `origem = 'VISITA'`, `restrita = 1`. Ações criadas na visita: o front chama `POST /api/ponto` com `visitaId`. Conferência durante a visita: bloco `#fv-conferir` lista os pontos `AGUARDANDO_VERIFICACAO` e cada escolha vira `POST /api/ponto/{id}/verificar` com `visitaId`. Exclusão (`POST /api/visita/{id}/excluir`): `FotoDao.excluirDaVisita` + arquivos, depois `GestaoDao.visitaExcluir` (ações soltas). Tempo: `GestaoDao.visitaAtrasada(dataPlanejada, agora)` = `dataPlanejada < inicioDia(agora)`.

### 7.4 Ações (pontos de melhoria), cadência, verificação e prova

**Criação** `GestaoDao.pontoCriar(prefixo, descricao, previsao, por, agora, visitaId, responsavel, prioridade)` (`tipo = 'ACAO'`, `prioridadeValida`). **Edição** `pontoAtualizar(id, status, solucao, previsao, agora, responsavel, prioridade, descricao)`: `COALESCE`; `status = RESOLVIDO` → `resolvido_em = agora`; `ABERTO`/`EM_TRATATIVA` → reabre (zera `resolvido_em`, `informado_em`, `verificado_em`, `verificado_visita_id`); `AGUARDANDO_VERIFICACAO` → `informado_em = agora`; `solucao`/`responsavel` com `""` limpam, `previsao` nunca é limpada. **Linha do tempo** `pontoComentar(id, texto, statusNovo, por, agora, tipo, adiarDias)`: insere `acao_atualizacao` (tipo normalizado por `tipoAtualizacaoValido`: `COBRANCA`, `STATUS`, `VERIFICACAO`, senão `RETORNO`), aplica o status (mesma lógica acima) e, se `COBRANCA`, `proxima_cobranca_em = agora + dias*DIA` com `dias = limitar(adiar)` ou a cadência da prioridade. `atualizacoes(pontoId)` → `[{id, texto, statusNovo, criadoPor, tipo, criadoEm}]` mais recente primeiro. Semântica: `RETORNO` e `VERIFICACAO` contam como "último retorno" (zeram o sem-retorno); `COBRANCA` conta em `cobrancas`/`ultima_cobranca`; `STATUS` não conta para nada.

**Transições** (como o front as dispara): `tratar` → `comentar {status:'EM_TRATATIVA', texto:'Tratativa iniciada', tipo:'STATUS'}`; `informou-enviar` → `comentar {status:'AGUARDANDO_VERIFICACAO', texto, tipo:'RETORNO'}` + fotos `DEPOIS`; `resolver-enviar` → `POST ponto/{id} {status:'RESOLVIDO', solucao}` + `comentar {texto:'Concluída: …', tipo:'STATUS'}` + fotos `DEPOIS` (não preenche `verificado_em`: sem foto do depois cai em "sem prova"); `reabrir` → `comentar {status:'ABERTO', texto:'Reaberta', tipo:'STATUS'}` (limpa a prova, **não** incrementa `reaberturas`); `confirmar`/`nao-feito` → `verificar`.

**Verificação** `pontoVerificar(id, confirmada, visitaId, texto, por, agora)`: os `UPDATE` têm `WHERE id = ? AND status = 'AGUARDANDO_VERIFICACAO'` (duplo clique não duplica; fora desse status devolve `{ok:false}` sem linha do tempo). `CONFIRMADO` → `RESOLVIDO`, `resolvido_em = verificado_em = agora`, `verificado_visita_id`, `solucao = COALESCE(solucao, texto)`, timeline `VERIFICACAO` "Conferido na visita: feito."; `NAO_FEITO` → `ABERTO`, `informado_em = NULL`, `reaberturas + 1`, timeline `VERIFICACAO` com `status_novo = 'ABERTO'` "Conferido na visita: não estava feito.". **Prova**: `comprovada = !aberta && (verificado_em != null || fotos_depois > 0)`; "sem prova" = `prova=SEM` (`status = 'RESOLVIDO' AND verificado_em IS NULL AND NOT EXISTS foto DEPOIS`).

**Cadência** (`GestaoDao.Cadencia`: `alta = 7`, `media = 15`, `baixa = 30`, `parada = 14`; `dias(prioridade)`; `json()` → `{ALTA, MEDIA, BAIXA, parada}`; `cadencia()` lê `config_parametro`; `cadenciaDefinir(alta, media, baixa, parada, por, agora)`). Cálculo em `GestaoDao.lerAcao` para ação aberta e não aguardando: `baseRetorno = max(criado_em, ultimo_retorno)`; `semRetornoDias = floor((agora − baseRetorno)/DIA)`; `parada = semRetornoDias >= cad.parada`; `cobradaHaDias`; `explicita = proxima_cobranca_em != null && ultima_cobranca != null && (ultimo_retorno == null || ultimo_retorno <= ultima_cobranca)`; `proximaCobranca = explicita ? proxima_cobranca_em : max(baseRetorno, ultima_cobranca) + cad.dias(prioridade)*DIA`; `cobrarHoje = explicita ? proximaCobranca <= agora : (vencida || parada || proximaCobranca <= agora)`. Para `RESOLVIDO`/`AGUARDANDO_VERIFICACAO` tudo isso é null/false. `vencida = aberta && !aguardando && previsao != null && previsao < inicioDia(agora)` (vence só no dia seguinte ao prazo); `diasParaPrazo = diasEntre(agora, previsao)`. `ordenarParaCobranca`: vencidas, paradas, `peso(prioridade)`, `previsao` crescente.

**Filtros de `GestaoDao.acoes(prefixo, status, prazo, regional, prioridade, prova, agora)`** (há sobrecarga sem `prova`; `pontos(prefixo, status)` chama com `agora = 0` e desliga os cálculos — não use em rotas): `status=PENDENTES` → `<> 'RESOLVIDO'`; outro valor → igualdade; `prazo=VENCIDAS` → `status NOT IN ('RESOLVIDO','AGUARDANDO_VERIFICACAO') AND previsao < hoje`; `7DIAS`/`30DIAS` → `previsao >= hoje AND < hoje + (7|30 + 1)*DIA`; `SEM` → `status <> 'RESOLVIDO' AND previsao IS NULL`; `COBRAR`/`PARADAS` → SQL exclui RESOLVIDO/AGUARDANDO e o filtro é feito em Java (`cobrarHoje`/`parada`), **após** o `LIMIT 1000`; `prova=SEM`. Ordem: status (RESOLVIDO 2, AGUARDANDO 1, demais 0), prioridade, `COALESCE(previsao, 9e15)`, `criado_em DESC`. A lista inclui `fotos` (`FotoDao.dasAcoes`, lotes de 400 ids). Exclusão: `pontoExcluir` (linha do tempo + ação) após `FotoDao.excluirDaAcao`.

### 7.5 Fotos

`FotoDao.inserir` (3 aridades): `origem` = `pontoId != null ? 'ACAO' : visitaId == null ? 'ADMIN' : 'VISITA'`; `restrita = restrita || pontoId != null`; `momento` só com `pontoId` (`DEPOIS` se o parâmetro for exatamente `"DEPOIS"`, senão `ANTES`). Arquivo: `UUID.randomUUID()` sem hífens + extensão por `ApiServlet.extensaoImagem` (`image/png`, `image/jpeg`, `image/gif`, `image/webp`; outro MIME → `ignoradas`), gravado em `ApiServlet.dirFotos()` (= `ATTR_FOTO_DIR`). Legenda `aparar 500`; nas fotos de ação é automática: `"Antes · "`/`"Depois · "` + `Texto.aparar(pontoDescricao(id), 120)`. `ApiServlet.responderFotos`: `gravadas == 0 && ignoradas > 0` → 400 "N arquivo(s) não aceito(s) — use JPG, PNG, WEBP ou GIF."; senão `{ok: gravadas > 0, gravadas, ignoradas}`. Limites do `web.xml` (`multipart-config`): 8 MB por arquivo, 40 MB por requisição, threshold 256 KB; estouro → `IllegalStateException` → 413 "Arquivo acima de 8 MB ou envio acima de 40 MB no total." (`ApiServlet.LIMITE_FOTO_MB = 8` só para a mensagem; `admin.js` repete em `LIMITE_FOTO`/`LIMITE_ENVIO`). Redução no cliente (`reduzirImagem`): lado maior 1600 px, `toBlob('image/jpeg', 0.82)`, original mantido se já cabe e tem < 900 KB ou se é GIF/SVG. Listagem por agência `FotoDao.listar` ordena `FACHADA` primeiro; JSON `id, prefixo, matricula, tipo, legenda, origem, criadoEm, visitaId, pontoId, momento, restrita`. `/foto/{id}` com `Cache-Control: private, max-age=86400`. `POST /api/admin/foto/{id}/excluir` apaga qualquer foto pelo id, sem checar origem.

### 7.6 Planejamento e KPIs

`GET /api/planejamento` → `GestaoDao.planejamento(agora)`: chaves `kpis`, `cadencia`, `naoVisitadas`, `planejadas`, `semFoto`, `pontosEstourados`, `acoesVencendo`, `cobrarHoje`, `aConferir`, `frias`, `evolucao`, `anotacoesGerais`.

| KPI | Fórmula |
|---|---|
| `total` | `COUNT(*) FROM agencia` |
| `visitadas` | `COUNT(DISTINCT prefixo) FROM visita WHERE status = 'REALIZADA'` (sem JOIN) |
| `visitas90` | visitas REALIZADA com `data_realizada >= agora − 90*DIA` |
| `notaMedia` | `AVG(nota_geral)` das REALIZADA com nota, 1 casa; null se nenhuma |
| `acoesAbertas` | `COUNT(*) ponto_melhoria WHERE status <> 'RESOLVIDO'` (sem JOIN com `agencia`) |
| `acoesVencidas`, `cobrarHoje`, `paradas`, `aguardando` | contagem em Java sobre `lerAcoes(SQL_ACOES_BASE + " AND p.status <> 'RESOLVIDO' ...")` (INNER JOIN com `agencia`) |
| `agendaSemana` | visitas PLANEJADA com `data_planejada` em `[inicioDia(agora), inicioDia(agora) + 8*DIA)` |
| `concluidas180` / `comprovadas180` | RESOLVIDO com `resolvido_em >= agora − 180*DIA` / idem com `verificado_em IS NOT NULL OR EXISTS foto DEPOIS` |
| `fechamentoComprovadoPct` | `round(100 * comprovadas180 / concluidas180)`; null se `concluidas180 == 0` |

Listas: `naoVisitadas` (sem visita REALIZADA; `prefixo, nome, uf, municipio, regional, pontosAbertos, conexao`; menor Conexão primeiro), `planejadas` (`id, prefixo, nome, uf, municipio, dataPlanejada, pontosAbertos, atrasada, estaSemana`), `semFoto` (nenhuma linha em `foto`), `pontosEstourados` (vencidas), `acoesVencendo` (`previsao < inicioDia(agora) + 8*DIA`), `cobrarHoje` (ordenada por `ordenarParaCobranca`), `aConferir` (aguardando), `frias` (última REALIZADA há mais de 120 dias; `ultimaVisita`, `dias`), `evolucao` (duas últimas `nota_geral` por agência: `ultima, anterior, delta, dataUltima`), `anotacoesGerais` (`anotacoes(null)`). As listas de ações aqui vêm sem `fotos` (`comFotos = false`). **Aviso do dia** `GET /api/hoje` → `GestaoDao.resumoDoDia(agora)`: `{cobrarHoje, vencidas, paradas, aConferir, altasParadas, visitasHoje, visitasAtrasadas, cadencia}` (visitas comparadas por `YEAR`/`DAY_OF_YEAR` em `America/Sao_Paulo`).

### 7.7 Anotações

`GestaoDao.anotacoes(prefixo)` (`prefixo == null` → gerais `prefixo IS NULL`; ordem `fixada DESC, COALESCE(atualizado_em, criado_em) DESC`; JSON `id, prefixo, texto, fixada, criadoPor, criadoEm, atualizadoEm`), `anotacaoCriar(prefixo, texto, por, agora)` (prefixo vazio → NULL; `aparar 8000`), `anotacaoAtualizar(id, texto, fixada, agora)` (só campos não nulos; `WHERE excluida = 0`), `anotacaoExcluir(id, agora)` (lógica). Rotas na seção 8; front `notaHtml`, `#a-texto`/`#a-salvar` (agência) e `#plan-anotacao`/`#plan-anotar` (gerais).

### 7.8 Exportações

`GET /api/export/visitas|acoes?prefixo=` (Master) → `GestaoDao.csvVisitas(prefixo)` / `csvAcoes(agora, prefixo)` via `Http.download` (`text/csv;charset=UTF-8`, `Content-Disposition: attachment`, BOM U+FEFF para o Excel). Separador `;`, aspas quando necessário, datas `dd/mm/aaaa` em Brasília, decimais com vírgula. Cabeçalho de visitas: `prefixo;agencia;uf;municipio;status;data_planejada;data_realizada;ambiencia;atendimento;organizacao;equipe;movimento;claros;nota_geral;melhorias;percepcao;resumo;fotos;registrado_por`. Cabeçalho de ações: `id;prefixo;agencia;regional;descricao;status;prioridade;responsavel;prazo;situacao_prazo;solucao;visita_id;registros;cobrancas;ultima_cobranca;proxima_cobranca;sem_retorno_dias;informado_em;verificado_em;reaberturas;fotos_antes;fotos_depois;criado_em;resolvido_em` — `situacao_prazo` ∈ `aguardando conferência` \| `concluída` \| `sem prazo` \| `vencida` \| `vence em 7 dias` \| `no prazo`, com sufixos ` · parada` e ` · cobrar`. Nomes `visitas-atlas-estilo[-{prefixo}].csv`, `acoes-atlas-estilo[-{prefixo}].csv`. A ordem das colunas é posicional na chamada `csv(...)`.

### 7.9 Dados de exemplo

`core/DadosExemplo.semear()`: transação única, `new Random(42)`, datas relativas a `System.currentTimeMillis()`. 26 agências (`AGENCIAS`: prefixos `9101`–`9108` SP, `9111`–`9113` RJ, `9121`–`9122` MG, `9131`–`9134` PR/PR/SC/RS, `9141`–`9143` BA/PE/CE, `9151`–`9153` DF/DF/GO, `9161` ES, `9162` AM, `9163` PA; `INSERT OR IGNORE`, `origem = 'EXEMPLO'`). Por agência: 1 gerente geral, 3..7 carteiras `EST-01…` com gerente (`funci.carteira` = código; `carteira.tipo = 'ESTILO'`, `qtd_clientes` 180..439), 2..4 assistentes, 2..5 de apoio (`OUTRO`); matrículas `F99%05d`; Conexão de 6 competências (`competenciasRecentes(agora, 6)`) para a agência e 2 para cada carteira; PDG `2023-1`…`2026-1`; metas `2026-2` para `Captação`, `Crédito`, `Seguridade`, `Investimentos`, `Encarteiramento`; visitas (`criado_por = 'EXEMPLO'`): `idx % 5 ∈ {0,1}` ganha uma REALIZADA + anotação, `idx % 5 == 2` uma PLANEJADA; 0..2 ações por agência (`PONTOS`), sem checklist/prioridade explícitos; 2 anotações gerais; **nenhuma foto**. `visita`/`anotacao`/`ponto_melhoria` usam `INSERT` simples: chamar `semear()` sem `limparExemplo()` duplica (a API sempre limpa antes de recarregar). Rotas: `POST /api/admin/exemplo` com `acao=limpar|recarregar`; a marca `exemplo.limpo` é gravada nos dois casos, e depois dela o boot nunca mais semeia sozinho.

## 8. API HTTP

Tudo em `web/ApiServlet` (`/api/*`), mais `web/FotoServlet` (`/foto/*`). Perfil mínimo: **COLEGA** = qualquer matrícula identificada (conteúdo filtrado pela jurisdição); **MODERADOR** = `veTudo()`; **MASTER** = `master()`. Todo POST exige, além disso, `X-Atlas` e matrícula sem `SOMENTE_LEITURA`. Datas são epoch millis; ids são `long`; `{ok}` reflete `executeUpdate() > 0`. Roteamento: `Http.caminho(req)` divide o `pathInfo` por `/` (`/api/agencia/1881/` → `["agencia","1881"]`); `cam[0]` escolhe o `case`.

### 8.1 GET

| Rota | Perfil | Parâmetros | Resposta | Erros |
|---|---|---|---|---|
| `GET /api/contexto` | COLEGA | — | `{matricula, nome, prefixo, perfil, regionalJurisdicao, veTudo, master, mapsAtivo, somenteLeitura}` + `cadencia:{ALTA, MEDIA, BAIXA, parada}` só para Master | — |
| `GET /api/mapa` | COLEGA | — | `AgenciaDao.mapa` (7.1), mascarado por perfil | — |
| `GET /api/municipios` | COLEGA | `uf` | `[{municipio, agencias, funcis, visitadas}]` | — |
| `GET /api/regiao` | COLEGA | `uf`, `municipio` (nome exato), `prefixos` (lista por vírgula), `regional` — todos opcionais | `MetricaDao.resumo` + `"conexao":` (`FonteDao.conexaoResumo` ou `null`) + `"visoes":` (`FonteDao.visoesCalculadas`) | — |
| `GET /api/regiao/lista` | COLEGA; `funcis`/`gerentes`/`assistentes` exigem MODERADOR | mesmos filtros + `tipo` (`agencias` padrão, `carteiras`, `pdg`, `conexao`, `funcis`, `gerentes`, `assistentes`) | `MetricaDao.lista` ou `FonteDao.conexaoLista` (`LIMIT 500`) | 403 "Seu perfil vê apenas os grandes números." |
| `GET /api/agencia/{prefixo}` | COLEGA (jurisdição) | `{prefixo}` via `Texto.prefixo` | `{agencia, resumo, pdgHistorico, conexao, visoes, fotos}` + `equipe, carteiras, metas` (veTudo) + `visitas, anotacoes, pontos` (Master) | 404 "Prefixo ausente."; 404 "Agência não encontrada na sua jurisdição." |
| `GET /api/planejamento` | MASTER | — | 7.6 | 403 |
| `GET /api/hoje` | MASTER | — | `{cobrarHoje, vencidas, paradas, aConferir, altasParadas, visitasHoje, visitasAtrasadas, cadencia}` | 403 |
| `GET /api/acoes` = `GET /api/pontos` | MASTER | `prefixo`, `status` (`ABERTO`, `EM_TRATATIVA`, `AGUARDANDO_VERIFICACAO`, `RESOLVIDO`, `PENDENTES`), `prazo` (`VENCIDAS`, `7DIAS`, `30DIAS`, `SEM`, `COBRAR`, `PARADAS`), `regional`, `prioridade`, `prova` (`SEM`) | `[ação]` com `id, prefixo, agencia, uf, municipio, regional, descricao, status, solucao, previsao, vencida, aguardando, diasParaPrazo, resolvidoEm, visitaId, responsavel, prioridade, tipo, atualizacoes, ultimaAtualizacao, ultimoRetorno, ultimaCobranca, cobrancas, semRetornoDias, cobradaHaDias, proximaCobranca, cobrarHoje, parada, cadenciaDias, informadoEm, verificadoEm, verificadoVisitaId, reaberturas, fotosAntes, fotosDepois, comprovada, criadoPor, criadoEm, fotos:[{id, momento, legenda}]` (`LIMIT 1000`) | 403 |
| `GET /api/ponto/{id}/atualizacoes` | MASTER | — | `[{id, texto, statusNovo, criadoPor, tipo, criadoEm}]` | 404 "Use /ponto/{id}/atualizacoes."; 400 id |
| `GET /api/export/visitas`, `GET /api/export/acoes` | MASTER | `prefixo` opcional | download CSV (7.8) | 404 "Export desconhecido (visitas \| acoes)." |
| `GET /api/admin/modelo/{tipo}` | MASTER | `{tipo}` ∈ `FonteDao.TIPOS` | download `modelo-{tipo}.csv` | 404 "Tipo de modelo desconhecido." |
| `GET /api/admin/masters` | MASTER | — | `[{matricula, incluidoPor, criadoEm}]` | 403 |
| `GET /api/admin/flags` | MASTER | — | `[{matricula, flag, criadoPor, criadoEm}]` | 403 |
| `GET /api/admin/importlog` | MASTER | — | 50 últimos `[{tipo, arquivo, inseridos, atualizados, ignorados, criadoPor, criadoEm}]` | 403 |
| `GET /api/admin/pasta` | MASTER | — | `FonteService.varrerPasta`: `{pasta, pastaPadrao, existe, ehPasta, legivel, total, arquivos:[{nome, tamanho, modificadoEm}] (até 200), monitorMinutos, estrito}` + `monitor:` (`MonitorCsv.estado()`) | 403 |
| `GET /api/admin/fontes` | MASTER | — | `[{id, nome, tipo, arquivo, ativo, automatico, mapeamento:{}, ultimaLeituraEm, ultimoArquivo, ultimoMtime, ultimoStatus, ultimoResumo}]` | 403 |
| `GET /api/admin/visoes` | MASTER | — | `[{id, titulo, fonteId, coluna, agregacao, formato, casas, meta, colunaMeta, melhor, minimo, maximo, perfilMinimo, ordem, ativo, fonteNome}]` | 403 |
| `GET /api/admin/monitor` | MASTER | — | `{ativo, minutos, ultimaExecucao, proximaExecucao, ultimoResultado}` | 403 |
| `GET /api/admin/cadencia` | MASTER | — | `{ALTA, MEDIA, BAIXA, parada}` | 403 |
| `GET /api/admin/campos/{tipo}` | MASTER | `{tipo}` ∈ `FonteDao.TIPOS` | `[{campo, obrigatorio, sinonimos:[]}]` | 404 "Tipo desconhecido." |
| `GET /api/admin/fonte/{id}/relatorio` | MASTER | — | JSON de `fonte_csv.ultimo_relatorio` ou `null` | 400 id |
| `GET /api/admin/fonte/{id}/rejeitadas` | MASTER | — | download `rejeitadas-fonte-{id}.csv` | 404 "Esta fonte não tem linhas rejeitadas na última leitura." |
| `GET /api/admin/fonte/{id}/colunas` | MASTER | — | `[{coluna, competencias, celulas, ultima}]`; fonte `conexao` → `[{coluna:"PONTOS", competencias}]` | 404 "Rota de fonte desconhecida."; 404 "Use /admin/fonte/{id}/relatorio\|rejeitadas\|colunas." |
| `GET /api/admin/<outro>` | MASTER | — | — | 404 "Rota admin desconhecida." |
| `GET /api/<outro>` | — | — | — | 404 "Rota desconhecida: <cam[0]>" |

### 8.2 POST (todos exigem MASTER, decidido antes do `switch` em `doPost`)

| Rota | Parâmetros | Resposta | Erros (além de 403/413/500) |
|---|---|---|---|
| `POST /api/visita` | `prefixo` **obrig.**; `status` (padrão `PLANEJADA`); `dataPlanejada`, `dataRealizada` (`lerData`); `resumo`; checklist `ambiencia`, `atendimento`, `organizacao`, `equipe`, `movimento`, `claros`, `notaGeral`, `melhorias`, `percepcao` | `{ok:true, id}` | 400 "Status de visita inválido."; 400 "Prefixo obrigatório." |
| `POST /api/visita/{id}` | mesmos campos; ausentes mantêm; `resumo` `""` limpa | `{ok}` | 400 status; 400 id |
| `POST /api/visita/{id}/foto` (multipart) | partes `arquivo`; `legenda` | `responderFotos` | 404 "Visita não encontrada." |
| `POST /api/visita/{id}/excluir` | — | `{ok}` | 400 id |
| `POST /api/anotacao` | `texto` **obrig.**; `prefixo` (vazio = geral) | `{ok:true, id}` | 400 "Texto obrigatório." |
| `POST /api/anotacao/{id}` | `texto` (vazio mantém); `fixada` (`"1"` fixa; outro desafixa; ausente mantém) | `{ok}` | 400 id |
| `POST /api/anotacao/{id}/excluir` | — | `{ok}` (soft delete) | 400 id |
| `POST /api/ponto` | `prefixo`, `descricao` **obrig.**; `previsao`, `visitaId`, `responsavel`, `prioridade` | `{ok:true, id}` | 400 "Prefixo e descrição são obrigatórios."; 400 "Status inválido." (se `status` vier fora dos 4); 400 `visitaId` |
| `POST /api/ponto/{id}` | `status`, `solucao`, `previsao`, `responsavel`, `prioridade`, `descricao` | `{ok}` | 400 status; 400 id |
| `POST /api/ponto/{id}/comentar` | `texto`; `status`; `tipo` (`COBRANCA`/`STATUS`/`VERIFICACAO`, outro → `RETORNO`); `adiar` (dias, 1..365, só COBRANCA) | `{ok:true, id}` (id em `acao_atualizacao`) | 400 "Escreva o retorno ou mude o status." (sem texto, sem status e tipo ≠ COBRANCA); 400 `adiar`/id não numérico |
| `POST /api/ponto/{id}/verificar` | `resultado` **obrig.** (`CONFIRMADO`/`NAO_FEITO`); `visitaId`; `texto` | `{ok}` (`false` se não estava em `AGUARDANDO_VERIFICACAO`) | 400 "Resultado inválido (CONFIRMADO \| NAO_FEITO)." |
| `POST /api/ponto/{id}/foto` (multipart) | `momento` (`DEPOIS`, outro → `ANTES`); partes `arquivo` | `responderFotos` | 404 "Ação não encontrada." |
| `POST /api/ponto/{id}/excluir` | — | `{ok}` | 400 id |
| `POST /api/agencia/{prefixo}/gmaps` | `url` (≤ 1000; vazio limpa) | `{ok}` | 400 "A URL do Maps deve começar com https://"; 404 "Rota de agência desconhecida." |
| `POST /api/admin/import/{tipo}` (multipart) | `{tipo}` ∈ `ImportService.TIPOS`; parte `arquivo` **obrig.**; `confirmar` (`"1"` grava) | `ImportService.processar`: `{tipo, confirmado, linhas, inseridos, atualizados, ignorados, erros[], encoding, separador, saneamento{}}` ou `{erro}` em HTTP 200 | 400 "Tipo de import desconhecido."; 400 "Envie o arquivo CSV ou XLSX." |
| `POST /api/admin/foto` (multipart) | `prefixo` **obrig.**; `tipo` (`FACHADA`/`INTERNA`/`PESSOA`/`OUTRA`, padrão `INTERNA`); `matricula`; `legenda`; partes `arquivo` (`getParts()` é chamado antes de validar `prefixo` para o 413 sair certo) | `responderFotos` (`origem='ADMIN'`, `restrita=0`) | 400 "Prefixo obrigatório."; 400 "Tipo de foto inválido." |
| `POST /api/admin/foto/{id}/excluir` | — | `{ok: arquivoExistia}` | — |
| `POST /api/admin/master` | `matricula` **obrig.**; `acao` (`incluir` padrão / `remover`) | `{ok:true}` + `invalidarPerfis()` | 400 "Matrícula obrigatória."; 400 "Você não pode remover a própria matrícula dos masters — peça a outro master."; 400 "Não é possível remover o último master." |
| `POST /api/admin/flag` | `matricula` **obrig.**; `flag` (`""` remove, `SOMENTE_LEITURA`, `BLOQUEADO`) | `{ok:true}` + `invalidarPerfis()` | 400 "Flag inválida."; 400 "Matrícula obrigatória."; 400 "Não é possível restringir a própria matrícula."; 400 "Remova a matrícula dos masters antes de restringi-la." |
| `POST /api/admin/exemplo` | `acao` (`limpar` / `recarregar`) | `{ok:true}` | 400 "Ação inválida." |
| `POST /api/admin/pasta` | `pasta` (absoluta), `minutos` (0..1440), `estrito` (`"1"`/`"0"`), todos opcionais | mesmo JSON do GET | 400 "Informe o caminho completo da pasta no servidor (ex.: D:\dados\atlasestilo\csv)." |
| `POST /api/admin/fonte` | `id` (0/ausente = nova); `nome`, `tipo`, `arquivo` **obrig.**; `ativo` (`"0"` desativa; ausente = true), `automatico` (`"0"`), `mapeamento` | `FonteDao.fonte(id).json()` | 400 "Nome e arquivo são obrigatórios."; 400 "Tipo de fonte inválido."; 400 "Informe só o nome (ou padrão) do arquivo dentro da pasta, sem caminho." |
| `POST /api/admin/fonte/{id}/analisar` | — | relatório (prévia; persiste status/relatório sem gravar dados) | 404 "Fonte não encontrada." |
| `POST /api/admin/fonte/{id}/importar` | — | relatório (grava, salvo modo estrito com rejeições) | 404 |
| `POST /api/admin/fonte/{id}/upload` (multipart) | parte `arquivo` **obrig.**; `confirmar` | `processarFonte` com `mtime = null` | 400 "Envie o arquivo CSV ou XLSX."; 404 |
| `POST /api/admin/fonte/{id}/excluir` | — | `{ok}` | 404; 404 "Ação de fonte desconhecida." |
| `POST /api/admin/visao` | `id`; `titulo`, `fonteId`, `coluna` **obrig.**; `agregacao`, `formato`, `casas`, `meta`, `colunaMeta`, `melhor`, `minimo`, `maximo`, `perfilMinimo`, `ordem`, `ativo` (valores fora das opções caem no primeiro da lista, `escolha`) | `{ok:true}` | 400 "Título, fonte e coluna são obrigatórios."; 400 "Fonte inexistente." |
| `POST /api/admin/visao/{id}/excluir` | — | `{ok}` | 400 id |
| `POST /api/admin/cadencia` | `alta`, `media`, `baixa`, `parada` (ausentes mantêm) | `{ALTA, MEDIA, BAIXA, parada}` | — |
| `POST /api/admin/monitor/rodar` | — | `{executadoEm, pasta, importadas, fontes:[{id, nome, tipo, acao, status}]}` | 404 "Use /admin/monitor/rodar." |
| `POST /api/admin/<outro>` / `POST /api/<outro>` | — | — | 404 "Rota admin desconhecida." / 404 "Rota desconhecida: <cam[0]>" |

Detalhe de `postFonte`: só com `cam.length >= 4` o caminho é tratado como ação sobre `{id}`; `POST /api/admin/fonte/5` sem ação cai no criar/editar e usa o parâmetro `id`. `GET /api/admin/modelo` aceita os 7 tipos; `POST /api/admin/import` só os 5 tipados (`conexao`/`indicadores` entram por `fonte/{id}/upload`).

### 8.3 `GET /foto/{id}` (`FotoServlet`)

Autenticado (qualquer perfil), regras na seção 5.8. Sempre por id, nunca pelo nome original.

### 8.4 Convenções de erro e de multipart

- Corpo de erro sempre `{"erro": "mensagem terminada em ponto."}`; `api()` no front lança `Error(j.erro || 'HTTP ' + status)` quando `!r.ok` **ou** quando o JSON tem a chave `erro` — portanto respostas `{erro}` com HTTP 200 (`ImportService.processar` com erro fatal, `FonteService.analisarFonte` sem fonte/arquivo/acima do teto) também são tratadas como falha. Não use a chave `erro` para dados legítimos.
- Status usados: 400 (validação, id inválido), 403 (sem sessão, sem `X-Atlas`, somente leitura, perfil insuficiente), 404 (rota/recurso), 413 (multipart acima do limite), 500 (SQL/runtime). `erro.jsp` responde `{"erro":"Rota não encontrada."}` (404) ou `{"erro":"Erro interno."}` quando a URI contém `/api/`.
- Multipart: partes chamadas exatamente `arquivo` com `getSize() > 0`; `nomeArquivo(Part)` lê o `content-disposition` (Servlet 3.0); `parteArquivo(req)` devolve a primeira parte não vazia (imports); limites 8 MB/40 MB no `web.xml`; MIME aceito só `image/png|jpeg|gif|webp` nas fotos; sem parte `arquivo` → `{ok:false, gravadas:0, ignoradas:0}` (200).
- Cache: estáticos `/css/`, `/js/` → `no-cache`; JSON → `no-store`; `/foto/{id}` → `private, max-age=86400`; JSP e CSV sem cabeçalho explícito.

## 9. Front-end

### 9.1 Páginas e casca

- `index.jsp`: `<head>` com favicon inline SVG (navy `#0b1324` + círculo dourado `#f5c518`), fontes Google (`Inter`, `Space Grotesk`, `JetBrains Mono`) carregadas de forma não bloqueante (`media="print" onload="this.media='all'"`; a intranet pode não alcançar o Google — os tokens `--ff`, `--ff-display`, `--ff-mono` têm fallback de sistema), `css/atlas.css`; `<body>` inclui `/WEB-INF/jspf/header.jspf`, `header.topo` (`#abrir-menu` só mobile, `h1#topo-titulo`, `small#topo-sub`, busca `#busca-input`/`#busca-res`), `main.conteudo#conteudo` com as três vistas `#vista-mapa` (`.cartao-mapa` com `#migalhas`, `svg#svg-mapa` `viewBox 0 0 1000 1000`, `.lenda-mapa`; `aside#painel-regiao`), `#vista-planejamento` (`#plan-resumo`, links `api/export/visitas|acoes`, `#grade-planejamento`) e `#vista-acoes` (`#acoes-resumo`, `#acoes-status` com botões `data-v` `COBRAR` (+`#acoes-n-cobrar`), `PENDENTES` (ativo), `VENCIDAS`, `PARADAS`, `7DIAS`, `AGUARDANDO`, `RESOLVIDO`, `SEMPROVA`, `""`; `#acoes-prioridade`, `#acoes-regional`, `#acoes-busca`; `#grade-acoes`); sobreposições `#veu`, `aside.drawer#drawer-agencia` (`#ag-rotulo`, `#ag-nome`, `#fechar-agencia`, `#ag-corpo`), `#painel-dash` (`#dash-nome`, `#fechar-dash`, `#dash-corpo`); inputs ocultos `#foto-visita-input`, `#foto-acao-input`; `#dica-mapa`, `#toast`; `window.ATLAS_CTX`; `js/atlas.js`.
- `admin.jsp`: gate em scriptlet (5.9); seções com ids por prefixo: cadência (`#cad-alta`, `#cad-media`, `#cad-baixa`, `#cad-parada`, `#cad-salvar`, `#cad-situacao`), pasta (`#pasta-caminho`, `#pasta-minutos`, `#pasta-estrito`, `#pasta-salvar`, `#pasta-situacao`, `#pasta-arquivos`, `#monitor-rodar`, `#monitor-estado`), fontes (`#corpo-fontes`, `#relatorio-fonte`, `#fonte-id`, `#fonte-nome`, `#fonte-tipo`, `#fonte-arquivo`, `#fonte-auto`, `#fonte-campos`, `#fonte-mapeamento`, `#fonte-salvar`, `#fonte-cancelar`, `#fonte-modelo`), visões (`#corpo-visoes`, `#visao-*`), imports (`#grade-imports`), fotos (`#foto-prefixo`, `#foto-tipo`, `#campo-matricula`/`#foto-matricula`, `#foto-legenda`, `#zona-foto`/`#foto-arquivos`, `#foto-enviar`, `#foto-status`), masters (`#lista-masters`, `#novo-master`, `#incluir-master`), flags (`#lista-flags`, `#flag-matricula`, `#flag-valor`, `#aplicar-flag`), exemplo (`#exemplo-recarregar`, `#exemplo-limpar`), histórico (`#corpo-importlog`); `js/admin.js`.
- `header.jspf`: script inline anti-flash (lê `localStorage['superpf1.tema']` em `try/catch` → `html[data-tema="claro"|"escuro"]`, padrão claro; `#botao-tema` alterna e grava; `#abrir-menu` abre `#sidebar.aberta` + `#veu-menu.aberto`); `div.app` → `aside.sidebar#sidebar` (`a.marca` com monograma "BB", `nav#nav-lateral` com `a[data-nav="mapa"]` e, se `sessao.master()`, `planejamento`, `acoes` (com `#nav-acoes-n`) e `admin`; `.sidebar-rodape` com `#botao-tema` (`.quando-claro`/`.quando-escuro`) e `.chip-usuario` com avatar `https://humanograma.intranet.bb.com.br/avatar/<matricula>` + fallback de iniciais no `onerror`, nome, perfil, regional e " · somente leitura"); abre `div.principal`. Tudo escapado com `Texto.esc`.
- `negado.jsp` e `erro.jsp`: standalone (`session="false"`), repetem o script do tema; `erro.jsp` é `isErrorPage="true"` e escolhe JSON/HTML pela URI.

### 9.2 Regras de ouro do JS

1. Renderização por strings: funções `*Html(...)` devolvem HTML e o chamador faz `innerHTML`; **todo dado passa por `esc()`**. Após `innerHTML` os listeners morrem: religar logo após (`$$('[data-prefixo]', alvo).forEach(...)`) ou usar as delegações únicas registradas em `iniciar` (`#ag-corpo` → `aoClicarGestaoAgencia`, `#grade-planejamento`, `#grade-acoes`).
2. O front só esconde; o servidor decide.
3. ES5 na sintaxe (`var`/`function`, IIFE em modo estrito), APIs modernas no runtime (`fetch`, `Promise`, `URLSearchParams`, `FormData`, `canvas.toBlob`, `100dvh`, `color-mix()`); alvo Chrome/Edge atuais.
4. `atlas.js` e `admin.js` **não compartilham código**: `esc`, `api`, `post`, `toast`, `reduzirImagem`, `fmtData` são cópias (o `fmtData` do admin inclui hora; o `post` do admin não checa somente leitura).
5. `[hidden]{display:none!important}` vence qualquer `display`: mostre/esconda pelo atributo, não por `style`.
6. `body{overflow:hidden}`; quem rola é `#conteudo`; drawer e dash rolam por conta própria.

### 9.3 Estado `App` e helpers (`atlas.js`)

```js
var App = { contexto: null, mapa: null, geo: null, proj: null, vb: {...}, vbBrasil: null,
            sel: { uf: null, municipio: null }, agencia: null, listaAberta: null };  // + App.hoje em runtime
```

`CTX = window.ATLAS_CTX || ''`; `UF_NOMES`; `$`/`$$`; `esc` (`& < > " '`); `api(rota, opts)` (`fetch(CTX + '/api/' + rota)` com `X-Atlas: 1`; JSON inválido → `{erro:'Resposta inválida.'}`; lança em `!r.ok` ou `j.erro`); `post(rota, dados)` (recusa em `somenteLeitura`; `URLSearchParams` ignorando `null`/`undefined`); `toast(msg)` (2600 ms); `fmtInt`, `fmtValor`, `fmtMeses`, `fmtData`, `dataParaEpoch` (`AAAA-MM-DD` → epoch ao **meio-dia local**), `isoLocal`, `compHumana` (`2026-09` → `set/2026`). Dicionários fechados: `TITULOS` (`mapa:'Atlas'`, `planejamento:'Minha gestão'`, `acoes:'Ações para dar retorno'`), `CRITERIOS` (`ambiencia`, `atendimento`, `organizacao`, `equipe`), `MELHORIAS` (12 chips), `MOVIMENTO`, `STATUS_ACAO` (`ABERTO:'aberta'`, `EM_TRATATIVA:'em tratativa'`, `AGUARDANDO_VERIFICACAO:'aguardando conferência'`, `RESOLVIDO:'concluída'`), `TIPO_ATU` (`RETORNO`, `COBRANCA`, `VERIFICACAO`, `STATUS`), `FAIXA_NOME`, `ROTULO_LISTA`, `ACOES_QUE_GRAVAM` — um valor novo no back sem entrada aqui renderiza `undefined`.

**Boot** (`iniciar`): `Promise.all([api('contexto'), api('mapa'), fetch(CTX + '/js/br-uf.min.geojson')])` → `App.contexto/mapa/geo/proj`; `document.documentElement.classList.toggle('master', ...)` e `toggle('somente-leitura', ...)`; `desenharMapa()`, `carregarPainel({})`, `ligarBusca()`; se Master `ligarAcoes()`, `atualizarContadorAcoes()`, `mostrarAvisoDoDia()`; se somente leitura `mostrarFaixa(...)`; `?aviso=admin` → toast; `trocarVista()`; `atualizarSubtitulo(...)`. Listeners globais únicos: `#fechar-agencia`, `#fechar-dash`, delegações, `#veu`, `keydown` Escape (em campo do drawer só `blur()`; senão dash → drawer → `voltarBrasil()`), `beforeunload` (se `formVisita.sujo`), `hashchange` → `trocarVista`.

### 9.4 Vistas e navegação

`location.hash` escolhe a vista (`#mapa` padrão). `trocarVista()`: `planejamento`/`acoes` sem Master caem em `mapa`; fecha dash/drawer (ignora o `false` do `confirm`); alterna `.ativa` nas vistas e em `#nav-lateral a[data-nav]`; `planejamento` → `carregarPlanejamento()`, `acoes` → `carregarAcoes()`. `irParaCobrancas()` abre a vista Ações já em `COBRAR`. Os links da sidebar são `ctx/#hash`, então funcionam também a partir de `admin.jsp`.

### 9.5 Painel da região e drawer

`carregarPainel(f)` (guarda de corrida `seqPainel`) pede `regiao`, `regiao/lista?tipo=agencias` e, com UF sem município, `municipios?uf=`; `renderPainel` monta `.grade-tiles` (`tileConexao`, `tile()` para funcis/gerentes/assistentes/carteiras/média/tempos/PDG — `tile` só abre lista de pessoas com `veTudo`), `visoesHtml`, chips `.so-master`, chips de municípios, `#zona-lista` e a `.lista-agencias` (`button.item-agencia[data-prefixo]`). `abrirLista(f, tipo, botao)` renderiza tabelas por tipo (`carteiras`, `conexao`, `pdg`, pessoas via `pessoaHtml`).

Drawer: `abrirAgencia(prefixo, estado)` (guarda `seqAgencia`; `confirm` se o checklist está sujo e o prefixo muda) → `api('agencia/' + prefixo)` → `App.agencia = d` → `renderAgencia(d)`: `#ag-rotulo`, `#ag-nome`, `fachadaHtml` + `briefingHtml` (só Master: próxima planejada, última realizada, ações vencidas/abertas, Conexão em queda/crítica, carteiras críticas, metas < 70 %), abas `geral`, `equipe`/`carteiras` (veTudo), `fotos`, `visitas`/`acoes` (Master); `ativarAba`, `estadoDrawer()` (aba + rolagem), `fecharAgencia()` (devolve `false` se o usuário desiste do `confirm`; só tira o véu se o dash não está aberto), `recarregar(prefixo)` (refaz `mapa` e reabre com `estadoDrawer()`). Se `formVisita.sujo`, `renderAgencia` transplanta o `#fv-form` antigo para o DOM novo (`manterForm`). `abrirDash(d)`/`fecharDash()` montam o "dashboard da porta" (metas do período `metas[0].periodo` se veTudo, visões, Conexão, PDG, números, e "Atendimento" para Master).

### 9.6 Formulário de visita e cartões de ação

`formVisita = { criterios, melhorias, fotos (File[]), acoes ([{texto, responsavel, prazo, prioridade}]), conferencias ({pontoId: 'CONFIRMADO'|'NAO_FEITO'}), editando (id ou null), sujo }`. `checklistFormHtml(v)` gera `#fv-form` (`#fv-status` `REALIZADA`/`PLANEJADA`, `#fv-data`, `#fv-resumo`, bloco `#fv-checklist` com `.pontos-toque[data-criterio]`, `#fv-movimento`, stepper `#fv-claros`, range `#fv-nota` 0–10 passo 0,5 padrão 7, chips `#fv-melhorias` + `#fv-melhoria-outra`, `#fv-percepcao`, bloco **Conferir** `#fv-conferir` com tri-botão `data-r` por ponto aguardando, ações da visita `#fv-acao`/`#fv-acao-resp`/`#fv-acao-prazo`/`#fv-acao-prio`/`#fv-acao-add`/`#fv-acoes`, fotos `#fv-fotos`/`#fv-foto-add`; rodapé `#fv-salvar`/`#fv-cancelar`). `ligarFormVisita(d, v)` liga tudo e `sujar()` marca `formVisita.sujo` em `input`/`change`/clique em botão (exceto salvar/cancelar). Gravação em `#fv-salvar`: `dados = {prefixo, status, resumo}`; `PLANEJADA` → `dataPlanejada`; `REALIZADA` → exige os 4 critérios, envia `dataRealizada`, notas, `movimento`, `claros`, `notaGeral`, `melhorias` (`join('|')`), `percepcao`; `post('visita'|'visita/{id}')` e depois, em sequência, um `post('ponto', {..., visitaId})` por ação, um `post('ponto/{pid}/verificar')` por conferência (se não PLANEJADA) e `enviarFotos('visita/{id}/foto', fotos)`; em falha parcial, `formVisita.editando = id` e o botão vira "Reenviar o que faltou". Limite de **12 fotos por visita** (`#fv-foto-add`). `visitaCardHtml(v, anterior, acoesDaVisita)`: badges, nota, deltas por critério, melhorias, percepção, ações da visita (`acaoCardHtml(p, {compacta:true})`), fotos + `foto-visita`; ações `realizar`, `editar-visita`, `excluir-visita`.

`acaoCardHtml(p, opts)` (`opts.compacta`, `opts.comAgencia`): `.acao-card` com classes `vencida`, `resolvida`, `aguardando`, `parada`, `cobrar`, `compacta`; `data-ponto`, `data-prefixo-acao`, `data-cadencia` (= `p.cadenciaDias`); `span.prio.<prioridade>`; `.meta` com status, `prazoBadge`, responsável, "sem retorno há N dia(s)", "cobrada há N dia(s) (K×)", "reaberta N×", "comprovada"/"sem prova", 📷 antes/depois, origem, registros, solução; botões `confirmar`/`nao-feito` (aguardando), `cobrei`, `tratar`, `informou`, `resolver`, `reabrir`, `historico`, `excluir-ponto`; só no card completo: `.fotos-par` (`foto-antes`, `foto-depois`), `.retorno` (`[data-campo="retorno"]`, `[data-campo="status-novo"]`, `retorno-enviar`) e `.cobranca` (`[data-campo="adiar"]` com `[3,7,15,30]` + cadência da prioridade, `cobrei`); `div.mini-form[data-mini]` e `div.timeline[data-timeline]`. `abrirMiniForm(cartao, modo)` (`resolver`/`informou`; até **6 fotos** por conclusão). `tratarAcaoCard(acao, cartao, depois)`: trava `cartao._emVoo` para `ACOES_QUE_GRAVAM` (`tratar`, `reabrir`, `resolver-enviar`, `informou-enviar`, `confirmar`, `nao-feito`, `cobrei`, `retorno-enviar`, `excluir-ponto`); `desistir()` solta a trava em validações; `cobrei` usa o select `adiar` ou, no card compacto, `data-cadencia`. `atualizarContadorAcoes()` → `api('hoje')` → `#nav-acoes-n` e `#acoes-n-cobrar`. `notaHtml(a)` com `fixar`/`excluir-anotacao` (Master).

### 9.7 Planejamento, vista Ações, busca, aviso do dia, fotos

- `carregarPlanejamento()`: `#plan-resumo`; `.kpis` via `kpi(valor, rotulo, cls)` (`destaque`/`ok`/`atencao`/`critico`; fechamento comprovado ≥ 70 ok, ≥ 40 atenção), cartões "Esta semana" (`estaSemana || atrasada`), "Cobrar hoje", "A conferir na próxima visita" (cards compactos com agência), "Fila de visitas", "Agências frias", "Evolução entre visitas" (`table.tabela.cartoes`), "Fotos pendentes", "Minhas anotações"; `animarContadores` (respeita `prefers-reduced-motion`).
- `carregarAcoes()`/`ligarAcoes()`: `filtroAcoes = {chave:'PENDENTES', prioridade:'', regional:'', texto:''}`, guarda `seqAcoes`; tradução: `PENDENTES` → `status=PENDENTES`, `RESOLVIDO` → `status=RESOLVIDO`, `VENCIDAS`/`7DIAS`/`COBRAR`/`PARADAS` → `prazo=…`, `AGUARDANDO` → `status=AGUARDANDO_VERIFICACAO`, `SEMPROVA` → `status=RESOLVIDO&prova=SEM`, `""` → sem filtro; texto filtrado só no cliente (`descricao`, `agencia`, `responsavel`, `solucao`, debounce 250 ms); `#acoes-regional` preenchido com as regionais de `App.mapa.agencias`; resultado agrupado por `prefixo` com card completo.
- `ligarBusca()`: filtra `App.mapa.agencias` por nome/prefixo/município/UF/regional (10 resultados); `/` foca, Enter abre o primeiro, Escape fecha.
- `mostrarAvisoDoDia()` (Master): chave `localStorage['atlas.aviso.' + isoLocal(Date.now())]` (uma por dia, nunca limpa); `api('hoje')` → faixa `#aviso-dia` com `cobrarHoje`, `vencidas`, `paradas`, `aConferir`, `visitasHoje`, `visitasAtrasadas` (`altasParadas` não é exibido), botões "Ver cobranças" e "Minha gestão", "fechar por hoje". `mostrarFaixa(html)` para a faixa de somente leitura.
- Fotos: `reduzirImagem`, `enviarFotos(rota, arquivos, extra)` (FormData com partes `arquivo`, renomeadas `.jpg` quando convertidas; recusa em somente leitura), `resumoFotos(r)`, `escolherFotos(inputId)`; exibição sempre `CTX + '/foto/' + id` com `onclick="window.open(this.src)"`.

### 9.8 Tema, tokens e classes de estado (`atlas.css`)

`:root` declara o tema claro e `color-scheme:light`; `html[data-tema="escuro"]` sobrescreve os mesmos tokens. Tokens: superfícies `--bg`, `--card`, `--elev`, `--bg-3`, `--bg-4`; bordas `--borda`, `--borda-forte`; texto `--texto`…`--texto-4`; acento dourado `--acento` (`#f5c518`), `--acento-2`, `--acento-texto`, `--acento-suave`, `--acento-brilho`, `--sobre-acento`; navy `--navy`, `--navy-2`, `--navy-texto`, `--navy-dim`, `--navy-rule`; semânticas `--verde`, `--ambar`, `--vinho`, `--info` (cada uma com `-suave` e `-texto`); sombras `--sombra`, `--sombra-md`, `--sombra-lg`; fontes `--ff`, `--ff-display`, `--ff-mono`; raios `--radius` (10px), `--radius-lg` (14px), `--radius-sm` (7px); `--ease-out`; mapa `--mapa-uf`, `--mapa-uf-borda`, `--mapa-uf-ativa`, `--mapa-extrusao`, `--mapa-grade`; medidas `--sidebar: 248px`, `--topo: 60px`; aliases herdados `--fundo`, `--papel`, `--tinta`, `--gold`, `--gold-bg`, `--wine`, `--wine-suave`, `--topo-bg`… (o `admin.js` ainda usa `var(--wine)`). Use sempre `var(--token)`, nunca hex direto; para estados siga o par `-suave`/`-texto`.

Classes que o JS liga: `html.master` (mostra `.so-master`, `display:inline-flex`), `html.somente-leitura` (esconde `#fv-form`, `#p-salvar`, `#a-salvar`, `#plan-anotar`, `#salvar-gmaps`, `.acao-card .acoes-nota/.retorno/.mini-form`, `.fotos-par .botao`, `.foto-add`, `.visita-card .acoes-nota`, `.nota .acoes-nota`, `#fv-modo`), `.vista.ativa`, `.aba-corpo.ativa`, `.nav-lateral a.ativa`, `.drawer.aberto`, `.painel-dash.aberto`, `.veu.aberto`, `.sidebar.aberta`, `.veu-menu.aberto`, `#svg-mapa.mapa-focado`, `.uf.tem-agencia/.sem-agencia/.selecionada`, `.palco-mapa.plano`, `.pin.visitada/.planejada`, `.acao-card.vencida/.resolvida/.aguardando/.parada/.cobrar/.compacta`, `.tile.ok/.atencao/.critico/.destaque`, `.badge.ouro/.verde/.vinho/.ambar/.info/.neutro`, `.faixa.excelencia/.forte/.atencao/.critico` (as strings `status`/`faixa` do back são usadas diretamente como classe), `.segmentado button.ativo`, `.chip.ativo`, `.pontos-toque button.sel/.marcado`, `.nota.fixada`, `.item-agencia.visitada`, `.farol.ok/.avisos/.erro`, `.toast.visivel`, `.dica-mapa.visivel`, `.aviso-dia`, `.aviso-dia.faixa-aviso`, `.tabela.cartoes` (≤ 1000 px vira cartões com `td::before{content:attr(data-th)}`).

Responsivo (desktop-first): `≤1180px` painel 380 px; `≤1000px` sidebar off-canvas, `.so-mobile` aparece, drawer `100vw`, dash `inset:0`, tabelas `.cartoes`; `≤900px` dash em 1 coluna; `≤480px` tiles menores; `prefers-reduced-motion` zera animações.

### 9.9 `admin.js`

Helpers próprios + `falhaSecao(seletor, colunas)` (fábrica de `catch` que escreve `.aviso` na seção). Seções: `montarImports` (`IMPORTS` = 5 tipos tipados; `FormData{arquivo, confirmar}` para `admin/import/{tipo}`; prévia e confirmação), `carregarCadencia`/`renderCadencia`/`montarCadencia`, `carregarPasta`/`renderPasta`/`montarPasta` (+ `#monitor-rodar` → `admin/monitor/rodar`), `carregarFontes` (farol por `ultimoStatus`; botões `data-f-analisar`, `data-f-importar`, `data-f-upload`, `data-f-relatorio`, `data-f-editar`, `data-f-excluir`), `rodarFonte`, `uploadFonte` (prévia com `confirmar:'0'` e botão `#rel-confirmar`), `renderRelatorio` (tiles, `porRegra`, correções, rejeitadas + link `/rejeitadas`), `editarFonte`/`limparFonte`/`atualizarCamposTipo` (`admin/campos/{tipo}`, `admin/modelo/{tipo}`)/`montarFontes` (não envia `ativo` — não há como desativar uma fonte pela tela, só `automatico=0`), `preencherFontesVisao` (só `indicadores`/`conexao`), `carregarColunasVisao`, `carregarVisoes`/`editarVisao`/`limparVisao`/`montarVisoes`, `carregarAgencias` (`regiao/lista?tipo=agencias`, LIMIT 500) e `montarFotos` (valida `LIMITE_FOTO`/`LIMITE_ENVIO`), `carregarMasters`/`carregarFlags`/`carregarLog`, botões de exemplo. Boot em `DOMContentLoaded`.

## 10. Build, testes e execução local

### 10.1 `build.sh`

Pré-requisitos: Bash (`set -euo pipefail`), **JDK 9+** (`javac --release 8 -Xlint:-options`), `java`, `jar`, `find`/`sed`/`awk`/`grep`/`cp`, e **`python3` só no modo dev**. Sem rede, Maven, Gradle ou npm. Faz `cd "$(dirname "$0")"`. `MODO="${1:-prod}"`.

Passos comuns: `CP="../lib/javax.servlet-api-3.1.0.jar:lib/sqlite-jdbc-3.36.0.3.jar"`; `rm -rf build`; compila **todo** `src/br/**/*.java` (inclusive `br.com.bb.sso`) em `build/classes` e `src/test/SelfTest.java` em `build/test-classes`; **roda `java … SelfTest`** (qualquer `AssertionError` aborta, sem flag para pular); monta `build/war/` (`WebContent/.`, `WEB-INF/classes`, `WEB-INF/lib/sqlite-jdbc-3.36.0.3.jar`). SSO: se existir `terceiros/sso/br/com/bb/sso/filter/FilterOauth2.class`, a árvore `terceiros/sso/br/com/bb/sso/.` (binários oficiais do BB) **sobrescreve** as classes próprias e o primeiro `terceiros/sso/json-*.jar` vai para `WEB-INF/lib/` ("SSO: usando os binários OFICIAIS do BB"). Fora do modo dev: se existir `terceiros/sso/oauth.properties`, ele vai para `WEB-INF/classes/oauth.properties` (aviso, sem abortar, se o `redirect_uri` não for `https://super-pf1.intranet.bb.com.br/atlasestilo`; `SSO_SEGREDO=1` se o `client_secret` não começa com `TROQUE_AQUI`); senão entra o `oauth.properties.exemplo`.

| | `./build.sh` (prod) | `./build.sh dev` |
|---|---|---|
| Saída | sempre `dist/atlasestilo.war` (remove antes `dist/atlasestilo.war` e um eventual `dist/atlasestilo-sem-sso.war` antigo) | `dist/atlasestilo-dev.war` |
| `web.xml` do WAR | intacto | `sed` apaga o intervalo `<!-- SSO-INICIO` … `SSO-FIM -->` e um heredoc Python troca `atlas.dev.simular` de `false` para `true` (as linhas `sed`/`awk` adjacentes são inertes) |
| `oauth.properties` no WAR | real (de `terceiros/sso/`) ou o `.exemplo` | não entra |
| `sql/schema.sql` | regenerado (`cp WebContent/WEB-INF/sql/schema.sql sql/schema.sql`) | não |
| Verificação | `jar tf` exige `WEB-INF/classes/br/com/bb/sso/filter/FilterOauth2.class`, `WEB-INF/classes/br/com/bb/sso/bean/Usuario.class` e `WEB-INF/classes/oauth.properties` | — |
| Mensagem final | `OK: dist/atlasestilo.war (produção, SSO do BB, oauth.properties real — NÃO commitar este WAR)` com segredo real; sem ele, `OK: … (produção, SSO do BB)` + AVISO de segredo de EXEMPLO | `OK: dist/atlasestilo-dev.war (DEV — sem SSO, usuário simulado)` |
| Sobe no Tomcat? | sim; sem segredo real o filtro mostra "SSO não configurado" | sim, só para teste |

Estado do checkout: `terceiros/sso/oauth.properties` existe localmente (ignorado) e `dist/atlasestilo.war` foi gerado (ignorado). `dist/atlasestilo-dev.war` é versionado; `build/` é ignorado. Como o segredo pode ficar fora do WAR (`OAUTH_CLIENT_SECRET` no ambiente do serviço do Tomcat, `-Doauth.client_secret` ou arquivo externo em `OAUTH_PROPERTIES`), um WAR gerado com o `.exemplo` também serve, desde que o servidor forneça o segredo por um desses meios.

### 10.2 `SelfTest` (`src/test/SelfTest.java`)

Classe `SelfTest` (pacote padrão) com `main`: cria pasta temporária, `Db.iniciar`, `AppListener.executarSql(WebContent/WEB-INF/sql/schema.sql)` (caminho relativo: **rode a partir de `atlas-estilo/`**), `Migracoes.aplicar()` duas vezes com conferência de colunas/índices via `pragma_table_info`/`sqlite_master`, `ConfigDao.semearMastersSeVazio()`, `DadosExemplo.semear()`. Helpers: `verifica(nome, ok)` (conta e lança `AssertionError("FALHOU: " + nome)`), `contar(sql)`, `ocorrencias(s, trecho)`, `objetoDe(json, trecho)` (recorta o objeto JSON **plano** que contém o trecho, ex.: `"\"id\":" + id + ","`), `igual(Double, double)`, `xlsxDeTeste()`. Grupos: contagens do exemplo; perfis (`Sessao.montar` com `f3548926`/`9999` → Master, `F0000001`/`9007` → Moderador, `F0000002`/`9101` → Colega `ESTILO SP CAPITAL`, prefixo `1234` → `NÃO MAPEADA`); mapa por perfil; métricas; gestão básica; `testarVisitasEAcoes` (checklist, COALESCE, fotos por perfil, filtros, cadência, verificação/prova, dia-calendário, `resumoDoDia`, `planejamento`, CSV, privacidade nos agregados, `AuthFilter.normalizar`, exclusões); imports (prévia × commit, última ocorrência vence, reimport parcial preserva `gmaps_url`/`regional`/`municipio`, coluna vazia limpa); `Csv.ler`, `Xlsx.ler`, `normalizarSemestre`, `Texto.prefixo/decimal`, `Xlsx.dataDeCelula`; município acentuado; master removido não volta; `testarSaneador`; `testarFontes` (fontes, `resolverArquivo`, `analisarFonte`, `MonitorCsv.rodar`, Conexão, indicadores, visões, modo estrito, `fonteExcluir`); limpar exemplo; `testarSso` (partes puras do login, sem rede: `JsonLeve` com todos os tipos, escapes e `\u`, rejeição de JSON malformado e de raiz não-objeto; `Usuario` com claims planas, `sub`/`given_name`+`family_name`/`dependencia.prefixo`, busca de claim sem distinguir maiúsculas, `null` sem matrícula, `claim.matricula` do properties prevalecendo; `Sessao.montar` lendo o `Usuario` próprio por reflexão; `FilterOauth2.urlAutorizacao`, `validar` (configuração válida, segredo de exemplo recusado, chave obrigatória ausente), `padrao`/`candidatosDescoberta`, `decodificarJwt`, sobreposição por system property, `novoState` de 32 hex). Saída final: `SelfTest OK — 199 verificações.` (número citado no `README-DEPLOY.md`; atualize-o ao acrescentar checks). O login de ponta a ponta foi testado fora do build contra um servidor OAuth2 simulado, nos três perfis, com e sem discovery e com segredo errado. Não existe teste da camada web (`ApiServlet.exigir`, `FotoServlet`, `AuthFilter.doFilter`); isso só foi coberto por e2e não versionados.

Rodar só o SelfTest:

```
cd atlas-estilo
CP="../lib/javax.servlet-api-3.1.0.jar:lib/sqlite-jdbc-3.36.0.3.jar"
javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP" -d build/classes $(find src/br -name '*.java')
javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP:build/classes" -d build/test-classes src/test/SelfTest.java
java -cp "build/classes:build/test-classes:lib/sqlite-jdbc-3.36.0.3.jar:../lib/javax.servlet-api-3.1.0.jar" SelfTest
```

### 10.3 WAR dev e perfis simulados

`./build.sh dev` → `dist/atlasestilo-dev.war` (sem `FilterOauth2`, `atlas.dev.simular=true`). Copie para `webapps/` de um Tomcat local (contexto `atlasestilo`); os dados vão para `<tomcat>/dados/atlasestilo/`. Abra `/atlasestilo/` (Master simulado `F3548926`), `/atlasestilo/?perfil=COLEGA`, `?perfil=MODERADOR`, `?perfil=MASTER` (seção 5.10; se não trocar, espere os 60 s do cache ou use janela anônima). Para testar somente leitura, aplique a flag à matrícula simulada no Admin. Chamadas POST por `curl` precisam de `-H 'X-Atlas: 1'` e do cookie de sessão. **Nunca implante o WAR dev em produção.**

### 10.4 Testes e2e (não versionados)

Nos trabalhos anteriores foram usados um Tomcat 9.0.98 local (porta 8099, não o 8.5 de produção), Playwright (`playwright-core`) e, antes do SSO próprio existir, um **stub** do SSO (`FilterOauth2` que lia `?sso=matricula:prefixo` e punha um `Usuario` na sessão; `org.json.JSONObject` mínimo empacotado como `json-20230618.jar`) injetado com `injetar-sso.sh` no então `-sem-sso.war`; no commit `58e7e40` o login real foi exercitado contra um servidor OAuth2 simulado. Os scripts cobriam contexto por perfil, 403 nas rotas de Master, `X-Atlas`, foto restrita, `admin.jsp`, `erro.jsp`, aviso do dia, cobrança compacta, mobile 390×844. Nada disso está no repositório (vivia no scratchpad da sessão); decidir se entra em `atlas-estilo/e2e/` é pendência (seção 14).

## 11. Deploy em produção e SSO do BB

### 11.1 Ambiente

Servidor `super-pf1`: Windows Server 2012, Apache Tomcat 8.5 (8.5.99) como serviço, **x86 (32 bits)**, JRE 1.8.0_25, em `C:\Program Files (x86)\Apache Software Foundation\Tomcat 8.5`; HTTPS 443 (keystore `superpf.jks`), HTTP 8080; URL pública `https://super-pf1.intranet.bb.com.br/<contexto>`. O contexto desta ferramenta é `/atlasestilo` (nome do WAR). Não há SSO global no Tomcat: cada webapp carrega o próprio `FilterOauth2` (nas outras ferramentas, o binário do BB; aqui, a implementação própria de 11.2). Não há JDK no servidor. O servidor precisa alcançar `login.intranet.bb.com.br` por HTTPS (o filtro fala com o SSO a partir do Tomcat, não só do navegador).

### 11.2 O login OAuth2 próprio (`src/br/com/bb/sso`)

Desde `58e7e40` o WAR autentica sozinho: `br.com.bb.sso.filter.FilterOauth2`, `br.com.bb.sso.bean.Usuario` e `br.com.bb.sso.util.JsonLeve` são código do projeto, com o **mesmo nome e contrato** da classe oficial do BB (1º filtro em `/*`; ao autenticar põe o `Usuario` em `HttpSession["usuario"]`, constante `FilterOauth2.ATTR_USUARIO`). Nada em `br.com.bb.atlasestilo.*` importa esse pacote.

**Fluxo de `FilterOauth2.doFilter`** (authorization code):

1. Força `UTF-8`. Com `usuario` na sessão: `/sso/diagnostico` → `diagnostico(resp)` (texto com `client_id`, `redirect_uri`, `login_endpoint`, `scopes`, flags e endpoints em uso, **sem** o segredo); um GET com `?code&state` sobrando → redirect a `/`; senão segue a cadeia.
2. Sem usuário: se `erroConfig != null` (`validar(cfg)` falhou: chave obrigatória ausente, `client_secret` ainda `TROQUE_AQUI…`, `login_endpoint` sem `http`) → `paginaErro` 500 "SSO não configurado". Se vier `?error&state` válido → 502 "O login do BB devolveu um erro" com `dicasPara(erro)` (`redirect_uri`, `invalid_client`, `invalid_scope`, `access_denied`). Se vier `?code&state` → `tratarRetorno`. Se `ehApi(req, caminho)` (`/api/*`, `/foto/*`, cabeçalho `X-Atlas` ou `X-Requested-With`, ou `Accept` JSON sem HTML) ou método não GET/HEAD → `json401` (`{"erro":"Sessão expirada. Recarregue a página para entrar de novo.","login":true}`, `Cache-Control: no-store`). Senão `iniciarLogin`.
3. `iniciarLogin`: `resolverEndpoints()`; sessão nova com `oauth2.state` (`novoState()`, 16 bytes `SecureRandom` em hex) e `oauth2.destino` (URI + query pedidas; `/sso/diagnostico` vira `/`); redirect a `urlAutorizacao(authorize, cfg, separador, state)` = `response_type=code&client_id=…&redirect_uri=…&scope=…&state=…` (scopes separados por `scopes_separador`, padrão espaço).
4. `tratarRetorno`: `stateConfere` (senão 403 "Não foi possível concluir o login" com dicas: acessar exatamente pelo `redirect_uri`, cookie `JSESSIONID`, sessão expirada); `trocarCodigo(tokenEndpoint, code)` — POST `grant_type=authorization_code` com `Authorization: Basic client_id:client_secret` e, se 401 ou 400 `invalid_client`, repete com `client_id`/`client_secret` no corpo; ≠ 200 → 502 com dicas (`redirect_uri_mismatch`/`invalid_grant`, `invalid_client`, 404 = `token_endpoint` errado); `obterClaims`: payload do `id_token` (`decodificarJwt`, sem validar assinatura) + `userinfo` (Bearer; prevalece) + `tokeninfo` (reserva, se ainda não há matrícula) + payload do próprio `access_token` se for JWT e nada veio; `new Usuario(claims)`; sem `getChaveUsuario()` → 502 "O SSO autenticou, mas não informou a matrícula" (dica: `claim.matricula=<nome>`); `sessao.invalidate()` + sessão nova só com `usuario` (anti-fixação); log `"login de <matrícula> (prefixo <p>)"`; redirect ao destino (ou `/` se o destino não é do contexto ou contém `code=`).
5. `resolverEndpoints()`: endpoints fixados no properties (`authorize_endpoint` + `token_endpoint`) dispensam descoberta; senão, no máximo uma tentativa por minuto em `candidatosDescoberta(login)` (`/sso/oauth2/.well-known/openid-configuration`, `/.well-known/openid-configuration`, `/oauth2/…`, `/openam/oauth2/…`, `/auth/…`) lendo `authorization_endpoint`, `token_endpoint`, `userinfo_endpoint`; sem sucesso, `padrao(login)` = `<login_endpoint>/sso/oauth2/{authorize,access_token,userinfo,tokeninfo}` com sobreposição individual pelo properties; resultado em cache até o redeploy (`origemEndpoints` diz de onde veio).
6. `http(...)`: `HttpURLConnection` com `timeout_ms` (padrão 10000), sem seguir redirects, `User-Agent: AtlasEstilo-SSO/1.0`, **sem proxy** salvo `usar_proxy=true`, `tls_ignorar_certificado=true` desliga a validação TLS (só provisório); `SSLException` → 502 "Falha de TLS ao falar com o SSO" com o comando `keytool -importcert -keystore <JRE>/lib/security/cacerts -alias bb-ca -file ca.cer`; `IOException` → 502 "Não consegui falar com o servidor do SSO".
7. `paginaErro` é HTML standalone em português (título, detalhe em `<code>`, lista "O que conferir", botão "Tentar de novo"); nunca a página do Tomcat.

**Configuração** (`FilterOauth2.carregarConfiguracao`): `WEB-INF/classes/oauth.properties` do WAR (constante `ARQUIVO`) → sobreposto por um arquivo externo apontado por `OAUTH_PROPERTIES`/`-Doauth.properties.path` → cada chave sobreposta por `-Doauth.<chave>` ou variável de ambiente `OAUTH_<CHAVE>` (pontos viram `_`; `FilterOauth2.valorAmbiente`). Chaves: obrigatórias `client_id=SUPERPF1`, `client_secret=<segredo>`, `redirect_uri=https://super-pf1.intranet.bb.com.br/atlasestilo`, `login_endpoint=https://login.intranet.bb.com.br`; `scopes=profile,bbprofile,bbrole`, `cookie_sso=BBSSOToken` (herdados das demais ferramentas); opcionais `authorize_endpoint`, `token_endpoint`, `userinfo_endpoint`, `tokeninfo_endpoint`, `descoberta` (`false` desliga), `scopes_separador`, `timeout_ms`, `usar_proxy`, `tls_ignorar_certificado`, `claim.matricula`, `claim.nome`, `claim.nomeGuerra`, `claim.prefixo`, `claim.comissao`, `claim.email` (listas por vírgula aplicadas por `Usuario.configurarMapeamento`). Modelo comentado: `terceiros/sso/oauth.properties.exemplo`.

**`Usuario`**: `new Usuario(Map claims)` achata mapas aninhados (`dependencia.prefixo`) e listas (valores unidos por vírgula) em `LinkedHashMap<String,String>`; `get(claim)` exato e depois sem distinguir maiúsculas; `getChaveUsuario` (maiúsculas; candidatas padrão `chaveUsuario, chave, matricula, uid, username, preferred_username, sub, login, user_id`), `getNomeUsuario` (`nomeUsuario, nome, name, displayName, nomeCompleto, cn, fullName, given_name`, concatenando `family_name` quando veio de `given_name`), `getNomeGuerra`, `getPrefixo` (`prefixo, prefixoDependencia, prefixo_dependencia, codigoPrefixo, dependencia.prefixo, codigoDependencia, prefixoLotacao, uor.prefixo, lotacao.prefixo, prefixoUor`; `"9007.0"` → `"9007"`), `getNomeComissao` (`nomeComissao, comissao, nome_comissao, cargo, funcao, nomeCargo, comissao.nome, nomeFuncao`), `getEmail`, `getClaims`; aliases `getUid`, `getUsername`, `getName`, `getDisplayName`, `getCargo`; construtor `Usuario(matricula, nome, prefixo, comissao)` para testes. `JsonLeve.ler`/`lerObjeto`/`texto`: parser estrito que devolve `LinkedHashMap`, `List`, `String`, `Long`, `Double`, `Boolean` ou `null`, com posição no erro.

### 11.3 Gerar o WAR pronto

1. `cp terceiros/sso/oauth.properties.exemplo terceiros/sso/oauth.properties` e trocar **só** o `client_secret` (o `redirect_uri` já aponta para `/atlasestilo`; os demais valores são os das outras ferramentas SUPER PF1). O arquivo é ignorado pelo git.
2. `./build.sh` → `dist/atlasestilo.war` (também ignorado pelo git) com a mensagem `OK: dist/atlasestilo.war (produção, SSO do BB, oauth.properties real — NÃO commitar este WAR)`. O build confere no WAR `FilterOauth2.class`, `Usuario.class` e `oauth.properties`.
3. Alternativa sem gravar o segredo no WAR: gerar com o `.exemplo` (o build avisa "segredo de EXEMPLO") e definir `OAUTH_CLIENT_SECRET` no ambiente do serviço do Tomcat, `-Doauth.client_secret` nas opções da JVM ou um arquivo externo em `OAUTH_PROPERTIES`.

**Opcional — binários oficiais do BB.** Copiar de `<Tomcat>\webapps\boaspraticas\WEB-INF\` os arquivos `classes\br\com\bb\sso\filter\FilterOauth2.class`, `classes\br\com\bb\sso\bean\Usuario.class` e `lib\json-20230618.jar` para `terceiros/sso/` (árvore `br/com/bb/sso/...`): o `./build.sh` passa a embuti-los **no lugar** das classes próprias. No servidor, sem JDK, `injetar-sso.bat [caminho\atlasestilo.war] [webapps\boaspraticas]` (→ `powershell -NoProfile -ExecutionPolicy Bypass -File injetar-sso.ps1` com `-War`, `-Origem`, `-RedirectUri`) faz a mesma troca dentro de um WAR já gerado: acha o WAR e a origem (`CATALINA_BASE`/`CATALINA_HOME`/`Program Files (x86)` + `webapps\boaspraticas` ou `dashjunho`), copia todas as `*.class` sob `br\com\bb\sso`, o primeiro `json-*.jar` e o `oauth.properties` da origem com o `redirect_uri` trocado, recusa o WAR dev (procura `br.com.bb.sso.filter.FilterOauth2` no `web.xml`) e grava `atlasestilo.war` ao lado (backup `.sem-sso.bak` se sobrescrever). Em Linux/macOS, `terceiros/sso/injetar-sso.sh [war] [origem] [redirect_uri]` (`unzip`/`zip`; origem em layout de webapp ou "solto"). Nada disso é necessário para o WAR funcionar.

### 11.4 Passo a passo do deploy

1. Pedir à equipe do SSO/BB o registro de **`https://super-pf1.intranet.bb.com.br/atlasestilo`** para o `client_id` `SUPERPF1` — idêntico, `https`, sem barra final. Sem isso o login volta com **`redirect_uri_mismatch`** (não é bug de código; a página de erro do filtro e a equipe do SSO mostram qual URI chegou). Acessar a ferramenta **exatamente por esse endereço**: outro host/porta cria outra sessão e o `state` do retorno não confere. Trocar qualquer valor do OAuth não exige recompilar: editar `<Tomcat>\webapps\atlasestilo\WEB-INF\classes\oauth.properties` (ou usar `OAUTH_<CHAVE>`) e reiniciar o Tomcat.
2. Ter o `atlasestilo.war` **com o segredo** (11.3) ou o segredo no ambiente do serviço.
3. Certificado do SSO: o Tomcat fala com `login.intranet.bb.com.br` a partir do servidor; se a JRE não confiar no certificado, a página de erro "Falha de TLS ao falar com o SSO" mostra o `keytool -importcert` a rodar (provisoriamente, `tls_ignorar_certificado=true`). Se o servidor só sai por proxy, `usar_proxy=true`.
4. Copiar para `<Tomcat>\webapps\` e **deixar o `.war` lá permanentemente** — removê-lo faz o Tomcat desfazer o deploy (404). Atualização de versão = novo `./build.sh` → substituir o `.war`; o banco não precisa de intervenção (`schema.sql` idempotente + `Migracoes.aplicar()`). Depois do primeiro login, `/atlasestilo/sso/diagnostico` confirma os endpoints em uso; se a matrícula ou o prefixo vierem com outro nome de claim, ajustar `claim.matricula`/`claim.prefixo`.
5. Pastas de dados: `${catalina.base}\dados\atlasestilo\{atlas.db, fotos, csv}` (context-params `atlas.db.path`, `atlas.foto.dir`, `atlas.csv.dir`), criadas na primeira subida com teste real de escrita. Se o log acusar `Sem permissão de ESCRITA em …` ou `Não foi possível criar a pasta de dados`: `icacls "<Tomcat>\dados" /grant "NETWORK SERVICE":(OI)(CI)M /T` (ajustar à conta de serviço real) e reiniciar. Não usar `C:\dados`.
6. Ler `logs\catalina.*.log`/`localhost.*.log`: sucesso é `[atlasestilo] Iniciado. db=… fotos=… csv=…` e `[SSO] pronto. client_id=… redirect_uri=… login_endpoint=… scopes=…` (ou `[SSO] CONFIGURAÇÃO INVÁLIDA: …`). 404 no contexto inteiro → stack trace do startup (`IllegalStateException` do `AppListener` = pasta/driver/schema) ou "Undeploying context" (`.war` removido).
7. Primeira subida com banco vazio: masters iniciais + 26 agências de exemplo (se `atlas.dados.exemplo=true`); o Master importa as planilhas reais no Admin e usa "limpar exemplo".
8. `atlas.maps.ativo` já vem `false` (a intranet não alcança `maps.google.com`); trocar para `true` só se a rede liberar.

### 11.5 Armadilhas do servidor

- **Antivírus** pode pôr as DLLs nativas do `sqlite-jdbc` em quarentena: contexto que "some sozinho" → verificar quarentena e criar exceção para a pasta do Tomcat. O aviso `clearReferencesJdbc` no stop é inofensivo.
- **Memória**: JVM x86 com heap pequeno; `FonteService` limita o CSV a `min(60 MB, maxMemory/16)` (status `ERRO` com o valor calculado); aumentar o "Maximum memory pool" em `Tomcat8w.exe` se preciso.
- **WAL**: `atlas.db-wal`/`atlas.db-shm` ficam ao lado do banco; copiar só o `atlas.db` com o Tomcat no ar pode perder transações — pare o Tomcat (ou faça checkpoint) antes de copiar.
- **Redeploy**: `MonitorCsv.parar()` espera até 60 s por importação em curso (a versão nova não pode migrar o banco enquanto a thread antiga escreve).
- **`File.delete` no Windows**: exclusões de foto caem para `deleteOnExit()` quando o arquivo está aberto.
- **Pasta de CSV**: `csv.pasta` gravado no Admin prevalece sobre `atlas.csv.dir` do `web.xml` para sempre (até ser trocado no Admin). A API exige caminho absoluto.

### 11.6 O segredo e a rotação

O `client_secret` do client `SUPERPF1` esteve versionado em claro no repositório **público** (primeiro commit `ce3ae83`, em `WebContent/WEB-INF/classes/oauth.properties`, e embutido nos WARs de `dist/` dos commits seguintes). Saiu do código no commit `c1c16f9` (virou `terceiros/sso/oauth.properties.exemplo` com placeholder; o WAR antigo foi removido; `.gitignore` ganhou as regras do SSO) e, desde `58e7e40`, o `dist/atlasestilo.war` gerado pelo build também é ignorado porque volta a embutir o segredo — mas o valor antigo **continua no histórico do git**. Pendência: pedir à equipe do SSO/BB a **rotação** do segredo e atualizar o `oauth.properties` de **todas** as ferramentas SUPER PF1 no servidor (mesmo client) e o `terceiros/sso/oauth.properties` local (fora do git). Ao citar a referência `sso-e-servidor.md` da skill (que traz o valor em claro), substitua por `<segredo>`. Nunca commitar o valor.

## 12. Receitas

Cada receita lista os pontos a tocar, na ordem. Depois de qualquer alteração: `./build.sh` (o `SelfTest` precisa passar) e atualizar este documento e o `README-DEPLOY.md` quando o comportamento mudar.

**R1 — Coluna nova numa tabela que já existe em produção.** (1) Acrescentar a tripla `{ "tabela", "coluna_minuscula", "TIPO [NOT NULL DEFAULT constante]" }` ao fim do bloco da mesma tabela em `db/Migracoes.COLUNAS` (não mexer no `CREATE TABLE` do `schema.sql`: convenção do projeto). (2) Índice sobre ela, se houver, em `Migracoes.INDICES` como `CREATE INDEX IF NOT EXISTS …` completo. (3) Gravar/ler a coluna no DAO (INSERT, UPDATE com `COALESCE`, JSON). (4) Em `SelfTest`, acrescentar o nome à verificação de migração via `pragma_table_info`. (5) `./build.sh`.

**R2 — Tabela nova.** `CREATE TABLE IF NOT EXISTS` (+ índices) em `WebContent/WEB-INF/sql/schema.sql`, uma linha terminada em `;` por comando, seguindo as convenções (epoch ms, 0/1, `criado_por`/`criado_em`, `origem` com DEFAULT 'IMPORT' quando vier de planilha); limpeza manual no DAO que exclui a "mãe" (não há FK); se o gerador de exemplo a alimentar, `origem = 'EXEMPLO'`/`criado_por = 'EXEMPLO'` e `DELETE` em `ConfigDao.limparExemplo`; `./build.sh` (prod) regenera `sql/schema.sql`.

**R3 — Rota GET nova.** `case "minharota":` no `switch` de `cam[0]` em `ApiServlet.doGet`; `if (!exigir(resp, s.master())) return;` (ou `s.veTudo()`) se restrita; parâmetros com `Http.param/paramInt/paramLong`, sub-rotas por `cam[1]`, `cam[2]`; JSON montado no DAO com `Json.obj()/arr()`; `Http.json(resp, json)`; erros `Http.erro(resp, 400|404, "Mensagem.")`; ids com `Long.parseLong` (o `catch` já devolve 400). No front, `api('minharota?x=1')`. Se tiver regra de perfil, verificação no `SelfTest`.

**R4 — Rota POST nova (Master).** `case "recurso": postRecurso(req, resp, s, cam, agora); return;` em `doPost` (o `exigir(master)` global cobre); validar (`Http.param` para obrigatórios; `req.getParameter` quando `""` tem significado); gravar via DAO com `s.matricula` e `agora`; responder `Json.obj().put("ok", true).put("id", id).fim()`; no front `post('recurso', {...})`; se mudar perfil/flag de alguém, `AuthFilter.invalidarPerfis()`. Para liberar uma rota a Moderador: trocar `exigir(resp, s.master())` por `exigir(resp, s.veTudo())` no `case` (em POST é preciso mover o `exigir` global para dentro de cada `case`).

**R5 — Vista nova na página principal.** `<section id="vista-xxx" class="vista">` em `index.jsp`; `<a href="<%= ctx %>/#xxx" data-nav="xxx">` em `header.jspf` (dentro do `if (sessao.master())` se restrita); em `atlas.js`: entrada em `TITULOS`, toggle de `.ativa` e bloqueio para não Master em `trocarVista`, `carregarXxx()` no padrão `alvo.innerHTML = '<div class="carregando">…'` → `api(...)` → render com `esc()` → religar listeners (replicar a guarda `var meu = ++seq`). Reutilizar `.cartao`, `.cartao-corpo`, `.painel-titulo.pagina`.

**R6 — Parâmetro novo em `config_parametro`.** Chave no padrão `grupo.nome`; leitura com `FonteDao.param("chave", "padrão")` (ou `GestaoDao.intParam` para inteiro com faixa); gravação com `FonteDao.paramDefinir(chave, valor, s.matricula, agora)` numa rota `POST /api/admin/...` em `ApiServlet.postAdmin`; exposição numa rota GET (ou em `/api/contexto` se o front precisa na abertura); documentar na tabela 6.3.

**R7 — Verificação nova no `SelfTest`.** `verifica("nome curto", <boolean>)` no grupo adequado (ou `private static void testarXxx(...)` chamado do `main`); usar `contar(sql)`, `objetoDe(json, "\"id\":" + id + ",")`, `ocorrencias`, `igual`; só métodos públicos da aplicação; rodar o SelfTest; atualizar "199 verificações" no `README-DEPLOY.md` e aqui (10.2).

**R8 — Gerar o WAR.** Dev: `./build.sh dev` → `dist/atlasestilo-dev.war`. Produção pronta: `cp terceiros/sso/oauth.properties.exemplo terceiros/sso/oauth.properties`, editar só o `client_secret` (o resto já vem certo para `/atlasestilo`), `./build.sh` → `dist/atlasestilo.war` com a mensagem `… oauth.properties real — NÃO commitar este WAR` (o arquivo já é ignorado pelo git). Sem o `oauth.properties` local o WAR sai com o segredo de exemplo e só funciona se o servidor fornecer `OAUTH_CLIENT_SECRET`/`-Doauth.client_secret`/`OAUTH_PROPERTIES`. Opcional: binários oficiais do BB em `terceiros/sso/` (seção 11.3) ou `injetar-sso.*` no servidor.

**R9 — Testar os perfis.** WAR dev + `?perfil=COLEGA|MODERADOR|MASTER` (5.10, 10.3); somente leitura via flag no Admin; `curl` com `-H 'X-Atlas: 1'` para POST. Para a jurisdição de um Colega em teste unitário: `Sessao.montar("F0000002", "Teste", "9101", null)` e `Selecao.de(colega, …).where()`.

**R10 — Trocar a cadência.** Em produção: Admin › Cadência (`POST /api/admin/cadencia` com `alta`, `media`, `baixa`, `parada`, 1..365) — grava `cadencia.ALTA/MEDIA/BAIXA/parada` em `config_parametro`. Para mudar os padrões ou acrescentar prioridade: `GestaoDao.Cadencia` (campos e `dias(prioridade)`), `cadencia()`/`cadenciaDefinir`, `prioridadeValida` e `peso`, `postAdmin` caso `cadencia`, `admin.js renderCadencia/montarCadencia` + inputs `#cad-*`, `acaoCardHtml` (opções de adiar, classe `.prio`), selects `#p-prio`/`#fv-acao-prio`/`#acoes-prioridade`, teste no `SelfTest` (que restaura 7/15/30/14).

**R11 — Limpar ou recarregar o exemplo.** Admin → "limpar exemplo" (`POST /api/admin/exemplo` `acao=limpar`) remove tudo com `origem`/`criado_por` `EXEMPLO` (inclusive o que o Master registrou em agências de exemplo) e grava `exemplo.limpo = '1'`; "recarregar" (`acao=recarregar`) limpa e semeia de novo. Para o boot voltar a semear sozinho num banco vazio seria preciso apagar a linha `exemplo.limpo` de `config_parametro` à mão.

**R12 — Campo novo no checklist da visita.** `Migracoes.COLUNAS` (tabela `visita`); campo em `GestaoDao.Checklist` e em `vazio()`; INSERT de `visitaCriar`, UPDATE com `COALESCE` de `visitaAtualizar`, JSON de `visitaJson`; leitura em `ApiServlet.lerChecklist`; coluna em `csvVisitas` (cabeçalho e valor, posicional); front `checklistFormHtml` (input `#fv-<campo>`), `#fv-salvar` (`dados.<campo>`), `visitaCardHtml`; `SelfTest`; README.

**R13 — Status de ação ou tipo de linha do tempo novo.** Status: lista em `ApiServlet.postPonto`; `pontoAtualizar`, `pontoComentar`, `lerAcao` (`aberta`/`aguardando`/reabrir/`informado_em`); cláusulas `NOT IN ('RESOLVIDO','AGUARDANDO_VERIFICACAO')` e `CASE p.status` em `acoes`; laços de `planejamento`/`resumoDoDia`; `situacao_prazo` em `csvAcoes`; front `STATUS_ACAO`, `prazoBadge`, botões de `acaoCardHtml`, select `status-novo`. Tipo: `tipoAtualizacaoValido`; subconsultas de `SQL_ACOES_BASE` (`ultimo_retorno` ou `cobrancas`); efeitos em `pontoComentar`; `TIPO_ATU` no front; comentário da tabela no `schema.sql`. Não precisa de migração (colunas TEXT).

**R14 — Filtro novo em `/api/acoes`.** Ramo em `GestaoDao.acoes` (se depender da cadência, marcar em `emJava` e filtrar sobre `AcaoLida` após `lerAcoes`); botão `data-v` em `index.jsp #acoes-status`; mapeamento em `atlas.js carregarAcoes` (`q.push('prazo=...')`) e mensagem de vazio; `SelfTest` com `GestaoDao.acoes(null, null, 'NOVO', null, null, agora)`.

**R15 — KPI ou lista nova em "Minha gestão".** Calcular em `GestaoDao.planejamento` (subselect agregado ou laço sobre `abertas`); incluir em `kpis` ou nova chave `putRaw`; renderizar em `carregarPlanejamento` via `kpi(valor, rotulo, cls)` ou novo cartão (`acaoCardHtml(a, {compacta:true, comAgencia:true})` para ações; lembrar `comFotos=false`); `SelfTest`.

**R16 — Item novo no aviso do dia.** Contar em `GestaoDao.resumoDoDia` e incluir no JSON; `mostrarAvisoDoDia` (`itens.push(...)`); se for contador do menu, `atualizarContadorAcoes`; `SelfTest`.

**R17 — Botão/ação nova no card de ação.** `<button class="botao mini claro" data-acao="minha-acao">` em `acaoCardHtml` (dentro de `.acoes-nota` se grava, para ser escondido em `html.somente-leitura`); tratar em `tratarAcaoCard` com `post(...).then(depois).catch(falha)`; se grava, entrar em `ACOES_QUE_GRAVAM` e chamar `desistir()` nos caminhos que abortam; sem listener novo (as delegações já encaminham); rota em `ApiServlet.postPonto`.

**R18 — Coluna cadastral nova na agência.** `schema.sql` (bancos novos) **e** `Migracoes.COLUNAS` (bancos existentes — exceção à R1 porque `agencia` é tabela base); `ImportService.campos("agencias")`, `SINONIMOS`, `numericos` se numérica; INSERT e laço de colunas dinâmicas em `ImportService.gravar` (regra "só sobrescreve o que o CSV traz"); `ImportService.modelo("agencias")`; `AgenciaDao.cabecalho` (e `mapa` se for ao mapa); `atlas.js renderAgencia`; `SelfTest`.

**R19 — Tipo de fonte tipada novo.** `ImportService.TIPOS`, `campos`, `obrigatoriosDe`, `numericos`, `SINONIMOS`, `modelo`, `validar`, `chave`, `existe`, `gravar`; tabela no `schema.sql`; `FonteDao.TIPOS`; `admin.js IMPORTS` e `TIPO_NOME`, `admin.jsp <select id="fonte-tipo">`; `SelfTest`. Sinônimo novo: `ImportService.SINONIMOS.put(campo, …)` (já em MAIÚSCULO sem acento) ou `FonteService.SIN`; sem código, use o campo `mapeamento` da fonte (`campo=Cabeçalho`).

**R20 — Card novo no dashboard a partir de um CSV qualquer.** Salvar o CSV na pasta com `prefixo` (+ `competencia` ou mês no nome, ex.: `nps_2026-09.csv`); `POST /api/admin/fonte` com `tipo=indicadores`, `arquivo=nps_*.csv`; `POST /api/admin/fonte/{id}/importar`; `GET /api/admin/fonte/{id}/colunas`; `POST /api/admin/visao` com `titulo`, `fonteId`, `coluna`, `agregacao`, `formato`, `meta` ou `colunaMeta`, `melhor`, `perfilMinimo`. Aparece em `/api/regiao`.`visoes` e `/api/agencia/{prefixo}`.`visoes`.

**R21 — Diagnosticar fonte que não importa.** `GET /api/admin/fontes` (`ultimoStatus`/`ultimoResumo`: `SEM ARQUIVO` = padrão não casa ou pasta errada; `ERRO` = ver resumo); `GET /api/admin/fonte/{id}/relatorio` (`avisos[]`, `mapeamento{}`, `saneamento.rejeitadas[]`, `estrito`); `GET /api/admin/pasta` (`existe/ehPasta/legivel`, `monitor.ultimoResultado`); `GET /api/admin/importlog`. `analisar` persiste status/relatório sem gravar dados.

**R22 — Depurar um 403.** Mensagem "Requisição sem o cabeçalho de proteção (X-Atlas)." → escrita sem o cabeçalho; "Sua matrícula está em modo somente leitura." → flag; "Acesso negado." → `Sessao` null (SSO sem `usuario`, matrícula vazia ou `BLOQUEADO`); "Seu perfil não tem acesso a esta função." → `exigir`; "Seu perfil vê apenas os grandes números." → lista de pessoas para Colega; "Seu perfil não visualiza fotos de pessoas."/"Foto restrita à gestão." → `FotoServlet`; se acabou de mudar master/flag, lembrar do cache de 60 s.

**R23 — Mudar os limites de upload.** `multipart-config` do `web.xml`, `ApiServlet.LIMITE_FOTO_MB` (texto do 413, que também cita "40 MB" literalmente), `LIMITE_FOTO`/`LIMITE_ENVIO` e mensagens em `admin.js`, textos "até 8 MB cada" em `admin.jsp` e `README-DEPLOY.md`; quantidade: 12 em `#fv-foto-add` (`ligarFormVisita`) e 6 em `mini-foto` (`tratarAcaoCard`), inclusive toasts.

**R24 — Diagnosticar falha de boot.** Procurar no log as mensagens do `AppListener`: "Não foi possível criar a pasta de dados", "Sem permissão de ESCRITA em", "Driver SQLite ausente do WAR (WEB-INF/lib).", "WEB-INF/sql/schema.sql ausente do WAR.", "Falha ao executar o schema", "Falha na migração/carga inicial.", "Falha ao semear o exemplo.". Com o Tomcat parado, conferir o esquema real com `PRAGMA table_info(tabela)` e `SELECT name FROM sqlite_master WHERE type='index'`.

## 13. Decisões de projeto e armadilhas

Cada item diz o que é e **por quê** — antes de "corrigir", leia o porquê.

**Arquitetura e segurança**

- **Sem framework, JSON à mão, DAOs estáticos** — convenção das ferramentas SUPER PF1: o servidor é x86 com JRE antiga, sem build tooling, e o código precisa ser lido e alterado por quem não conhece frameworks. Consequência: cada JSON é montado explicitamente e as respostas compostas por cirurgia de string (`regiao`, `admin/pasta`, `ImportService.processar`, `FonteDao.visoesJson`) assumem que a base termina em `}`/`]` e não é `null`.
- **Leitura do bean do SSO por reflexão** — compila sem o JAR do BB e tolera variações do bean. Custo: getter renomeado vira `null` silencioso ("Acesso negado" para todos).
- **`FilterOauth2` antes do `AuthFilter`, pela ordem dos `<filter-mapping>`** — o `AuthFilter` depende do atributo `usuario`; invertido, todo primeiro acesso cai em `negado.jsp`. Marcadores `SSO-INICIO/FIM` são contrato do `build.sh dev`.
- **Filtros sem `<dispatcher>`** — só REQUEST: forwards e `error-page` não passam pelo `AuthFilter`; por isso `erro.jsp`/`negado.jsp` são standalone.
- **`Http.erro` com `setStatus`** — `sendError` dispararia a `error-page` e descartaria o JSON.
- **Anti-CSRF por cabeçalho `X-Atlas`, sem token** — formulários de outros sites não conseguem enviar cabeçalho custom; o valor não importa. Simples e suficiente numa intranet. Consequência: nada de `<form method=post>`.
- **Cache da `Sessao` de 60 s** — `Sessao.montar` faz três consultas por requisição; o cache evita isso e `invalidarPerfis()` cobre as mudanças que importam (masters/flags). Consequência: mudança de regional via import demora até 60 s; `?perfil=` no WAR dev é ignorado dentro da janela.
- **Moderador = prefixo literal `"9007"`** — não há tabela de moderadores; é a SUPER PF1. Um SSO sem `getPrefixo` torna todo mundo Colega `NÃO MAPEADA`.
- **Colega perdido vê as agências `'NÃO MAPEADA'`** — é o DEFAULT de `agencia.regional` e o que o import grava sem regional; comportamento aceito (normalmente nenhuma agência).
- **`normalizar` rejeita `;` e `%`** — fecha truques de segmento que o container trataria diferente; rejeita também `;jsessionid=`. Valores com acento vão na query string.
- **Autorização Master decidida antes do `switch` em `doPost`** — um só ponto para toda escrita; liberar algo a Moderador exige mover o `exigir` para dentro dos `case`.
- **`erro.jsp` para 404/500/Throwable** — nunca mostrar a página do Tomcat (stack trace, versão).
- **Todo POST com `status` inválido em `POST /api/ponto` (criar) responde 400** mesmo sendo ignorado na criação — a validação acontece antes de distinguir criar/editar.

**Banco e boot**

- **Dados em `${catalina.base}/dados/atlasestilo`** — sobrevivem a redeploy e a conta de serviço tem escrita; `C:\dados` falha quando a VM reinicia. `criarPastaComEscrita` testa escrita de verdade porque `mkdirs` pode "funcionar" sem permissão de gravação.
- **Masters semeados pelo código, não pelo `schema.sql`** — reexecutar o schema no boot não pode ressuscitar um master removido (commit `3549176`).
- **Colunas migradas só em `Migracoes.COLUNAS`** — o `CREATE TABLE IF NOT EXISTS` é no-op em produção; manter uma fonte da verdade. Índices sobre colunas migradas em `Migracoes.INDICES` porque o schema roda antes. Nomes em minúsculas (o `PRAGMA table_info` é comparado em minúsculas; senão "duplicate column name" na segunda subida).
- **Parser do `schema.sql` divide por linha terminada em `;`** e descarta só linhas que começam com `--`; sem transação. Um `;` no fim de linha dentro de literal quebra o arquivo.
- **Sem `FOREIGN KEY`** — cascatas manuais e previsíveis (6.6); `PRAGMA foreign_keys=ON` é inócuo. Tabela nova com relação precisa do próprio código de limpeza.
- **`bancoVazio()` devolve `false` em `SQLException`** — um erro ali silencia a semeadura em vez de derrubar a subida.
- **`exemplo.limpo` gravado também no "recarregar"** — depois de qualquer limpeza o boot nunca mais semeia sozinho; só o botão.
- **`limparExemplo` apaga também o que o Master registrou em agências de exemplo** e deixa órfãos registros `IMPORT` lotados em prefixos de exemplo — aceito, porque o exemplo não é para uso real.
- **`DadosExemplo` usa `INSERT OR IGNORE` nas tabelas cadastrais e `INSERT` simples em visita/anotação/ponto** — chamar `semear()` sem limpar duplica; a API sempre limpa antes.
- **WAL + `busy_timeout=5000`** — leituras concorrentes com uma escrita; r17 da revisão (serializar imports da API com a `TRAVA` do monitor) ficou em aberto.
- **Conexão por operação, sem pool** — simplicidade; `Connection` só é passada entre métodos dentro de transações explícitas (`FonteDao.conexaoGravar`/`indicadoresGravar`).
- **Chaves de `config_parametro` irregulares** (`cadencia.ALTA` maiúsculo, `cadencia.parada`/`csv.*` minúsculos) — históricas; são case-sensitive. `MonitorCsv` faz `Integer.parseInt` direto em `csv.monitor.minutos` (a API limita 0..1440, um UPDATE manual não).
- **`fonte_csv.ultimo_status = 'SEM ARQUIVO'` com espaço**; `visita.melhorias` com `|`; `foto.id` UUID sem hífens — formatos fixos consumidos pelo front e pelo `FotoServlet`.

**Gestão (visitas e ações)**

- **Prazos por dia civil em Brasília** (`inicioDia`) enquanto o front grava datas ao meio-dia local (`dataParaEpoch`) — evita que uma ação "vença" no próprio dia do prazo; usuário com fuso diferente pode ver um dia de diferença. Não troque para UTC/ISO com hora.
- **`SQL_ACOES_BASE` com INNER JOIN em `agencia`** — ação de prefixo removido some de `acoes`, `planejamento`, `resumoDoDia` e `csvAcoes`, mas `kpis.acoesAbertas` e `kpis.visitadas` contam sem JOIN: os números podem não bater.
- **`LIMIT 1000` antes dos filtros Java `COBRAR`/`PARADAS`** — com mais de 1000 ações abertas, ações a cobrar podem ficar de fora. `FotoDao.dasAcoes` lota em 400 ids (limite de variáveis do SQLite).
- **`COALESCE` nas atualizações** — ausente mantém; `""` limpa em `resumo`, `melhorias`, `percepcao`, `solucao`, `responsavel`; `previsao`, `descricao` e datas de visita nunca são limpadas.
- **`reaberturas` só incrementa em `NAO_FEITO`** — reabrir pelo botão (`STATUS`) limpa a prova mas não conta reabertura: "reaberta" mede conferência frustrada, não arrependimento.
- **`pontoVerificar` só age em `AGUARDANDO_VERIFICACAO`** — duplo clique não duplica; devolve `{ok:false}` que o front não checa (toast de sucesso mesmo assim).
- **Depois de um "cobrei" com `proxima_cobranca_em`, `cobrarHoje` passa a ser só `proximaCobranca <= agora`** (`explicita`): uma vencida/parada sai de "Cobrar hoje" até a data marcada; um `RETORNO`/`VERIFICACAO` posterior volta à cadência. `COBRANCA`/`STATUS` não zeram o "sem retorno".
- **"Informou que fez" vai com `tipo=RETORNO`** — conta como retorno do responsável. "Concluir" faz duas requisições (status + comentário `STATUS`) e depois fotos; se a segunda falhar, fica concluída sem registro na linha do tempo.
- **`adiar` passa por `limitar` (1..365)** — `adiar=0` vira 1; não numérico cai no `NumberFormatException` com a mensagem enganosa "Identificador inválido na rota.".
- **Exclusão de visita solta as ações** (`visita_id`/`verificado_visita_id` = NULL) e apaga as fotos antes e fora da transação; exclusão de ação apaga linha do tempo e fotos.
- **`CANCELADA`, `30DIAS` e `prova=SEM` isolado existem só na API** — sem UI; mantidos para uso futuro/scripts.
- **Janela "esta semana" = 8 dias** (`inicioDia(agora)` a `+ 8*DIA`, exclusivo) em `agendaSemana`, `estaSemana`, `acoesVencendo` e no CSV ("vence em 7 dias"). `frias` só lista já visitadas (> 120 dias); nunca visitadas vão para `naoVisitadas`. O briefing do front calcula "atrasada" por 24 h (`Date.now() - 864e5`), diferente de `visitaAtrasada` (dia civil).
- **`Checklist.vazio()` trata `melhorias = ""` como não vazio** — `checklist_em` muda mesmo com chips vazios; `visitaCriar` grava `resumo = ""` (não NULL) quando não vem.
- **Tamanhos truncados em silêncio por `Texto.aparar`** — `descricao`/`solucao`/`resumo`/texto de atualização 4000, `responsavel` 200, `melhorias` 2000, `percepcao`/anotação 8000, legenda 500, legenda automática 120 da descrição.
- **`planejamento`, `resumoDoDia` e `csvAcoes` usam `comFotos=false`** — cards compactos não recebem `fotos`, só `fotosAntes`/`fotosDepois`.

**Fotos**

- **Foto de ação sempre restrita** (`FotoDao.inserir` força); fotos do Admin ficam `restrita = 0` (exceto `PESSOA`, que exige `veTudo`).
- **`FotoServlet` só serve ids `[0-9a-f]{32}`** — ids inseridos à mão com outro formato (como os do `SelfTest`) nunca são servidos.
- **`Cache-Control: private, max-age=86400` em `/foto/{id}`** — após excluir, o navegador pode mostrar a antiga por até um dia; ids são UUID, então não há reuso.
- **Limites de foto em quatro lugares** (`web.xml`, `LIMITE_FOTO_MB`, `admin.js`, textos) e **quantidades** (12 por visita, 6 por conclusão) — mudar um exige mudar todos.
- **`reduzirImagem` converte para JPEG 0,82** (PNG perde transparência), não toca GIF/SVG (SVG volta como "não aceita"), mantém o original se já é pequeno.
- **`POST /api/admin/foto/{id}/excluir` apaga qualquer foto** sem checar origem — ferramenta de Master.

**Fontes, imports e Saneador**

- **Upsert de agências só sobrescreve colunas presentes** — `r.containsKey(col)` é verdadeiro sempre que a coluna foi mapeada, mesmo com célula vazia: coluna presente e vazia **limpa** (`regional` vira `'NÃO MAPEADA'`). `segmento` nunca é escrito pelo import.
- **Município comparado de forma exata** — o `UPPER` do SQLite não cobre acento; o front manda o nome como veio de `/api/municipios`. Normalizar municípios no import quebra o filtro.
- **Números com exatamente 3 casas decimais e ponto são lidos como milhar** em estilo BR (`Saneador.numero`, e o duplo parse `Json.num` → `Texto.decimal` em `ImportService`): `-23.586` → −23586. Use 4+ casas ou vírgula nas coordenadas (o próprio modelo traz `-23.586;-46.681`). `%` só é removido (12,5% → 12.5).
- **`conexaoLista` usa a competência `MAX` global; `conexaoResumo` usa as presentes na seleção; `visoesCalculadas` usa as duas últimas globais** — regionais atrasadas podem mostrar tile com média e lista vazia; `anterior`/`delta` nem sempre são "o mês anterior" apesar do rótulo.
- **Monitor reprocessa fontes em `ERRO` a cada ciclo** (inclusive bloqueio do modo estrito, que grava `ERRO`); upload via `/upload` grava `ultimo_mtime = NULL`, então o monitor reimporta da pasta na próxima varredura.
- **`ignorados` significa coisas diferentes**: em `ImportService.Resultado` inclui rejeitadas (linhas − únicas válidas); em `FonteService` conta só duplicatas.
- **`fonteExcluir` não apaga `conexao`** (sem `fonte_id`); `limparExemplo` apaga `conexao` só com `origem='EXEMPLO'`.
- **`resolverArquivo` aceita qualquer nome que case** (inclusive `.bak`), case-insensitive, mais recente vence; `varrerPasta` lista 200 arquivos mas `total` conta todos; `.xlsx` só pelo sufixo do nome.
- **Indicadores sem competência gravam `''`** — cada reimport sobrescreve a "foto atual"; renomear uma coluna no CSV cria indicador novo e a visão continua apontando para o antigo.
- **`admin.js montarFontes` não envia `ativo`** — ausência vira `true`; desativar uma fonte pela tela não é possível (só `automatico=0`).
- **Teto `min(60 MB, maxMemory/16)`** — numa JVM x86 com heap pequeno pode ser poucos MB; uploads via `/api/admin/import` e `/upload` não passam pelo teto, mas pelo `multipart-config`.
- **`Csv.java` não é usado em produção** — só o `SelfTest`; não o estenda esperando efeito nos imports.
- **Agência com `uf` NULL derruba `/api/mapa`** (`TreeMap.get(null)`); garanta UF no import ou proteja o acumulador.

**Front-end**

- **Tudo por `innerHTML` + `esc()`**; delegações únicas em `#ag-corpo`, `#grade-planejamento`, `#grade-acoes` — um segundo listener de `[data-acao]` duplica gravações.
- **`ACOES_QUE_GRAVAM` + `cartao._emVoo`** — trava contra duplo clique; toda ação nova que grava precisa entrar no mapa e chamar `desistir()` ao abortar, senão o card fica travado.
- **Card compacto usa `data-cadencia` como `adiar`** — se o back parar de mandar `cadenciaDias`, a cobrança compacta vai sem `adiar`.
- **`renderAgencia` transplanta o `#fv-form` sujo** — enquanto o checklist está em preenchimento, o formulário e o bloco Conferir não se atualizam.
- **`trocarVista` ignora o `false` de `fecharAgencia`** — cancelar o `confirm` muda a vista mesmo assim com o drawer aberto por cima (comportamento atual).
- **`#veu` compartilhado** por drawer e dash — `fecharAgencia` só tira o véu se o dash não está aberto, e vice-versa.
- **Três guardas de corrida** (`seqPainel`, `seqAgencia`, `seqAcoes`) — replicar em carregadores novos.
- **`recarregar(prefixo)` re-renderiza o drawer inteiro** — mini-forms abertos (texto, `cartao._fotosMini`) são perdidos; por isso `depois()` só é chamado ao fim da cadeia.
- **`localStorage`**: `superpf1.tema` compartilhado com as outras ferramentas (não renomear); `atlas.aviso.<AAAA-MM-DD>` cria uma chave por dia e nunca limpa; tudo em `try/catch`.
- **Fontes do Google e avatares do Humanograma podem não chegar** — fallback é o esperado, não é bug.
- **Sem bundler/minificação/cache-busting** — `Cache-Control: no-cache` em `/css/` e `/js/` cobre o deploy.
- **Links de export são `<a href>` GET** — não converter em POST (precisariam de `X-Atlas`).

**Build e repositório**

- **`SelfTest` como portão** — renomear qualquer método público usado por ele quebra o build mesmo que a aplicação compile; `objetoDe` só funciona com objetos JSON planos.
- **`build.sh dev` exige `python3`** — sem ele o WAR dev sai com `atlas.dev.simular=false` e sem `FilterOauth2`: ninguém loga.
- **`build.sh` só avisa sobre `redirect_uri` divergente** e sobre segredo de exemplo; nunca aborta por isso. Confere no WAR `FilterOauth2.class`, `Usuario.class` e `oauth.properties`.
- **`dist/atlasestilo.war` é ignorado pelo git** porque embute o `oauth.properties` real; `dist/atlasestilo-dev.war` é versionado e pode estar defasado se ninguém rodou `./build.sh dev` após mexer no código.
- **`.gitignore` cobre `atlas-estilo/terceiros/sso/oauth.properties`, `atlas-estilo/WebContent/WEB-INF/classes/oauth.properties` e `atlas-estilo/dist/atlasestilo.war`** — o arquivo real em qualquer **outro** caminho (ou um WAR renomeado) passaria pelo ignore.
- **SSO próprio com o nome da classe oficial** (`br.com.bb.sso.filter.FilterOauth2`) — permite que os binários do BB sobrescrevam as classes sem tocar no `web.xml` nem no `AuthFilter`. Consequências: `Usuario` é `Serializable` e guarda só `String`s (claims achatadas); `decodificarJwt` **não valida assinatura** (o id_token veio direto do token endpoint por TLS); `tls_ignorar_certificado=true` desliga a validação do certificado do SSO (só provisório); as chamadas ao SSO vão sem proxy salvo `usar_proxy=true`; a descoberta é tentada no máximo uma vez por minuto e o resultado fica em cache até o redeploy; `Usuario.configurarMapeamento` altera campos estáticos (vale para o WAR inteiro).
- **Hierarquia da skill (141 prefixos/8 regionais) não foi carregada** — adaptação deliberada: regional vem de `agencia.regional`, administrada pelo import; dados de exemplo usam regionais fictícias `ESTILO …`.
- **Identidade visual parcialmente adaptada** — tema "dashboard" (sidebar navy + dourado, Inter/Space Grotesk/JetBrains Mono) em vez da paleta editorial da skill, mantendo os mecanismos (chave de tema, boot inline, monograma, avatar, páginas standalone) e aliases de compatibilidade no CSS.

## 14. Histórico e estado atual

### 14.1 Commits que tocam `atlas-estilo/` (do mais antigo ao mais recente)

1. **`ce3ae83`** (2026-10-06) — *nova ferramenta — Atlas interativo das agências Estilo*. Mapa SVG com zoom por UF, métricas com drill-down, página da agência (fachada, dashboard na porta), planejamento/anotações/pontos de melhoria, Admin com import CSV/XLSX e fotos, masters e flags; padrões SUPER PF1; 26 agências de exemplo; `build.sh` com SelfTest (31 verificações) e modo dev. Continha `WebContent/WEB-INF/classes/oauth.properties` com o segredo real e `dist/atlasestilo.war`.
2. **`3549176`** (2026-10-06) — *correções da revisão de código*. Município por valor exato; atualização de visita preserva campos não enviados; carga de masters sai do `schema.sql` (`ConfigDao.semearMastersSeVazio`); anti-CSRF `X-Atlas`; UTF-8 no request; `Texto.decimal` com milhar; carteiras sem matrícula do gerente para Colega; `limparExemplo` abrange gestão `EXEMPLO`; delegação única; sessão cacheada 60 s e estáticos fora do `AuthFilter`; id não numérico → 400 JSON. SelfTest 37.
3. **`87de68a`** (2026-10-07) — *fontes CSV na pasta do servidor, saneamento tolerante e visões configuráveis*. `util/Saneador`, `core/FonteService`, `core/MonitorCsv`, `dao/FonteDao`; tabelas `config_parametro`, `fonte_csv`, `conexao`, `indicador_valor`, `visao_dashboard`; `atlas.csv.dir`/`atlas.csv.monitor`; cards de Conexão e visões; exemplo com Conexão de 6 meses. SelfTest 101.
4. **`c571cd4`** (2026-10-07) — *ciclo da visita (checklist, fotos, ações) só para Masters + front-end dashboard*. Ações com responsável/prazo/prioridade/status e `acao_atualizacao`; checklist; briefing; agenda e alertas em Minha gestão; evolução; exports CSV; privacidade do Master nos agregados e `/foto/{id}` 403; `db/Migracoes.java`; front redesenhado (sidebar navy, tema claro/escuro, responsivo); `AuthFilter.normalizar`; `?perfil=` no WAR dev. SelfTest 138.
5. **`3e2c0ba`** (2026-10-07) — *cadência de cobrança ("Cobrar hoje") e fechamento comprovado das ações*. Cadência por prioridade e parada em `config_parametro`; `cobrarHoje`, `semRetornoDias`, `parada`; "cobrei" (`COBRANCA`); filtros Cobrar hoje/Paradas/A conferir/Sem prova; aviso do dia (`/api/hoje`); fotos antes/depois; `AGUARDANDO_VERIFICACAO`; bloco Conferir; KPI fechamento comprovado; CSV ampliado. SelfTest 164.
6. **`3c2b58e`** (2026-10-07) — *correções de revisão (cobrei compacto, índices, órfãos, prévias)*. "cobrei" no card compacto; `Migracoes.INDICES`; limpar exemplo sem órfãos; rota geral marca `informado_em`; prévias liberam URLs blob. SelfTest 165.
7. **`c1c16f9`** (2026-10-07) — *Revisão: correções necessárias, WAR de produção sem segredo e injeção do SSO*. Prazos por dia civil; reabrir limpa prova; "sem prova" considera conferência; exclusões em transação; `exemplo.limpo`; resumo da visita (`null` mantém, `""` limpa); uploads com 413 e `ignoradas`; Admin não remove a si mesmo nem restringe masters; não-Master cai no Atlas com `?aviso=admin`; `AuthFilter` recusa `;`/`%` e manda `no-cache`; `erro.jsp` + `error-page`s; import de agências só sobrescreve colunas presentes; teto proporcional à memória; `MonitorCsv` com `awaitTermination`; front com formulário sujo preservado/`beforeunload`, reenvio parcial, somente leitura explícito, favicon, fontes não bloqueantes. Empacotamento: `dist/atlasestilo.war` → `dist/atlasestilo-sem-sso.war`; `oauth.properties` → `terceiros/sso/oauth.properties.exemplo`; `injetar-sso.{ps1,bat,sh}`; `.gitignore` do SSO; recomendação de rotação do segredo. SelfTest 181.
8. **`27079a8`** (2026-10-08) — *WAR de produção regenerado e ponteiro para o contexto de IAs*. `dist/atlasestilo-sem-sso.war` reconstruído (SelfTest 181 OK); `README-DEPLOY.md` passa a apontar para `CONTEXTO-PARA-IAS.md`.
9. **`58e7e40`** (2026-10-08, `HEAD`) — *login OAuth2 próprio no WAR (FilterOauth2/Usuario), WAR pronto sem binários externos*. `src/br/com/bb/sso/{filter/FilterOauth2, bean/Usuario, util/JsonLeve}` com o mesmo nome e contrato da classe oficial; fluxo authorization code completo (state, discovery `.well-known/openid-configuration` ou layout OpenAM, troca do code com Basic auth e fallback no corpo, claims de userinfo + id_token, sessão nova, retorno à página pedida); 401 JSON para API sem sessão; página de erro em português; `/sso/diagnostico`; `Usuario` com claims achatadas e listas `claim.*`; configuração sobreposta por `OAUTH_<CHAVE>`, `-Doauth.<chave>` ou `OAUTH_PROPERTIES`; `build.sh` gera sempre `dist/atlasestilo.war` (ignorado pelo git por carregar o segredo); `-sem-sso.war` deixa de existir; injetores viram opcionais; SelfTest 199; primeira versão (parcial) deste documento.

### 14.2 O que está pronto

Tudo o que as seções 5 a 9 e 11 descrevem está implementado, coberto pelo `SelfTest` (199 verificações) e foi exercitado em testes e2e locais: login OAuth2 próprio (testado contra um servidor OAuth2 simulado nos três perfis, com e sem discovery e com segredo errado) e WAR de produção pronto a partir de `terceiros/sso/oauth.properties`; mapa e painel por perfil; página da agência com fachada, equipe, carteiras, metas, fotos, Conexão, visões e PDG; imports tipados com prévia e saneamento; fontes na pasta com monitor, relatório e modo estrito; visões configuráveis; ciclo completo da visita (checklist, fotos, ações da visita, conferência); ações com cadência, cobrança, verificação, prova e linha do tempo; Minha gestão, vista Ações, aviso do dia, exports; Admin completo (cadência, pasta, fontes, visões, imports, fotos, masters, flags, exemplo, histórico); build com SelfTest, WAR dev, WAR sem SSO e scripts de injeção; páginas de erro e acesso negado.

### 14.3 Itens parciais ou em aberto

- **Primeiro login real contra o SSO do BB ainda não aconteceu**: o filtro próprio foi validado só contra um servidor OAuth2 simulado. Pontos a confirmar em produção: endpoint de descoberta (ou layout OpenAM), nomes das claims de matrícula/prefixo (ajustáveis por `claim.*`), separador de scopes, certificado TLS da JRE. `/sso/diagnostico` e a página de erro do filtro foram feitos para esse ajuste.
- **`redirect_uri` a registrar** para o client `SUPERPF1` (11.4) e **rotação do `client_secret`** (11.6) — dependem do BB. O WAR gerado localmente carrega o segredo (por isso é ignorado pelo git) — tratar o arquivo como sensível.
- **Binários oficiais do BB** continuam opcionais: se a equipe preferir a classe original, copiar para `terceiros/sso/` ou usar `injetar-sso.*`.
- **r13 da revisão**: não há `testarCamadaWeb` no `SelfTest` (`ApiServlet.exigir`, `FotoServlet`, `AuthFilter.doFilter` só foram cobertos por e2e não versionados).
- **r17**: `busy_timeout=5000` mantido; imports da API não são serializados com a `TRAVA` do `MonitorCsv`; fotos copiadas para o disco antes do insert.
- **r27 (parcial)**: `/api/ponto/{id}/comentar` aceita `tipo=VERIFICACAO` via `tipoAtualizacaoValido`, enquanto o `README-DEPLOY.md` cita `RETORNO|COBRANCA|STATUS`.
- **r36/r38 (desejáveis)**: sem verificação automatizada dos selos de "Agências frias"/"Esta semana"; o rótulo "cards definidos pelo Master a partir dos CSV da pasta" aparece para todos os perfis.
- **Testes e2e e stub do SSO** vivem só no scratchpad da sessão; decidir se entram no repositório (ex.: `atlas-estilo/e2e/`).
- **`dist/atlasestilo-dev.war`** não foi regenerado no último commit.

### 14.4 Recomendações de UX pendentes (aguardando decisão do dono)

Diagnóstico registrado: tudo tem o mesmo peso visual (nove tiles iguais, até oito controles por cartão de ação, três loops de animação no drawer, título repetido, iframe cinza como maior elemento da agência) e o layout pensa em coluna única (lista de agências a 880 px em 1366×768, mapa some ao rolar). Propostas, todas de esforço médio, nenhuma implementada:

1. **Drawer da agência**: identidade no cabeçalho (rótulo/h2/linha de fatos, botão Dashboard), fachada rebaixada a 200 px com mini-mapa SVG da UF quando não há foto/embed, um único loop de animação (`fachadaHtml`, `renderAgencia`, `briefingHtml`, `desenharPins`).
2. **Painel da região**: herói Conexão em largura total, tiles como lista `.stat` em grupos PESSOAS/ESTRUTURA/TEMPO, comparativo com Brasil/regional via `App.resumoRef`, construtor `tileHtml` (`renderPainel`, `carregarPainel`, `tileConexao`).
3. **Um título por tela**: `#topo-titulo` vira `<nav class="trilha">`, `#topo-sub` único resumo, barra de página de 44 px, `.abas` sticky no drawer (`montarMigalhas`, `trocarVista`, `abrirAgencia`).
4. **Cartão de ação em três níveis**: um `.botao.primario` por cartão, `.botao.fantasma`, urgência só na faixa esquerda, destrutivo num `.menu-mais`, cartão em repouso de duas linhas (`prazoBadge`, `acaoCardHtml`, `tratarAcaoCard`).
5. **Atlas em layout de aplicação**: mapa à altura da janela, KPIs e lista sempre visíveis, três colunas ≥ 1700 px, realce cruzado lista ↔ pin (`.grade-mapa`, `.cena-mapa`, `desenharPins`).
6. **Busca como paleta**: agência, UF, município e navegação agrupadas, Ctrl+K, ↑↓/Enter, prévia no mapa sem efeitos colaterais, atalho para buscar em Ações (`ligarBusca`).
7. **Orçamento de movimento**: um loop por tela, indicador de aba deslizante, cascata curta só na primeira abertura, timeline que abre com `grid-template-rows`, vazios que convidam (`.pulso`, `.pin circle.pulso`, `.abas`, `.painel-dash`, `.vazio`).

Quick wins sugeridos: `.tile .valor{white-space:nowrap}` + `<small class="unidade">`, `.abas` sticky, remover `.porta::before`, `.pin circle.pulso{animation:none}` fora de hover/planejada, helper `plural()`, trocar "toque" por "clique", cabeçalho de grupo da vista Ações como `<h3>` (`atlas.maps.ativo=false` já foi aplicado). Ordem sugerida: quick wins, depois 1 e 2, e a 5 só depois da 2. Deixados de fora deliberadamente: tokens semânticos, grade de 12 colunas em Minha gestão, esqueletos, pins proporcionais, escala tipográfica, vista Ações como tabela, checklist em duas colunas, undo com toast, split-view, rotas no hash, foco/teclado completos, camadas do mapa.

## 15. Glossário

**Negócio**

- **Agência Estilo** — agência do segmento Estilo (alta renda) do BB; unidade básica do atlas, identificada pelo prefixo.
- **Prefixo** — código numérico da dependência (agência) no BB; chave de `agencia` e de todas as tabelas; canonizado por `Texto.prefixo` (só dígitos, sem sufixo após `-`, sem zeros à esquerda).
- **Matrícula (chave)** — identificador do funcionário no SSO (ex.: `F3548926`); canonizada por `Texto.matricula`; identidade gravada em `criado_por`/`atualizado_por`.
- **Regional** — agrupamento de agências em `agencia.regional`; única hierarquia prefixo → regional do projeto; jurisdição do Colega; DEFAULT `'NÃO MAPEADA'`.
- **Super regional** — nível acima da regional (`agencia.super_regional`), só informativo.
- **SUPER PF1 / SUPER PF I** — Superintendência de Pessoa Física 1 do BB, dona da ferramenta; prefixo `9007` (Moderador).
- **Funci** — funcionário (tabela `funci`; tipos `GERENTE`, `ASSISTENTE`, `OUTRO`).
- **Carteira** — portfólio de clientes de um gerente (`carteira.codigo`, ex.: `EST-01`); em `conexao.carteira`, `''` é a linha da agência.
- **Claros** — vagas em aberto no quadro de funcionários da agência (`visita.claros`).
- **PDG** — programa de desempenho semestral; `pdg.semestre` `AAAA-N`, `atingiu` 0/1, `pontuacao`; "semestres com PDG" = `pdgGanhos`.
- **Meta / período / indicador** — tabela `meta` (meta, realizado, projeção por prefixo, período e indicador); só `veTudo()`.
- **Conexão** — pontuação 0–1000 de relacionamento por agência e por carteira/gerente, mensal (`conexao`); faixas `excelencia` / `forte` / `atencao` / `critico` (`FonteDao.faixaConexao`).
- **Competência** — mês de referência `AAAA-MM` (`conexao.competencia`, `indicador_valor.competencia`; `''` = foto atual sem histórico); `compHumana` mostra `set/2026`.
- **DERIVADO** — `conexao.origem` da nota de agência calculada como média das carteiras por falta de linha própria.
- **Visita** — registro do Master de uma ida à agência (`PLANEJADA`, `REALIZADA`, `CANCELADA`) com checklist.
- **Checklist** — critérios 1–5 (`ambiencia`, `atendimento`, `organizacao`, `equipe`), `movimento` (`VAZIA`/`NORMAL`/`CHEIA`), `claros`, `nota_geral` 0–10, `melhorias` (chips separados por `|`), `percepcao`; `checklist_em` marca o preenchimento.
- **Briefing ("Antes de ir")** — bloco automático de `briefingHtml` com última visita, pendências, Conexão e metas; só Master.
- **Ação / ponto de melhoria** — linha de `ponto_melhoria` (`tipo='ACAO'`): descrição, responsável, prazo (`previsao`), prioridade, status, solução, linha do tempo.
- **Responsável** — dono da ação (`ponto_melhoria.responsavel`, texto livre).
- **Previsão / prazo** — `ponto_melhoria.previsao` (gravado ao meio-dia local pelo front); vence no dia seguinte.
- **Prioridade** — `ALTA`, `MEDIA` (padrão), `BAIXA`; define cadência e ordem.
- **Vencida** — ação aberta, não aguardando, com `previsao < inicioDia(agora)`.
- **Sem retorno (semRetornoDias)** — dias desde o último `RETORNO`/`VERIFICACAO` ou desde a criação.
- **Parada** — ação aberta com `semRetornoDias >= cadencia.parada` (padrão 14).
- **Retorno** — `acao_atualizacao` tipo `RETORNO` (o responsável deu notícia); zera o sem-retorno.
- **Cobrança / "cobrei"** — tipo `COBRANCA`; conta `cobrancas`/`ultima_cobranca`, grava `proxima_cobranca_em`; não zera o sem-retorno.
- **Cadência** — dias entre cobranças por prioridade (`cadencia.ALTA/MEDIA/BAIXA`, padrões 7/15/30) e limite de parada (`cadencia.parada`); Admin › Cadência.
- **Cobrar hoje** — ação cuja próxima cobrança chegou (vencida, parada ou cadência vencida; após "cobrei" com data, só quando a data chega); filtro `COBRAR`, contador do menu, lista `cobrarHoje`.
- **Adiar** — parâmetro de `comentar` com `tipo=COBRANCA` (1..365 dias).
- **Informou que fez / A conferir** — transição para `AGUARDANDO_VERIFICACAO` (`informado_em`); sai das cobranças e entra em "A conferir na próxima visita".
- **Conferência / verificação** — `pontoVerificar`: `CONFIRMADO` conclui com a visita como prova; `NAO_FEITO` reabre e conta reabertura; gera atualização `VERIFICACAO`.
- **Reabertura** — `reaberturas`, incrementado só por `NAO_FEITO`.
- **Fechamento comprovado / prova** — ação `RESOLVIDO` com `verificado_em` ou foto `DEPOIS` (`comprovada`); "sem prova" = `prova=SEM`; KPI `fechamentoComprovadoPct`.
- **Fotos antes/depois** — fotos de ação com `momento` `ANTES`/`DEPOIS`; "depois" é a prova fotográfica.
- **Foto restrita** — `foto.restrita = 1` (visita e ação); só Master.
- **Fachada** — foto `FACHADA`, capa do drawer; na falta, embed do Maps ou placeholder.
- **Agência fria** — já visitada, última visita há mais de 120 dias.
- **Fila de visitas** — agências nunca visitadas, menor Conexão primeiro (`naoVisitadas`).
- **Esta semana / agenda** — visitas planejadas na janela de 8 dias a partir de hoje.
- **Evolução** — comparação das duas últimas notas gerais por agência.
- **Anotação** — lembrete livre; com prefixo é da agência, sem prefixo é geral; `fixada`; exclusão lógica.
- **Aviso do dia** — faixa `#aviso-dia` (`GET /api/hoje`) só para Master; fecha por um dia.
- **Minha gestão** — vista `#planejamento` (`GET /api/planejamento`).
- **Vista Ações** — `#acoes`, fila nacional com filtros, agrupada por agência.
- **Dashboard da porta** — `#painel-dash` aberto por "Entrar · dashboard".
- **Drawer** — painel lateral da agência (`#drawer-agencia`) com abas.
- **Painel da região** — `#painel-regiao` com tiles, visões, municípios e lista de agências.
- **Grandes números** — campos de `MetricaDao.resumo` que todo perfil vê.
- **Fonte (CSV)** — linha de `fonte_csv` ligando um arquivo/padrão da pasta do servidor a um tipo de dado.
- **Tipo de fonte** — `agencias`, `funcis`, `carteiras`, `pdg`, `metas` (tipados), `conexao`, `indicadores`.
- **Indicador genérico** — célula numérica de uma fonte `indicadores` em `indicador_valor`.
- **Visão** — card configurável (`visao_dashboard`): fonte, coluna, agregação, formato, meta, direção, faixa plausível, perfil mínimo; farol `ok`/`atencao`/`critico`.
- **Saneamento** — correções automáticas do `Saneador` com relatório (regra, linha, de/para).
- **Modo estrito** — `csv.estrito='1'`: nada é gravado se houve linha rejeitada ou célula inválida.
- **Monitor** — `MonitorCsv`; varredura periódica da pasta de CSV (`csv.monitor.minutos`; 0 desliga; `atlas.csv.monitor=false` desativa).
- **De-para / mapeamento** — `fonte_csv.mapeamento` (`campo=CABECALHO;…`).
- **Prévia / análise** — processamento com `confirmar=false`; nada gravado, relatório persistido.
- **Dados de exemplo** — 26 agências fictícias de `DadosExemplo` (`origem='EXEMPLO'`/`criado_por='EXEMPLO'`); `exemplo.limpo` impede nova semeadura.
- **Humanograma** — serviço da intranet com a foto do funcionário (`https://humanograma.intranet.bb.com.br/avatar/<matricula>`).
- **boaspraticas / dashjunho** — outras ferramentas SUPER PF1 já implantadas, origem dos artefatos do SSO.
- **apigol** — outra ferramenta do mesmo monorepo.

**Código e infraestrutura**

- **Colega / Moderador / Master** — perfis cumulativos (`Sessao.PERFIL_*`): qualquer matrícula / prefixo `9007` / matrícula em `config_master`.
- **`veTudo()` / `master()` / `moderador()`** — métodos de `Sessao`; `veTudo` = Master ou Moderador.
- **Jurisdição (`regionalJurisdicao`)** — regional que limita o Colega; `null` para quem vê tudo; aplicada por `Selecao.de`.
- **Somente leitura / Bloqueado** — flags `SOMENTE_LEITURA` (sem escrita) e `BLOQUEADO` (sem acesso) em `usuario_flag`.
- **`Selecao`** — `dao/Selecao`: filtros + jurisdição em `WHERE` sobre `agencia a` (`where()`, `aplicar(ps, pos)`).
- **`exigir(resp, condicao)`** — helper do `ApiServlet`: 403 "Seu perfil não tem acesso a esta função.".
- **`X-Atlas`** — cabeçalho obrigatório em toda requisição não GET/HEAD (anti-CSRF).
- **`"sessao"` / `"sessao.atlas"` / `"usuario"` / `"dev.perfil"`** — atributos: `Sessao` no request; cache na `HttpSession`; bean do SSO; perfil simulado.
- **`geracaoPerfis` / `invalidarPerfis()`** — contador de `AuthFilter` que invalida todos os caches de sessão.
- **`normalizar(caminho)`** — método de `AuthFilter`; `null` → 400 "Caminho inválido.".
- **Caminhos isentos** — `/negado.jsp`, `/erro.jsp`, `/css/*`, `/js/*`.
- **`FilterOauth2` / `Usuario` / `JsonLeve`** — login OAuth2 próprio em `src/br/com/bb/sso` (`filter`, `bean`, `util`), com o nome e o contrato da classe oficial do BB, que pode sobrescrevê-los.
- **`oauth.properties`** — configuração do SSO em `WEB-INF/classes` (modelo `terceiros/sso/oauth.properties.exemplo`): `client_id=SUPERPF1`, `client_secret=<segredo>`, `redirect_uri`, `login_endpoint`, `cookie_sso=BBSSOToken`, `scopes` + opcionais `authorize_endpoint`, `token_endpoint`, `userinfo_endpoint`, `tokeninfo_endpoint`, `descoberta`, `scopes_separador`, `timeout_ms`, `usar_proxy`, `tls_ignorar_certificado`, `claim.*`.
- **`OAUTH_<CHAVE>` / `-Doauth.<chave>` / `OAUTH_PROPERTIES`** — sobreposições do `oauth.properties` por variável de ambiente, system property ou arquivo externo (`FilterOauth2.valorAmbiente`).
- **`/sso/diagnostico`** — rota do filtro (logado) que mostra a configuração em uso, sem o segredo.
- **state / `oauth2.state` / `oauth2.destino`** — proteção anti-CSRF do login e página de retorno, guardadas na sessão pelo filtro.
- **Discovery / layout OpenAM** — descoberta dos endpoints em `.well-known/openid-configuration` (`candidatosDescoberta`) ou padrão `<login_endpoint>/sso/oauth2/{authorize,access_token,userinfo,tokeninfo}` (`padrao`).
- **`redirect_uri` / `redirect_uri_mismatch`** — URL de retorno do OAuth2 (`https://super-pf1.intranet.bb.com.br/atlasestilo`) e o erro quando não está registrada.
- **SSO stub** — `FilterOauth2`/`Usuario` falsos usados em testes locais antes do SSO próprio existir.
- **WAR dev / produção** — `dist/atlasestilo-dev.war` (versionado, sem login) e `dist/atlasestilo.war` (ignorado, com o segredo); o antigo `-sem-sso.war` deixou de existir em `58e7e40`.
- **`injetar-sso`** — scripts opcionais `.ps1`/`.bat`/`.sh` que trocam a implementação própria pelos binários oficiais do BB dentro de um WAR já gerado.
- **`SSO_SEGREDO`** — variável do `build.sh` (1 quando o `oauth.properties` tem segredo real) que só muda a mensagem final.
- **`SSO-INICIO/FIM`, `DEV-SIMULAR-INICIO/FIM`** — marcadores do `web.xml` usados pelo build dev.
- **`atlas.dev.simular`** — context-param (`false` em produção) que liga `AuthFilter.simulada`.
- **context-param** — parâmetros do `web.xml` lidos pelo `AppListener` (`atlas.db.path`, `atlas.foto.dir`, `atlas.csv.dir`, `atlas.csv.monitor`, `atlas.dados.exemplo`, `atlas.maps.ativo`, `atlas.dev.simular`).
- **`ATTR_FOTO_DIR` / `ATTR_MAPS_ATIVO` / `ATTR_DEV_SIMULAR`** — atributos do `ServletContext` (`"atlas.foto.dir.resolvido"`, `"atlas.maps.ativo"`, `"atlas.dev.simular"`).
- **`catalina.base`** — pasta da instância do Tomcat; token `${catalina.base}` expandido por `AppListener.resolver`.
- **`icacls`** — comando Windows para dar escrita à conta de serviço (`/grant "NETWORK SERVICE":(OI)(CI)M /T`).
- **WAL / `busy_timeout`** — `PRAGMA journal_mode=WAL` em `Db.iniciar`; `PRAGMA busy_timeout=5000` por conexão.
- **Upsert** — `INSERT ... ON CONFLICT(chave) DO UPDATE SET col = excluded.col`.
- **`schema.sql`** — DDL idempotente em `WebContent/WEB-INF/sql/`; `sql/schema.sql` é cópia de referência.
- **Migração** — entrada em `Migracoes.COLUNAS`/`INDICES`.
- **`config_parametro`** — tabela chave/valor de parâmetros (`csv.*`, `cadencia.*`, `exemplo.limpo`).
- **`import_log`** — auditoria de imports (`criado_por='MONITOR'` quando automático).
- **Epoch millis / dia civil** — datas em ms desde 1970; prazos por `GestaoDao.inicioDia` (meia-noite em `America/Sao_Paulo`); `GestaoDao.DIA` = 86 400 000.
- **`SelfTest` / `verifica` / `objetoDe`** — auto-teste do build e seus helpers.
- **`Http.json` / `Http.erro` / `Http.caminho` / `Http.download`** — helpers de resposta.
- **`Json.Obj` / `Json.Arr` / `putRaw`** — builders de JSON manual.
- **`Texto.esc` / `esc()`** — escape HTML no servidor (JSP) e no front.
- **`chaveColuna` / `cabecalhoNorm`** — nome normalizado de coluna (MAIÚSCULO sem acento, só `A-Z0-9%`).
- **Estilo numérico (BR/EN)** — `Saneador.Estilo`; convenção decimal inferida por coluna; empate → BR.
- **Teto** — limite de tamanho de arquivo de fonte: `min(60 MB, maxMemory/16)`.
- **`LIMITE_FOTO_MB` / `multipart-config`** — 8 MB por arquivo, 40 MB por requisição; 413.
- **Parte `arquivo`** — nome obrigatório das partes multipart de upload.
- **`responderFotos` / `extensaoImagem`** — resposta padrão de upload e mapeamento MIME → extensão.
- **`ATLAS_CTX` / `CTX`** — context path injetado pelas JSPs e usado pelo JS.
- **`App`** — objeto de estado de `atlas.js` (`contexto`, `mapa`, `geo`, `proj`, `vb`, `sel`, `agencia`, `listaAberta`, `hoje`).
- **`api()` / `post()` / `enviarFotos()`** — funções do front para a API.
- **Vista / drawer / dash / tile / badge / toast / faixa / migalhas / roseta / pin** — elementos de interface descritos na seção 9.
- **`superpf1.tema` / `atlas.aviso.<data>`** — chaves de `localStorage`.
- **`html.master` / `html.somente-leitura` / `.so-master`** — classes de estado ligadas por `iniciar`.
- **Playwright** — automação de navegador usada nos e2e não versionados.
