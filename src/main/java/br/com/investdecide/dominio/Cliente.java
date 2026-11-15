package br.com.investdecide.dominio;

/** Contexto do cliente que orienta a decisão. */
public class Cliente {

    public enum ModeloDeclaracao { COMPLETA, SIMPLIFICADA, ISENTA }

    public enum RegimePrevidencia { PROGRESSIVA, REGRESSIVA }

    private String nome = "Cliente";
    private PerfilRisco perfil = PerfilRisco.MODERADO;
    private double aporte = 10_000.0;
    private int horizonteMeses = 36;
    private int liquidezDias = 30;
    private String objetivo = "Fazer crescer";

    /** Renda bruta tributável anual (R$). */
    private double rendaBruta = 120_000.0;
    /** Deduções legais da última declaração (R$). */
    private double deducoes = 12_000.0;
    private ModeloDeclaracao modelo = ModeloDeclaracao.COMPLETA;
    private RegimePrevidencia regime = RegimePrevidencia.REGRESSIVA;

    public double getBaseCalculo() {
        return Math.max(0, rendaBruta - deducoes);
    }

    public double getHorizonteAnos() {
        return horizonteMeses / 12.0;
    }

    /** Valores para inputs numéricos: sem separador de milhar e sem zeros à direita. */
    public String getAporteInput() { return plain(aporte); }
    public String getRendaBrutaInput() { return plain(rendaBruta); }
    public String getDeducoesInput() { return plain(deducoes); }

    private static String plain(double valor) {
        return java.math.BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
    }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public PerfilRisco getPerfil() { return perfil; }
    public void setPerfil(PerfilRisco perfil) { this.perfil = perfil; }

    public double getAporte() { return aporte; }
    public void setAporte(double aporte) { this.aporte = aporte; }

    public int getHorizonteMeses() { return horizonteMeses; }
    public void setHorizonteMeses(int horizonteMeses) { this.horizonteMeses = horizonteMeses; }

    public int getLiquidezDias() { return liquidezDias; }
    public void setLiquidezDias(int liquidezDias) { this.liquidezDias = liquidezDias; }

    public String getObjetivo() { return objetivo; }
    public void setObjetivo(String objetivo) { this.objetivo = objetivo; }

    public double getRendaBruta() { return rendaBruta; }
    public void setRendaBruta(double rendaBruta) { this.rendaBruta = rendaBruta; }

    public double getDeducoes() { return deducoes; }
    public void setDeducoes(double deducoes) { this.deducoes = deducoes; }

    public ModeloDeclaracao getModelo() { return modelo; }
    public void setModelo(ModeloDeclaracao modelo) { this.modelo = modelo; }

    public RegimePrevidencia getRegime() { return regime; }
    public void setRegime(RegimePrevidencia regime) { this.regime = regime; }
}
