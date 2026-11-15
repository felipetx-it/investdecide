package br.com.investdecide.dominio;

/** Formulário de configuração dos cenários (indicadores + choques). */
public class CenarioForm {

    private IndicadoresMercado indicadores = new IndicadoresMercado();
    private Shocks otimista = new Shocks();
    private Shocks pessimista = new Shocks();
    private Shocks personalizado = new Shocks();

    public IndicadoresMercado getIndicadores() { return indicadores; }
    public void setIndicadores(IndicadoresMercado indicadores) { this.indicadores = indicadores; }

    public Shocks getOtimista() { return otimista; }
    public void setOtimista(Shocks otimista) { this.otimista = otimista; }

    public Shocks getPessimista() { return pessimista; }
    public void setPessimista(Shocks pessimista) { this.pessimista = pessimista; }

    public Shocks getPersonalizado() { return personalizado; }
    public void setPersonalizado(Shocks personalizado) { this.personalizado = personalizado; }
}
