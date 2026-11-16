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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        preencherDestaquesTop3(ranking);
    }

    private enum CriterioTop3 { LIQUIDEZ, RETORNO, RISCO_VOL, TRIBUTACAO, EQUILIBRIO }

    private void preencherDestaquesTop3(List<AvaliacaoProduto> ranking) {
        int n = Math.min(3, ranking.size());
        if (n == 0) {
            return;
        }
        List<AvaliacaoProduto> top = ranking.subList(0, n);
        Locale ptBR = new Locale("pt", "BR");

        int minLiq = top.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).min().orElse(0);
        int maxLiq = top.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).max().orElse(0);
        double maxRet = top.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).max().orElse(0);
        double minRet = top.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).min().orElse(0);
        int minRisco = top.stream().mapToInt(AvaliacaoProduto::getRisco).min().orElse(1);
        int maxRisco = top.stream().mapToInt(AvaliacaoProduto::getRisco).max().orElse(1);
        double minVolNoMenorRisco = top.stream()
                .filter(a -> a.getRisco() == minRisco)
                .mapToDouble(AvaliacaoProduto::getVolatilidade)
                .min().orElse(0);
        double maxVol = top.stream().mapToDouble(AvaliacaoProduto::getVolatilidade).max().orElse(0);
        double minCarga = top.stream().mapToDouble(AvaliacaoProduto::getCargaTributaria).min().orElse(0);
        double maxCarga = top.stream().mapToDouble(AvaliacaoProduto::getCargaTributaria).max().orElse(0);

        CriterioTop3[] escolhidos = atribuirCriteriosDistintos(
                top, minLiq, maxLiq, maxRet, minRet, minRisco, maxRisco, minVolNoMenorRisco, maxVol, minCarga, maxCarga);

        Set<String> titulosUsados = new HashSet<>();
        for (int i = 0; i < n; i++) {
            AvaliacaoProduto a = top.get(i);
            CriterioTop3 criterio = escolhidos[i];
            switch (criterio) {
                case LIQUIDEZ -> {
                    boolean temMelhorRetorno = n == 1 || (a.getRetornoLiquido() >= maxRet - 1e-6 && maxRet - minRet >= 0.05);
                    boolean outroComRetorno = false;
                    for (int j = 0; j < n; j++) {
                        if (j != i && escolhidos[j] == CriterioTop3.RETORNO) {
                            outroComRetorno = true;
                        }
                    }
                    if (temMelhorRetorno && !outroComRetorno && i == 0) {
                        a.setDestaqueTop3("Melhor liquidez (" + prazoRotulo(a.getLiquidezDias()) + ") e maior retorno");
                        a.setMotivoTop3(String.format(ptBR,
                                "Combina %s ao maior retorno líquido projetado do Top 3 (%.2f%% a.a.).",
                                prazoTexto(a.getLiquidezDias()), a.getRetornoLiquido()));
                    } else {
                        a.setDestaqueTop3("Melhor liquidez (" + prazoRotulo(a.getLiquidezDias()) + ")");
                        a.setMotivoTop3(String.format(ptBR,
                                "Menor prazo de resgate entre as líderes (%s), mantendo retorno líquido de %.2f%% a.a. e risco %d.",
                                prazoTexto(a.getLiquidezDias()), a.getRetornoLiquido(), a.getRisco()));
                    }
                }
                case RETORNO -> {
                    a.setDestaqueTop3(String.format(ptBR, "Maior retorno líquido (%.2f%% a.a.)", a.getRetornoLiquido()));
                    a.setMotivoTop3(String.format(ptBR,
                            "Entrega o maior saldo líquido projetado do Top 3 (R$ %,.2f), com resgate em %s e risco %d.",
                            a.getValorFinal(), prazoRotulo(a.getLiquidezDias()), a.getRisco()));
                }
                case RISCO_VOL -> {
                    a.setDestaqueTop3(String.format(ptBR, "Menor risco e volatilidade (%.2f%% a.a.)", a.getVolatilidade()));
                    a.setMotivoTop3(String.format(ptBR,
                            "Alternativa mais estável do Top 3 (risco %d e volatilidade de %.2f%% a.a.), com retorno líquido de %.2f%% a.a.",
                            a.getRisco(), a.getVolatilidade(), a.getRetornoLiquido()));
                }
                case TRIBUTACAO -> {
                    a.setDestaqueTop3(a.getProduto().isIsentoIr()
                            ? "Maior eficiência tributária (Isento de IR)"
                            : String.format(ptBR, "Menor carga tributária (%.1f%%)", a.getCargaTributaria()));
                    a.setMotivoTop3(String.format(ptBR,
                            "Menor retenção tributária efetiva no horizonte (%.1f%%), aliada a resgate em %s e retorno líquido de %.2f%% a.a.",
                            a.getCargaTributaria(), prazoRotulo(a.getLiquidezDias()), a.getRetornoLiquido()));
                }
                case EQUILIBRIO -> {
                    String titulo = i == 0
                            ? String.format(ptBR, "Melhor equilíbrio geral (%.1f/100)", a.getScore())
                            : String.format(ptBR, "Equilíbrio entre retorno (%.2f%% a.a.) e %s",
                                    a.getRetornoLiquido(), a.getRisco() <= 2 ? "baixo risco" : "risco " + a.getRisco());
                    if (!titulosUsados.add(titulo)) {
                        titulo = String.format(ptBR, "%dª melhor pontuação multicritério (%.1f/100)", i + 1, a.getScore());
                    }
                    a.setDestaqueTop3(titulo);
                    a.setMotivoTop3(String.format(ptBR,
                            "Sustenta posição no Top 3 pelo equilíbrio conjunto no índice SAW (%.1f/100): retorno líquido de %.2f%% a.a., risco %d (volatilidade %.2f%%) e resgate em %s.",
                            a.getScore(), a.getRetornoLiquido(), a.getRisco(), a.getVolatilidade(), prazoRotulo(a.getLiquidezDias())));
                }
            }
            titulosUsados.add(a.getDestaqueTop3());
        }
    }

    private static CriterioTop3[] atribuirCriteriosDistintos(
            List<AvaliacaoProduto> top, int minLiq, int maxLiq, double maxRet, double minRet,
            int minRisco, int maxRisco, double minVol, double maxVol, double minCarga, double maxCarga) {
        int n = top.size();
        double[][] matriz = new double[n][4];
        CriterioTop3[] principais = {CriterioTop3.LIQUIDEZ, CriterioTop3.RETORNO, CriterioTop3.RISCO_VOL, CriterioTop3.TRIBUTACAO};

        for (int i = 0; i < n; i++) {
            AvaliacaoProduto a = top.get(i);
            boolean ehLiq = a.getLiquidezDias() == minLiq;
            boolean ehRet = a.getRetornoLiquido() >= maxRet - 1e-6;
            boolean ehRisco = a.getRisco() == minRisco && a.getVolatilidade() <= minVol + 1e-4;
            boolean ehTrib = a.getCargaTributaria() <= minCarga + 1e-4
                    && (maxCarga - minCarga >= 0.05 || a.getProduto().isIsentoIr());

            matriz[i][0] = ehLiq ? (maxLiq > minLiq ? 30.0 : 8.0) : -1;
            matriz[i][1] = ehRet ? (maxRet - minRet >= 0.01 ? 32.0 : 9.0) : -1;
            matriz[i][2] = ehRisco ? ((maxRisco > minRisco || maxVol - minVol >= 0.01) ? 28.0 : 7.0) : -1;
            matriz[i][3] = ehTrib ? 26.0 : -1;
        }

        // Busca a atribuição sem repetição que maximiza a especificidade dos destaques.
        CriterioTop3[] melhor = new CriterioTop3[n];
        double[] melhorPontuacao = {-1.0};
        CriterioTop3[] atual = new CriterioTop3[n];
        buscarMelhorAtribuicao(0, n, matriz, principais, new boolean[4], atual, 0.0, melhor, melhorPontuacao);
        return melhor;
    }

    private static void buscarMelhorAtribuicao(int idx, int n, double[][] matriz, CriterioTop3[] principais,
                                               boolean[] usados, CriterioTop3[] atual, double soma,
                                               CriterioTop3[] melhor, double[] melhorPontuacao) {
        if (idx == n) {
            if (soma > melhorPontuacao[0]) {
                melhorPontuacao[0] = soma;
                System.arraycopy(atual, 0, melhor, 0, n);
            }
            return;
        }
        for (int c = 0; c < 4; c++) {
            if (!usados[c] && matriz[idx][c] > 0) {
                usados[c] = true;
                atual[idx] = principais[c];
                buscarMelhorAtribuicao(idx + 1, n, matriz, principais, usados, atual,
                        soma + matriz[idx][c], melhor, melhorPontuacao);
                usados[c] = false;
            }
        }
        atual[idx] = CriterioTop3.EQUILIBRIO;
        buscarMelhorAtribuicao(idx + 1, n, matriz, principais, usados, atual, soma + 1.0, melhor, melhorPontuacao);
    }

    private static String prazoRotulo(int dias) {
        return dias <= 0 ? "D+0" : "D+" + dias;
    }

    private static String prazoTexto(int dias) {
        return dias <= 0 ? "liquidez imediata (D+0)" : "resgate em D+" + dias;
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
