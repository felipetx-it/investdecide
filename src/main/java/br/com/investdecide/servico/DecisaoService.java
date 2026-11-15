package br.com.investdecide.servico;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.GrupoProdutos;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.dominio.TipoProduto;
import br.com.investdecide.servico.TributacaoService.ResultadoTributario;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Modelo de decisão do SAD.
 *
 * <p>Fluxo: (1) projeta o resultado líquido de cada alternativa no cenário escolhido;
 * (2) elimina as que violam restrições do cliente (elegibilidade); (3) agrupa as elegíveis por
 * similaridade; (4) normaliza os critérios e calcula o índice multicritério pelo método SAW
 * (Simple Additive Weighting); (5) ordena, justifica e testa a robustez dos pesos.</p>
 */
@Service
public class DecisaoService {

    private static final int GRUPOS = 4;

    private final AnaliticaService analitica;
    private final TributacaoService tributacao;

    public DecisaoService(AnaliticaService analitica, TributacaoService tributacao) {
        this.analitica = analitica;
        this.tributacao = tributacao;
    }

    public ResultadoDecisao avaliar(Cliente cliente, List<Produto> produtos, Cenario cenario, Pesos pesos) {
        int meses = Math.max(1, cliente.getHorizonteMeses());
        double anos = meses / 12.0;

        List<AvaliacaoProduto> avaliacoes = new ArrayList<>();
        for (Produto produto : produtos) {
            AvaliacaoProduto avaliacao = new AvaliacaoProduto(produto);
            double retornoBruto = analitica.retornoProjetado(produto, cenario, meses);
            double valorBruto = cliente.getAporte() * Math.pow(1 + retornoBruto / 100, anos);
            ResultadoTributario tributo = calcularTributoOtimo(produto, cliente, valorBruto, meses, avaliacao);

            avaliacao.setRetornoBruto(retornoBruto);
            avaliacao.setValorFinal(tributo.valorLiquido());
            avaliacao.setCargaTributaria(tributo.cargaSobreGanho());
            avaliacao.setBeneficioFiscal(tributo.beneficioFiscal());
            avaliacao.setVolatilidade(analitica.volatilidadeAnual(produto));
            double liquido = tributo.valorLiquido() / cliente.getAporte();
            avaliacao.setRetornoLiquido(liquido <= 0 ? -100 : (Math.pow(liquido, 1 / anos) - 1) * 100);

            aplicarElegibilidade(avaliacao, cliente);
            avaliacoes.add(avaliacao);
        }

        List<AvaliacaoProduto> elegiveis = new ArrayList<>(avaliacoes.stream()
                .filter(AvaliacaoProduto::isElegivel)
                .toList());

        if (!elegiveis.isEmpty()) {
            calcularEscores(elegiveis, pesos);
            elegiveis.sort(Comparator.comparingDouble(AvaliacaoProduto::getScore).reversed()
                    .thenComparing(AvaliacaoProduto::getNome));
            for (int i = 0; i < elegiveis.size(); i++) {
                elegiveis.get(i).setPosicao(i + 1);
            }
            justificar(elegiveis, cliente, cenario);
            List<GrupoProdutos> grupos = analitica.agrupar(elegiveis, GRUPOS);
            for (GrupoProdutos grupo : grupos) {
                grupo.getItens().forEach(item -> item.setGrupo(grupo.getNome()));
            }
            return montarResultado(cliente, cenario, pesos, avaliacoes, elegiveis, grupos);
        }

        return montarResultado(cliente, cenario, pesos, avaliacoes, List.of(), List.of());
    }

    // ------------------------------------------------------------ elegibilidade

    private void aplicarElegibilidade(AvaliacaoProduto avaliacao, Cliente cliente) {
        Produto produto = avaliacao.getProduto();
        String motivo = null;

        if (produto.getRisco() > cliente.getPerfil().getRiscoMaximo()) {
            motivo = "Risco " + produto.getRisco() + " acima do perfil " + cliente.getPerfil().getRotulo();
        } else if (produto.getLiquidezDias() > cliente.getLiquidezDias()) {
            motivo = "Resgate em D+" + produto.getLiquidezDias() + " acima da necessidade de D+" + cliente.getLiquidezDias();
        } else if (produto.getCarenciaMeses() > cliente.getHorizonteMeses()) {
            motivo = "Carência de " + produto.getCarenciaMeses() + " meses maior que o horizonte";
        } else if (produto.getMinimo() > cliente.getAporte()) {
            motivo = String.format("Aporte mínimo de R$ %,.0f", produto.getMinimo()).replace(',', '.');
        }

        avaliacao.setElegivel(motivo == null);
        avaliacao.setMotivo(motivo == null ? "Elegível" : motivo);
    }

