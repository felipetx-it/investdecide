"""Gera uma base sintética no formato dos arquivos abertos da CVM, para testar o pipeline offline."""

import csv
import io
import random
import zipfile
from datetime import date, timedelta
from pathlib import Path

random.seed(7)

RAW = Path(r"C:\Users\felip\OneDrive\Documentos\deepseek-harness\default-workspace\investdecide-prototipo\scripts\_fixture\raw")
RAW.mkdir(parents=True, exist_ok=True)

FUNDOS = [
    ("11.111.111/0001-11", "Atlas Renda Fixa FIRF", "FI", "Renda Fixa", 0.60, "S", 900_000_000),
    ("22.222.222/0001-22", "Horizonte Multimercado", "FI", "Multimercados", 1.80, "S", 450_000_000),
    ("33.333.333/0001-33", "Atlas Ações Brasil", "FI", "Ações", 2.00, "S", 220_000_000),
    ("44.444.444/0001-44", "Vita Prev RF PGBL", "FI", "Previdência", 0.90, "N", 300_000_000),
    ("55.555.555/0001-55", "Vita Prev RF VGBL", "FI", "Previdência", 0.90, "N", 280_000_000),
    ("66.666.666/0001-66", "Fundo Exclusivo Private", "FI", "Multimercados", 0.40, "S", 120_000_000),
    ("77.777.777/0001-77", "Small Cap FIA", "FI", "Ações", 2.50, "S", 45_000_000),
    ("88.888.888/0001-88", "Fundo Curto Prazo", "FI", "Renda Fixa", 0.30, "S", 800_000_000),
]

HEADER_INF = ["TP_FUNDO_CLASSE", "CNPJ_FUNDO_CLASSE", "ID_SUBCLASSE", "DT_COMPTC",
              "VL_TOTAL", "VL_QUOTA", "VL_PATRIM_LIQ", "CAPTC_DIA", "RESG_DIA", "NR_COTST"]


def numero(valor, casas=2):
    texto = f"{valor:,.{casas}f}"
    return texto.replace(",", "X").replace(".", ",").replace("X", ".")


def dias_uteis(ano, mes):
    dia = date(ano, mes, 1)
    while dia.month == mes:
        if dia.weekday() < 5:
            yield dia
        dia += timedelta(days=1)


estado = {cnpj: {"quota": 1.0, "pl": pl} for cnpj, _, _, _, _, _, pl in FUNDOS}

for ano, mes in [(2026, 1), (2026, 2)]:
    buffer = io.StringIO()
    writer = csv.writer(buffer, delimiter=";", lineterminator="\n")
    writer.writerow(HEADER_INF)
    for dia in dias_uteis(ano, mes):
        for cnpj, nome, tipo, classe, taxa, trib, _ in FUNDOS:
            st = estado[cnpj]
            variacao = random.gauss(0.00045 if classe != "Ações" else 0.0009, 0.0035)
            st["quota"] *= 1 + variacao
            st["pl"] *= 1 + variacao + random.uniform(-0.002, 0.004)
            captacao = max(0.0, random.gauss(1_500_000, 900_000))
            resgate = max(0.0, random.gauss(1_200_000, 800_000))
            writer.writerow([tipo, cnpj, "", dia.isoformat(), numero(st["pl"] * 1.01),
                             numero(st["quota"], 8), numero(st["pl"]), numero(captacao),
                             numero(resgate), random.randint(800, 9000)])
    caminho = RAW / f"inf_diario_fi_{ano}{mes:02d}.zip"
    with zipfile.ZipFile(caminho, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(f"inf_diario_fi_{ano}{mes:02d}.csv", buffer.getvalue().encode("latin-1"))

with open(RAW / "cad_fi.csv", "w", encoding="latin-1", newline="") as fh:
    writer = csv.writer(fh, delimiter=";", lineterminator="\n")
    writer.writerow(["CNPJ_FUNDO", "DENOM_SOCIAL", "TP_FUNDO", "CLASSE_ANBIMA", "TAXA_ADM",
                     "TAXA_PERFM", "TRIB_LPRAZO", "PUBLICO_ALVO", "CONDOM", "SIT", "GESTOR",
                     "ADMIN", "RENTAB_FUNDO", "DT_REG"])
    for cnpj, nome, tipo, classe, taxa, trib, _ in FUNDOS:
        condominio = "Fechado" if "Exclusivo" in nome else "Aberto"
        alvo = "Qualificado" if "Exclusivo" in nome else "Público Geral"
        writer.writerow([cnpj, nome, tipo, classe, numero(taxa, 2), "0,00", trib, alvo,
                         condominio, "Em Funcionamento Normal", "Gestora Exemplo",
                         "Administradora Exemplo", "CDI", "2015-03-10"])

print("fixture gerada em", RAW)
