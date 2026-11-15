#!/usr/bin/env python3
"""
cvm_xp.py — monta a base real do InvestDecide a partir da CVM.

O que faz:
  1. baixa o cadastro de fundos/classes da CVM (registro_fundo_classe.zip);
  2. casa a lista de fundos distribuídos pela XP (nome comercial) com o cadastro,
     usando tokens do nome e limiar de similaridade;
  3. baixa os informes diários dos últimos N meses;
  4. filtra os fundos da XP em funcionamento, com patrimônio mínimo;
  5. calcula retorno mensal, volatilidade e PL médio por fundo;
  6. grava CSVs prontos para o sistema Java em data/processed/.

Uso:
    python scripts/cvm_xp.py --lista "C:\\caminho\\fundos-xp.txt" --meses 12

Observações:
  * a lista da XP usa nome comercial, truncado em ~30 caracteres; o casamento é
    aproximado e fica registrado em catalogo_fundos.csv (coluna score_match);
  * a CVM não informa a taxa de administração no cadastro novo (RCVM 175) — o campo
    fica vazio para parte dos fundos, e o sistema trata como "não informado";
  * liquidez não é informada pela CVM; o sistema assume D+30 para fundos e sinaliza.
"""

from __future__ import annotations

import argparse
import io
import re
import unicodedata
import urllib.request
import zipfile
from difflib import SequenceMatcher
from pathlib import Path

import numpy as np
import pandas as pd

CVM = "https://dados.cvm.gov.br/dados/FI"
CADASTRO = CVM + "/CAD/DADOS/registro_fundo_classe.zip"
INF_DIARIO = CVM + "/DOC/INF_DIARIO/DADOS/inf_diario_fi_{ym}.zip"

GENERICOS = {"FI", "FIF", "FIC", "FIM", "FIA", "FIDC", "FIP", "FIE", "CP", "LP", "RL",
             "CIC", "EM", "DE", "DO", "DA", "E", "ADVISORY", "INSTITUCIONAL", "MASTER",
             "FEEDER", "COTAS", "FUNDO", "INVESTIMENTO", "CLASSES", "CLASSE", "SEG"}


def log(msg: str) -> None:
    print(f"[xp] {msg}", flush=True)


