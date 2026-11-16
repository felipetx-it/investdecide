package br.com.investdecide.dominio;

/** Resultado da avaliação de uma alternativa para um cliente e cenário. */
public class AvaliacaoProduto {

    private Produto produto;
    private boolean elegivel;
    private String motivo;
    private String grupo = "";
    private String tipoRotuloEfetivo = "";

    private double retornoBruto;
    private double retornoLiquido;
    private double cargaTributaria;
    private double beneficioFiscal;
    private double valorFinal;
    private double volatilidade;
    private double score;
    private int posicao;
    private String justificativa = "";
    private String ressalva = "";
    private String destaqueTop3 = "";
    private String motivoTop3 = "";

    public AvaliacaoProduto(Produto produto) {
        this.produto = produto;
    }

    public String getNome() { return produto.getNome(); }
    public String getTipoRotulo() {
        return tipoRotuloEfetivo != null && !tipoRotuloEfetivo.isBlank()
                ? tipoRotuloEfetivo
                : produto.getRotuloTipo();
    }
    public String getRemuneracao() { return produto.getRemuneracao(); }
    public int getRisco() { return produto.getRisco(); }
    public int getLiquidezDias() { return produto.getLiquidezDias(); }
    public String getLiquidezTexto() { return produto.getLiquidezTexto(); }
    public String getLiquidezFonte() { return produto.getLiquidezFonte(); }
    public boolean isLiquidezEstimada() { return "estimada".equals(produto.getLiquidezFonte()); }

    public Produto getProduto() { return produto; }
    public void setProduto(Produto produto) { this.produto = produto; }

    public boolean isElegivel() { return elegivel; }
    public void setElegivel(boolean elegivel) { this.elegivel = elegivel; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public String getGrupo() { return grupo; }
    public void setGrupo(String grupo) { this.grupo = grupo; }

    public String getTipoRotuloEfetivo() { return tipoRotuloEfetivo; }
    public void setTipoRotuloEfetivo(String tipoRotuloEfetivo) { this.tipoRotuloEfetivo = tipoRotuloEfetivo; }

    public double getRetornoBruto() { return retornoBruto; }
    public void setRetornoBruto(double retornoBruto) { this.retornoBruto = retornoBruto; }

    public double getRetornoLiquido() { return retornoLiquido; }
    public void setRetornoLiquido(double retornoLiquido) { this.retornoLiquido = retornoLiquido; }

    public double getCargaTributaria() { return cargaTributaria; }
    public void setCargaTributaria(double cargaTributaria) { this.cargaTributaria = cargaTributaria; }

    public double getBeneficioFiscal() { return beneficioFiscal; }
    public void setBeneficioFiscal(double beneficioFiscal) { this.beneficioFiscal = beneficioFiscal; }

    public double getValorFinal() { return valorFinal; }
    public void setValorFinal(double valorFinal) { this.valorFinal = valorFinal; }

    public double getVolatilidade() { return volatilidade; }
    public void setVolatilidade(double volatilidade) { this.volatilidade = volatilidade; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public int getPosicao() { return posicao; }
    public void setPosicao(int posicao) { this.posicao = posicao; }

    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }

    public String getRessalva() { return ressalva; }
    public void setRessalva(String ressalva) { this.ressalva = ressalva; }

    public String getDestaqueTop3() { return destaqueTop3; }
    public void setDestaqueTop3(String destaqueTop3) { this.destaqueTop3 = destaqueTop3; }

    public String getMotivoTop3() { return motivoTop3; }
    public void setMotivoTop3(String motivoTop3) { this.motivoTop3 = motivoTop3; }
}
