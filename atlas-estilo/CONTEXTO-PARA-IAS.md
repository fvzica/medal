# Atlas Estilo — contexto completo para IAs

**Para que serve este documento.** É o ponto de partida de quem vai dar manutenção no Atlas Estilo sem ter visto o código — uma IA ou uma pessoa técnica. Ele descreve o propósito, as regras que não se negociam, cada arquivo do repositório, a arquitetura, o modelo de dados, a API, o front-end, o build, o deploy com o SSO do BB, receitas para as tarefas mais comuns, as armadilhas conhecidas, o histórico e o que está pendente. Foi escrito a partir da leitura integral do código e conferido contra ele.

**Como usá-lo.** Leia a seção 2 (regras inegociáveis) antes de qualquer alteração. Use a seção 3 para localizar arquivos, a 6 e a 8 como referência de tabelas e rotas, a 12 como passo a passo e a 13 antes de "corrigir" algo que parece estranho (quase sempre é decisão deliberada). Caminhos são relativos a `atlas-estilo/`; símbolos (classes, métodos, funções, chaves, rotas) são citados com o nome exato que está no código, sem números de linha. Quando este documento e o código divergirem, o código manda — e este documento deve ser corrigido no mesmo commit.

**Estado retratado.** 2026-10-08, branch `claude/passagem-aerea-sp-orlando-0k2jhb`, código da revisão `c1c16f9` ("Revisão: correções necessárias, WAR de produção sem segredo e injeção do SSO"); o `HEAD` atual é `27079a8`, que só regenerou `dist/atlasestilo-sem-sso.war` e fez o `README-DEPLOY.md` apontar para este arquivo. Repositório git: `/home/user/medal` (monorepo público em `github.com/fvzica/medal`; esta ferramenta vive na pasta `atlas-estilo/`).

---

## 1. O que é o Atlas Estilo

O Atlas Estilo é uma ferramenta web interna da **SUPER PF1** (Superintendência de Pessoa Física 1 do Banco do Brasil, prefixo `9007`) para acompanhar as **agências Estilo** (segmento de alta renda). Ela mostra um mapa do Brasil em SVG com zoom por UF e município, os "grandes números" da seleção (funcis, gerentes, assistentes, carteiras, tempos médios de posse, semestres de PDG, nota Conexão e cards de indicadores configuráveis), e uma página por agência com fachada, equipe, carteiras, metas, fotos e um "dashboard na porta".

Para o perfil **Master** ela é também um instrumento de gestão do atendimento: planejar e registrar **visitas** com checklist padronizado (notas de 1 a 5, movimento, claros, nota geral, melhorias, percepção, fotos), abrir **ações** (pontos de melhoria) com responsável, prazo e prioridade, acompanhar a **cadência de cobrança** ("cobrar hoje", "paradas"), conferir in loco o que o responsável disse ter feito (**fechamento comprovado** com conferência ou foto do depois), manter anotações e acompanhar tudo em "Minha gestão", na vista "Ações" e no aviso do dia. Tudo o que o Master registra é visível **somente a Masters**.

Quem usa: qualquer funcionário do BB autenticado pelo SSO (perfil **Colega**, que vê os grandes números da própria regional), a equipe da SUPER PF1 (perfil **Moderador**, prefixo `9007`, leitura ampla) e os gestores cadastrados em `config_master` (perfil **Master**). Os dados cadastrais vêm de planilhas CSV/XLSX (upload no Admin ou fontes numa pasta do servidor monitorada por uma thread); em banco vazio a ferramenta semeia 26 agências fictícias para ser navegável de cara.

Onde roda: um único WAR (`/atlasestilo`) num Tomcat 8.5 Windows x86 (JRE 1.8.0_25) do servidor `super-pf1`, acessado em `https://super-pf1.intranet.bb.com.br/atlasestilo`, com banco SQLite e fotos gravados em `${catalina.base}/dados/atlasestilo/`. Não há dependências externas além do driver `sqlite-jdbc` e dos binários do SSO do BB.

