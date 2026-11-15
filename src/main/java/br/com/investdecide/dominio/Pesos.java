package br.com.investdecide.dominio;

/**
 * Pesos do índice multicritério (SAW). Os valores chegam como percentuais e são normalizados
 * para somar 1 antes do cálculo do escore.
 */
public class Pesos {

    private double retorno = 40;
    private double risco = 25;
    private double tributacao = 20;
    private double liquidez = 15;

    public Pesos() {
    }

    public Pesos(double retorno, double risco, double tributacao, double liquidez) {
        this.retorno = retorno;
        this.risco = risco;
        this.tributacao = tributacao;
        this.liquidez = liquidez;
    }

    public double getSoma() {
        return retorno + risco + tributacao + liquidez;
    }

    /** Peso normalizado; devolve 0 se a soma for inválida. */
    public double normalizado(double valor) {
        double soma = getSoma();
        return soma <= 0 ? 0 : valor / soma;
    }

    public double getRetornoNorm() { return normalizado(retorno); }
    public double getRiscoNorm() { return normalizado(risco); }
    public double getTributacaoNorm() { return normalizado(tributacao); }
    public double getLiquidezNorm() { return normalizado(liquidez); }

    public String getRetornoInput() { return plain(retorno); }
    public String getRiscoInput() { return plain(risco); }
    public String getTributacaoInput() { return plain(tributacao); }
    public String getLiquidezInput() { return plain(liquidez); }

    private static String plain(double valor) {
        return java.math.BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
    }

    public double getRetorno() { return retorno; }
    public void setRetorno(double retorno) { this.retorno = retorno; }

    public double getRisco() { return risco; }
    public void setRisco(double risco) { this.risco = risco; }

    public double getTributacao() { return tributacao; }
    public void setTributacao(double tributacao) { this.tributacao = tributacao; }

    public double getLiquidez() { return liquidez; }
    public void setLiquidez(double liquidez) { this.liquidez = liquidez; }
}
