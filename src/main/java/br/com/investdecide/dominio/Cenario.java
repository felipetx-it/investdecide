package br.com.investdecide.dominio;

/** Cenário econômico usado nas projeções (taxas anuais em %). */
public class Cenario {

    private String nome;
    private String descricao;
    private double cdi;
    private double ipca;
    private double dolar;
    private double ibovespa;

    public Cenario() {
    }

    public Cenario(String nome, String descricao, double cdi, double ipca, double dolar, double ibovespa) {
        this.nome = nome;
        this.descricao = descricao;
        this.cdi = cdi;
        this.ipca = ipca;
        this.dolar = dolar;
        this.ibovespa = ibovespa;
    }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }

    public double getCdi() { return cdi; }
    public void setCdi(double cdi) { this.cdi = cdi; }

    public double getIpca() { return ipca; }
    public void setIpca(double ipca) { this.ipca = ipca; }

    public double getDolar() { return dolar; }
    public void setDolar(double dolar) { this.dolar = dolar; }

    public double getIbovespa() { return ibovespa; }
    public void setIbovespa(double ibovespa) { this.ibovespa = ibovespa; }
}
