package br.com.investdecide.dominio;

/** Indexador de remuneração de um produto. */
public enum Indexador {

    CDI("CDI"),
    IPCA("IPCA"),
    PREFIXADO("Prefixado");

    private final String rotulo;

    Indexador(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }
}
