-- Atlas Estilo — schema SQLite (idempotente; executado a cada subida)
-- Datas em epoch millis. Identidade = matrícula do SSO em maiúsculas.

CREATE TABLE IF NOT EXISTS agencia (
  prefixo        TEXT PRIMARY KEY,
  nome           TEXT NOT NULL,
  segmento       TEXT NOT NULL DEFAULT 'ESTILO',
  uf             TEXT,
  municipio      TEXT,
  endereco       TEXT,
  cep            TEXT,
  lat            REAL,
  lng            REAL,
  regional       TEXT NOT NULL DEFAULT 'NÃO MAPEADA',
  super_regional TEXT,
  gmaps_url      TEXT,
  origem         TEXT NOT NULL DEFAULT 'IMPORT',
  atualizado_em  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_agencia_uf ON agencia(uf);
CREATE INDEX IF NOT EXISTS idx_agencia_regional ON agencia(regional);

CREATE TABLE IF NOT EXISTS funci (
  matricula     TEXT PRIMARY KEY,
  nome          TEXT NOT NULL,
  prefixo       TEXT NOT NULL,
  cargo         TEXT,
  funcao        TEXT,
  tipo          TEXT NOT NULL DEFAULT 'OUTRO',
  carteira      TEXT,
  posse_cargo   INTEGER,
  posse_funcao  INTEGER,
  origem        TEXT NOT NULL DEFAULT 'IMPORT',
  atualizado_em INTEGER
);
CREATE INDEX IF NOT EXISTS idx_funci_prefixo ON funci(prefixo);

CREATE TABLE IF NOT EXISTS carteira (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  prefixo           TEXT NOT NULL,
  codigo            TEXT NOT NULL,
  nome              TEXT,
  tipo              TEXT,
  gerente_matricula TEXT,
  qtd_clientes      INTEGER NOT NULL DEFAULT 0,
  origem            TEXT NOT NULL DEFAULT 'IMPORT',
  UNIQUE (prefixo, codigo)
);
CREATE INDEX IF NOT EXISTS idx_carteira_prefixo ON carteira(prefixo);

CREATE TABLE IF NOT EXISTS pdg (
  prefixo   TEXT NOT NULL,
  semestre  TEXT NOT NULL,
  atingiu   INTEGER NOT NULL DEFAULT 0,
  pontuacao REAL,
  origem    TEXT NOT NULL DEFAULT 'IMPORT',
  PRIMARY KEY (prefixo, semestre)
);

CREATE TABLE IF NOT EXISTS meta (
  prefixo       TEXT NOT NULL,
  periodo       TEXT NOT NULL,
  indicador     TEXT NOT NULL,
  meta          REAL,
  realizado     REAL,
  projecao      REAL,
  origem        TEXT NOT NULL DEFAULT 'IMPORT',
  atualizado_em INTEGER,
  PRIMARY KEY (prefixo, periodo, indicador)
);
CREATE INDEX IF NOT EXISTS idx_meta_prefixo ON meta(prefixo);

CREATE TABLE IF NOT EXISTS visita (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  prefixo        TEXT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'PLANEJADA',
  data_planejada INTEGER,
  data_realizada INTEGER,
  resumo         TEXT,
  criado_por     TEXT,
  criado_em      INTEGER,
  atualizado_em  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_visita_prefixo ON visita(prefixo);

CREATE TABLE IF NOT EXISTS anotacao (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  prefixo       TEXT,
  texto         TEXT NOT NULL,
  fixada        INTEGER NOT NULL DEFAULT 0,
  excluida      INTEGER NOT NULL DEFAULT 0,
  criado_por    TEXT,
  criado_em     INTEGER,
  atualizado_em INTEGER
);
CREATE INDEX IF NOT EXISTS idx_anotacao_prefixo ON anotacao(prefixo);

CREATE TABLE IF NOT EXISTS ponto_melhoria (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  prefixo       TEXT NOT NULL,
  descricao     TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'ABERTO',
  solucao       TEXT,
  previsao      INTEGER,
  resolvido_em  INTEGER,
  criado_por    TEXT,
  criado_em     INTEGER,
  atualizado_em INTEGER
);
CREATE INDEX IF NOT EXISTS idx_ponto_prefixo ON ponto_melhoria(prefixo);

-- Linha do tempo de uma ação (ponto_melhoria): comentários de retorno e
-- mudanças de status, para o Master acompanhar a cobrança.
-- tipo: RETORNO (do responsável), COBRANCA (do Master), STATUS (mudança feita
-- pelo Master), VERIFICACAO (conferência in loco). As demais colunas novas de
-- ponto_melhoria e foto (cadência, verificação, antes/depois) entram pelo
-- db/Migracoes.java para valer também em bancos já existentes.
CREATE TABLE IF NOT EXISTS acao_atualizacao (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ponto_id    INTEGER NOT NULL,
  texto       TEXT,
  status_novo TEXT,
  criado_por  TEXT,
  criado_em   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_acao_atu_ponto ON acao_atualizacao(ponto_id);

CREATE TABLE IF NOT EXISTS foto (
  id         TEXT PRIMARY KEY,
  prefixo    TEXT NOT NULL,
  matricula  TEXT,
  tipo       TEXT NOT NULL DEFAULT 'INTERNA',
  legenda    TEXT,
  arquivo    TEXT NOT NULL,
  mime       TEXT,
  origem     TEXT NOT NULL DEFAULT 'ADMIN',
  criado_por TEXT,
  criado_em  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_foto_prefixo ON foto(prefixo);

-- Carga inicial de masters: feita pelo código (ConfigDao.semearMastersSeVazio)
-- SOMENTE quando a tabela está vazia — reexecutar o schema no boot não pode
-- ressuscitar um master removido pela tela Admin.
CREATE TABLE IF NOT EXISTS config_master (
  matricula    TEXT PRIMARY KEY,
  incluido_por TEXT,
  criado_em    INTEGER
);

CREATE TABLE IF NOT EXISTS usuario_flag (
  matricula  TEXT PRIMARY KEY,
  flag       TEXT NOT NULL,
  criado_por TEXT,
  criado_em  INTEGER
);

CREATE TABLE IF NOT EXISTS import_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  tipo        TEXT NOT NULL,
  arquivo     TEXT,
  inseridos   INTEGER NOT NULL DEFAULT 0,
  atualizados INTEGER NOT NULL DEFAULT 0,
  ignorados   INTEGER NOT NULL DEFAULT 0,
  criado_por  TEXT,
  criado_em   INTEGER
);

-- ------------------------------------------------------------------------
-- Fontes de dados em CSV na pasta do servidor (configuradas no Admin)
-- ------------------------------------------------------------------------

-- Parâmetros editáveis pelo Master (pasta dos CSV, intervalo do monitor...)
CREATE TABLE IF NOT EXISTS config_parametro (
  chave          TEXT PRIMARY KEY,
  valor          TEXT,
  atualizado_por TEXT,
  atualizado_em  INTEGER
);

-- Cada fonte aponta para um arquivo (ou padrão, ex.: conexao_*.csv) da pasta.
-- tipo: agencias | funcis | carteiras | pdg | metas | conexao | indicadores
CREATE TABLE IF NOT EXISTS fonte_csv (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  nome              TEXT NOT NULL,
  tipo              TEXT NOT NULL,
  arquivo           TEXT NOT NULL,
  ativo             INTEGER NOT NULL DEFAULT 1,
  automatico        INTEGER NOT NULL DEFAULT 1,
  mapeamento        TEXT,
  ultima_leitura_em INTEGER,
  ultimo_arquivo    TEXT,
  ultimo_mtime      INTEGER,
  ultimo_status     TEXT,
  ultimo_resumo     TEXT,
  ultimo_relatorio  TEXT,
  ultimo_rejeitadas TEXT,
  criado_por        TEXT,
  criado_em         INTEGER
);

-- Conexão por competência (AAAA-MM). carteira = '' é a nota da agência;
-- as demais linhas são a Conexão de cada carteira/gerente.
CREATE TABLE IF NOT EXISTS conexao (
  prefixo           TEXT NOT NULL,
  competencia       TEXT NOT NULL,
  carteira          TEXT NOT NULL DEFAULT '',
  gerente_matricula TEXT,
  gerente_nome      TEXT,
  pontos            REAL NOT NULL,
  origem            TEXT NOT NULL DEFAULT 'IMPORT',
  atualizado_em     INTEGER,
  PRIMARY KEY (prefixo, competencia, carteira)
);
CREATE INDEX IF NOT EXISTS idx_conexao_comp ON conexao(competencia);

-- Valores genéricos de uma fonte 'indicadores' (uma linha por célula numérica)
CREATE TABLE IF NOT EXISTS indicador_valor (
  fonte_id    INTEGER NOT NULL,
  prefixo     TEXT NOT NULL,
  competencia TEXT NOT NULL DEFAULT '',
  coluna      TEXT NOT NULL,
  valor       REAL,
  PRIMARY KEY (fonte_id, prefixo, competencia, coluna)
);
CREATE INDEX IF NOT EXISTS idx_indicador_col ON indicador_valor(fonte_id, coluna, competencia);

-- Visões (cards) do dashboard montadas a partir das fontes
CREATE TABLE IF NOT EXISTS visao_dashboard (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  titulo        TEXT NOT NULL,
  fonte_id      INTEGER NOT NULL,
  coluna        TEXT NOT NULL,
  agregacao     TEXT NOT NULL DEFAULT 'MEDIA',
  formato       TEXT NOT NULL DEFAULT 'INTEIRO',
  casas         INTEGER NOT NULL DEFAULT 0,
  meta          REAL,
  coluna_meta   TEXT,
  melhor        TEXT NOT NULL DEFAULT 'MAIOR',
  minimo        REAL,
  maximo        REAL,
  perfil_minimo TEXT NOT NULL DEFAULT 'COLEGA',
  ordem         INTEGER NOT NULL DEFAULT 0,
  ativo         INTEGER NOT NULL DEFAULT 1,
  criado_por    TEXT,
  criado_em     INTEGER
);
