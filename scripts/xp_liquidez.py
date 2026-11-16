#!/usr/bin/env python3
"""
xp_liquidez.py — coleta a liquidez de resgate dos fundos no portal da XP.

O portal de fundos da XP (https://www.xpi.com.br/investimentos/fundos-de-investimento/lista/)
mostra, para cada fundo:

  * Cotização de resgate — D+n em dias corridos (ou úteis);
  * Liquidação de resgate — D+n em dias úteis (ou corridos).

A CVM não publica esse prazo, por isso o InvestDecide antes o inferia pelo nome/classe
ANBIMA. Este script substitui a inferência pelo dado real do portal:

  1. consulta a API usada pela própria página da XP (yield-portal/investment-funds);
  2. guarda o retorno bruto em data/raw/xp_fundos.json (auditoria/reuso com --offline);
  3. casa cada fundo do catálogo (data/processed/catalogo_fundos.csv) por CNPJ e,
     quando o CNPJ difere (classe x fundo), por similaridade de tokens do nome;
  4. grava data/processed/liquidez_fundos.csv consumido pelo sistema Java.

Convenção do campo liquidez_dias: prazo total, em dias corridos, até o dinheiro cair na
conta = cotização + liquidação, convertendo dias úteis em corridos por 7/5 (arredondado
para cima). As duas parcelas originais ficam no CSV para conferência.

Uso:
    python scripts/xp_liquidez.py
    python scripts/xp_liquidez.py --offline      # reaproveita data/raw/xp_fundos.json
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import unicodedata
import urllib.request
from datetime import date
from difflib import SequenceMatcher
from pathlib import Path

API = ("https://api.xpi.com.br/investment-funds/yield-portal/v2/investment-funds"
       "?onlyrpps=false&family=null")
CVM_EXTRATO_URL = "https://dados.cvm.gov.br/dados/FI/DOC/EXTRATO/DADOS/extrato_fi.csv"
# Chave pública usada pela própria página da XP (header ocp-apim-subscription-key).
CHAVE = "602224c06c434f35b6352ca902c5020a"
NAVEGADOR = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")

GENERICOS = {"FI", "FIF", "FIC", "FIM", "FIA", "FIDC", "FIP", "FIE", "CP", "LP", "RL",
             "CIC", "EM", "DE", "DO", "DA", "E", "ADVISORY", "INSTITUCIONAL", "MASTER",
             "FEEDER", "COTAS", "FUNDO", "INVESTIMENTO", "CLASSES", "CLASSE", "SEG"}
LIMIAR_NOME = 0.80
FATOR_DIA_UTIL = 7 / 5


def log(msg: str) -> None:
    print(f"[xp-liquidez] {msg}", flush=True)


def norm(texto: str) -> str:
    t = "".join(c for c in unicodedata.normalize("NFKD", str(texto or ""))
                if not unicodedata.combining(c))
    t = t.upper()
    t = re.sub(r"\.{2,}$", "", t)
    t = re.sub(r"[^A-Z0-9 ]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


# ------------------------------------------------------------------ coleta

def baixar(raiz: Path, offline: bool) -> list[dict]:
    destino = raiz / "raw" / "xp_fundos.json"
    if offline:
        if not destino.exists():
            raise SystemExit(f"--offline pedido, mas {destino} não existe")
        log(f"lendo snapshot {destino}")
        return json.loads(destino.read_text(encoding="utf-8"))

    requisicao = urllib.request.Request(API, headers={
        "accept": "application/json",
        "ocp-apim-subscription-key": CHAVE,
        "user-agent": NAVEGADOR,
        "origin": "https://www.xpi.com.br",
        "referer": "https://www.xpi.com.br/",
    })
    log("consultando a API do portal de fundos da XP")
    with urllib.request.urlopen(requisicao, timeout=120) as resposta:
        fundos = json.loads(resposta.read().decode("utf-8"))["data"]

    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(json.dumps(fundos, ensure_ascii=False, indent=1), encoding="utf-8")
    log(f"{len(fundos)} fundos gravados em {destino}")
    return fundos


def baixar_extrato_cvm(raiz: Path, offline: bool) -> list[dict]:
    """Carrega extrato_fi.csv da CVM (cache em data/raw/extrato_fi.csv)."""
    destino = raiz / "raw" / "extrato_fi.csv"
    if not offline or not destino.exists():
        if offline and not destino.exists():
            log(f"aviso: {destino} não encontrado em --offline; pulando complemento da CVM")
            return []
        log("baixando extrato_fi.csv da CVM para complementar previdência e fundos ausentes")
        req = urllib.request.Request(CVM_EXTRATO_URL, headers={"user-agent": NAVEGADOR})
        with urllib.request.urlopen(req, timeout=120) as resp:
            destino.parent.mkdir(parents=True, exist_ok=True)
            destino.write_bytes(resp.read())
        log(f"extrato CVM salvo em {destino}")

    por_cnpj: dict[str, dict] = {}
    with destino.open("r", encoding="latin1", newline="") as f:
        leitor = csv.DictReader(f, delimiter=";")
        for row in leitor:
            cnpj14 = re.sub(r"\D", "", str(row.get("CNPJ_FUNDO_CLASSE") or "")).zfill(14)
            if not cnpj14 or cnpj14 == "00000000000000":
                continue
            cot_str = (row.get("QT_DIA_CONVERSAO_COTA") or "").strip()
            pag_str = (row.get("QT_DIA_PAGTO_COTA") or "").strip()
            if not cot_str and not pag_str:
                continue
            comp = (row.get("DT_COMPTC") or "").strip()
            atual = por_cnpj.get(cnpj14)
            if atual is None or comp >= atual.get("_comp", ""):
                cot_d = int(float(cot_str)) if cot_str else 0
                liq_d = int(float(pag_str)) if pag_str else 0
                tp = (row.get("TP_DIA_PAGTO_RESGATE") or "").strip().upper()
                e_util = "UTE" in tp or "ÚTE" in tp
                cot_txt = f"D+{cot_d} ({'Dias Úteis' if e_util else 'Dias Corridos'})"
                liq_txt = f"D+{liq_d} (Dias Úteis)"
                por_cnpj[cnpj14] = {
                    "cnpj": cnpj14,
                    "name": (row.get("DENOM_SOCIAL") or "").strip(),
                    "redemptionQuotation": cot_txt,
                    "redemptionQuotationDays": cot_d,
                    "redemptionSettlement": liq_txt,
                    "_comp": comp,
                    "_fonte": "CVM",
                }
    log(f"{len(por_cnpj)} fundos com cotização/liquidação válidos em extrato_fi.csv")
    return list(por_cnpj.values())


# ------------------------------------------------------------------ liquidez

def unidade(texto: str) -> str:
    return "UTEIS" if "UTEIS" in norm(texto) else "CORRIDOS"


def dias_em_corridos(dias: int, texto: str) -> int:
    """Converte D+n para dias corridos (dias úteis aproximados por 7/5)."""
    if dias <= 0:
        return 0
    if unidade(texto) == "UTEIS":
        return math.ceil(dias * FATOR_DIA_UTIL)
    return dias


def liquidez(fundo: dict) -> dict | None:
    cotizacao = (fundo.get("redemptionQuotation") or "").strip()
    liquidacao = (fundo.get("redemptionSettlement") or "").strip()
    cotizacao_dias = fundo.get("redemptionQuotationDays")
    if not cotizacao or cotizacao_dias is None:
        return None
    liquidacao_dias = int(re.search(r"D\+(\d+)", liquidacao).group(1)) if re.search(r"D\+(\d+)", liquidacao) else 0
    total = dias_em_corridos(int(cotizacao_dias), cotizacao) + dias_em_corridos(liquidacao_dias, liquidacao)
    return {
        "cotizacao_resgate": cotizacao,
        "liquidacao_resgate": liquidacao,
        "cotizacao_dias": int(cotizacao_dias),
        "liquidacao_dias": liquidacao_dias,
        "liquidez_dias": total,
    }


# ------------------------------------------------------------------ casamento

def preparar(fundos: list[dict]) -> tuple[dict, dict]:
    for fundo in fundos:
        fundo["_cnpj"] = re.sub(r"\D", "", str(fundo.get("cnpj") or ""))
        fundo["_norm"] = norm(fundo.get("name"))
        fundo["_tokens"] = fundo["_norm"].split()
    por_cnpj = {f["_cnpj"]: f for f in fundos if f["_cnpj"]}
    por_primeiro: dict[str, list[dict]] = {}
    for fundo in fundos:
        if fundo["_tokens"]:
            por_primeiro.setdefault(fundo["_tokens"][0], []).append(fundo)
    return por_cnpj, por_primeiro


def candidatos_nome(nome_xp: str, por_primeiro: dict) -> list[tuple[float, dict]]:
    """Ranking de candidatos da API para o nome comercial da XP (com "..." truncado)."""
    truncado = (nome_xp or "").strip().endswith("...")
    tokens = norm(nome_xp).split()
    if truncado and len(tokens) > 1:
        tokens = tokens[:-1]
    uteis = [t for t in tokens if t not in GENERICOS]
    if not tokens or not uteis:
        return []

    ranking = []
    for candidato in por_primeiro.get(tokens[0], []):
        tokens_cand = set(candidato["_tokens"])
        acertos = sum(1 for t in uteis if any(c.startswith(t) for c in tokens_cand))
        score = acertos / len(uteis)
        if " ".join(tokens[:3]) in candidato["_norm"]:
            score += 0.05
        # desempate: similaridade textual completa
        score += 0.01 * SequenceMatcher(None, " ".join(tokens), candidato["_norm"]).ratio()
        if score >= LIMIAR_NOME:
            ranking.append((score, candidato))
    ranking.sort(key=lambda item: (-item[0], item[1]["_norm"]))
    return ranking


def casar(catalogo: list[dict], fundos: list[dict]) -> list[dict]:
    por_cnpj, por_primeiro = preparar(fundos)
    resultados: list[dict] = []
    usados: set[str] = set()
    catalogo_usado: set[str] = set()

    # 1) CNPJ idêntico (mais confiável)
    pendentes = []
    for linha in catalogo:
        cnpj_cat = linha["cnpj"].strip().zfill(14)
        fundo = por_cnpj.get(cnpj_cat)
        if fundo and fundo["_cnpj"] not in usados and cnpj_cat not in catalogo_usado:
            usados.add(fundo["_cnpj"])
            catalogo_usado.add(cnpj_cat)
            resultados.append({"linha": linha, "fundo": fundo, "via": "cnpj", "score": 1.0})
        elif not fundo:
            pendentes.append(linha)

    # 2) nome: previdência só casa com candidato que também seja "prev" (plano != fundo)
    candidatos = []
    for linha in pendentes:
        for score, fundo in candidatos_nome(linha.get("nome_xp") or linha.get("nome_cvm"), por_primeiro):
            if linha.get("tipo_xp") == "Previdência" and "PREVID" not in fundo["_norm"] and "PREV" not in fundo["_norm"]:
                continue
            candidatos.append((score, linha, fundo))

    candidatos.sort(key=lambda x: (-x[0], x[1]["cnpj"]))
    for score, linha, fundo in candidatos:
        cnpj_cat = linha["cnpj"].strip().zfill(14)
        if fundo["_cnpj"] in usados or cnpj_cat in catalogo_usado:
            continue
        usados.add(fundo["_cnpj"])
        catalogo_usado.add(cnpj_cat)
        resultados.append({"linha": linha, "fundo": fundo, "via": "nome", "score": round(score, 3)})

    return resultados


# ------------------------------------------------------------------ saída

COLUNAS = ["cnpj", "nome_xp", "tipo_xp", "cnpj_xp", "nome_xp_api", "cotizacao_resgate",
           "cotizacao_dias", "liquidacao_resgate", "liquidacao_dias", "liquidez_dias",
           "match", "score_match_xp", "fonte", "coletado_em"]


def main() -> None:
    parser = argparse.ArgumentParser(description="Liquidez de resgate dos fundos no portal da XP e CVM")
    parser.add_argument("--raiz", default="data")
    parser.add_argument("--offline", action="store_true", help="reaproveita snapshots em data/raw/")
    parser.add_argument("--detalhar", action="store_true", help="lista os casamentos por nome")
    args = parser.parse_args()

    raiz = Path(args.raiz)
    fundos_xp = baixar(raiz, args.offline)
    for f in fundos_xp:
        f["_fonte"] = "XP"

    catalogo = list(csv.DictReader((raiz / "processed" / "catalogo_fundos.csv")
                                   .open(encoding="utf-8-sig")))
    casados_xp = casar(catalogo, fundos_xp)
    cnpjs_xp_ok = {c["linha"]["cnpj"].strip().zfill(14) for c in casados_xp if liquidez(c["fundo"])}

    restantes = [l for l in catalogo if l["cnpj"].strip().zfill(14) not in cnpjs_xp_ok]
    fundos_cvm = baixar_extrato_cvm(raiz, args.offline)
    casados_cvm = casar(restantes, fundos_cvm) if fundos_cvm and restantes else []

    casados = casados_xp + casados_cvm

    hoje = date.today().isoformat()
    registros = []
    sem_dado = 0
    vistos: set[str] = set()
    for item in casados:
        cnpj_cat = item["linha"]["cnpj"].strip().zfill(14)
        if cnpj_cat in vistos:
            continue
        dados = liquidez(item["fundo"])
        if not dados:
            sem_dado += 1
            continue
        vistos.add(cnpj_cat)
        linha = item["linha"]
        registros.append({
            "cnpj": linha["cnpj"],
            "nome_xp": linha.get("nome_xp", ""),
            "tipo_xp": linha.get("tipo_xp", ""),
            "cnpj_xp": item["fundo"]["_cnpj"],
            "nome_xp_api": item["fundo"].get("name", ""),
            **dados,
            "match": item["via"],
            "score_match_xp": item["score"],
            "fonte": item["fundo"].get("_fonte", "XP"),
            "coletado_em": hoje,
        })

    destino = raiz / "processed" / "liquidez_fundos.csv"
    with destino.open("w", newline="", encoding="utf-8-sig") as arquivo:
        escritor = csv.DictWriter(arquivo, fieldnames=COLUNAS)
        escritor.writeheader()
        escritor.writerows(sorted(registros, key=lambda r: r["nome_xp"]))

    por_fonte = {ft: sum(1 for r in registros if r["fonte"] == ft) for ft in ("XP", "CVM")}
    por_via = {via: sum(1 for r in registros if r["match"] == via) for via in ("cnpj", "nome")}
    tipos = {}
    for registro in registros:
        tipos[registro["tipo_xp"]] = tipos.get(registro["tipo_xp"], 0) + 1

    log(f"catálogo: {len(catalogo)} fundos | com liquidez real: {len(registros)} (fontes: {por_fonte})")
    log(f"casamento: {por_via} | sem cotização na fonte: {sem_dado}")
    log(f"por tipo: {tipos}")
    log(f"arquivo: {destino}")

    if args.detalhar:
        for item in sorted(registros, key=lambda r: r["score_match_xp"]):
            if item["match"] == "nome":
                print(f'  {item["score_match_xp"]:.2f} | {item["nome_xp"][:58]:58} -> '
                      f'{item["nome_xp_api"][:52]} | D+{item["liquidez_dias"]}')


if __name__ == "__main__":
    main()
