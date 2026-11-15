package br.com.investdecide.dominio;

import java.util.ArrayList;
import java.util.List;

/** Grupo (cluster) de alternativas semelhantes, produzido pelo agrupamento. */
public class GrupoProdutos {

    private String nome;
    private String descricao;
    private final List<AvaliacaoProduto> itens = new ArrayList<>();

    public GrupoProdutos(String nome, String descricao) {
        this.nome = nome;
        this.descricao = descricao;
    }

    public void adicionar(AvaliacaoProduto avaliacao) {
        itens.add(avaliacao);
    }

    public int getQuantidade() {
        return itens.size();
    }

    public double getRetornoMedio() {
        return itens.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).average().orElse(0);
    }

    public double getRiscoMedio() {
        return itens.stream().mapToInt(AvaliacaoProduto::getRisco).average().orElse(0);
    }

    public double getCargaMedia() {
        return itens.stream().mapToDouble(AvaliacaoProduto::getCargaTributaria).average().orElse(0);
    }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }

    public List<AvaliacaoProduto> getItens() { return itens; }
}