## 2. Regras inegociáveis

1. **Privacidade Master-only.** Visitas, checklist, anotações, ações e sua linha do tempo, fotos restritas (`foto.restrita = 1`), planejamento, aviso do dia, exports e Admin só saem da API quando `Sessao.master()` é verdadeiro. Os agregados derivados (`visitada`, `planejada`, `pontosAbertos`, `visitadas`, `temFoto` com restritas) são mascarados no servidor (`AgenciaDao`, `MetricaDao`). O front apenas esconde o que já não vem (`html.master` ↔ `.so-master`). Nunca libere nada disso a Moderador ou Colega sem decisão explícita do dono.
2. **Autorização no servidor, sempre.** Todo endpoint revalida o perfil (`ApiServlet.exigir`, `FotoServlet`, gate de `admin.jsp`). "Esconder botão no front não é proteção" (comentário de `AuthFilter`).
3. **Stack fixa.** Java 8 (`javac --release 8`), JSP/Servlets puros (Servlet 3.0, `web.xml`), SQLite via `sqlite-jdbc-3.36.0.3.jar` dentro do WAR, front-end vanilla JS/CSS sem framework, bundler ou transpilação. Sem Spring, JSF, Maven, Gradle, npm, Hibernate, Jackson/Gson — JSON é montado à mão com `util/Json`.
4. **Compatível com o servidor de produção.** Tomcat 8.5 em Windows Server x86 (32 bits) com JRE 1.8.0_25: nada de API além do Java 8, heap pequeno (o teto de leitura de CSV é `min(60 MB, maxMemory/16)`), caminhos Windows, conta de serviço com permissões limitadas.
5. **SSO do BB, por reflexão.** O login é o `FilterOauth2` do Banco (binário, primeiro filtro em `/*`); a aplicação lê o bean `Usuario` da sessão por reflexão em `Sessao.montar(Object)` e compila sem o JAR do BB. Não se referencia `br.com.bb.sso.*` no código.
6. **Nenhum segredo no repositório.** `oauth.properties` real, `FilterOauth2.class`, `Usuario.class` e `json-*.jar` ficam em `terceiros/sso/` fora do git (`.gitignore` na raiz). O valor do `client_secret` **nunca** é escrito em documento, código, exemplo ou commit — use `<segredo>`. O segredo já vazou no histórico e precisa de rotação (seção 11).
7. **Dados fora da webapp e dentro do Tomcat.** `${catalina.base}/dados/atlasestilo/` (banco, fotos, csv), resolvido por `AppListener.resolver`; nunca dentro da pasta da webapp (some no redeploy) nem em `C:\dados` (a conta de serviço não tem acesso).
8. **Toda escrita HTTP exige o cabeçalho `X-Atlas`** (anti-CSRF em `AuthFilter`) e é recusada para matrícula `SOMENTE_LEITURA`. Formulários HTML com `method=post` não funcionam; só `fetch` via `api()`/`post()`.
9. **Esquema evolutivo e idempotente.** `WEB-INF/sql/schema.sql` roda em toda subida (`CREATE ... IF NOT EXISTS`); coluna nova em tabela que já existe em produção entra em `db/Migracoes.COLUNAS` (e índice sobre ela em `Migracoes.INDICES`); o `SelfTest` precisa continuar verde — ele é portão do `build.sh`.
10. **Desktop-first.** O layout padrão é o de mesa (sidebar 248 px + conteúdo); `@media (max-width: …)` reduz para tablet/celular. Não existe `min-width`.
11. **Identidade visual SUPER PF1.** Tema claro/escuro em `html[data-tema]` com a chave `localStorage['superpf1.tema']` compartilhada com as outras ferramentas, boot anti-flash inline no `header.jspf`, monograma BB, avatar do Humanograma com fallback, páginas `negado.jsp`/`erro.jsp` standalone.
12. **Datas em epoch millis; prazos por dia civil em `America/Sao_Paulo`** (`GestaoDao.inicioDia`). Textos em português do Brasil.

