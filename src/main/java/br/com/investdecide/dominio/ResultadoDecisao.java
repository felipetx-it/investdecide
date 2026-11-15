package br.com.investdecide.dominio;

import java.util.List;

/** Saída completa do modelo de decisão para um cliente e um cenário. */
public class ResultadoDecisao {

    private Cliente cliente;
    private Cenario cenario;
    private Pesos pesos;

    private List<AvaliacaoProduto> avaliacoes;
    private List<AvaliacaoProduto> ranking;
    private List<GrupoProdutos> grupos;

    private int totalAlternativas;
    private int totalElegiveis;
    private double cargaTributariaRecomendada;
    private double valorFinalRecomendado;
    private double silhueta;
    private String sensibilidade = "";
    private String premissas = "";

    public double getScoreMaximo() {
        return ranking == null || ranking.isEmpty() ? 0 : ranking.get(0).getScore();
    }

    public AvaliacaoProduto getRecomendada() {
        return ranking == null || ranking.isEmpty() ? null : ranking.get(0);
    }

    public double getDiferencaParaSegunda() {
        if (ranking == null || ranking.size() < 2) {
            return 0;
        }
        return ranking.get(0).getValorFinal() - ranking.get(1).getValorFinal();
    }

    /** Explica quando a 2ª colocada projeta valor líquido maior que a recomendada. */
    public String getNotaDiferenca() {
        if (ranking == null || ranking.size() < 2) {
            return "";
        }
        double diferenca = getDiferencaParaSegunda();
        if (diferenca >= 0) {
            return "";
        }
        return String.format(new java.util.Locale("pt", "BR"),
                "A 2ª opção projeta R$ %.2f a mais em valor líquido, mas perde no índice por risco, liquidez ou carga tributária.",
                Math.abs(diferenca));
    }

    public Cliente getCliente() { return cliente; }
    public void setCliente(Cliente cliente) { this.cliente = cliente; }

    public Cenario getCenario() { return cenario; }
    public void setCenario(Cenario cenario) { this.cenario = cenario; }

    public Pesos getPesos() { return pesos; }
    public void setPesos(Pesos pesos) { this.pesos = pesos; }

    public List<AvaliacaoProduto> getAvaliacoes() { return avaliacoes; }
    public void setAvaliacoes(List<AvaliacaoProduto> avaliacoes) { this.avaliacoes = avaliacoes; }

    public List<AvaliacaoProduto> getRanking() { return ranking; }
    public void setRanking(List<AvaliacaoProduto> ranking) { this.ranking = ranking; }

    public List<GrupoProdutos> getGrupos() { return grupos; }
    public void setGrupos(List<GrupoProdutos> grupos) { this.grupos = grupos; }

    public int getTotalAlternativas() { return totalAlternativas; }
    public void setTotalAlternativas(int totalAlternativas) { this.totalAlternativas = totalAlternativas; }

    public int getTotalElegiveis() { return totalElegiveis; }
    public void setTotalElegiveis(int totalElegiveis) { this.totalElegiveis = totalElegiveis; }

    public double getCargaTributariaRecomendada() { return cargaTributariaRecomendada; }
    public void setCargaTributariaRecomendada(double v) { this.cargaTributariaRecomendada = v; }

    public double getValorFinalRecomendado() { return valorFinalRecomendado; }
    public void setValorFinalRecomendado(double valorFinalRecomendado) { this.valorFinalRecomendado = valorFinalRecomendado; }

    public double getSilhueta() { return silhueta; }
    public void setSilhueta(double silhueta) { this.silhueta = silhueta; }

    public String getSensibilidade() { return sensibilidade; }
    public void setSensibilidade(String sensibilidade) { this.sensibilidade = sensibilidade; }

    public String getPremissas() { return premissas; }
    public void setPremissas(String premissas) { this.premissas = premissas; }
}
