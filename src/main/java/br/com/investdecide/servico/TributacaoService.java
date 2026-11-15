package br.com.investdecide.servico;

import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.TipoProduto;
import org.springframework.stereotype.Service;

/**
 * Regras tributárias aplicadas à comparação de alternativas.
 *
 * <p>É uma simplificação parametrizada: considera a tabela regressiva
 * de fundos/CDB, a isenção de LCI/LCA, os regimes progressivo e regressivo da previdência e o
 * limite de dedução do PGBL (12% da renda bruta tributável). Come-cotas e IOF não são
 * modelados — o horizonte mínimo da simulação é de 12 meses.</p>
 */
@Service
public class TributacaoService {

    /** Alíquotas do IRPF por faixa de base de cálculo (tabela de referência). */
    private static final double[][] TABELA_IRPF = {
            {24_511.92, 0.0},
            {33_919.80, 7.5},
            {45_012.60, 15.0},
            {55_976.16, 22.5},
            {Double.MAX_VALUE, 27.5}
    };

    private static final double LIMITE_DEDUCAO_PGBL = 0.12;

    public record ResultadoTributario(
            double valorBruto,
            double imposto,
            double beneficioFiscal,
            double valorLiquido,
            double cargaSobreGanho,
            String regime,
            String observacao) {
    }

    public ResultadoTributario calcular(Produto produto, Cliente cliente, double aporte,
                                        double valorBruto, int meses) {
        double ganho = Math.max(0, valorBruto - aporte);
        TipoProduto tipo = produto.getTipo();

        if (produto.isIsentoIr()) {
            String obsIsento = (tipo != null && tipo.isRendaFixaBancaria())
                    ? "LCI/LCA são isentas de imposto de renda para pessoa física."
                    : "Fundo incentivado de infraestrutura isento de IR para pessoa física (Lei 12.431).";
            return new ResultadoTributario(valorBruto, 0, 0, valorBruto, 0, "Isento de IR", obsIsento);
        }

        if (tipo != null && tipo.isPrevidencia()) {
            double aliquota = aliquotaPrevidencia(cliente, meses);
            double beneficio = 0;
            String observacao;
            double imposto;

            if (tipo == TipoProduto.PREVIDENCIA_PGBL) {
                imposto = Math.max(0, valorBruto) * aliquota / 100;
                beneficio = beneficioPrevidencia(cliente, aporte);
                observacao = "PGBL tributa principal + rendimento no resgate; "
                        + (beneficio > 0 ? "a dedução gera restituição estimada no ano do aporte." : "sem benefício fiscal para este perfil.");
            } else {
                imposto = ganho * aliquota / 100;
                observacao = "VGBL tributa apenas o rendimento no resgate.";
            }

            double liquido = valorBruto - imposto + beneficio;
            // Quando não há ganho positivo, preserva a alíquota efetiva/nominal para não premiar prejuízo como "isento"
            double carga = ganho > 0 ? Math.max(0, (imposto - beneficio) / ganho * 100) : aliquota;
            return new ResultadoTributario(valorBruto, imposto, beneficio, liquido, carga,
                    "Previdência · " + rotuloRegime(cliente.getRegime()) + String.format(" (%.1f%%)", aliquota),
                    observacao);
        }

        if (tipo == TipoProduto.FUNDO_ACOES) {
            double aliquota = 15.0;
            double imposto = ganho * aliquota / 100;
            double carga = ganho > 0 ? imposto / ganho * 100 : aliquota;
            return new ResultadoTributario(valorBruto, imposto, 0, valorBruto - imposto, carga,
                    "Renda variável · 15,0%",
                    "Fundos de ações têm alíquota única de 15% sobre o ganho no resgate, sem come-cotas.");
        }

        double aliquota = aliquotaFundo(meses);
        double imposto = ganho * aliquota / 100;
        double carga = ganho > 0 ? imposto / ganho * 100 : aliquota;
        return new ResultadoTributario(valorBruto, imposto, 0, valorBruto - imposto, carga,
                "Tabela regressiva · " + String.format("%.1f%%", aliquota),
                "IR calculado sobre o ganho; come-cotas não modelado nesta versão.");
    }

    /** Alíquota regressiva de fundos e CDB conforme o prazo (em meses). */
    public double aliquotaFundo(int meses) {
        int dias = meses * 30;
        if (dias <= 180) return 22.5;
        if (dias <= 360) return 20.0;
        if (dias <= 720) return 17.5;
        return 15.0;
    }

    /** Alíquota efetiva da previdência considerando o regime (regressiva ou progressiva pela base do cliente). */
    public double aliquotaPrevidencia(Cliente cliente, int meses) {
        if (cliente.getRegime() == Cliente.RegimePrevidencia.PROGRESSIVA) {
            // Retenção mínima de 15% na fonte, ajustada à alíquota marginal na declaração
            return Math.max(15.0, aliquotaMarginal(cliente.getBaseCalculo()));
        }
        return aliquotaPrevidencia(cliente.getRegime(), meses);
    }

    /** Alíquota da previdência conforme o regime e o prazo em meses. */
    public double aliquotaPrevidencia(Cliente.RegimePrevidencia regime, int meses) {
        if (regime == Cliente.RegimePrevidencia.PROGRESSIVA) {
            return 15.0;
        }
        if (meses <= 24) return 35.0;
        if (meses <= 48) return 30.0;
        if (meses <= 72) return 25.0;
        if (meses <= 96) return 20.0;
        if (meses <= 120) return 15.0;
        return 10.0;
    }

    /** Alíquota marginal do IRPF para a base de cálculo informada. */
    public double aliquotaMarginal(double baseCalculo) {
        for (double[] faixa : TABELA_IRPF) {
            if (baseCalculo <= faixa[0]) {
                return faixa[1];
            }
        }
        return 27.5;
    }

    /** Limite anual de dedução do PGBL (12% da renda bruta tributável). */
    public double limiteDeducao(double rendaBruta) {
        return rendaBruta * LIMITE_DEDUCAO_PGBL;
    }

    /**
     * Benefício fiscal do PGBL: só existe para quem declara no modelo completo.
     * O valor é a restituição estimada no ano do aporte.
     */
    public double beneficioPrevidencia(Cliente cliente, double aporte) {
        if (cliente.getModelo() != Cliente.ModeloDeclaracao.COMPLETA) {
            return 0;
        }
        double dedutivel = Math.min(aporte, limiteDeducao(cliente.getRendaBruta()));
        return dedutivel * aliquotaMarginal(cliente.getBaseCalculo()) / 100;
    }

    /** Versão da tabela em vigor, exibida nas premissas do relatório. */
    public String versaoTabela() {
        return "Tabela IRPF de referência (versão 2026.1)";
    }

    private static String rotuloRegime(Cliente.RegimePrevidencia regime) {
        return regime == Cliente.RegimePrevidencia.PROGRESSIVA ? "progressiva" : "regressiva";
    }
}