    // ------------------------------------------------------------------ escore

    private void calcularEscores(List<AvaliacaoProduto> elegiveis, Pesos pesos) {
        double minRetorno = elegiveis.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).min().orElse(0);
        double maxRetorno = elegiveis.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).max().orElse(0);
        double minRisco = elegiveis.stream().mapToInt(AvaliacaoProduto::getRisco).min().orElse(1);
        double maxRisco = elegiveis.stream().mapToInt(AvaliacaoProduto::getRisco).max().orElse(5);
        double minCarga = elegiveis.stream().mapToDouble(AvaliacaoProduto::getCargaTributaria).min().orElse(0);
        double maxCarga = elegiveis.stream().mapToDouble(AvaliacaoProduto::getCargaTributaria).max().orElse(100);
        double minLiquidez = elegiveis.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).min().orElse(0);
        double maxLiquidez = elegiveis.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).max().orElse(90);

        for (AvaliacaoProduto avaliacao : elegiveis) {
            double nRetorno = normalizar(avaliacao.getRetornoLiquido(), minRetorno, maxRetorno);
            double nRisco = 1 - normalizar(avaliacao.getRisco(), minRisco, maxRisco);
            double nCarga = 1 - normalizar(avaliacao.getCargaTributaria(), minCarga, maxCarga);
            double nLiquidez = 1 - normalizar(avaliacao.getLiquidezDias(), minLiquidez, maxLiquidez);

            double escore = 100 * (pesos.getRetornoNorm() * nRetorno
                    + pesos.getRiscoNorm() * nRisco
                    + pesos.getTributacaoNorm() * nCarga
                    + pesos.getLiquidezNorm() * nLiquidez);
            avaliacao.setScore(escore);
        }
    }

    private static double normalizar(double valor, double minimo, double maximo) {
        if (maximo - minimo < 1e-9) {
            return 0.5;
        }
        return (valor - minimo) / (maximo - minimo);
    }

    // -------------------------------------------------------------- justificativa

    private void justificar(List<AvaliacaoProduto> ranking, Cliente cliente, Cenario cenario) {
        AvaliacaoProduto primeira = ranking.get(0);
        double diferenca = ranking.size() > 1
                ? primeira.getValorFinal() - ranking.get(1).getValorFinal()
                : 0;

        double maiorRetorno = ranking.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).max().orElse(0);
        int menorRisco = ranking.stream().mapToInt(AvaliacaoProduto::getRisco).min().orElse(0);
        double menorCarga = ranking.stream()
                .filter(a -> a.getRetornoLiquido() > 0)
                .mapToDouble(AvaliacaoProduto::getCargaTributaria)
                .min().orElse(0);
        int menorLiquidez = ranking.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).min().orElse(0);
        String objetivo = cliente.getObjetivo() == null ? "" : cliente.getObjetivo();

        for (AvaliacaoProduto avaliacao : ranking) {
            StringBuilder justificativa = new StringBuilder();
            justificativa.append(String.format("Retorno líquido de %.2f%% a.a.", avaliacao.getRetornoLiquido()));
            justificativa.append(String.format(" e carga tributária de %.1f%% sobre o ganho", avaliacao.getCargaTributaria()));
            if (avaliacao.getLiquidezDias() == 0) {
                justificativa.append("; liquidez imediata.");
            } else {
                justificativa.append(String.format("; resgate em D+%d.", avaliacao.getLiquidezDias()));
            }
            if (avaliacao.getBeneficioFiscal() > 0) {
                justificativa.append(String.format(new Locale("pt", "BR"),
                        " Modalidade PGBL indicada com restituição estimada de R$ %,.2f no aporte.",
                        avaliacao.getBeneficioFiscal()));
            }

            List<String> destaques = new ArrayList<>();
            if (avaliacao.getRetornoLiquido() >= maiorRetorno - 1e-9) {
                destaques.add("maior retorno líquido do conjunto");
            }
            if (avaliacao.getRisco() == menorRisco) {
                destaques.add("menor risco");
            }
            if (avaliacao.getRetornoLiquido() > 0 && Math.abs(avaliacao.getCargaTributaria() - menorCarga) < 1e-9) {
                destaques.add("menor carga tributária");
            }
            if (avaliacao.getLiquidezDias() == menorLiquidez) {
                destaques.add("liquidez mais curta");
            }
            if (!destaques.isEmpty()) {
                justificativa.append(" Destaca-se por: ").append(String.join(", ", destaques)).append(".");
            }
            if ("Preservar".equals(objetivo) && avaliacao.getRisco() <= 2 && avaliacao.getRetornoLiquido() > 0) {
                justificativa.append(" Alinhado ao objetivo de preservação.");
            } else if ("Previdência".equals(objetivo) && avaliacao.getProduto().getTipo().isPrevidencia()) {
                justificativa.append(" Alinhado ao objetivo previdenciário.");
            } else if ("Fazer crescer".equals(objetivo) && avaliacao.getRetornoLiquido() >= cenario.getCdi() * 0.85) {
                justificativa.append(" Alinhado ao objetivo de crescimento.");
            }
            avaliacao.setJustificativa(justificativa.toString());

            StringBuilder ressalva = new StringBuilder();
            if (avaliacao.getRetornoLiquido() < 0) {
                ressalva.append("Projeção negativa no cenário avaliado. ");
            }
            if (avaliacao.getProduto().getTipo().isPrevidencia()) {
                if (TipoProduto.PREVIDENCIA_PGBL.getRotulo().equals(avaliacao.getTipoRotulo())) {
                    ressalva.append("PGBL tributa principal + rendimento no resgate; vantajoso frente ao VGBL pelo benefício fiscal no aporte (limite de 12% da renda). ");
                } else {
                    ressalva.append("Avaliado como VGBL (IR apenas sobre o rendimento); benefício do PGBL exigiria declaração completa e prazo maior. ");
                }
            }
            if ("Preservar".equals(objetivo) && avaliacao.getRisco() >= 3) {
                ressalva.append("Objetivo é preservar capital: avalie alternativas de menor risco. ");
            }
            if (avaliacao.getRisco() == cliente.getPerfil().getRiscoMaximo()) {
                ressalva.append("Risco no limite do perfil do cliente. ");
            }
            Integer vencimento = avaliacao.getProduto().getVencimentoMeses();
            if (vencimento != null && vencimento < cliente.getHorizonteMeses()) {
                ressalva.append("Vence em ").append(vencimento)
                        .append(" meses, antes do horizonte: o trecho restante é projetado com prêmio de incerteza de ")
                        .append(String.format("%.2f", analitica.premioReinvestimento(avaliacao.getProduto(), cliente.getHorizonteMeses())))
                        .append(" p.p. ao ano. ");
            }
            if (avaliacao.getPosicao() == 1 && ranking.size() > 1) {
                double percentual = primeira.getValorFinal() > 0
                        ? Math.abs(diferenca) / primeira.getValorFinal() * 100
                        : 0;
                if (percentual < 1.0) {
                    ressalva.append(String.format("Margem estreita para a 2ª opção (%.2f%%): decisão sensível aos pesos. ", percentual));
                }
            }
            if (ressalva.isEmpty()) {
                ressalva.append("Sem restrições adicionais no cenário avaliado.");
            }
            avaliacao.setRessalva(ressalva.toString().trim());
        }
    }

    private ResultadoTributario calcularTributoOtimo(Produto produto, Cliente cliente, double valorBruto,
                                                     int meses, AvaliacaoProduto avaliacao) {
        if (produto.getTipo() != null && produto.getTipo().isPrevidencia()) {
            Produto vgbl = copiaComTipo(produto, TipoProduto.PREVIDENCIA_VGBL);
            ResultadoTributario resVgbl = tributacao.calcular(vgbl, cliente, cliente.getAporte(), valorBruto, meses);
            if (cliente.getModelo() == Cliente.ModeloDeclaracao.COMPLETA && cliente.getRendaBruta() > 0) {
                Produto pgbl = copiaComTipo(produto, TipoProduto.PREVIDENCIA_PGBL);
                ResultadoTributario resPgbl = tributacao.calcular(pgbl, cliente, cliente.getAporte(), valorBruto, meses);
                if (resPgbl.valorLiquido() > resVgbl.valorLiquido()) {
                    avaliacao.setTipoRotuloEfetivo(TipoProduto.PREVIDENCIA_PGBL.getRotulo());
                    return resPgbl;
                }
            }
            avaliacao.setTipoRotuloEfetivo(TipoProduto.PREVIDENCIA_VGBL.getRotulo());
            return resVgbl;
        }
        return tributacao.calcular(produto, cliente, cliente.getAporte(), valorBruto, meses);
    }

    private static Produto copiaComTipo(Produto origem, TipoProduto tipo) {
        Produto copia = new Produto();
        copia.setId(origem.getId());
        copia.setNome(origem.getNome());
        copia.setTipo(tipo);
        copia.setIsentoIr(origem.isIsentoIr());
        return copia;
    }

    // ------------------------------------------------------------- sensibilidade

    /** Resultado de uma perturbação de peso: qual alternativa passou a liderar. */
    public record Perturbacao(String criterio, String lider, boolean mudou) {
    }

    /**
     * Reforça cada critério em 20%, recalcula os escores e devolve quem lidera em cada caso.
     * Ao final, restaura os escores originais.
     */
    public List<Perturbacao> perturbarPesos(List<AvaliacaoProduto> elegiveis, Pesos pesos) {
        List<Perturbacao> saida = new ArrayList<>();
        if (elegiveis.size() < 2) {
            return saida;
        }
        String liderAtual = elegiveis.get(0).getProduto().getId();
        String[] nomes = {"Retorno", "Risco", "Tributação", "Liquidez"};
        double[] valores = {pesos.getRetorno(), pesos.getRisco(), pesos.getTributacao(), pesos.getLiquidez()};

        for (int criterio = 0; criterio < 4; criterio++) {
            Pesos perturbado = new Pesos(valores[0], valores[1], valores[2], valores[3]);
            double novo = valores[criterio] * 1.2;
            switch (criterio) {
                case 0 -> perturbado.setRetorno(novo);
                case 1 -> perturbado.setRisco(novo);
                case 2 -> perturbado.setTributacao(novo);
                default -> perturbado.setLiquidez(novo);
            }
            calcularEscores(elegiveis, perturbado);
            AvaliacaoProduto novoLider = elegiveis.stream()
                    .max(Comparator.comparingDouble(AvaliacaoProduto::getScore))
                    .orElse(elegiveis.get(0));
            saida.add(new Perturbacao(nomes[criterio], novoLider.getNome(),
                    !novoLider.getProduto().getId().equals(liderAtual)));
        }
        calcularEscores(elegiveis, pesos);
        return saida;
    }

    private String testarSensibilidade(List<AvaliacaoProduto> elegiveis, Pesos pesos) {
        if (elegiveis.size() < 2) {
            return "Há apenas uma alternativa elegível.";
        }
        return perturbarPesos(elegiveis, pesos).stream()
                .filter(Perturbacao::mudou)
                .findFirst()
                .map(p -> "Instável: ao reforçar o peso de " + p.criterio().toLowerCase()
                        + " em 20%, a liderança passa para " + p.lider() + ".")
                .orElse("Ranking estável: a recomendação se mantém nas quatro perturbações de +20% nos pesos.");
    }

    // ------------------------------------------------------------------ resultado

    private ResultadoDecisao montarResultado(Cliente cliente, Cenario cenario, Pesos pesos,
                                             List<AvaliacaoProduto> avaliacoes,
                                             List<AvaliacaoProduto> ranking,
                                             List<GrupoProdutos> grupos) {
        ResultadoDecisao resultado = new ResultadoDecisao();
        resultado.setCliente(cliente);
        resultado.setCenario(cenario);
        resultado.setPesos(pesos);
        resultado.setAvaliacoes(avaliacoes);
        resultado.setRanking(new ArrayList<>(ranking));
        resultado.setGrupos(grupos);
        resultado.setTotalAlternativas(avaliacoes.size());
        resultado.setTotalElegiveis(ranking.size());

        if (!ranking.isEmpty()) {
            resultado.setSensibilidade(testarSensibilidade(ranking, pesos));
            resultado.setCargaTributariaRecomendada(ranking.get(0).getCargaTributaria());
            resultado.setValorFinalRecomendado(ranking.get(0).getValorFinal());
            resultado.setSilhueta(analitica.calcularSilhueta(ranking, grupos));
        }

        resultado.setPremissas(String.format(
                "Cenário %s: CDI %.2f%%, IPCA %.2f%%, dólar %.1f%%, Ibovespa %.1f%% · horizonte %d meses · pesos %.0f/%.0f/%.0f/%.0f",
                cenario.getNome(), cenario.getCdi(), cenario.getIpca(), cenario.getDolar(), cenario.getIbovespa(),
                cliente.getHorizonteMeses(), pesos.getRetorno(), pesos.getRisco(),
                pesos.getTributacao(), pesos.getLiquidez()));
        return resultado;
    }
}
