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

CREATE TABLE IF NOT EXISTS config_master (
  matricula    TEXT PRIMARY KEY,
  incluido_por TEXT,
  criado_em    INTEGER
);
INSERT OR IGNORE INTO config_master (matricula) VALUES ('F3548926');
INSERT OR IGNORE INTO config_master (matricula) VALUES ('F3191837');
INSERT OR IGNORE INTO config_master (matricula) VALUES ('F6323371');

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
