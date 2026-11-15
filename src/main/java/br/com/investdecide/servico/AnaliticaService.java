package br.com.investdecide.servico;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.GrupoProdutos;
import br.com.investdecide.dominio.Produto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Funções analíticas do SAD: séries históricas reais da CVM, regressão fatorial multifatorial
 * regularizada (CDI, IPCA, dólar e proxy de Ibovespa), agrupamento (k-médias++ com validação
 * por coeficiente de silhueta) e sumarização dos grupos.
 */
@Service
public class AnaliticaService {

    private final DadosMercadoService dados;
    /** Janela de estimação da regressão, em meses (24 escolhido na comparação 12/24/36). */
    private final int janela;

    public AnaliticaService(DadosMercadoService dados,
                            @org.springframework.beans.factory.annotation.Value("${investdecide.janela-regressao:24}")
                            int janela) {
        this.dados = dados;
        this.janela = janela;
    }

    // ------------------------------------------------------------------ séries

    /** Retorno mensal real do fundo, alinhado à série de meses (NaN onde não há dado). */
    public double[] serieProduto(Produto produto) {
        return dados.serie(produto.getChaveSerie());
    }

    public int observacoes(Produto produto) {
        int total = 0;
        for (double valor : serieProduto(produto)) {
            if (!Double.isNaN(valor)) {
                total++;
            }
        }
        return total;
    }

    public double volatilidadeAnual(Produto produto) {
        double[] serie = serieProduto(produto);
        int inicio = Math.max(0, serie.length - janela);
        double soma = 0;
        double somaQuadrados = 0;
        int n = 0;
        for (int i = inicio; i < serie.length; i++) {
            double valor = serie[i];
            if (!Double.isNaN(valor)) {
                soma += valor;
                somaQuadrados += valor * valor;
                n++;
            }
        }
        if (n < 3) {
            return produto.getVolMensal() * Math.sqrt(12);
        }
        double media = soma / n;
        double variancia = Math.max(0, (somaQuadrados - n * media * media) / (n - 1));
        return Math.sqrt(variancia) * Math.sqrt(12);
    }

    // -------------------------------------------------------------- regressão

    /** Coeficientes estimados por regressão fatorial regularizada para retorno mensal ~ CDI + IPCA + dólar + Ibovespa. */
    public record Coeficientes(double alpha, double betaCdi, double betaIpca, double betaDolar, double betaIbov, double r2) {
    }

    public Coeficientes regredir(Produto produto) {
        double[] serie = serieProduto(produto);
        int inicio = Math.max(0, serie.length - janela);
        int obs = 0;
        for (int i = inicio; i < serie.length; i++) {
            if (!Double.isNaN(serie[i])) {
                obs++;
            }
        }
        if (obs < 6) {
            return fallback(produto, serie);
        }

        int k = 5;
        double[][] xtx = new double[k][k];
        double[] xty = new double[k];
        double[] y = new double[obs];
        double[][] xLinhas = new double[obs][k];
        int posicao = 0;
        double somaY = 0;

        for (int i = inicio; i < serie.length; i++) {
            if (Double.isNaN(serie[i])) {
                continue;
            }
            double[] fatores = dados.fatoresDoMes(i);
            double ibov = fatores.length > 3 ? fatores[3] : 0.0;
            double[] linha = {1.0, fatores[0], fatores[1], fatores[2], ibov};
            y[posicao] = serie[i];
            xLinhas[posicao] = linha;
            somaY += serie[i];
            posicao++;

            for (int a = 0; a < k; a++) {
                for (int b = 0; b < k; b++) {
                    xtx[a][b] += linha[a] * linha[b];
                }
                xty[a] += linha[a] * serie[i];
            }
        }

        // Regularização Ridge ancorada na classe para mitigar a colinearidade entre intercepto e CDI
        boolean isAcoes = produto.getTipo() == br.com.investdecide.dominio.TipoProduto.FUNDO_ACOES;
        double[] prior = {0.0, isAcoes ? 0.0 : 1.0, 0.0, 0.0, isAcoes ? 1.0 : 0.0};
        double[] penalidade = {0.0, 1.5, 0.5, 1.0, 1.0};
        for (int a = 0; a < k; a++) {
            xtx[a][a] += penalidade[a];
            xty[a] += penalidade[a] * prior[a];
        }

        double[] beta = resolver(xtx, xty);
        if (beta == null) {
            return fallback(produto, serie);
        }

        double mediaY = somaY / obs;
        double sse = 0;
        double sst = 0;
        for (int i = 0; i < obs; i++) {
            double[] l = xLinhas[i];
            double previsto = beta[0] + beta[1] * l[1] + beta[2] * l[2] + beta[3] * l[3] + beta[4] * l[4];
            sse += Math.pow(y[i] - previsto, 2);
            sst += Math.pow(y[i] - mediaY, 2);
        }
        double r2 = sst == 0 ? 0 : Math.max(0, 1 - sse / sst);
        return new Coeficientes(beta[0], beta[1], beta[2], beta[3], beta[4], r2);
    }

