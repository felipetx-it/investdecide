package br.com.investdecide.dominio;

/**
 * Alternativa de investimento do catálogo.
 *
 * <p>Fundos e previdência têm o retorno projetado por modelo fatorial (regressão estimada a
 * partir da série histórica). CDB, LCI e LCA são contratuais: o retorno vem da regra de
 * remuneração informada no cadastro.</p>
 */
public class Produto {

    private String id;
    private String nome;
    private String emissor;
    private TipoProduto tipo;

    private Indexador indexador = Indexador.CDI;
    /** Percentual do indexador (100 = 100% do CDI) para produtos pós-fixados. */
    private double percentualIndexador = 100.0;
    /** Spread anual (% a.a.) somado ao indexador; para prefixado, é a própria taxa. */
    private double spreadAnual = 0.0;

    /** Taxa de administração (% a.a.) — usada como indicador de custo. */
    private double taxaAdm = 0.0;

    /** Prazo até o vencimento em meses; nulo quando o produto não tem vencimento. */
    private Integer vencimentoMeses;
    /** Prazo de resgate em dias corridos (0 = liquidez imediata). */
    private int liquidezDias = 0;
    /** Origem do prazo de resgate: "XP" (portal), "CVM" (extrato), "estimada" (classe ANBIMA) ou vazio (manual). */
    private String liquidezFonte = "";
    /** Texto original do portal da XP (cotização e liquidação), para conferência. */
    private String liquidezDetalhe = "";
    /** Carência em meses antes de permitir resgate. */
    private int carenciaMeses = 0;

    /** Risco de 1 (muito baixo) a 5 (muito alto). */
    private int risco = 2;
    private double minimo = 1000.0;
    private boolean isentoIr = false;

    /** Sensibilidades usadas para gerar a série sintética e para a projeção. */
    private double betaCdi = 1.0;
    private double betaIpca = 0.0;
    private double betaDolar = 0.0;
    /** Retorno esperado idiossincrático (alpha mensal, %). */
    private double alphaMensal = 0.0;
    /** Volatilidade mensal específica (%); define o ruído da série. */
    private double volMensal = 0.5;

    /** Chave da série histórica real (CNPJ do fundo na base da CVM). */
    private String chaveSerie;

    /** Indica produto cadastrado manualmente pelo usuário. */
    private boolean manual = false;

    public boolean isContratual() {
        return tipo != null && tipo.isRendaFixaBancaria();
    }

    public String getRotuloTipo() {
        return tipo == null ? "" : tipo.getRotulo();
    }

    /** Texto curto da remuneração, para exibição. */
    public String getRemuneracao() {
        if (tipo != null && tipo.isFundo()) {
            return "gestão ativa";
        }
        return switch (indexador) {
            case CDI -> String.format("%.0f%% do CDI", percentualIndexador)
                    + (spreadAnual != 0 ? String.format(" + %.2f%%", spreadAnual) : "");
            case IPCA -> String.format("IPCA + %.2f%%", spreadAnual);
            case PREFIXADO -> String.format("%.2f%% a.a.", spreadAnual);
        };
    }

    // getters e setters -------------------------------------------------------

    /** Valores para inputs numéricos: ponto decimal e sem separador de milhar. */
    public String getPercentualIndexadorInput() { return plain(percentualIndexador); }
    public String getSpreadAnualInput() { return plain(spreadAnual); }
    public String getMinimoInput() { return plain(minimo); }

    private static String plain(double valor) {
        return java.math.BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getEmissor() { return emissor; }
    public void setEmissor(String emissor) { this.emissor = emissor; }

    public TipoProduto getTipo() { return tipo; }
    public void setTipo(TipoProduto tipo) { this.tipo = tipo; }

    public Indexador getIndexador() { return indexador; }
    public void setIndexador(Indexador indexador) { this.indexador = indexador; }

    public double getPercentualIndexador() { return percentualIndexador; }
    public void setPercentualIndexador(double percentualIndexador) { this.percentualIndexador = percentualIndexador; }

    public double getSpreadAnual() { return spreadAnual; }
    public void setSpreadAnual(double spreadAnual) { this.spreadAnual = spreadAnual; }

    public double getTaxaAdm() { return taxaAdm; }
    public void setTaxaAdm(double taxaAdm) { this.taxaAdm = taxaAdm; }

    public Integer getVencimentoMeses() { return vencimentoMeses; }
    public void setVencimentoMeses(Integer vencimentoMeses) { this.vencimentoMeses = vencimentoMeses; }

    public int getLiquidezDias() { return liquidezDias; }
    public void setLiquidezDias(int liquidezDias) { this.liquidezDias = liquidezDias; }

    public String getLiquidezFonte() { return liquidezFonte; }
    public void setLiquidezFonte(String liquidezFonte) { this.liquidezFonte = liquidezFonte; }

    public String getLiquidezDetalhe() { return liquidezDetalhe; }
    public void setLiquidezDetalhe(String liquidezDetalhe) { this.liquidezDetalhe = liquidezDetalhe; }

    /** Texto de apoio do prazo: "D+32 · Fonte: XP · D+30 (Dias Corridos) · D+1 (Dias Úteis)". */
    public String getLiquidezTexto() {
        String prazo = liquidezDias == 0 ? "D+0" : "D+" + liquidezDias;
        if ("estimada".equals(liquidezFonte)) {
            return prazo + " · estimativa por classe ANBIMA";
        }
        String prefixo = (liquidezFonte != null && !liquidezFonte.isBlank()) ? "Fonte: " + liquidezFonte : "";
        String detalhe = liquidezDetalhe != null ? liquidezDetalhe.trim() : "";
        if (!prefixo.isBlank() && !detalhe.isBlank()) {
            return prazo + " · " + prefixo + " · " + detalhe;
        }
        if (!detalhe.isBlank()) {
            return prazo + " · " + detalhe;
        }
        return prefixo.isBlank() ? prazo : prazo + " · " + prefixo;
    }

    public int getCarenciaMeses() { return carenciaMeses; }
    public void setCarenciaMeses(int carenciaMeses) { this.carenciaMeses = carenciaMeses; }

    public int getRisco() { return risco; }
    public void setRisco(int risco) { this.risco = risco; }

    public double getMinimo() { return minimo; }
    public void setMinimo(double minimo) { this.minimo = minimo; }

    public boolean isIsentoIr() { return isentoIr; }
    public void setIsentoIr(boolean isentoIr) { this.isentoIr = isentoIr; }

    public double getBetaCdi() { return betaCdi; }
    public void setBetaCdi(double betaCdi) { this.betaCdi = betaCdi; }

    public double getBetaIpca() { return betaIpca; }
    public void setBetaIpca(double betaIpca) { this.betaIpca = betaIpca; }

    public double getBetaDolar() { return betaDolar; }
    public void setBetaDolar(double betaDolar) { this.betaDolar = betaDolar; }

    public double getAlphaMensal() { return alphaMensal; }
    public void setAlphaMensal(double alphaMensal) { this.alphaMensal = alphaMensal; }

    public double getVolMensal() { return volMensal; }
    public void setVolMensal(double volMensal) { this.volMensal = volMensal; }

    public String getChaveSerie() { return chaveSerie; }
    public void setChaveSerie(String chaveSerie) { this.chaveSerie = chaveSerie; }

    public boolean isManual() { return manual; }
    public void setManual(boolean manual) { this.manual = manual; }
}
