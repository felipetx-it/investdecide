package br.com.investdecide.dominio;

/** Famílias de produto tratadas pelo SAD. */
public enum TipoProduto {

    FUNDO_RF("Fundo renda fixa", Categoria.FUNDO),
    FUNDO_MULTI("Fundo multimercado", Categoria.FUNDO),
    FUNDO_ACOES("Fundo de ações", Categoria.FUNDO),
    PREVIDENCIA_PGBL("Previdência PGBL", Categoria.PREVIDENCIA),
    PREVIDENCIA_VGBL("Previdência VGBL", Categoria.PREVIDENCIA),
    CDB("CDB", Categoria.RENDA_FIXA_BANCARIA),
    LCI("LCI", Categoria.RENDA_FIXA_BANCARIA),
    LCA("LCA", Categoria.RENDA_FIXA_BANCARIA);

    public enum Categoria { FUNDO, PREVIDENCIA, RENDA_FIXA_BANCARIA }

    private final String rotulo;
    private final Categoria categoria;

    TipoProduto(String rotulo, Categoria categoria) {
        this.rotulo = rotulo;
        this.categoria = categoria;
    }

    public String getRotulo() {
        return rotulo;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public boolean isFundo() {
        return categoria == Categoria.FUNDO || categoria == Categoria.PREVIDENCIA;
    }

    public boolean isPrevidencia() {
        return categoria == Categoria.PREVIDENCIA;
    }

    public boolean isRendaFixaBancaria() {
        return categoria == Categoria.RENDA_FIXA_BANCARIA;
    }
}
