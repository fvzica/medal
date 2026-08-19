# apigol — Painel API GOL · SUPER PF1

Ferramenta interna da SUPER PF1 (Banco do Brasil) que consulta o GOL
(`gol.intranet.bb.com.br`) por WebSocket/HTTP, consolida os números por
Prefixo ou Carteira e apresenta um painel dinâmico com exportação XLSX/CSV.

Este repositório nasceu da recuperação dos fontes do WAR em produção (os
`.java` originais não existiam — as classes foram decompiladas com CFR a
partir do bytecode e revisadas) e já incorpora as alterações pedidas no
`apigol.docx`.

## O que mudou (requisitos do apigol.docx)

### 1. Quantidade de operações com prestamista em todos os créditos

Crédito Pessoal `{1002}`, Consignado `{1001}`, Veículo `{1003}` e Demais
`{1004}` passam a exibir a quantidade de operações com prestamista — por
**duas fontes candidatas**, já que a associação definitiva ainda precisa ser
validada com payload real (ver §Validação):

| Coluna | Fonte | Configuração |
|---|---|---|
| `Com Prestamista` | campo `qtde_com_prestamista` do próprio payload de desembolso (`cop`) | dimensão em `dimensoes` |
| `Operações com Prestamista` | tópico `web~gol2~cdc~prestamista~relacionamento~vendas-<visão>` consultado com o mesmo prefixo/visão e os blocos do crédito | bloco `prestamista_operacoes` do produto |

A consulta do tópico de prestamista roda **em paralelo, com timeout curto
(8s)** — se o GOL não responder àquele tópico/blocos, a coluna sai zerada e o
painel não fica mais lento. As duas colunas entram nas linhas, nos totais,
nos KPIs e nas exportações CSV/XLSX.

### 2. Crédito Total = soma dos quatro créditos

`credito_total` virou um produto **composto** (`"tipo": "composto"`): soma
Crédito Pessoal + Consignado + Veículo + Demais **por chave de negócio**
(Prefixo no nível regional, Carteira no nível carteira), nunca por posição
de linha. Campos ausentes valem zero; `Nome` e identificadores não são
somados; o bloco `{1000}` **não** é consultado nem somado (a composição é a
nova fonte de verdade, conforme o requisito). As colunas somadas estão em
`metricas_somadas` — incluindo as duas métricas de prestamista e Contatos.

### 3. Catálogo completo de produtos

O `produtos.json` passou de 2 para **49 produtos**, cobrindo todas as seções
do documento: Crédito PF, Crédito Agro (Total, Familiar, Empresarial,
Regulariza, Giro, Comercialização, CPR, Custeio, Investimento), Seguridade
(Total, Prestamista, Vida, Vidinha/BB Proteção, Residencial, Patrimônio,
Auto, Rural, Dental, Kit BB Seguros, Consórcio Protegido, Previdência,
Ourocap), Aplicações e Captação (Poupança, CDB, LCA, LCI, Fundos,
Previdência, Portabilidade), Consórcios e Pacotes/Clubes.

## Novidades de configuração (produtos.json)

- **Blocos por tópico** — itens de `vendas`/`cancelamentos`/`pendentes`/
  `contatos` aceitam, além da string, o objeto
  `{"topico": "web~...~%s", "blocos": "{...}"}`. Necessário quando o mesmo
  produto assina origens com blocos distintos (Seguridade Total, BB
  Regulariza Agro). Se o mesmo tópico se repetir, os blocos são unidos
  (`{a}`+`{b}` → `{a,b}`), pois o transporte não distingue duas assinaturas
  do mesmo tópico na mesma sessão.
- **`prestamista_operacoes`** — `{"topico", "campo", "rotulo", "blocos"?}`;
  sem `blocos`, usa os blocos do próprio crédito.
- **Produto composto** — `{"tipo": "composto", "componentes": [...],
  "metricas_somadas": [...]}`. `agregacao`/`chaves_por_nivel` são
  documentacionais (a engine já usa Prefixo/Carteira conforme o nível).
- Editar o `produtos.json` não exige recompilar: dá para ajustar direto em
  `webapps/apigol/WEB-INF/classes/produtos.json` no servidor (recarregar o
  contexto para limpar o cache do catálogo).

## Visão unificada

Com o catálogo grande, o mesmo tópico aparece em vários produtos com blocos
diferentes — por isso a visão unificada agora consulta **uma sessão
WebSocket por produto, em paralelo (máx. 6 simultâneas, timeout 12s por
produto)**, em vez de uma sessão única (que silenciosamente misturava/perdia
blocos). Compostos são derivados dos componentes já consultados, sem
re-consulta. Com todos os 49 produtos selecionados a visão unificada ficou
mais pesada; use a seleção `?produtos=chave1,chave2` quando possível.

## Build

- Linux/macOS: `./build.sh`
- Windows: `build.bat`

Compila para **bytecode Java 8** (`--release 8` / `-source 8 -target 8`),
roda o self-test (`EngineSelfTest`, sem rede) e gera `dist/apigol.war`. Os
JARs de `lib/` (servlet-api 3.1, websocket-api 1.1) são só de compilação —
no servidor quem os fornece é o Tomcat; o WAR não tem `WEB-INF/lib`.

## Deploy (Tomcat 8.5 / super-pf1)

1. Copiar `dist/apigol.war` para `webapps\` do Tomcat (o `.war` deve
   **permanecer** lá depois de expandido — remover desfaz o deploy).
2. A autenticação continua a mesma: cookies da sessão GOL do usuário
   (`BBSSOToken` etc.) repassados pelo navegador, ou cabeçalho
   `X-GOL-Cookie`.
3. Rotas: `/painel`, `/captura`, `/health`, `/glossario`, `/produtos`,
   `/produto/{chave}/{prefixo}`, `/unificado/{prefixo}`, `/export/...`,
   `/raw/{prefixo}?topico=...`.

## Validação em produção (pendências do requisito)

1. Abrir Crédito Pessoal no painel e comparar as duas colunas de
   prestamista com a tela do GOL:
   - Se **`Com Prestamista`** trouxer os números certos, remover o bloco
     `prestamista_operacoes` dos quatro créditos no `produtos.json` (e de
     `metricas_somadas` do `credito_total`).
   - Se **`Operações com Prestamista`** for a correta, remover a dimensão
     `Com Prestamista` dos quatro créditos (e de `metricas_somadas`).
   - Teste rápido da hipótese do tópico:
     `/raw/9007?topico=web~gol2~cdc~prestamista~relacionamento~vendas-jurisdicao&blocos={1002}`.
2. Conferir o Crédito Total composto contra a soma manual dos quatro
   créditos (regional e carteira).
3. **Crédito Investimento Agro** está com blocos `{5005}` — os mesmos do
   Custeio — porque é o que consta no `apigol.docx`; se a intenção era outro
   bloco, ajustar no `produtos.json`.
4. BB Regulariza Agro ganhou o tópico `swp` (blocos `{5101}`, presente no
   documento) como métrica de vendas ("Regularizações") — validar o formato
   do payload.
5. Seguridade Total assina cada origem com os blocos capturados da própria
   página do GOL (blocos por tópico) — conferir os totais com a tela.
