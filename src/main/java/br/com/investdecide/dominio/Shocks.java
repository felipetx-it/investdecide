package br.com.investdecide.dominio;

/** Choques aplicados sobre o cenário base para gerar cenários otimista e pessimista. */
public class Shocks {

    private double cdi = 0;
    private double ipca = 0;
    private double dolar = 0;
    private double ibovespa = 0;

    public Shocks() {
    }

    public Shocks(double cdi, double ipca, double dolar, double ibovespa) {
        this.cdi = cdi;
        this.ipca = ipca;
        this.dolar = dolar;
        this.ibovespa = ibovespa;
    }

    public Shocks copia() {
        return new Shocks(cdi, ipca, dolar, ibovespa);
    }

    public double getCdi() { return cdi; }
    public void setCdi(double cdi) { this.cdi = cdi; }

    public double getIpca() { return ipca; }
    public void setIpca(double ipca) { this.ipca = ipca; }

    public double getDolar() { return dolar; }
    public void setDolar(double dolar) { this.dolar = dolar; }

    public double getIbovespa() { return ibovespa; }
    public void setIbovespa(double ibovespa) { this.ibovespa = ibovespa; }

    public String resumo() {
        return String.format("CDI %+.2f p.p. · IPCA %+.2f p.p. · dólar %+.1f%% · Ibovespa %+.1f%%",
                cdi, ipca, dolar, ibovespa);
    }
}