**O que nunca fazer:**

- Nunca escrever o `client_secret` em lugar nenhum; nunca versionar `oauth.properties`, `*.class` do BB ou `json-*.jar`.
- Nunca implantar `dist/atlasestilo-dev.war` em produção (sem SSO, usuário simulado Master) nem `dist/atlasestilo-sem-sso.war` (não sobe: `ClassNotFoundException` do `FilterOauth2`).
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
│   ├── atlasestilo-sem-sso.war         WAR de produção SEM os binários do SSO (não sobe no Tomcat; matéria-prima do injetar-sso)
│   └── atlasestilo-dev.war             WAR de teste sem SSO, atlas.dev.simular=true (nunca em produção)
├── terceiros/sso/
│   ├── LEIAME.txt                      explica os 4 artefatos do SSO que ficam fora do git
│   ├── oauth.properties.exemplo        modelo do oauth.properties (client_id=SUPERPF1, redirect_uri, login_endpoint, cookie_sso, scopes; segredo = placeholder)
│   ├── injetar-sso.ps1                 PowerShell/.NET: completa o WAR -sem-sso com FilterOauth2/Usuario/json-*.jar/oauth.properties copiados do boaspraticas
│   ├── injetar-sso.bat                 invólucro do .ps1 (roteiro manual se o PowerShell falhar)
│   └── injetar-sso.sh                  versão Linux/macOS (unzip/zip)
├── src/test/
│   └── SelfTest.java                   auto-teste de integração sem Tomcat (SQLite temporário, schema real, migrações, exemplo, DAOs, perfis, imports, saneador, fontes); 181 verificações; obrigatório no build
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

Fora de `atlas-estilo/`, na raiz `/home/user/medal`: `.gitignore` (regras `build/`, `*.class`, `atlas-estilo/terceiros/sso/oauth.properties`, `atlas-estilo/terceiros/sso/*.jar`, `atlas-estilo/WebContent/WEB-INF/classes/oauth.properties`), `lib/javax.servlet-api-3.1.0.jar` (compartilhado) e a outra ferramenta do monorepo, "apigol", com `build.sh` e `dist/apigol.war` próprios.

## 4. Arquitetura e fluxo de uma requisição

Monólito clássico de servlets: filtros → servlet → DAO estático → SQLite. Não há injeção de dependência, pool, ORM nem camada de serviço além de `core/` (fontes e imports). Classes de `dao/`, `core/`, `util/` e `db/` são `final` com métodos estáticos.

```
navegador (Chrome/Edge da estação BB)
   │  GET /atlasestilo/…            fetch CTX+'/api/…' com 'X-Atlas: 1' (js/atlas.js api())
   ▼
Tomcat 8.5
   │
   ├─ FilterOauth2 (BB, binário, 1º filter-mapping /*)
   │     OAuth2 contra login.intranet.bb.com.br; cookie BBSSOToken;
   │     ao autenticar põe br.com.bb.sso.bean.Usuario em HttpSession["usuario"]
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

1. `FilterOauth2` garante `usuario` na `HttpSession` (ou redireciona ao login).
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

**Leitura do bean do SSO por reflexão** (`Sessao.montar(Object)`, pacote-privado): `call(alvo, metodo)` faz `getClass().getMethod(m).invoke` e devolve `null` em qualquer exceção; `primeiro(...)` pega o primeiro valor não vazio. Ordem dos getters: matrícula `getChaveUsuario`, `getUid`, `getUsername` (→ `Texto.matricula`); nome `getNomeUsuario`, `getName`, `getDisplayName`, `getNomeGuerra`; prefixo `getPrefixo` (→ `Texto.prefixo`); comissão `getNomeComissao`, `getCargo`. Se o BB renomear os getters, o sintoma é "Acesso negado"/`negado.jsp` para todos, sem stack trace.

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

<!-- CONTINUA -->