def norm(texto: str) -> str:
    t = "".join(c for c in unicodedata.normalize("NFKD", str(texto)) if not unicodedata.combining(c))
    t = t.upper()
    t = re.sub(r"\.{2,}$", "", t)
    t = re.sub(r"[^A-Z0-9 ]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def baixar(url: str, destino: Path) -> Path:
    if destino.exists() and destino.stat().st_size > 0:
        return destino
    destino.parent.mkdir(parents=True, exist_ok=True)
    log(f"baixando {url}")
    with urllib.request.urlopen(url, timeout=300) as r, open(destino, "wb") as f:
        f.write(r.read())
    return destino


def ler(buf: bytes) -> pd.DataFrame:
    for enc in ("utf-8-sig", "latin-1"):
        try:
            return pd.read_csv(io.BytesIO(buf), sep=";", dtype=str, encoding=enc, low_memory=False)
        except UnicodeDecodeError:
            continue
    return pd.read_csv(io.BytesIO(buf), sep=";", dtype=str, encoding="latin-1")


def para_numero(serie: pd.Series) -> pd.Series:
    texto = serie.astype("string").str.strip()
    tem_virgula = texto.str.contains(",", na=False)
    texto = texto.where(~tem_virgula, texto.str.replace(".", "", regex=False))
    texto = texto.str.replace(",", ".", regex=False)
    texto = texto.str.replace(r"[^0-9.\-]", "", regex=True)
    return pd.to_numeric(texto, errors="coerce")


# ------------------------------------------------------------------ casamento XP

def casar_xp(lista: pd.DataFrame, classes: pd.DataFrame) -> pd.DataFrame:
    classes = classes.copy()
    classes["nome_norm"] = classes["Denominacao_Social"].map(norm)
    classes["tokens"] = classes["nome_norm"].str.split()

    por_primeiro = {}
    for i, linha in classes.iterrows():
        if linha["tokens"]:
            por_primeiro.setdefault(linha["tokens"][0], []).append(i)

    registros = []
    for _, linha in lista.iterrows():
        tokens = linha["nome_norm"].split()
        truncado = linha["nome"].endswith("...")
        if truncado and len(tokens) > 1:
            tokens = tokens[:-1] + [tokens[-1][:6]]
        uteis = [t for t in tokens if t not in GENERICOS]
        melhor, melhor_score = None, 0.0
        for i in por_primeiro.get(tokens[0], []):
            cand = classes.loc[i]
            cand_tokens = set(cand["tokens"])
            acertos = sum(1 for t in uteis if any(ct.startswith(t) for ct in cand_tokens))
            score = acertos / max(1, len(uteis))
            if " ".join(tokens[:3]) in cand["nome_norm"]:
                score += 0.05
            if score > melhor_score:
                melhor, melhor_score = cand, score

        if melhor is not None and melhor_score >= 0.8:
            registros.append({
                "cnpj": melhor["CNPJ_Classe"],
                "nome_cvm": melhor["Denominacao_Social"],
                "nome_xp": linha["nome"],
                "tipo_xp": linha["tipo"],
                "score_match": round(melhor_score, 3),
                "classe_anbima": melhor.get("Classificacao_Anbima"),
                "trib_lp": melhor.get("Tributacao_Longo_Prazo"),
                "exclusivo": melhor.get("Exclusivo"),
                "publico_alvo": melhor.get("Publico_Alvo"),
                "situacao": melhor.get("Situacao"),
                "forma_condominio": melhor.get("Forma_Condominio"),
                "pl_cadastro": para_numero(pd.Series([melhor.get("Patrimonio_Liquido")])).iloc[0],
            })
    return pd.DataFrame(registros)


# ------------------------------------------------------------------ processamento

def processar(args) -> None:
    raiz = Path(args.raiz)
    raw = raiz / "raw"
    saida = raiz / "processed"
    saida.mkdir(parents=True, exist_ok=True)

    # 1. cadastro
    zip_cad = baixar(CADASTRO, raw / "registro_fundo_classe.zip")
    with zipfile.ZipFile(zip_cad) as zf:
        classes = ler(zf.read("registro_classe.csv"))
        fundos = ler(zf.read("registro_fundo.csv"))
    log(f"cadastro: {len(classes)} classes / {len(fundos)} fundos")

    # 2. lista XP
    lista = pd.read_csv(args.lista, sep="\t", encoding="utf-8")
    lista.columns = ["numero", "nome", "tipo"]
    lista["nome_norm"] = lista["nome"].map(norm)
    casados = casar_xp(lista, classes)
    casados["cnpj"] = casados["cnpj"].astype(str).str.replace(r"\D", "", regex=True)
    log(f"lista XP: {len(lista)} | casados: {len(casados)}")
    if casados.empty:
        raise SystemExit("nenhum fundo casou com a lista — verifique o arquivo de entrada")

    # completa com dados do fundo (gestor, administrador, CNPJ do fundo)
    classes_index = classes.drop_duplicates(subset="CNPJ_Classe").set_index("CNPJ_Classe")
    casados["id_fundo"] = casados["cnpj"].map(classes_index["ID_Registro_Fundo"])
    fundos_index = fundos.drop_duplicates(subset="ID_Registro_Fundo").set_index("ID_Registro_Fundo")
    casados["cnpj_fundo"] = casados["id_fundo"].map(fundos_index["CNPJ_Fundo"])
    casados["gestor"] = casados["id_fundo"].map(fundos_index["Gestor"])
    casados["administrador"] = casados["id_fundo"].map(fundos_index["Administrador"])
    casados["tipo_fundo"] = casados["id_fundo"].map(fundos_index["Tipo_Fundo"])

    # 3. informes diários
    meses = pd.period_range(end=pd.Timestamp.today().to_period("M") - 1, periods=args.meses, freq="M")
    blocos = []
    for periodo in meses:
        ym = periodo.strftime("%Y%m")
        caminho = baixar(INF_DIARIO.format(ym=ym), raw / f"inf_diario_fi_{ym}.zip")
        with zipfile.ZipFile(caminho) as zf:
            nome = next(n for n in zf.namelist() if n.lower().endswith(".csv"))
            df = ler(zf.read(nome))
        coluna_cnpj = "CNPJ_FUNDO_CLASSE" if "CNPJ_FUNDO_CLASSE" in df.columns else "CNPJ_FUNDO"
        df["cnpj"] = df[coluna_cnpj].astype(str).str.replace(r"\D", "", regex=True)
        df = df[df["cnpj"].isin(set(casados["cnpj"]))]
        if df.empty:
            continue
        df["data"] = pd.to_datetime(df["DT_COMPTC"], errors="coerce")
        df["ano_mes"] = periodo.strftime("%Y-%m")
        df["VL_QUOTA"] = para_numero(df["VL_QUOTA"])
        df["VL_PATRIM_LIQ"] = para_numero(df["VL_PATRIM_LIQ"])
        df["NR_COTST"] = para_numero(df["NR_COTST"])
        blocos.append(df[["cnpj", "data", "ano_mes", "VL_QUOTA", "VL_PATRIM_LIQ", "NR_COTST"]])
        log(f"  {ym}: {len(df)} linhas")

    if not blocos:
        raise SystemExit("nenhum informe diário disponível para os fundos da XP")

    diario = pd.concat(blocos, ignore_index=True).drop_duplicates(subset=["cnpj", "data"], keep="last")
    diario = diario.sort_values(["cnpj", "data"])

    # 4. retorno mensal e volatilidade
    diario["retorno_dia"] = diario.groupby("cnpj")["VL_QUOTA"].pct_change()
    mensal = []
    for (cnpj, mes), grupo in diario.groupby(["cnpj", "ano_mes"]):
        grupo = grupo.dropna(subset=["VL_QUOTA"])
        if len(grupo) < 5:
            continue
        primeira, ultima = grupo["VL_QUOTA"].iloc[0], grupo["VL_QUOTA"].iloc[-1]
        if not primeira or primeira <= 0:
            continue
        retornos = grupo["retorno_dia"].dropna()
        mensal.append({
            "cnpj": cnpj,
            "ano_mes": mes,
            "dias": len(grupo),
            "retorno_mes": (ultima / primeira - 1) * 100,
            "vol_anual": retornos.std(ddof=1) * np.sqrt(252) * 100 if len(retornos) > 3 else np.nan,
            "pl_medio": grupo["VL_PATRIM_LIQ"].mean(),
            "cotistas_medio": grupo["NR_COTST"].mean(),
        })
    mensal = pd.DataFrame(mensal)

    # 5. catálogo final: apenas fundos com série mínima
    observados = mensal.groupby("cnpj").agg(meses=("ano_mes", "nunique"),
                                            retorno_medio=("retorno_mes", "mean"),
                                            vol_media=("vol_anual", "mean"),
                                            pl_medio=("pl_medio", "mean")).reset_index()
    catalogo = casados.merge(observados, on="cnpj", how="inner")
    catalogo = catalogo[catalogo["meses"] >= max(3, args.meses - 2)]
    catalogo = catalogo[catalogo["pl_medio"] >= args.pl_minimo]
    catalogo = catalogo[catalogo["situacao"].fillna("").str.upper().str.startswith("EM FUNCIONAMENTO")]

    mensal = mensal[mensal["cnpj"].isin(set(catalogo["cnpj"]))]

    # 6. gravação
    catalogo.to_csv(saida / "catalogo_fundos.csv", index=False, encoding="utf-8-sig")
    mensal.to_csv(saida / "fundos_mensal.csv", index=False, encoding="utf-8-sig")
    diario[diario["cnpj"].isin(set(catalogo["cnpj"]))].to_csv(
        saida / "fundos_diario.csv", index=False, encoding="utf-8-sig")

    log(f"catálogo final: {len(catalogo)} fundos | {len(mensal)} observações mensais")
    log(f"por tipo: {catalogo['tipo_xp'].value_counts().to_dict()}")
    log(f"arquivos em {saida}")

    if not args.sem_fatores:
        baixar_fatores(args.meses, saida / "fatores_macro.csv")


# ------------------------------------------------------------------ fatores BCB

SGS = ("https://api.bcb.gov.br/dados/serie/bcdata.sgs.{codigo}/dados"
       "?formato=json&dataInicial={inicio}&dataFinal={fim}")
SERIES = {"cdi": 12, "ipca": 433, "dolar": 1}


def baixar_fatores(meses: int, destino: Path) -> None:
    """Série mensal de CDI, IPCA e dólar a partir do SGS/BCB."""
    import json

    fim = pd.Timestamp.today()
    inicio = fim - pd.DateOffset(months=meses + 2)

    def serie(codigo: int) -> pd.Series:
        url = SGS.format(codigo=codigo, inicio=inicio.strftime("%d/%m/%Y"), fim=fim.strftime("%d/%m/%Y"))
        with urllib.request.urlopen(url, timeout=120) as r:
            dados = json.loads(r.read().decode("utf-8"))
        df = pd.DataFrame(dados)
        df["data"] = pd.to_datetime(df["data"], format="%d/%m/%Y")
        df["valor"] = para_numero(df["valor"])
        return df.set_index("data")["valor"].sort_index()

    cdi = serie(SERIES["cdi"])
    ipca = serie(SERIES["ipca"])
    dolar = serie(SERIES["dolar"])

    tabela = pd.DataFrame(index=sorted(set(cdi.index.to_period("M")) | set(ipca.index.to_period("M"))))
    mensal = pd.DataFrame(index=tabela.index)
    mensal["cdi_mes"] = (1 + cdi.fillna(0) / 100).groupby(cdi.index.to_period("M")).prod().sub(1).mul(100)
    mensal["ipca_mes"] = ipca.groupby(ipca.index.to_period("M")).sum()
    mensal["dolar_fim"] = dolar.groupby(dolar.index.to_period("M")).last()
    mensal["dolar_ret"] = mensal["dolar_fim"].pct_change() * 100
    mensal = mensal.reset_index().rename(columns={"index": "ano_mes"})
    mensal["ano_mes"] = mensal["ano_mes"].astype(str)
    mensal.to_csv(destino, index=False, encoding="utf-8-sig")
    log(f"fatores macro: {len(mensal)} meses -> {destino.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Base real de fundos da XP a partir da CVM")
    parser.add_argument("--lista", required=True, help="arquivo TSV com Numero/Fundos/Tipo")
    parser.add_argument("--meses", type=int, default=12)
    parser.add_argument("--raiz", default="data")
    parser.add_argument("--pl-minimo", type=float, default=10_000_000)
    parser.add_argument("--sem-fatores", action="store_true")
    processar(parser.parse_args())
