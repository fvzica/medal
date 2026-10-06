# Atlas Estilo — SUPER PF1 · Banco do Brasil

Atlas interativo das agências Estilo: mapa do Brasil com efeito 3D e zoom por
estado/município, métricas filtráveis da região (funcis, gerentes, assistentes,
carteiras, tempos médios, PDG), página da agência com fachada (foto ou Google
Maps) e o botão **“Entrar · dashboard”** na porta, planejamento de visitas,
anotações e pontos de melhoria com previsão de término.

Stack: Java 8 · JSP/Servlets puros · SQLite (WAR) · Tomcat 8.5 (Windows x86) ·
SSO OAuth2 do BB · identidade editorial SUPER PF1 (claro/escuro).

## Perfis

| Perfil | Quem | O que vê |
|---|---|---|
| **Colega** | qualquer matrícula no SSO | grandes números dos prefixos da **sua regional** (sem pessoas, sem fotos de pessoas, sem gestão) |
| **Moderador** | prefixo 9007 | tudo em leitura (todas as regiões, equipes, planejamento) |
| **Master** | tabela `config_master` | tudo + gestão (visitas, anotações, pontos, imports, fotos, acessos) |

Carga inicial de masters: `F3548926`, `F3191837`, `F6323371`.

## Build

```
./build.sh        # produção -> dist/atlasestilo.war
./build.sh dev    # teste local SEM SSO (usuário simulado Master) -> dist/atlasestilo-dev.war
```

Compila com `--release 8`, roda o SelfTest (31 verificações, sem rede) e
empacota. O WAR leva o driver SQLite em `WEB-INF/lib`.

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
