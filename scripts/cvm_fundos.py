#!/usr/bin/env python3
"""
cvm_fundos.py — ingestão de dados abertos da CVM e do Banco Central para o InvestDecide.

O script monta a base analítica do SAD a partir de fontes públicas e gratuitas:

  * Informe Diário de Fundos (CVM)  -> cota, patrimônio líquido, captação/resgate, cotistas
  * Cadastro de Fundos (CVM)        -> taxa de administração, classe ANBIMA, tributação de LP
  * Séries do Banco Central (SGS)   -> CDI, IPCA e dólar (fatores para a regressão)

Saídas (CSV e, se pyarrow estiver instalado, também Parquet):

  catalogo_fundos.csv  — um registro por fundo, com atributos e período observado
  fundos_diario.csv    — painel diário dos fundos selecionados
  fundos_mensal.csv    — agregação mensal (retorno, volatilidade, PL, captação)
  fatores_macro.csv    — CDI, IPCA e dólar por mês
  qualidade.json       — contagens, nulos, duplicatas e meses faltantes

Exemplos
--------
    # baixa 24 meses, processa e grava em data/
    python scripts/cvm_fundos.py tudo --meses 24

    # apenas processa arquivos já baixados (funciona offline)
    python scripts/cvm_fundos.py processar --raw-dir data/raw --out-dir data/processed

    # universo menor, para testar rápido
    python scripts/cvm_fundos.py tudo --meses 6 --limite 40 --min-pl 10000000

Observações
-----------
* A CVM publica arquivos mensais em ZIP (CSV separado por ';'). O script baixa uma vez e
  reutiliza o cache em --raw-dir.
* O informe diário tem defasagem de um dia útil (art. 59 da ICVM 555).
* A cota já é líquida da taxa de administração: não desconte a taxa novamente ao calcular
  retorno. Use TAXA_ADM apenas para comparar custos entre produtos.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import zipfile
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

CVM_BASE = "https://dados.cvm.gov.br/dados/FI"
INF_DIARIO_URL = CVM_BASE + "/DOC/INF_DIARIO/DADOS/inf_diario_fi_{ym}.zip"
CADASTRO_LEGADO_URL = CVM_BASE + "/CAD/DADOS/cad_fi.csv"
CADASTRO_NOVO_URL = CVM_BASE + "/CAD/DADOS/registro_fundo_classe.zip"

BCB_SGS = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.{codigo}/dados"

# Códigos das séries do SGS/BCB. Confira em https://www3.bcb.gov.br/sgspub/
SERIES_BCB = {
    "cdi_diario": 12,   # Taxa de juros - CDI, % ao dia
    "ipca_mes": 433,    # IPCA, variação mensal (%)
    "dolar": 1,         # Dólar comercial (venda), R$
}

COLUNAS_INFORME = [
    "CNPJ_FUNDO_CLASSE", "CNPJ_FUNDO", "DT_COMPTC", "VL_QUOTA",
    "VL_PATRIM_LIQ", "CAPTC_DIA", "RESG_DIA", "NR_COTST", "TP_FUNDO_CLASSE",
]

# Apelidos aceitos para cada campo: o schema da CVM muda (RCVM 175) e os nomes variam
# entre o arquivo legado (cad_fi.csv) e os novos (registro_*.csv).
APELIDOS = {
    "cnpj": ["CNPJ_FUNDO_CLASSE", "CNPJ_CLASSE", "CNPJ_FUNDO", "CNPJ"],
    "nome": ["DENOM_SOCIAL", "DENOMINACAO_SOCIAL", "NOME_FUNDO", "NOME"],
    "tipo_fundo": ["TP_FUNDO", "TIPO_FUNDO"],
    "classe_anbima": ["CLASSE_ANBIMA", "CLASSE_ANBIMA_FUNDO"],
    "taxa_adm": ["TAXA_ADM", "TAXA_ADMINISTRACAO", "TAXA_DE_ADMINISTRACAO"],
    "taxa_perf": ["TAXA_PERFM", "TAXA_PERFORMANCE"],
    "trib_lp": ["TRIB_LPRAZO", "TRIBUTACAO_LPRAZO", "TRIBUTACAO_LONGO_PRAZO"],
    "publico_alvo": ["PUBLICO_ALVO", "PUBLICO_ALVO_FUNDO"],
    "condominio": ["CONDOM", "CONDOMINIO"],
    "situacao": ["SIT", "SITUACAO"],
    "gestor": ["GESTOR"],
    "administrador": ["ADMIN", "ADMINISTRADOR"],
    "benchmark": ["RENTAB_FUNDO", "BENCHMARK", "INDICADOR_DESEMPENHO"],
    "data_registro": ["DT_REG", "DATA_REGISTRO"],
}


# --------------------------------------------------------------------------- utilidades


def log(msg: str) -> None:
    print(f"[cvm] {msg}", flush=True)


def meses_entre(inicio: str, fim: str) -> list[str]:
    """Lista de 'YYYYMM' entre dois 'YYYY-MM' (inclusive)."""
    ini = pd.Period(inicio, freq="M")
    end = pd.Period(fim, freq="M")
    return [p.strftime("%Y%m") for p in pd.period_range(ini, end, freq="M")]


def ultimo_mes_fechado() -> str:
    hoje = pd.Timestamp.today()
    return (hoje.to_period("M") - 1).strftime("%Y-%m")


def baixar(url: str, destino: Path, tentativas: int = 3, force: bool = False) -> Path:
    destino.parent.mkdir(parents=True, exist_ok=True)
    if destino.exists() and destino.stat().st_size > 0 and not force:
        return destino
    for tentativa in range(1, tentativas + 1):
        try:
            log(f"baixando {url}")
            with urllib.request.urlopen(url, timeout=120) as resp, open(destino, "wb") as fh:
                fh.write(resp.read())
            return destino
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if tentativa == tentativas:
                raise RuntimeError(f"falha ao baixar {url}: {exc}") from exc
            espera = 2 ** tentativa
            log(f"  erro ({exc}); tentando de novo em {espera}s")
            time.sleep(espera)
    return destino


def ler_csv(texto: bytes | str | Path) -> pd.DataFrame:
    """Lê CSV da CVM detectando codificação e separador usuais (';' + latin-1)."""
    if isinstance(texto, Path):
        bruto = texto.read_bytes()
    elif isinstance(texto, str):
        bruto = texto.encode("utf-8")
    else:
        bruto = texto

    amostra = bruto[:8192].decode("latin-1", errors="replace")
    sep = ";" if amostra.count(";") >= amostra.count(",") else ","

    for encoding in ("utf-8-sig", "latin-1"):
        try:
            return pd.read_csv(io.BytesIO(bruto), sep=sep, encoding=encoding, dtype=str,
                               low_memory=False, on_bad_lines="skip")
        except UnicodeDecodeError:
            continue
    return pd.read_csv(io.BytesIO(bruto), sep=sep, encoding="latin-1", dtype=str,
                       low_memory=False, on_bad_lines="skip", encoding_errors="replace")


def para_numero(serie: pd.Series) -> pd.Series:
    """Converte '1.234,56' ou '1234.56' em float, sem depender do locale."""
    texto = serie.astype("string").str.strip()
    tem_virgula = texto.str.contains(",", na=False)
    # assume '.' como milhar apenas quando há vírgula decimal
    texto = texto.where(~tem_virgula, texto.str.replace(".", "", regex=False))
    texto = texto.str.replace(",", ".", regex=False)
    texto = texto.str.replace(r"[^0-9.\-]", "", regex=True)
    return pd.to_numeric(texto, errors="coerce")


def primeira_coluna(df: pd.DataFrame, candidatas: list[str]) -> str | None:
    for coluna in candidatas:
        if coluna in df.columns:
            return coluna
    return None


def extrair_csv_do_zip(caminho: Path) -> bytes:
    with zipfile.ZipFile(caminho) as zf:
        nomes = [n for n in zf.namelist() if n.lower().endswith(".csv")]
        if not nomes:
            raise RuntimeError(f"nenhum CSV dentro de {caminho}")
        return zf.read(nomes[0])


# --------------------------------------------------------------------------- download


def baixar_informes(meses: list[str], raw_dir: Path, force: bool) -> list[Path]:
    caminhos = []
    for ym in meses:
        destino = raw_dir / f"inf_diario_fi_{ym}.zip"
        caminhos.append(baixar(INF_DIARIO_URL.format(ym=ym), destino, force=force))
    return caminhos


def baixar_cadastro(raw_dir: Path, force: bool) -> None:
    baixar(CADASTRO_LEGADO_URL, raw_dir / "cad_fi.csv", force=force)
    baixar(CADASTRO_NOVO_URL, raw_dir / "registro_fundo_classe.zip", force=force)


def baixar_fatores(inicio: str, fim: str, destino: Path) -> pd.DataFrame | None:
    """Séries do BCB (SGS) consolidadas por mês."""
    ini = pd.Period(inicio, freq="M").start_time
    end = pd.Period(fim, freq="M").end_time
    params = f"?formato=json&dataInicial={ini:%d/%m/%Y}&dataFinal={end:%d/%m/%Y}"

    dados: dict[str, pd.Series] = {}
    for nome, codigo in SERIES_BCB.items():
        url = BCB_SGS.format(codigo=codigo) + params
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                bruto = json.loads(resp.read().decode("utf-8"))
        except Exception as exc:  # rede indisponível não deve derrubar o pipeline
            log(f"aviso: não foi possível baixar a série {nome} ({exc})")
            continue
        if not bruto:
            continue
        serie = pd.DataFrame(bruto)
        serie["data"] = pd.to_datetime(serie["data"], format="%d/%m/%Y")
        serie["valor"] = para_numero(serie["valor"])
        dados[nome] = serie.set_index("data")["valor"].sort_index()

    if not dados:
        return None

    tabela = pd.DataFrame(dados)
    tabela.index.name = "data"
    mensal = pd.DataFrame(index=tabela.index.to_period("M").unique())

    if "cdi_diario" in tabela:
        cdi = (1 + tabela["cdi_diario"].fillna(0) / 100).groupby(tabela.index.to_period("M")).prod() - 1
        mensal["cdi_mes"] = cdi * 100
    if "ipca_mes" in tabela:
        mensal["ipca_mes"] = tabela["ipca_mes"].groupby(tabela.index.to_period("M")).sum()
    if "dolar" in tabela:
        dolar = tabela["dolar"].groupby(tabela.index.to_period("M")).last()
        mensal["dolar_fim"] = dolar
        mensal["dolar_ret"] = dolar.pct_change() * 100

    mensal = mensal.reset_index().rename(columns={"index": "ano_mes"})
    mensal["ano_mes"] = mensal["ano_mes"].astype(str)
    destino.parent.mkdir(parents=True, exist_ok=True)
    mensal.to_csv(destino, index=False, encoding="utf-8-sig")
    log(f"fatores macro: {len(mensal)} meses -> {destino.name}")
    return mensal


# --------------------------------------------------------------------------- leitura


def sem_acento(texto: str) -> str:
    decomposto = unicodedata.normalize("NFKD", str(texto))
    return "".join(c for c in decomposto if not unicodedata.combining(c)).upper().strip()


def mapear_colunas(df: pd.DataFrame) -> pd.DataFrame:
    """Renomeia colunas do cadastro aceitando variações de nome entre versões do layout."""
    normalizadas = {sem_acento(coluna): coluna for coluna in df.columns}
    mapa: dict[str, str] = {}
    for destino, apelidos in APELIDOS.items():
        for apelido in apelidos:
            original = normalizadas.get(sem_acento(apelido))
            if original is not None:
                mapa[original] = destino
                break
    if "cnpj" not in mapa.values():
        for normalizada, original in normalizadas.items():
            if normalizada.startswith("CNPJ"):
                mapa[original] = "cnpj"
                break
    return df.rename(columns=mapa)


def carregar_cadastro(raw_dir: Path) -> pd.DataFrame:
    """Une o cadastro legado (cad_fi.csv) e o novo (registro_fundo_classe.zip)."""
    partes = []

    legado = raw_dir / "cad_fi.csv"
    if legado.exists():
        try:
            df = mapear_colunas(ler_csv(legado))
            if "cnpj" in df.columns:
                df["origem_cadastro"] = "cad_fi"
                partes.append(df)
        except Exception as exc:
            log(f"aviso: cadastro legado ignorado ({exc})")

    novo = raw_dir / "registro_fundo_classe.zip"
    if novo.exists():
        try:
            with zipfile.ZipFile(novo) as zf:
                for nome in zf.namelist():
                    if not nome.lower().endswith(".csv"):
                        continue
                    df = mapear_colunas(ler_csv(zf.read(nome)))
                    if "cnpj" not in df.columns:
                        continue
                    df["origem_cadastro"] = Path(nome).stem
                    partes.append(df)
        except Exception as exc:
            log(f"aviso: cadastro novo ignorado ({exc})")

    if not partes:
        raise RuntimeError("nenhum arquivo de cadastro encontrado em " + str(raw_dir))

    cadastro = pd.concat(partes, ignore_index=True, sort=False)
    cadastro["cnpj"] = cadastro["cnpj"].astype(str).str.strip()
    cadastro = cadastro.drop_duplicates(subset="cnpj", keep="last")

    for coluna in ("taxa_adm", "taxa_perf"):
        if coluna in cadastro.columns:
            cadastro[coluna] = para_numero(cadastro[coluna])

    for coluna in ("classe_anbima", "trib_lp", "publico_alvo", "condominio", "situacao",
                   "gestor", "administrador", "benchmark", "nome", "tipo_fundo"):
        if coluna not in cadastro.columns:
            cadastro[coluna] = None

    log(f"cadastro: {len(cadastro)} fundos")
    return cadastro


def carregar_informe(caminhos: list[Path], cnpjs: set[str] | None = None,
                     colunas: list[str] | None = None) -> pd.DataFrame:
    """Lê os ZIPs mensais do informe diário, opcionalmente filtrando por CNPJ."""
    colunas = colunas or COLUNAS_INFORME
    blocos = []
    for caminho in caminhos:
        try:
            bruto = extrair_csv_do_zip(caminho)
        except Exception as exc:
            log(f"aviso: {caminho.name} ignorado ({exc})")
            continue
        df = ler_csv(bruto)
        df = df[[c for c in colunas if c in df.columns]]

        chave = primeira_coluna(df, ["CNPJ_FUNDO_CLASSE", "CNPJ_FUNDO"])
        if chave is None:
            log(f"aviso: {caminho.name} sem coluna de CNPJ")
            continue
        df["cnpj"] = df[chave].astype(str).str.strip()

        if cnpjs is not None:
            df = df[df["cnpj"].isin(cnpjs)]
        if df.empty:
            continue

        df["data"] = pd.to_datetime(df["DT_COMPTC"], errors="coerce")
        df = df.dropna(subset=["data"])
        df["ano_mes"] = df["data"].dt.strftime("%Y-%m")

        for coluna in ("VL_QUOTA", "VL_PATRIM_LIQ", "CAPTC_DIA", "RESG_DIA", "NR_COTST"):
            if coluna in df.columns:
                df[coluna] = para_numero(df[coluna])

        blocos.append(df[["cnpj", "data", "ano_mes", "VL_QUOTA", "VL_PATRIM_LIQ",
                          "CAPTC_DIA", "RESG_DIA", "NR_COTST"]].copy())
        log(f"  {caminho.name}: {len(df):,} linhas".replace(",", "."))

    if not blocos:
        raise RuntimeError("nenhum dado do informe diário foi lido")

    diario = pd.concat(blocos, ignore_index=True)
    diario = diario.drop_duplicates(subset=["cnpj", "data"], keep="last")
    diario = diario.sort_values(["cnpj", "data"])
    return diario


# --------------------------------------------------------------------------- transformação


def selecionar_universo(diario: pd.DataFrame, cadastro: pd.DataFrame, args) -> tuple[list[str], pd.DataFrame]:
    """Escolhe os fundos do estudo a partir do último mês disponível."""
    ultimo = diario["ano_mes"].max()
    referencia = diario[diario["ano_mes"] == ultimo].groupby("cnpj").agg(
        pl=("VL_PATRIM_LIQ", "last"),
        cotistas=("NR_COTST", "last"),
        dias=("data", "nunique"),
    )
    referencia = referencia[referencia["dias"] >= 10]

    if args.min_pl > 0:
        referencia = referencia[referencia["pl"] >= args.min_pl]
    if args.min_cotistas > 0:
        referencia = referencia[referencia["cotistas"].fillna(0) >= args.min_cotistas]
    if args.cnpjs:
        referencia = referencia[referencia.index.isin(args.cnpjs)]

    catalogo = referencia.join(cadastro.set_index("cnpj"), how="left")

    if args.excluir_exclusivos:
        catalogo = catalogo[catalogo["condominio"].fillna("").str.upper() != "FECHADO"]
    if args.publico_alvo:
        alvo = catalogo["publico_alvo"].fillna("Público Geral").str.upper()
        catalogo = catalogo[alvo.isin(["PÚBLICO GERAL", "PUBLICO GERAL", "QUALIFICADO"])]

    catalogo = catalogo.sort_values("pl", ascending=False)
    if args.limite and args.limite > 0:
        catalogo = catalogo.head(args.limite)

    if catalogo.empty:
        raise RuntimeError("nenhum fundo atende aos filtros — relaxe --min-pl, --limite ou --cnpjs")

    log(f"universo: {len(catalogo)} fundos (mês de referência {ultimo})")
    return catalogo.index.tolist(), catalogo.reset_index()


def agregar_mensal(diario: pd.DataFrame) -> pd.DataFrame:
    """Converte o painel diário em retorno/volatilidade/PL por fundo-mês."""
    diario = diario.sort_values(["cnpj", "data"]).copy()
    diario["retorno_dia"] = diario.groupby("cnpj")["VL_QUOTA"].pct_change()

    linhas = []
    for (cnpj, ano_mes), grupo in diario.groupby(["cnpj", "ano_mes"], sort=True):
        grupo = grupo.dropna(subset=["VL_QUOTA"])
        if len(grupo) < 5:
            continue
        primeira = grupo["VL_QUOTA"].iloc[0]
        ultima = grupo["VL_QUOTA"].iloc[-1]
        if not primeira or primeira <= 0:
            continue
        retornos = grupo["retorno_dia"].dropna()
        linhas.append({
            "cnpj": cnpj,
            "ano_mes": ano_mes,
            "dias": len(grupo),
            "retorno_mes": (ultima / primeira - 1) * 100,
            "vol_anual": retornos.std(ddof=1) * np.sqrt(252) * 100 if len(retornos) > 2 else np.nan,
            "pl_medio": grupo["VL_PATRIM_LIQ"].mean(),
            "cotistas_medio": grupo["NR_COTST"].mean(),
            "captacao_liquida": (grupo["CAPTC_DIA"].fillna(0) - grupo["RESG_DIA"].fillna(0)).sum(),
        })

    mensal = pd.DataFrame(linhas)
    if mensal.empty:
        return mensal

    mensal["ano_mes"] = mensal["ano_mes"].astype(str)
    mensal = mensal.sort_values(["cnpj", "ano_mes"])
    mensal["retorno_12m"] = (
        mensal.groupby("cnpj")["retorno_mes"]
        .transform(lambda s: (1 + s / 100).rolling(12, min_periods=6).apply(np.prod, raw=True) - 1) * 100
    )
    mensal["acumulado"] = (
        mensal.groupby("cnpj")["retorno_mes"]
        .transform(lambda s: ((1 + s / 100).cumprod() - 1) * 100)
    )
    return mensal


def painel_balanceado(mensal: pd.DataFrame, meses: list[str]) -> pd.DataFrame:
    """Mantém apenas fundos com observação em todos os meses pedidos."""
    presentes = mensal.groupby("cnpj")["ano_mes"].nunique()
    completos = presentes[presentes >= max(1, len(meses) - 1)].index
    return mensal[mensal["cnpj"].isin(completos)].copy()


# --------------------------------------------------------------------------- saída


def escrever(df: pd.DataFrame, nome: str, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    csv_path = out_dir / f"{nome}.csv"
    df.to_csv(csv_path, index=False, encoding="utf-8-sig")
    try:
        import pyarrow  # noqa: F401
        df.to_parquet(out_dir / f"{nome}.parquet", index=False)
    except ImportError:
        pass
    log(f"{nome}: {len(df):,} linhas -> {csv_path.name}".replace(",", "."))
    return csv_path


def relatorio_qualidade(diario: pd.DataFrame, mensal: pd.DataFrame, meses: list[str],
                        catalogo: pd.DataFrame, out_dir: Path) -> None:
    por_mes = set(diario["ano_mes"].unique())
    faltantes = [m for m in meses if f"{m[:4]}-{m[4:]}" not in por_mes]
    info = {
        "gerado_em": date.today().isoformat(),
        "meses_solicitados": len(meses),
        "meses_sem_dados": faltantes,
        "fundos_no_universo": int(len(catalogo)),
        "linhas_diarias": int(len(diario)),
        "linhas_mensais": int(len(mensal)),
        "duplicatas_removidas": "ver drop_duplicates em carregar_informe",
        "cotas_ausentes_pct": float(diario["VL_QUOTA"].isna().mean() * 100),
        "pl_ausente_pct": float(diario["VL_PATRIM_LIQ"].isna().mean() * 100),
        "fundos_sem_taxa_adm": int(catalogo["taxa_adm"].isna().sum()) if "taxa_adm" in catalogo else None,
        "periodo": {"de": diario["ano_mes"].min(), "ate": diario["ano_mes"].max()},
    }
    caminho = out_dir / "qualidade.json"
    caminho.write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"qualidade -> {caminho.name}")


# --------------------------------------------------------------------------- comandos


def resolver_periodo(args) -> tuple[str, str]:
    """Resolve (início, fim) a partir de --inicio ou --meses."""
    fim = args.fim or ultimo_mes_fechado()
    if args.inicio:
        inicio = args.inicio
    elif getattr(args, "meses", None):
        inicio = (pd.Period(fim, freq="M") - (args.meses - 1)).strftime("%Y-%m")
    else:
        raise SystemExit("informe --inicio AAAA-MM ou --meses N")
    if pd.Period(inicio, freq="M") > pd.Period(fim, freq="M"):
        raise SystemExit("--inicio deve ser anterior a --fim")
    return inicio, fim


def comando_baixar(args) -> None:
    inicio, fim = resolver_periodo(args)
    meses = meses_entre(inicio, fim)
    log(f"baixando {len(meses)} meses ({meses[0]}..{meses[-1]})")
    baixar_informes(meses, Path(args.raw_dir), args.force)
    baixar_cadastro(Path(args.raw_dir), args.force)
    if not args.sem_fatores:
        baixar_fatores(inicio, fim, Path(args.out_dir) / "fatores_macro.csv")


def comando_processar(args) -> None:
    raw_dir = Path(args.raw_dir)
    out_dir = Path(args.out_dir)
    inicio, fim = resolver_periodo(args)
    meses = meses_entre(inicio, fim)
    ym = [m for m in meses]

    caminhos = [raw_dir / f"inf_diario_fi_{m}.zip" for m in ym]
    existentes = [c for c in caminhos if c.exists()]
    if not existentes:
        raise SystemExit(f"nenhum arquivo em {raw_dir}; rode o comando 'baixar' primeiro")
    if len(existentes) < len(caminhos):
        log(f"aviso: {len(caminhos) - len(existentes)} meses sem arquivo local")

    cadastro = carregar_cadastro(raw_dir)

    # primeira passada: usa só o último mês para escolher o universo
    amostra = carregar_informe(existentes[-1:])
    cnpjs, catalogo = selecionar_universo(amostra, cadastro, args)

    # segunda passada: filtra todos os meses pelo universo escolhido
    diario = carregar_informe(existentes, cnpjs=set(cnpjs))

    mensal = agregar_mensal(diario)
    if args.painel_balanceado and not mensal.empty:
        mensal = painel_balanceado(mensal, meses)

    catalogo = catalogo.copy()
    if not mensal.empty:
        periodo = mensal.groupby("cnpj").agg(
            meses_observados=("ano_mes", "nunique"),
            retorno_medio_mes=("retorno_mes", "mean"),
            vol_media=("vol_anual", "mean"),
            pl_medio=("pl_medio", "mean"),
        ).reset_index()
        catalogo = catalogo.merge(periodo, on="cnpj", how="left")
        catalogo = catalogo[catalogo["meses_observados"].fillna(0) > 0]

    escrever(catalogo, "catalogo_fundos", out_dir)
    escrever(diario, "fundos_diario", out_dir)
    escrever(mensal, "fundos_mensal", out_dir)
    relatorio_qualidade(diario, mensal, meses, catalogo, out_dir)

    if not args.sem_fatores:
        destino = out_dir / "fatores_macro.csv"
        if not destino.exists():
            baixar_fatores(inicio, fim, destino)
        else:
            log("fatores macro já existem; mantendo arquivo atual")


def construir_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Ingestão CVM/BCB para o InvestDecide")
    sub = parser.add_subparsers(dest="comando")

    def comuns(p):
        p.add_argument("--inicio", help="primeiro mês, formato AAAA-MM")
        p.add_argument("--meses", type=int, help="quantidade de meses terminando em --fim")
        p.add_argument("--fim", default=None, help="último mês, formato AAAA-MM (padrão: último mês fechado)")
        p.add_argument("--raw-dir", default="data/raw")
        p.add_argument("--out-dir", default="data/processed")
        p.add_argument("--sem-fatores", action="store_true", help="não baixar séries do BCB")
        p.add_argument("--force", action="store_true", help="rebaixar arquivos já em cache")

    p_baixar = sub.add_parser("baixar", help="baixa ZIPs e cadastro")
    comuns(p_baixar)

    p_proc = sub.add_parser("processar", help="processa arquivos locais")
    comuns(p_proc)
    p_proc.add_argument("--min-pl", type=float, default=50_000_000, help="PL mínimo no mês de referência")
    p_proc.add_argument("--min-cotistas", type=int, default=100)
    p_proc.add_argument("--limite", type=int, default=200, help="máximo de fundos (0 = todos)")
    p_proc.add_argument("--cnpjs", type=lambda s: {c.strip() for c in s.split(",") if c.strip()},
                        default=None, help="lista de CNPJs separada por vírgula")
    p_proc.add_argument("--excluir-exclusivos", action=argparse.BooleanOptionalAction, default=True)
    p_proc.add_argument("--publico-alvo", action=argparse.BooleanOptionalAction, default=True,
                        help="manter apenas fundos de varejo/qualificado")
    p_proc.add_argument("--painel-balanceado", action=argparse.BooleanOptionalAction, default=True)

    p_tudo = sub.add_parser("tudo", help="baixa e processa")
    comuns(p_tudo)
    p_tudo.add_argument("--min-pl", type=float, default=50_000_000)
    p_tudo.add_argument("--min-cotistas", type=int, default=100)
    p_tudo.add_argument("--limite", type=int, default=200)
    p_tudo.add_argument("--cnpjs", type=lambda s: {c.strip() for c in s.split(",") if c.strip()}, default=None)
    p_tudo.add_argument("--excluir-exclusivos", action=argparse.BooleanOptionalAction, default=True)
    p_tudo.add_argument("--publico-alvo", action=argparse.BooleanOptionalAction, default=True)
    p_tudo.add_argument("--painel-balanceado", action=argparse.BooleanOptionalAction, default=True)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = construir_parser()
    args = parser.parse_args(argv)

    if not args.comando:
        # atalho: python cvm_fundos.py --meses 24
        parser.print_help()
        return 1

    if args.comando == "baixar":
        comando_baixar(args)
    elif args.comando == "processar":
        comando_processar(args)
    elif args.comando == "tudo":
        comando_baixar(args)
        comando_processar(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
