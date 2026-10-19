-- InvestDecide — esquema mínimo para carregar as saídas de scripts/cvm_fundos.py
--
-- Uso (psql):
--   CREATE DATABASE investdecide;
--   \c investdecide
--   \i db/schema.sql
--   \copy catalogo_fundos FROM 'data/processed/catalogo_fundos.csv' WITH (FORMAT csv, HEADER true, DELIMITER ',', ENCODING 'UTF8')
--   \copy fundos_mensal   FROM 'data/processed/fundos_mensal.csv'   WITH (FORMAT csv, HEADER true, DELIMITER ',', ENCODING 'UTF8')
--   \copy fundos_diario   FROM 'data/processed/fundos_diario.csv'   WITH (FORMAT csv, HEADER true, DELIMITER ',', ENCODING 'UTF8')
--   \copy fatores_macro   FROM 'data/processed/fatores_macro.csv'   WITH (FORMAT csv, HEADER true, DELIMITER ',', ENCODING 'UTF8')
--
-- Observação: os CSVs trazem cabeçalho em minúsculas; os nomes abaixo seguem o mesmo padrão,
-- então o \copy casa as colunas pela ordem do arquivo.

DROP TABLE IF EXISTS fundos_diario;
DROP TABLE IF EXISTS fundos_mensal;
DROP TABLE IF EXISTS catalogo_fundos;
DROP TABLE IF EXISTS fatores_macro;

CREATE TABLE catalogo_fundos (
    cnpj               varchar(20) PRIMARY KEY,
    pl                 numeric(20,2),
    cotistas           integer,
    dias               integer,
    nome               text,
    tipo_fundo         text,
    classe_anbima      text,
    taxa_adm           numeric(10,4),
    taxa_perf          numeric(10,4),
    trib_lp            varchar(4),
    publico_alvo       text,
    condominio         text,
    situacao           text,
    gestor             text,
    administrador      text,
    benchmark          text,
    data_registro      date,
    origem_cadastro    text,
    meses_observados   integer,
    retorno_medio_mes  numeric(12,6),
    vol_media          numeric(12,6),
    pl_medio           numeric(20,2)
);

CREATE TABLE fundos_diario (
    cnpj           varchar(20) NOT NULL REFERENCES catalogo_fundos (cnpj),
    data           date        NOT NULL,
    ano_mes        varchar(7)  NOT NULL,
    vl_quota       numeric(24,10),
    vl_patrim_liq  numeric(20,2),
    captc_dia      numeric(20,2),
    resg_dia       numeric(20,2),
    nr_cotst       integer,
    PRIMARY KEY (cnpj, data)
);
CREATE INDEX ix_fundos_diario_mes ON fundos_diario (ano_mes);

CREATE TABLE fundos_mensal (
    cnpj              varchar(20) NOT NULL REFERENCES catalogo_fundos (cnpj),
    ano_mes           varchar(7)  NOT NULL,
    dias              integer,
    retorno_mes       numeric(12,6),
    vol_anual         numeric(12,6),
    pl_medio          numeric(20,2),
    cotistas_medio    numeric(12,2),
    captacao_liquida  numeric(20,2),
    retorno_12m       numeric(12,6),
    acumulado         numeric(12,6),
    PRIMARY KEY (cnpj, ano_mes)
);

CREATE TABLE fatores_macro (
    ano_mes    varchar(7) PRIMARY KEY,
    cdi_mes    numeric(10,6),
    ipca_mes   numeric(10,6),
    dolar_fim  numeric(12,6),
    dolar_ret  numeric(10,6)
);

-- Consulta de apoio: retorno mensal com os fatores, pronta para a regressão.
CREATE OR REPLACE VIEW vw_retorno_fatores AS
SELECT m.ano_mes,
       m.cnpj,
       c.nome,
       c.classe_anbima,
       c.taxa_adm,
       c.trib_lp,
       m.retorno_mes,
       m.vol_anual,
       f.cdi_mes,
       f.ipca_mes,
       f.dolar_ret
FROM fundos_mensal m
JOIN catalogo_fundos c ON c.cnpj = m.cnpj
LEFT JOIN fatores_macro f ON f.ano_mes = m.ano_mes;
