package br.com.investdecide.servico;

import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.TipoProduto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Relatório de eficiência tributária: compara a mesma rentabilidade bruta entre CDB (sem come-cotas),
 * Fundo RF/Multimercado LP (com come-cotas semestral), veículos isentos (LCI/LCA/FI-Infra),
 * PGBL (com e sem reinvestimento da restituição) e VGBL.
 */
@Service
public class RelatorioTributarioService {

    private final TributacaoService tributacao;

    public RelatorioTributarioService(TributacaoService tributacao) {
        this.tributacao = tributacao;
    }

    public record LinhaTributaria(String nome, String regime, double valorBruto, double imposto,
                                  double beneficio, double liquidoSem, double liquidoCom,
                                  String observacao) {
        public String getNome() { return nome; }
        public String getRegime() { return regime; }
        public double getValorBruto() { return valorBruto; }
        public double getImposto() { return imposto; }
        public double getBeneficio() { return beneficio; }
        public double getLiquidoSem() { return liquidoSem; }
        public double getLiquidoCom() { return liquidoCom; }
        public String getObservacao() { return observacao; }
    }

    public record Relatorio(Cliente cliente, double taxaAnual, int meses, List<LinhaTributaria> linhas,
                            double ganhoReinvestimento, String conclusao, String explicacaoReinvestimento) {
        public Cliente getCliente() { return cliente; }
        public double getTaxaAnual() { return taxaAnual; }
        public int getMeses() { return meses; }
        public List<LinhaTributaria> getLinhas() { return linhas; }
        public double getGanhoReinvestimento() { return ganhoReinvestimento; }
        public String getConclusao() { return conclusao; }
        public String getExplicacaoReinvestimento() { return explicacaoReinvestimento; }
        public double getBeneficioPgbl() {
            return linhas.stream().filter(l -> "PGBL".equals(l.nome())).mapToDouble(LinhaTributaria::beneficio).findFirst().orElse(0);
        }
    }

    public Relatorio comparar(Cliente cliente, double taxaAnual, int meses) {
        double anos = meses / 12.0;
        double aporte = cliente.getAporte();
        double bruto = aporte * Math.pow(1 + taxaAnual / 100, anos);

        List<LinhaTributaria> linhas = new ArrayList<>();

        Produto cdb = produto("CDB 100% CDI (sem come-cotas)", TipoProduto.CDB, false);
        var tribCdb = tributacao.calcular(cdb, cliente, aporte, bruto, meses);
        linhas.add(new LinhaTributaria(cdb.getNome(), "Tabela regressiva (no vencimento)", bruto,
                tribCdb.imposto(), 0, tribCdb.valorLiquido(), tribCdb.valorLiquido(),
                "IR somente no vencimento sobre o ganho; preserva a capitalização composta."));

        Produto fundoLp = produto("Fundo RF LP (com come-cotas)", TipoProduto.FUNDO_RF, false);
        var tribFundo = tributacao.calcular(fundoLp, cliente, aporte, bruto, meses);
        linhas.add(new LinhaTributaria(fundoLp.getNome(), "Regressiva + come-cotas 15%", bruto,
                tribFundo.imposto(), 0, tribFundo.valorLiquido(), tribFundo.valorLiquido(),
                "Sofre retenção semestral de 15% a cada 6 meses, reduzindo a base capitalizada frente ao CDB."));

        Produto lci = produto("LCI/LCA / FI-Infra (isento)", TipoProduto.LCI, true);
        var tribLci = tributacao.calcular(lci, cliente, aporte, bruto, meses);
        linhas.add(new LinhaTributaria(lci.getNome(), "Isento de IR", bruto,
                0, 0, tribLci.valorLiquido(), tribLci.valorLiquido(),
                "Isenção total para pessoa física compensa parte do spread menor."));

        Produto pgbl = produto("PGBL", TipoProduto.PREVIDENCIA_PGBL, false);
        var tribPgbl = tributacao.calcular(pgbl, cliente, aporte, bruto, meses);
        double brutoComReinvestimento = (aporte + tribPgbl.beneficioFiscal())
                * Math.pow(1 + taxaAnual / 100, anos);
        double aliquota = tributacao.aliquotaPrevidencia(cliente, meses);
        double impostoCom = brutoComReinvestimento * aliquota / 100;
        double liquidoCom = brutoComReinvestimento - impostoCom;
        linhas.add(new LinhaTributaria(pgbl.getNome(), "Previdência · " + cliente.getRegime().name().toLowerCase(),
                bruto, tribPgbl.imposto(), tribPgbl.beneficioFiscal(), tribPgbl.valorLiquido(), liquidoCom,
                "A restituição do aporte, quando reinvestida, cresce junto com o plano."));

        Produto vgbl = produto("VGBL", TipoProduto.PREVIDENCIA_VGBL, false);
        var tribVgbl = tributacao.calcular(vgbl, cliente, aporte, bruto, meses);
        linhas.add(new LinhaTributaria(vgbl.getNome(), "Previdência · " + cliente.getRegime().name().toLowerCase(),
                bruto, tribVgbl.imposto(), 0, tribVgbl.valorLiquido(), tribVgbl.valorLiquido(),
                "IR só sobre o rendimento; indicado para quem usa a declaração simplificada."));

        double ganhoReinvestimento = liquidoCom - tribPgbl.valorLiquido();
        String conclusao = String.format(new java.util.Locale("pt", "BR"),
                "Efeito líquido de reinvestir a restituição no próprio PGBL: R$ %,.2f no horizonte. "
                        + "Sem reinvestimento, o PGBL %s frente ao CDB.",
                ganhoReinvestimento,
                tribPgbl.valorLiquido() >= tribCdb.valorLiquido() ? "ainda é mais eficiente" : "perde eficiência");

        String explicacao;
        if (tribPgbl.beneficioFiscal() <= 0) {
            explicacao = "Sem declaração completa ou espaço tributável, não há restituição de IR no aporte para reinvestir no PGBL.";
        } else if (ganhoReinvestimento >= 0) {
            explicacao = "A restituição reinvestida cresce dentro do plano e supera o valor mantido em caixa, "
                    + "porque a alíquota de resgate é igual ou inferior ao benefício obtido no aporte.";
        } else {
            explicacao = String.format(new java.util.Locale("pt", "BR"),
                    "Neste horizonte a alíquota de resgate (%.1f%%) supera a economia obtida no aporte "
                            + "(alíquota marginal de %.1f%%), então reinvestir dentro do PGBL reduz o patrimônio líquido frente a manter a restituição em caixa. "
                            + "A vantagem aparece em horizontes maiores (acima de 10 anos na tabela regressiva, quando a alíquota cai para 10%%).",
                    aliquota, tributacao.aliquotaMarginal(cliente.getBaseCalculo()));
        }

        return new Relatorio(cliente, taxaAnual, meses, linhas, ganhoReinvestimento, conclusao, explicacao);
    }

    private static Produto produto(String nome, TipoProduto tipo, boolean isento) {
        Produto produto = new Produto();
        produto.setNome(nome);
        produto.setTipo(tipo);
        produto.setIsentoIr(isento);
        return produto;
    }
}
