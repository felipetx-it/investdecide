package br.com.investdecide.dominio;

/** Perfil de risco do investidor (suitability). */
public enum PerfilRisco {

    CONSERVADOR("Conservador", 2),
    MODERADO("Moderado", 3),
    ARROJADO("Arrojado", 5);

    private final String rotulo;
    private final int riscoMaximo;

    PerfilRisco(String rotulo, int riscoMaximo) {
        this.rotulo = rotulo;
        this.riscoMaximo = riscoMaximo;
    }

    public String getRotulo() {
        return rotulo;
    }

    public int getRiscoMaximo() {
        return riscoMaximo;
    }
}