    /** Sem histórico suficiente: usa o retorno médio como alfa. */
    private static Coeficientes fallback(Produto produto, double[] serie) {
        double soma = 0;
        int n = 0;
        for (double valor : serie) {
            if (!Double.isNaN(valor)) {
                soma += valor;
                n++;
            }
        }
        double media = n == 0 ? produto.getAlphaMensal() : soma / n;
        return new Coeficientes(media, 0, 0, 0, 0, 0);
    }

    /** Eliminação de Gauss com pivoteamento parcial. */
    private static double[] resolver(double[][] matriz, double[] termos) {
        int n = termos.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matriz[i], 0, a[i], 0, n);
            a[i][n] = termos[i];
        }
        for (int col = 0; col < n; col++) {
            int pivô = col;
            for (int lin = col + 1; lin < n; lin++) {
                if (Math.abs(a[lin][col]) > Math.abs(a[pivô][col])) {
                    pivô = lin;
                }
            }
            if (Math.abs(a[pivô][col]) < 1e-9) {
                return null;
            }
            double[] troca = a[col];
            a[col] = a[pivô];
            a[pivô] = troca;

            for (int lin = col + 1; lin < n; lin++) {
                double fator = a[lin][col] / a[col][col];
                for (int c = col; c <= n; c++) {
                    a[lin][c] -= fator * a[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double soma = a[i][n];
            for (int j = i + 1; j < n; j++) {
                soma -= a[i][j] * x[j];
            }
            x[i] = soma / a[i][i];
        }
        return x;
    }

    // -------------------------------------------------------------- projeção

    /**
     * Retorno anual projetado (%) para o cenário e o horizonte informados.
     *
     * <p>Produtos com vencimento anterior ao horizonte têm o trecho pós-vencimento projetado à
     * taxa do cenário com um prêmio de incerteza: ninguém sabe a taxa que estará disponível no
     * futuro, e quanto maior a exposição, maior a penalidade. Isso evita que um CDB/LCI/LCA de
     * 1 ou 2 anos pareça render a mesma taxa por 10 anos.</p>
     */
    public double retornoProjetado(Produto produto, Cenario cenario, int horizonteMeses) {
        double base = produto.isContratual()
                ? retornoContratual(produto, cenario)
                : retornoModelo(produto, cenario);
        return ajustarPorVencimento(produto, cenario, base, horizonteMeses);
    }

    /** Prêmio de incerteza (p.p. ao ano) aplicado ao período posterior ao vencimento. */
    public double premioReinvestimento(Produto produto, int horizonteMeses) {
        Integer vencimento = produto.getVencimentoMeses();
        if (vencimento == null || vencimento >= horizonteMeses) {
            return 0;
        }
        double anosExpostos = (horizonteMeses - vencimento) / 12.0;
        return Math.min(4.0, 0.75 * anosExpostos);
    }

    private double ajustarPorVencimento(Produto produto, Cenario cenario, double base, int horizonteMeses) {
        Integer vencimento = produto.getVencimentoMeses();
        if (vencimento == null || vencimento >= horizonteMeses) {
            return base;
        }
        double anosExpostos = (horizonteMeses - vencimento) / 12.0;
        double taxaReinvestimento = cenario.getCdi() - premioReinvestimento(produto, horizonteMeses);
        double fator = Math.pow(1 + base / 100, vencimento / 12.0)
                * Math.pow(1 + taxaReinvestimento / 100, anosExpostos);
        return (Math.pow(fator, 12.0 / horizonteMeses) - 1) * 100;
    }

    private double retornoModelo(Produto produto, Cenario cenario) {
        Coeficientes coef = regredir(produto);
        double cdiMensal = mensal(cenario.getCdi());
        double ipcaMensal = mensal(cenario.getIpca());
        double dolarMensal = mensal(cenario.getDolar());
        double ibovMensal = mensal(cenario.getIbovespa());
        double mensalProjetado = coef.alpha()
                + coef.betaCdi() * cdiMensal
                + coef.betaIpca() * ipcaMensal
                + coef.betaDolar() * dolarMensal
                + coef.betaIbov() * ibovMensal;
        return (Math.pow(1 + mensalProjetado / 100, 12) - 1) * 100;
    }

    private double retornoContratual(Produto produto, Cenario cenario) {
        return switch (produto.getIndexador()) {
            case CDI -> cenario.getCdi() * (produto.getPercentualIndexador() / 100.0) + produto.getSpreadAnual();
            case IPCA -> ((1 + cenario.getIpca() / 100) * (1 + produto.getSpreadAnual() / 100) - 1) * 100;
            case PREFIXADO -> produto.getSpreadAnual();
        };
    }

    private static double mensal(double anual) {
        return (Math.pow(1 + anual / 100, 1.0 / 12) - 1) * 100;
    }

    // ------------------------------------------------------------ agrupamento

    /**
     * Agrupa as alternativas elegíveis em k grupos por similaridade de retorno líquido,
     * risco, liquidez e carga tributária (k-médias sobre variáveis padronizadas).
     */
    public List<GrupoProdutos> agrupar(List<AvaliacaoProduto> elegiveis, int k) {
        if (elegiveis.isEmpty()) {
            return List.of();
        }
        double[][] normalizado = padronizar(extrairAtributos(elegiveis));

        // Número de grupos proporcional ao tamanho da carteira, respeitando o teto k informado.
        int total = elegiveis.size();
        int gruposAlvo = k > 0 ? k : 4;
        int grupos = total >= 19 ? gruposAlvo : (total >= 9 ? Math.min(3, gruposAlvo) : 2);
        grupos = Math.min(grupos, total);
        int[] rotulos = null;
        for (long semente : new long[]{7L, 11L, 23L, 42L}) {
            int[] candidato = kMeans(normalizado, grupos, semente);
            if (menorTamanho(candidato, grupos) >= 2) {
                rotulos = candidato;
                break;
            }
        }
        if (rotulos == null) {
            rotulos = fundirSingletons(normalizado, kMeans(normalizado, grupos, 7L), grupos);
        }

        Map<Integer, List<AvaliacaoProduto>> porGrupo = new LinkedHashMap<>();
        for (int i = 0; i < rotulos.length; i++) {
            porGrupo.computeIfAbsent(rotulos[i], g -> new ArrayList<>()).add(elegiveis.get(i));
        }

        List<GrupoProdutos> resultado = new ArrayList<>();
        Map<String, Integer> nomesUsados = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<AvaliacaoProduto>> entrada : porGrupo.entrySet()) {
            List<AvaliacaoProduto> itens = entrada.getValue();
            String nome = nomearGrupo(itens);
            int ocorrencias = nomesUsados.merge(nome, 1, Integer::sum);
            if (ocorrencias > 1) {
                double riscoMedio = itens.stream().mapToInt(AvaliacaoProduto::getRisco).average().orElse(0);
                nome = nome + String.format(" · risco médio %.1f", riscoMedio);
            }
            GrupoProdutos grupo = new GrupoProdutos(nome, descreverGrupo(itens));
            itens.sort(Comparator.comparing(AvaliacaoProduto::getNome));
            itens.forEach(grupo::adicionar);
            resultado.add(grupo);
        }
        calcularSilhueta(elegiveis, resultado);
        resultado.sort(Comparator.comparing(GrupoProdutos::getNome));
        return resultado;
    }

    /**
     * Calcula o coeficiente de silhueta (Rousseeuw, 1987) sobre os atributos padronizados,
     * atualizando a silhueta média de cada grupo e retornando a silhueta global da partição.
     */
    public double calcularSilhueta(List<AvaliacaoProduto> elegiveis, List<GrupoProdutos> grupos) {
        if (elegiveis.size() < 2 || grupos.size() < 2) {
            return 0;
        }
        double[][] pontos = padronizar(extrairAtributos(elegiveis));
        Map<AvaliacaoProduto, Integer> grupoPorItem = new java.util.IdentityHashMap<>();
        for (int g = 0; g < grupos.size(); g++) {
            for (AvaliacaoProduto item : grupos.get(g).getItens()) {
                grupoPorItem.put(item, g);
            }
        }

        int n = elegiveis.size();
        int[] rotulo = new int[n];
        for (int i = 0; i < n; i++) {
            rotulo[i] = grupoPorItem.getOrDefault(elegiveis.get(i), 0);
        }

        double somaGlobal = 0;
        double[] somaGrupo = new double[grupos.size()];
        int[] tamGrupo = new int[grupos.size()];
        for (int r : rotulo) {
            tamGrupo[r]++;
        }

        for (int i = 0; i < n; i++) {
            int g = rotulo[i];
            if (tamGrupo[g] <= 1) {
                continue;
            }
            double[] somaDist = new double[grupos.size()];
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                somaDist[rotulo[j]] += Math.sqrt(distancia2(pontos[i], pontos[j]));
            }
            double a = somaDist[g] / (tamGrupo[g] - 1);
            double b = Double.MAX_VALUE;
            for (int outro = 0; outro < grupos.size(); outro++) {
                if (outro != g && tamGrupo[outro] > 0) {
                    b = Math.min(b, somaDist[outro] / tamGrupo[outro]);
                }
            }
            double denom = Math.max(a, b);
            double s = denom > 1e-12 ? (b - a) / denom : 0.0;
            somaGlobal += s;
            somaGrupo[g] += s;
        }

        for (int g = 0; g < grupos.size(); g++) {
            grupos.get(g).setSilhuetaMedia(tamGrupo[g] > 0 ? somaGrupo[g] / tamGrupo[g] : 0.0);
        }
        return somaGlobal / n;
    }

    private static double[][] extrairAtributos(List<AvaliacaoProduto> elegiveis) {
        double[][] dados = new double[elegiveis.size()][4];
        for (int i = 0; i < elegiveis.size(); i++) {
            AvaliacaoProduto avaliacao = elegiveis.get(i);
            dados[i][0] = avaliacao.getRetornoLiquido();
            dados[i][1] = avaliacao.getRisco();
            dados[i][2] = Math.log1p(avaliacao.getLiquidezDias());
            dados[i][3] = avaliacao.getCargaTributaria();
        }
        return dados;
    }

    private static double[][] padronizar(double[][] dados) {
        int colunas = dados[0].length;
        double[][] saida = new double[dados.length][colunas];
        for (int c = 0; c < colunas; c++) {
            double media = 0;
            for (double[] linha : dados) {
                media += linha[c];
            }
            media /= dados.length;
            double desvio = 0;
            for (double[] linha : dados) {
                desvio += Math.pow(linha[c] - media, 2);
            }
            desvio = Math.sqrt(desvio / Math.max(1, dados.length - 1));
            for (int i = 0; i < dados.length; i++) {
                saida[i][c] = desvio < 1e-9 ? 0 : (dados[i][c] - media) / desvio;
            }
        }
        return saida;
    }

    private static int[] kMeans(double[][] dados, int k, long semente) {
        Random random = new Random(semente);
        double[][] centroides = new double[k][dados[0].length];
        for (int c = 0; c < centroides[0].length; c++) {
            centroides[0][c] = dados[random.nextInt(dados.length)][c];
        }
        // k-médias++: escolhe centroides distantes entre si
        for (int grupo = 1; grupo < k; grupo++) {
            double[] distancias = new double[dados.length];
            double total = 0;
            for (int i = 0; i < dados.length; i++) {
                double menor = Double.MAX_VALUE;
                for (int g = 0; g < grupo; g++) {
                    menor = Math.min(menor, distancia2(dados[i], centroides[g]));
                }
                distancias[i] = menor;
                total += menor;
            }
            double alvo = random.nextDouble() * total;
            double acumulado = 0;
            int escolhido = 0;
            for (int i = 0; i < dados.length; i++) {
                acumulado += distancias[i];
                if (acumulado >= alvo) {
                    escolhido = i;
                    break;
                }
            }
            System.arraycopy(dados[escolhido], 0, centroides[grupo], 0, dados[0].length);
        }

        int[] rotulos = new int[dados.length];
        for (int iteracao = 0; iteracao < 200; iteracao++) {
            boolean mudou = false;
            for (int i = 0; i < dados.length; i++) {
                int melhor = 0;
                double menor = Double.MAX_VALUE;
                for (int g = 0; g < k; g++) {
                    double d = distancia2(dados[i], centroides[g]);
                    if (d < menor) {
                        menor = d;
                        melhor = g;
                    }
                }
                if (rotulos[i] != melhor) {
                    rotulos[i] = melhor;
                    mudou = true;
                }
            }
            for (int g = 0; g < k; g++) {
                double[] soma = new double[dados[0].length];
                int contagem = 0;
                for (int i = 0; i < dados.length; i++) {
                    if (rotulos[i] == g) {
                        for (int c = 0; c < soma.length; c++) {
                            soma[c] += dados[i][c];
                        }
                        contagem++;
                    }
                }
                if (contagem > 0) {
                    for (int c = 0; c < soma.length; c++) {
                        centroides[g][c] = soma[c] / contagem;
                    }
                }
            }
            if (!mudou) {
                break;
            }
        }
        return rotulos;
    }

    private static double distancia2(double[] a, double[] b) {
        double soma = 0;
        for (int i = 0; i < a.length; i++) {
            soma += Math.pow(a[i] - b[i], 2);
        }
        return soma;
    }

    /** Menor tamanho de grupo em uma partição. */
    private static int menorTamanho(int[] rotulos, int k) {
        int menor = Integer.MAX_VALUE;
        for (int g = 0; g < k; g++) {
            int contagem = 0;
            for (int rotulo : rotulos) {
                if (rotulo == g) {
                    contagem++;
                }
            }
            menor = Math.min(menor, contagem);
        }
        return menor;
    }

    /** Move itens de grupos com um único elemento para o centróide mais próximo. */
    private static int[] fundirSingletons(double[][] dados, int[] rotulos, int grupos) {
        int[] saida = rotulos.clone();
        for (int iteracao = 0; iteracao < grupos; iteracao++) {
            Map<Integer, Integer> contagem = new LinkedHashMap<>();
            for (int rotulo : saida) {
                contagem.merge(rotulo, 1, Integer::sum);
            }
            Integer solitario = contagem.entrySet().stream()
                    .filter(e -> e.getValue() == 1)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (solitario == null || contagem.size() <= 2) {
                break;
            }

            Map<Integer, double[]> centroides = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> entrada : contagem.entrySet()) {
                if (entrada.getKey().equals(solitario)) {
                    continue;
                }
                double[] soma = new double[dados[0].length];
                int quantidade = 0;
                for (int i = 0; i < dados.length; i++) {
                    if (saida[i] == entrada.getKey()) {
                        for (int c = 0; c < soma.length; c++) {
                            soma[c] += dados[i][c];
                        }
                        quantidade++;
                    }
                }
                for (int c = 0; c < soma.length; c++) {
                    soma[c] /= quantidade;
                }
                centroides.put(entrada.getKey(), soma);
            }

            int indice = -1;
            for (int i = 0; i < saida.length; i++) {
                if (saida[i] == solitario) {
                    indice = i;
                    break;
                }
            }
            if (indice < 0) {
                break;
            }

            int destino = -1;
            double menor = Double.MAX_VALUE;
            for (Map.Entry<Integer, double[]> entrada : centroides.entrySet()) {
                double distancia = distancia2(dados[indice], entrada.getValue());
                if (distancia < menor) {
                    menor = distancia;
                    destino = entrada.getKey();
                }
            }
            if (destino < 0) {
                break;
            }
            saida[indice] = destino;
        }
        return saida;
    }

    private static String nomearGrupo(List<AvaliacaoProduto> itens) {
        int total = itens.size();
        double carga = itens.stream()
                .filter(a -> a.getRetornoBruto() > 0)
                .mapToDouble(AvaliacaoProduto::getCargaTributaria)
                .average()
                .orElseGet(() -> itens.stream().mapToDouble(AvaliacaoProduto::getCargaTributaria).average().orElse(0));
        double risco = itens.stream().mapToInt(AvaliacaoProduto::getRisco).average().orElse(0);
        double liquidez = itens.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).average().orElse(0);
        long previdencia = itens.stream().filter(a -> a.getProduto().getTipo().isPrevidencia()).count();
        long isentos = itens.stream().filter(a -> a.getProduto().isIsentoIr()).count();
        long bancarios = itens.stream().filter(a -> a.getProduto().getTipo().isRendaFixaBancaria()).count();

        if (total >= 2 && isentos * 2 >= total) {
            return "Isentos de imposto de renda";
        }
        if (total >= 2 && previdencia * 2 > total) {
            return "Previdência";
        }
        if (risco >= 3.4) {
            return "Maior risco e retorno";
        }
        if (liquidez <= 7) {
            return "Liquidez imediata";
        }
        if (carga <= 12) {
            return "Tributação favorecida";
        }
        if (liquidez >= 60) {
            return "Longo prazo";
        }
        if (bancarios * 2 > total) {
            return "Renda fixa bancária";
        }
        return "Fundos de renda fixa";
    }

    private static String descreverGrupo(List<AvaliacaoProduto> itens) {
        double retorno = itens.stream().mapToDouble(AvaliacaoProduto::getRetornoLiquido).average().orElse(0);
        double risco = itens.stream().mapToInt(AvaliacaoProduto::getRisco).average().orElse(0);
        double liquidez = itens.stream().mapToInt(AvaliacaoProduto::getLiquidezDias).average().orElse(0);
        long previdencia = itens.stream().filter(a -> a.getProduto().getTipo().isPrevidencia()).count();
        long bancarios = itens.stream().filter(a -> a.getProduto().getTipo().isRendaFixaBancaria()).count();
        long fundos = itens.size() - previdencia - bancarios;
        return String.format("%d alternativas (%d fundos, %d previdência, %d CDB/LCI/LCA) · retorno líquido médio %.2f%% a.a. · risco médio %.1f · liquidez média %d dias",
                itens.size(), fundos, previdencia, bancarios, retorno, risco, Math.round(liquidez));
    }
}
