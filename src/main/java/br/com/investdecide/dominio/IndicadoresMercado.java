package br.com.investdecide.dominio;

/**
 * Indicadores de mercado usados para montar o cenário base.
 * Os rótulos correspondem à leitura de mercado: curva DI, inflação implícita, dólar futuro
 * e expectativa para o Ibovespa.
 */
public class IndicadoresMercado {

    private double curvaDi = 10.75;
    private double inflacaoImplicita = 4.50;
    private double dolarFuturo = 2.00;
    private double ibovespaEsperado = 8.00;
    private String dataReferencia = "15/11/2026";

    public IndicadoresMercado() {
    }

    public IndicadoresMercado(double curvaDi, double inflacaoImplicita, double dolarFuturo,
                              double ibovespaEsperado, String dataReferencia) {
        this.curvaDi = curvaDi;
        this.inflacaoImplicita = inflacaoImplicita;
        this.dolarFuturo = dolarFuturo;
        this.ibovespaEsperado = ibovespaEsperado;
        this.dataReferencia = dataReferencia;
    }

    public double getCurvaDi() { return curvaDi; }
    public void setCurvaDi(double curvaDi) { this.curvaDi = curvaDi; }

    public double getInflacaoImplicita() { return inflacaoImplicita; }
    public void setInflacaoImplicita(double inflacaoImplicita) { this.inflacaoImplicita = inflacaoImplicita; }

    public double getDolarFuturo() { return dolarFuturo; }
    public void setDolarFuturo(double dolarFuturo) { this.dolarFuturo = dolarFuturo; }

    public double getIbovespaEsperado() { return ibovespaEsperado; }
    public void setIbovespaEsperado(double ibovespaEsperado) { this.ibovespaEsperado = ibovespaEsperado; }

    public String getDataReferencia() { return dataReferencia; }
    public void setDataReferencia(String dataReferencia) { this.dataReferencia = dataReferencia; }
}
