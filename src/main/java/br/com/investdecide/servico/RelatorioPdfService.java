package br.com.investdecide.servico;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.DecisaoService.Perturbacao;
import br.com.investdecide.servico.RelatorioTributarioService.LinhaTributaria;
import br.com.investdecide.servico.RelatorioTributarioService.Relatorio;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Relatórios executivos em PDF voltados para apresentação ao cliente, com identidade visual
 * institucional, quadros-resumo de KPIs e gráficos vetoriais nativos em OpenPDF.
 */
@Service
public class RelatorioPdfService {

    private static final Locale BR = new Locale("pt", "BR");

    private static final Color MARINHO = new Color(23, 41, 67);
    private static final Color AZUL = new Color(32, 60, 102);
    private static final Color MENTA = new Color(53, 185, 154);
    private static final Color MENTA_FUNDO = new Color(235, 249, 245);
    private static final Color AMBAR = new Color(234, 163, 97);
    private static final Color VIOLETA = new Color(135, 113, 207);
    private static final Color CORAL = new Color(204, 84, 84);
    private static final Color TINTA = new Color(29, 43, 64);
    private static final Color CINZA = new Color(110, 122, 135);
    private static final Color LINHA = new Color(224, 231, 240);
    private static final Color ZEBRA = new Color(248, 250, 253);
    private static final Color CARD_BG = new Color(243, 247, 252);
    private static final Color FUNDO_CABECALHO = new Color(236, 241, 248);

    private static final Font F_TITULO_BANNER = new Font(Font.HELVETICA, 16, Font.BOLD, Color.WHITE);
    private static final Font F_SUB_BANNER = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, new Color(203, 217, 235));
    private static final Font F_TAG_BANNER = new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(159, 231, 209));
    private static final Font F_KPI_LBL = new Font(Font.HELVETICA, 7f, Font.BOLD, CINZA);
    private static final Font F_KPI_VAL = new Font(Font.HELVETICA, 11f, Font.BOLD, MARINHO);
    private static final Font F_KPI_SUB = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, CINZA);
    private static final Font F_SECAO = new Font(Font.HELVETICA, 10.5f, Font.BOLD, MARINHO);
    private static final Font F_TEXTO = new Font(Font.HELVETICA, 8.8f, Font.NORMAL, new Color(45, 58, 74));
    private static final Font F_NOTA = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, CINZA);
    private static final Font F_CABECALHO = new Font(Font.HELVETICA, 7.3f, Font.BOLD, new Color(55, 70, 88));
    private static final Font F_CELULA = new Font(Font.HELVETICA, 7.8f, Font.NORMAL, new Color(45, 58, 74));
    private static final Font F_CELULA_FORTE = new Font(Font.HELVETICA, 7.8f, Font.BOLD, MARINHO);

    // ------------------------------------------------------------------ 1. comparativo

    public byte[] comparativo(ResultadoDecisao resultado) {
        Documento holder = novoDocumento("InvestDecide · Relatório Executivo de Alocação");
        Document doc = holder.doc();
        Cliente cli = resultado.getCliente();

        doc.add(banner(
                "ESTUDO COMPARATIVO DE ALTERNATIVAS",
                "Cliente: " + cli.getNome() + "  ·  Perfil " + cli.getPerfil().getRotulo()
                        + "  ·  Cenário " + resultado.getCenario().getNome(),
                "Emitido em " + hoje()
        ));

        AvaliacaoProduto lider = resultado.getRecomendada();
        doc.add(kpis(new String[][]{
                {"APORTE SIMULADO", moeda(cli.getAporte()), "Horizonte: " + cli.getHorizonteMeses() + " meses · D+" + cli.getLiquidezDias()},
                {"UNIVERSO ELEGÍVEL", resultado.getTotalElegiveis() + " de " + resultado.getTotalAlternativas(), "Após filtros de risco e liquidez"},
                {"1ª ALTERNATIVA (ÍNDICE)", lider != null ? numero(lider.getScore()) + " / 100" : "—", lider != null ? abrev(lider.getNome(), 28) : "Sem elegíveis"},
                {"SALDO LÍQUIDO PROJETADO", lider != null ? moeda(lider.getValorFinal()) : "—", lider != null ? numero(lider.getRetornoLiquido()) + "% a.a. líq." : ""}
        }));

        doc.add(secao("1. Contexto tributário e premissas da decisão"));
        doc.add(texto("Renda bruta anual: " + moeda(cli.getRendaBruta())
                + "  |  Deduções: " + moeda(cli.getDeducoes())
                + "  |  Base de cálculo: " + moeda(cli.getBaseCalculo())
                + "  |  Declaração: " + cli.getModelo().name().toLowerCase()
                + "  |  Regime previdência: " + cli.getRegime().name().toLowerCase() + "."));
        doc.add(texto(resultado.getPremissas()
                + ". Fundos de RF/multimercado não isentos incluem retenção semestral de come-cotas (15%)."));

        if (!resultado.getRanking().isEmpty()) {
            doc.add(secao("2. Comparativo gráfico das melhores alternativas (top 8)"));
            doc.add(caixaGrafico(graficoTop8(holder.writer().getDirectContent(), resultado.getRanking())));
            doc.add(nota("Barras em verde destacam a shortlist (top 3) recomendada para deliberação conjunta com o assessor."));

            doc.add(secao("3. Shortlist recomendada (top 3)"));
            List<String[]> top = new ArrayList<>();
            int limite = Math.min(3, resultado.getRanking().size());
            for (int i = 0; i < limite; i++) {
                AvaliacaoProduto item = resultado.getRanking().get(i);
                top.add(new String[]{
                        (i + 1) + "º",
                        item.getNome(),
                        item.getTipoRotulo(),
                        numero(item.getScore()) + " / 100",
                        numero(item.getRetornoLiquido()) + "% a.a.",
                        moeda(item.getValorFinal())
                });
            }
            doc.add(tabela(new String[]{"#", "Alternativa", "Classe / Modalidade", "Índice", "Retorno líq.", "Valor final"},
                    top, new float[]{5, 36, 17, 13, 13, 16}, true, true));

            doc.newPage();
            doc.add(secao("4. Evolução projetada do saldo líquido (top 3)"));
            doc.add(caixaGrafico(graficoEvolucaoTop3(holder.writer().getDirectContent(), resultado)));
            doc.add(nota("Projeção capitalizada mês a mês do aporte inicial até o valor líquido final estimado para as três melhores alternativas."));

            doc.add(secao("5. Justificativa e robustez da recomendação"));
            doc.add(texto("Justificativa da 1ª alternativa: " + lider.getJustificativa()));
            doc.add(texto("Ressalva ao cliente: " + lider.getRessalva()));
            doc.add(texto("Análise de robustez: " + resultado.getSensibilidade()));
        }

        doc.add(nota("Documento informativo gerado pelo InvestDecide a partir das bases públicas da CVM, BCB e portal XP. "
                + "Não substitui o Suitability nem constitui oferta pública de valores mobiliários."));
        return fechar(holder);
    }

    // ------------------------------------------------------------------ 2. tributacao

    public byte[] tributacao(Relatorio relatorio) {
        Documento holder = novoDocumento("InvestDecide · Estudo de Eficiência Tributária");
        Document doc = holder.doc();
        Cliente cli = relatorio.getCliente();

        doc.add(banner(
                "ESTUDO DE EFICIÊNCIA TRIBUTÁRIA",
                "Cliente: " + cli.getNome() + "  ·  Regime previdenciário " + cli.getRegime().name().toLowerCase()
                        + "  ·  Declaração " + cli.getModelo().name().toLowerCase(),
                "Emitido em " + hoje()
        ));

        doc.add(kpis(new String[][]{
                {"APORTE ANALISADO", moeda(cli.getAporte()), "Renda bruta: " + moeda(cli.getRendaBruta())},
                {"RENTABILIDADE BRUTA", numero(relatorio.getTaxaAnual()) + "% a.a.", "Horizonte: " + relatorio.getMeses() + " meses"},
                {"BENEFÍCIO PGBL (APORTE)", moeda(relatorio.getBeneficioPgbl()), "Limite de 12% na decl. completa"},
                {"GANHO DE REINVESTIR", moeda(relatorio.getGanhoReinvestimento()), "Restituição reaplicada no plano"}
        }));

        doc.add(secao("1. Comparativo visual de saldo líquido e custo tributário (R$)"));
        doc.add(caixaGrafico(graficoTributario(holder.writer().getDirectContent(), relatorio.getLinhas())));
        doc.add(nota("Compara o saldo líquido final obtido sob a mesma rentabilidade bruta, destacando a perda de capitalização do come-cotas no Fundo RF LP frente ao CDB."));

        doc.add(secao("2. Evolução projetada do saldo líquido ao longo do horizonte"));
        doc.add(caixaGrafico(graficoEvolucaoTributaria(holder.writer().getDirectContent(), relatorio)));
        doc.add(nota("Curvas traçadas do aporte inicial ao valor líquido final de cada veículo tributário ao longo dos " + relatorio.getMeses() + " meses."));

        doc.newPage();
        doc.add(secao("3. Tabela comparativa entre veículos tributários"));
        List<String[]> linhas = new ArrayList<>();
        for (LinhaTributaria linha : relatorio.getLinhas()) {
            linhas.add(new String[]{
                    linha.getNome(),
                    linha.getRegime(),
                    moeda(linha.getValorBruto()),
                    moeda(linha.getImposto()),
                    moeda(linha.getBeneficio()),
                    moeda(linha.getLiquidoSem()),
                    moeda(linha.getLiquidoCom())
            });
        }
        doc.add(tabela(new String[]{"Veículo", "Regime", "Valor bruto", "IR / Custo", "Benefício", "Sem reinvestir", "Com reinvestimento"},
                linhas, new float[]{22, 18, 12, 11, 10, 13.5f, 13.5f}, true, false));

        doc.add(secao("4. Análise do reinvestimento da restituição do PGBL"));
        doc.add(texto("Efeito líquido estimado: " + moeda(relatorio.getGanhoReinvestimento()) + ". "
                + relatorio.getExplicacaoReinvestimento()));
        doc.add(texto(relatorio.getConclusao()));

        doc.add(secao("5. Premissas regulatórias e notas técnicas"));
        doc.add(texto("• Come-cotas semestral: fundos abertos de renda fixa e multimercado não isentos sofrem antecipação de 15% a cada 6 meses, reduzindo a base capitalizada."));
        doc.add(texto("• PGBL: dedução limitada a 12% da renda bruta tributável para optantes pela declaração completa; no resgate, o IR incide sobre principal + rendimento."));
        doc.add(texto("• VGBL e LCI/LCA/FI-Infra: no VGBL o IR recai apenas sobre o rendimento; LCI/LCA e debêntures incentivadas de infraestrutura são isentas para pessoa física."));
        doc.add(nota("IOF desconsiderado em virtude do horizonte mínimo de 12 meses. Alíquotas baseadas na tabela IRPF vigente em 2026."));
        return fechar(holder);
    }

    // ------------------------------------------------------------------ 3. sensibilidade (foco em gráficos de cenários)

    public byte[] sensibilidade(List<ResultadoDecisao> porCenario, List<Perturbacao> perturbacoes) {
        Documento holder = novoDocumento("InvestDecide · Relatório de Cenários e Sensibilidade");
        Document doc = holder.doc();
        ResultadoDecisao base = porCenario.isEmpty() ? null : porCenario.get(0);
        Cliente cli = base != null ? base.getCliente() : null;
        AvaliacaoProduto liderBase = base != null ? base.getRecomendada() : null;

        doc.add(banner(
                "ANÁLISE DE CENÁRIOS E SENSIBILIDADE",
                cli != null
                        ? "Cliente: " + cli.getNome() + "  ·  Aporte: " + moeda(cli.getAporte()) + "  ·  Horizonte: " + cli.getHorizonteMeses() + " meses"
                        : "Comparativo de cenários econômicos",
                "Emitido em " + hoje()
        ));

        if (cli != null) {
            long mudancas = perturbacoes.stream().filter(Perturbacao::mudou).count();
            doc.add(kpis(new String[][]{
                    {"PERFIL DO CLIENTE", cli.getPerfil().getRotulo(), "Liquidez mín.: D+" + cli.getLiquidezDias()},
                    {"LÍDER NO CENÁRIO BASE", liderBase != null ? abrev(liderBase.getNome(), 26) : "—", liderBase != null ? moeda(liderBase.getValorFinal()) : ""},
                    {"CENÁRIOS AVALIADOS", porCenario.size() + " hipóteses macro", "Base · Otimista · Pessimista · Pers."},
                    {"ROBUSTEZ DOS PESOS", mudancas == 0 ? "Liderança estável" : mudancas + " troca(s) em +20%", "Perturbação individual nos 4 critérios"}
            }));
        }

        if (!porCenario.isEmpty()) {
            doc.add(secao("1. Visão gráfica dos cenários macroeconômicos (% a.a.)"));
            doc.add(caixaGrafico(graficoCenariosMacro(holder.writer().getDirectContent(), porCenario)));
            doc.add(nota("O cenário Base ancora-se na curva DI, IPCA implícito, dólar futuro e Ibovespa esperado; os demais aplicam choques configuráveis."));

            doc.add(secao("2. Impacto no patrimônio líquido final do cliente por cenário"));
            doc.add(caixaGrafico(graficoImpactoCenario(holder.writer().getDirectContent(), porCenario, base)));
            doc.add(nota("Cada barra mostra o patrimônio líquido final da alternativa líder daquele cenário (e sua rentabilidade líquida anual equivalente)."));

            doc.newPage();
            doc.add(secao("3. Evolução do saldo líquido ao longo do horizonte por cenário"));
            doc.add(caixaGrafico(graficoEvolucaoCenarios(holder.writer().getDirectContent(), porCenario, base)));
            doc.add(nota("Trajetória patrimonial projetada do aporte inicial até o horizonte final sob a alternativa líder de cada cenário econômico."));
        }

        doc.add(secao("4. Comparativo numérico entre os cenários econômicos"));
        List<String[]> linhas = new ArrayList<>();
        for (ResultadoDecisao resultado : porCenario) {
            AvaliacaoProduto lider = resultado.getRecomendada();
            linhas.add(new String[]{
                    resultado.getCenario().getNome(),
                    numero(resultado.getCenario().getCdi()) + "%",
                    numero(resultado.getCenario().getIpca()) + "%",
                    numero(resultado.getCenario().getDolar()) + "%",
                    numero(resultado.getCenario().getIbovespa()) + "%",
                    lider == null ? "—" : lider.getNome(),
                    lider == null ? "—" : numero(lider.getScore()),
                    lider == null ? "—" : moeda(lider.getValorFinal())
            });
        }
        doc.add(tabela(new String[]{"Cenário", "CDI", "IPCA", "Dólar", "Ibovespa", "Alternativa líder", "Índice", "Valor final"},
                linhas, new float[]{12, 8, 8, 8, 9, 29, 9, 17}, true, false));

        if (!porCenario.isEmpty() && porCenario.size() > 1 && liderBase != null) {
            doc.add(secao("5. Comportamento da alternativa líder do Base nos demais cenários"));
            List<String[]> valores = new ArrayList<>();
            for (ResultadoDecisao resultado : porCenario) {
                AvaliacaoProduto mesma = resultado.getAvaliacoes().stream()
                        .filter(a -> a.getProduto().getId().equals(liderBase.getProduto().getId()))
                        .findFirst().orElse(null);
                valores.add(new String[]{
                        resultado.getCenario().getNome(),
                        mesma == null ? "—" : numero(mesma.getRetornoLiquido()) + "% a.a.",
                        mesma == null ? "—" : moeda(valorBruto(base, mesma)),
                        mesma == null ? "—" : moeda(mesma.getValorFinal()),
                        mesma == null ? "—" : numero(mesma.getScore()) + " pts"
                });
            }
            doc.add(tabela(new String[]{"Cenário", "Retorno líq.", "Valor bruto proj.", "Valor líquido", "Índice"},
                    valores, new float[]{18, 18, 22, 22, 20}, true, false));
        }

        doc.add(secao("6. Sensibilidade aos pesos do modelo multicritério (+20% por critério)"));
        if (perturbacoes.isEmpty()) {
            doc.add(texto("Há apenas uma alternativa elegível; a variação de pesos não altera a liderança."));
        } else {
            List<String[]> pesos = new ArrayList<>();
            for (Perturbacao perturbacao : perturbacoes) {
                pesos.add(new String[]{
                        perturbacao.criterio() + " +20%",
                        perturbacao.lider(),
                        perturbacao.mudou() ? "Liderança alterada" : "Liderança mantida"
                });
            }
            doc.add(tabela(new String[]{"Perturbação aplicada", "Alternativa líder", "Efeito"},
                    pesos, new float[]{26, 44, 30}, true, false));
            boolean estavel = perturbacoes.stream().noneMatch(Perturbacao::mudou);
            doc.add(texto(estavel
                    ? "Recomendação robusta: nenhuma das perturbações individuais de +20% nos pesos altera a alternativa líder."
                    : "Recomendação sensível aos pesos: ao menos uma perturbação altera a alternativa líder — ponto importante de alinhamento com o cliente."));
        }

        doc.add(nota("Documento gerado pelo InvestDecide. Cenários representam testes de estresse e hipóteses analíticas, não garantias de retorno futuro."));
        return fechar(holder);
    }

    // ------------------------------------------------------------------ gráficos vetoriais OpenPDF

    private Image graficoTop8(PdfContentByte cb, List<AvaliacaoProduto> ranking) {
        int n = Math.min(8, ranking.size());
        float width = 500f;
        float height = 38f + n * 21f;
        PdfTemplate tpl = cb.createTemplate(width, height);
        try {
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            tpl.beginText();
            tpl.setFontAndSize(bfBold, 8.5f);
            tpl.setColorFill(MARINHO);
            tpl.showTextAligned(Element.ALIGN_LEFT, "Índice Multicritério SAW (0 a 100 pts) e Retorno Líquido Anual (% a.a.)", 12, height - 15, 0);
            tpl.endText();

            desenharLegendaItem(tpl, bf, width - 175, height - 15, MENTA, "Top 3 (Shortlist)");
            desenharLegendaItem(tpl, bf, width - 85, height - 15, AZUL, "4º ao 8º");

            float labelX = 185f;
            float barAreaW = width - labelX - 95f;
            double maxScore = Math.max(100.0, ranking.stream().limit(n).mapToDouble(AvaliacaoProduto::getScore).max().orElse(100.0));

            tpl.setLineWidth(0.4f);
            tpl.setColorStroke(LINHA);
            for (int tick = 0; tick <= 100; tick += 25) {
                float gx = labelX + (float) (tick / maxScore) * barAreaW;
                tpl.moveTo(gx, 14f);
                tpl.lineTo(gx, height - 26f);
                tpl.stroke();
                tpl.beginText();
                tpl.setFontAndSize(bf, 6.5f);
                tpl.setColorFill(CINZA);
                tpl.showTextAligned(Element.ALIGN_CENTER, tick + "", gx, 5f, 0);
                tpl.endText();
            }

            for (int i = 0; i < n; i++) {
                AvaliacaoProduto item = ranking.get(i);
                float yCenter = height - 38f - i * 21f;
                String nome = (i + 1) + ". " + abrev(item.getNome(), 33);

                tpl.beginText();
                tpl.setFontAndSize(i < 3 ? bfBold : bf, 7.4f);
                tpl.setColorFill(TINTA);
                tpl.showTextAligned(Element.ALIGN_LEFT, nome, 10f, yCenter - 2.5f, 0);
                tpl.endText();

                float barW = (float) (Math.max(0.0, item.getScore()) / maxScore) * barAreaW;
                tpl.setColorFill(i < 3 ? MENTA : AZUL);
                tpl.rectangle(labelX, yCenter - 6f, barW, 12f);
                tpl.fill();

                String valTxt = numero(item.getScore()) + " pts (" + numero(item.getRetornoLiquido()) + "%)";
                tpl.beginText();
                tpl.setFontAndSize(bfBold, 7.2f);
                tpl.setColorFill(MARINHO);
                tpl.showTextAligned(Element.ALIGN_LEFT, valTxt, labelX + barW + 5f, yCenter - 2.5f, 0);
                tpl.endText();
            }
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao desenhar gráfico Top 8 no PDF", e);
        }
    }

    private Image graficoTributario(PdfContentByte cb, List<LinhaTributaria> linhas) {
        int n = linhas.size();
        float width = 500f;
        float height = 56f + n * 28f;
        PdfTemplate tpl = cb.createTemplate(width, height);
        try {
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            tpl.beginText();
            tpl.setFontAndSize(bfBold, 8.5f);
            tpl.setColorFill(MARINHO);
            tpl.showTextAligned(Element.ALIGN_LEFT, "Comparativo Tributário em R$ (Saldo Líquido vs. IR / Custo de Come-Cotas)", 12, height - 14, 0);
            tpl.endText();

            desenharLegendaItem(tpl, bf, 14f, height - 28f, AZUL, "Líq. s/ reinvestir");
            desenharLegendaItem(tpl, bf, 118f, height - 28f, MENTA, "C/ reinvest. PGBL");
            desenharLegendaItem(tpl, bf, 224f, height - 28f, AMBAR, "IR / Custo come-cotas");

            float labelX = 168f;
            float barAreaW = width - labelX - 80f;
            double maxVal = linhas.stream()
                    .mapToDouble(l -> Math.max(l.getValorBruto(), Math.max(l.getLiquidoCom(), l.getLiquidoSem())))
                    .max().orElse(100_000.0);
            if (maxVal <= 0) maxVal = 1.0;

            for (int i = 0; i < n; i++) {
                LinhaTributaria l = linhas.get(i);
                float yTop = height - 48f - i * 28f;

                tpl.beginText();
                tpl.setFontAndSize(bfBold, 7.2f);
                tpl.setColorFill(TINTA);
                tpl.showTextAligned(Element.ALIGN_LEFT, abrev(l.getNome(), 34), 10f, yTop - 6f, 0);
                tpl.endText();

                float wLiq = (float) (Math.max(0.0, l.getLiquidoSem()) / maxVal) * barAreaW;
                tpl.setColorFill(AZUL);
                tpl.rectangle(labelX, yTop - 4f, wLiq, 7f);
                tpl.fill();

                if (Math.abs(l.getLiquidoCom() - l.getLiquidoSem()) > 1.0) {
                    float wCom = (float) (Math.max(0.0, l.getLiquidoCom()) / maxVal) * barAreaW;
                    tpl.setColorFill(MENTA);
                    tpl.rectangle(labelX, yTop - 12f, wCom, 6f);
                    tpl.fill();

                    tpl.beginText();
                    tpl.setFontAndSize(bfBold, 6.8f);
                    tpl.setColorFill(new Color(28, 128, 104));
                    tpl.showTextAligned(Element.ALIGN_LEFT, moeda(l.getLiquidoCom()) + " (c/ reinv.)", labelX + wCom + 4f, yTop - 11f, 0);
                    tpl.endText();
                } else if (l.getImposto() > 0) {
                    float wIr = Math.max(3f, (float) (l.getImposto() / maxVal) * barAreaW);
                    tpl.setColorFill(AMBAR);
                    tpl.rectangle(labelX, yTop - 12f, wIr, 6f);
                    tpl.fill();

                    tpl.beginText();
                    tpl.setFontAndSize(bf, 6.8f);
                    tpl.setColorFill(new Color(165, 103, 35));
                    tpl.showTextAligned(Element.ALIGN_LEFT, "IR/custo: " + moeda(l.getImposto()), labelX + wIr + 4f, yTop - 11f, 0);
                    tpl.endText();
                } else {
                    tpl.beginText();
                    tpl.setFontAndSize(bf, 6.8f);
                    tpl.setColorFill(new Color(28, 128, 104));
                    tpl.showTextAligned(Element.ALIGN_LEFT, "Isento de IR (IR: R$ 0,00)", labelX, yTop - 11f, 0);
                    tpl.endText();
                }

                tpl.beginText();
                tpl.setFontAndSize(bfBold, 7.0f);
                tpl.setColorFill(MARINHO);
                tpl.showTextAligned(Element.ALIGN_LEFT, moeda(l.getLiquidoSem()), labelX + wLiq + 4f, yTop - 3f, 0);
                tpl.endText();
            }
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao desenhar gráfico tributário no PDF", e);
        }
    }

    private Image graficoCenariosMacro(PdfContentByte cb, List<ResultadoDecisao> porCenario) {
        float width = 500f;
        float height = 165f;
        PdfTemplate tpl = cb.createTemplate(width, height);
        try {
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            tpl.beginText();
            tpl.setFontAndSize(bfBold, 8.5f);
            tpl.setColorFill(MARINHO);
            tpl.showTextAligned(Element.ALIGN_LEFT, "Premissas Macroeconômicas por Cenário (% a.a.)", 12, height - 15, 0);
            tpl.endText();

            desenharLegendaItem(tpl, bf, 275f, height - 15f, AZUL, "CDI");
            desenharLegendaItem(tpl, bf, 315f, height - 15f, AMBAR, "IPCA");
            desenharLegendaItem(tpl, bf, 362f, height - 15f, VIOLETA, "Dólar");
            desenharLegendaItem(tpl, bf, 412f, height - 15f, MENTA, "Ibovespa");

            double minVal = 0.0;
            double maxVal = 5.0;
            for (ResultadoDecisao r : porCenario) {
                Cenario c = r.getCenario();
                minVal = Math.min(minVal, Math.min(Math.min(c.getCdi(), c.getIpca()), Math.min(c.getDolar(), c.getIbovespa())));
                maxVal = Math.max(maxVal, Math.max(Math.max(c.getCdi(), c.getIpca()), Math.max(c.getDolar(), c.getIbovespa())));
            }
            minVal = Math.floor((minVal - 2.0) / 5.0) * 5.0;
            if (minVal > 0) minVal = 0.0;
            maxVal = Math.ceil((maxVal + 2.0) / 5.0) * 5.0;
            double span = Math.max(10.0, maxVal - minVal);

            float plotLeft = 42f;
            float plotRight = width - 16f;
            float plotBottom = 26f;
            float plotTop = height - 32f;
            float plotH = plotTop - plotBottom;
            float plotW = plotRight - plotLeft;
            float yZero = plotBottom + (float) ((0.0 - minVal) / span) * plotH;

            tpl.setLineWidth(0.4f);
            for (int v = (int) minVal; v <= (int) maxVal; v += 5) {
                float gy = plotBottom + (float) ((v - minVal) / span) * plotH;
                tpl.setColorStroke(v == 0 ? new Color(160, 174, 192) : LINHA);
                tpl.setLineWidth(v == 0 ? 0.8f : 0.4f);
                tpl.moveTo(plotLeft, gy);
                tpl.lineTo(plotRight, gy);
                tpl.stroke();

                tpl.beginText();
                tpl.setFontAndSize(bf, 6.8f);
                tpl.setColorFill(CINZA);
                tpl.showTextAligned(Element.ALIGN_RIGHT, v + "%", plotLeft - 5f, gy - 2.2f, 0);
                tpl.endText();
            }

            int nGrupos = porCenario.size();
            float grupoW = plotW / Math.max(1, nGrupos);
            float barW = Math.min(18f, (grupoW - 22f) / 4f);
            Color[] cores = {AZUL, AMBAR, VIOLETA, MENTA};

            for (int i = 0; i < nGrupos; i++) {
                Cenario c = porCenario.get(i).getCenario();
                double[] vals = {c.getCdi(), c.getIpca(), c.getDolar(), c.getIbovespa()};
                float xStart = plotLeft + i * grupoW + (grupoW - 4 * (barW + 2f)) / 2f;

                for (int j = 0; j < 4; j++) {
                    float bx = xStart + j * (barW + 2f);
                    float yVal = plotBottom + (float) ((vals[j] - minVal) / span) * plotH;
                    float bh = Math.abs(yVal - yZero);
                    float by = vals[j] >= 0 ? yZero : yVal;
                    tpl.setColorFill(cores[j]);
                    tpl.rectangle(bx, by, barW, Math.max(1.5f, bh));
                    tpl.fill();

                    float txtY = vals[j] >= 0 ? yVal + 3f : yVal - 8f;
                    tpl.beginText();
                    tpl.setFontAndSize(bfBold, 6.2f);
                    tpl.setColorFill(TINTA);
                    tpl.showTextAligned(Element.ALIGN_CENTER, String.format(BR, "%.1f", vals[j]), bx + barW / 2f, txtY, 0);
                    tpl.endText();
                }

                tpl.beginText();
                tpl.setFontAndSize(bfBold, 7.6f);
                tpl.setColorFill(MARINHO);
                tpl.showTextAligned(Element.ALIGN_CENTER, c.getNome(), plotLeft + i * grupoW + grupoW / 2f, 10f, 0);
                tpl.endText();
            }
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao desenhar gráfico de cenários macro no PDF", e);
        }
    }

    private Image graficoImpactoCenario(PdfContentByte cb, List<ResultadoDecisao> porCenario, ResultadoDecisao base) {
        int n = porCenario.size();
        float width = 500f;
        float height = 42f + n * 27f;
        PdfTemplate tpl = cb.createTemplate(width, height);
        try {
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            double aporte = base != null ? base.getCliente().getAporte() : 0.0;
            tpl.beginText();
            tpl.setFontAndSize(bfBold, 8.5f);
            tpl.setColorFill(MARINHO);
            tpl.showTextAligned(Element.ALIGN_LEFT, "Patrimônio Líquido Final do Líder por Cenário Econômico (R$)", 12, height - 15, 0);
            tpl.endText();

            if (aporte > 0) {
                tpl.beginText();
                tpl.setFontAndSize(bf, 7.2f);
                tpl.setColorFill(CINZA);
                tpl.showTextAligned(Element.ALIGN_RIGHT, "Aporte inicial: " + moeda(aporte), width - 10f, height - 15f, 0);
                tpl.endText();
            }

            float labelX = 110f;
            float barAreaW = width - labelX - 135f;
            double maxVal = porCenario.stream()
                    .mapToDouble(r -> r.getRecomendada() != null ? r.getRecomendada().getValorFinal() : 0.0)
                    .max().orElse(aporte > 0 ? aporte : 1.0);
            if (maxVal <= 0) maxVal = 1.0;

            Color[] coresCenario = {AZUL, MENTA, AMBAR, VIOLETA};
            for (int i = 0; i < n; i++) {
                ResultadoDecisao r = porCenario.get(i);
                AvaliacaoProduto lider = r.getRecomendada();
                float yCenter = height - 40f - i * 27f;

                tpl.beginText();
                tpl.setFontAndSize(bfBold, 7.8f);
                tpl.setColorFill(MARINHO);
                tpl.showTextAligned(Element.ALIGN_LEFT, r.getCenario().getNome(), 12f, yCenter + 1f, 0);
                tpl.endText();

                tpl.beginText();
                tpl.setFontAndSize(bf, 6.7f);
                tpl.setColorFill(CINZA);
                tpl.showTextAligned(Element.ALIGN_LEFT, lider != null ? abrev(lider.getNome(), 20) : "—", 12f, yCenter - 8f, 0);
                tpl.endText();

                double val = lider != null ? lider.getValorFinal() : 0.0;
                double ret = lider != null ? lider.getRetornoLiquido() : 0.0;
                float barW = (float) (Math.max(0.0, val) / maxVal) * barAreaW;

                tpl.setColorFill(coresCenario[i % coresCenario.length]);
                tpl.rectangle(labelX, yCenter - 6f, barW, 13f);
                tpl.fill();

                tpl.beginText();
                tpl.setFontAndSize(bfBold, 7.4f);
                tpl.setColorFill(MARINHO);
                tpl.showTextAligned(Element.ALIGN_LEFT, moeda(val) + " (" + numero(ret) + "% a.a.)", labelX + barW + 6f, yCenter - 2.5f, 0);
                tpl.endText();
            }

            if (aporte > 0 && aporte <= maxVal) {
                float xAporte = labelX + (float) (aporte / maxVal) * barAreaW;
                tpl.setColorStroke(new Color(148, 163, 184));
                tpl.setLineWidth(0.9f);
                tpl.setLineDash(3f, 2f, 0f);
                tpl.moveTo(xAporte, 10f);
                tpl.lineTo(xAporte, height - 26f);
                tpl.stroke();
                tpl.setLineDash(0f);
            }
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao desenhar gráfico de impacto por cenário no PDF", e);
        }
    }

    private record SerieEvolucao(String rotulo, double valorFinal, Color cor) {
    }

    private Image graficoEvolucaoTop3(PdfContentByte cb, ResultadoDecisao resultado) {
        double aporte = resultado.getCliente() != null ? resultado.getCliente().getAporte() : 0.0;
        int meses = resultado.getCliente() != null ? resultado.getCliente().getHorizonteMeses() : 36;
        Color[] cores = {MENTA, AZUL, VIOLETA};
        List<SerieEvolucao> series = new ArrayList<>();
        List<AvaliacaoProduto> rank = resultado.getRanking();
        int limite = Math.min(3, rank.size());
        for (int i = 0; i < limite; i++) {
            AvaliacaoProduto item = rank.get(i);
            series.add(new SerieEvolucao((i + 1) + "º " + abrev(item.getNome(), 22),
                    item.getValorFinal(), cores[i % cores.length]));
        }
        return graficoEvolucaoSaldo(cb, "Projeção de Saldo Líquido no Tempo — Top 3 Alternativas (R$)",
                aporte, meses, series);
    }

    private Image graficoEvolucaoTributaria(PdfContentByte cb, Relatorio relatorio) {
        Cliente cli = relatorio.getCliente();
        double aporte = cli != null ? cli.getAporte() : 0.0;
        Color[] paleta = {AZUL, AMBAR, MENTA, CORAL, VIOLETA};
        Color pgblReinvCor = new Color(20, 128, 102);
        List<SerieEvolucao> series = new ArrayList<>();
        List<LinhaTributaria> linhas = relatorio.getLinhas();
        for (int i = 0; i < linhas.size(); i++) {
            LinhaTributaria l = linhas.get(i);
            String nome = l.getNome();
            if (nome.startsWith("CDB")) {
                series.add(new SerieEvolucao("CDB (s/ come-cotas)", l.getLiquidoSem(), paleta[i % paleta.length]));
            } else if (nome.startsWith("Fundo RF")) {
                series.add(new SerieEvolucao("Fundo RF (c/ come-cotas)", l.getLiquidoSem(), paleta[i % paleta.length]));
            } else if (nome.startsWith("LCI")) {
                series.add(new SerieEvolucao("LCI/LCA / FI-Infra", l.getLiquidoSem(), paleta[i % paleta.length]));
            } else if ("PGBL".equals(nome)) {
                series.add(new SerieEvolucao("PGBL (s/ reinv.)", l.getLiquidoSem(), paleta[i % paleta.length]));
                series.add(new SerieEvolucao("PGBL (c/ reinvest.)", l.getLiquidoCom(), pgblReinvCor));
            } else {
                series.add(new SerieEvolucao(nome, l.getLiquidoSem(), paleta[i % paleta.length]));
            }
        }
        return graficoEvolucaoSaldo(cb, "Evolução do Saldo Líquido por Veículo Tributário (R$)",
                aporte, relatorio.getMeses(), series);
    }

    private Image graficoEvolucaoCenarios(PdfContentByte cb, List<ResultadoDecisao> porCenario, ResultadoDecisao base) {
        double aporte = base != null ? base.getCliente().getAporte() : 0.0;
        int meses = base != null ? base.getCliente().getHorizonteMeses() : 36;
        Color[] cores = {AZUL, MENTA, AMBAR, VIOLETA};
        List<SerieEvolucao> series = new ArrayList<>();
        for (int i = 0; i < porCenario.size(); i++) {
            ResultadoDecisao r = porCenario.get(i);
            double vf = r.getRecomendada() != null ? r.getRecomendada().getValorFinal() : aporte;
            series.add(new SerieEvolucao(r.getCenario().getNome(), vf, cores[i % cores.length]));
        }
        return graficoEvolucaoSaldo(cb, "Evolução Projetada do Saldo Líquido por Cenário (R$)",
                aporte, meses, series);
    }

    private Image graficoEvolucaoSaldo(PdfContentByte cb, String titulo, double aporte, int meses,
                                       List<SerieEvolucao> series) {
        float width = 500f;
        boolean duasLinhasLegenda = series.size() > 4;
        float height = duasLinhasLegenda ? 192f : 176f;
        PdfTemplate tpl = cb.createTemplate(width, height);
        try {
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            tpl.beginText();
            tpl.setFontAndSize(bfBold, 8.5f);
            tpl.setColorFill(MARINHO);
            tpl.showTextAligned(Element.ALIGN_LEFT, titulo, 12f, height - 14f, 0);
            tpl.endText();

            if (!duasLinhasLegenda && !series.isEmpty()) {
                float colW = (width - 28f) / series.size();
                for (int i = 0; i < series.size(); i++) {
                    desenharLegendaItem(tpl, bf, 14f + i * colW, height - 28f, series.get(i).cor(), series.get(i).rotulo());
                }
            } else {
                for (int i = 0; i < series.size(); i++) {
                    int row = i / 3;
                    int col = i % 3;
                    float lx = 14f + col * 164f;
                    float ly = height - (row == 0 ? 28f : 40f);
                    desenharLegendaItem(tpl, bf, lx, ly, series.get(i).cor(), series.get(i).rotulo());
                }
            }

            double minVal = aporte > 0 ? aporte : Double.MAX_VALUE;
            double maxVal = aporte;
            for (SerieEvolucao s : series) {
                minVal = Math.min(minVal, s.valorFinal());
                maxVal = Math.max(maxVal, s.valorFinal());
            }
            if (minVal == Double.MAX_VALUE) minVal = 0.0;
            if (maxVal <= minVal) maxVal = minVal + 10_000.0;

            double step = passoBonito((maxVal - minVal) / 4.0);
            double axisMin = Math.floor(minVal / step) * step;
            double axisMax = Math.ceil((maxVal * 1.01) / step) * step;
            if (axisMax <= axisMin) axisMax = axisMin + step * 4;
            double span = axisMax - axisMin;

            float plotLeft = 64f;
            float plotRight = width - 18f;
            float plotBottom = 26f;
            float plotTop = height - (duasLinhasLegenda ? 54f : 42f);
            float plotW = plotRight - plotLeft;
            float plotH = plotTop - plotBottom;

            tpl.setLineWidth(0.4f);
            for (double v = axisMin; v <= axisMax + step * 0.01; v += step) {
                float gy = plotBottom + (float) ((v - axisMin) / span) * plotH;
                tpl.setColorStroke(LINHA);
                tpl.moveTo(plotLeft, gy);
                tpl.lineTo(plotRight, gy);
                tpl.stroke();

                tpl.beginText();
                tpl.setFontAndSize(bf, 6.6f);
                tpl.setColorFill(CINZA);
                String lbl = v >= 1_000_000.0
                        ? String.format(BR, "R$ %.1fM", v / 1_000_000.0)
                        : (v >= 1_000.0 ? String.format(BR, "R$ %.0fk", v / 1_000.0) : moeda(v));
                tpl.showTextAligned(Element.ALIGN_RIGHT, lbl, plotLeft - 6f, gy - 2.2f, 0);
                tpl.endText();
            }

            int passos = 6;
            int horizonte = Math.max(1, meses);
            for (int t = 0; t <= passos; t++) {
                float gx = plotLeft + (t / (float) passos) * plotW;
                int mes = (int) Math.round(horizonte * (t / (double) passos));
                tpl.beginText();
                tpl.setFontAndSize(bf, 6.8f);
                tpl.setColorFill(CINZA);
                tpl.showTextAligned(Element.ALIGN_CENTER, mes == 0 ? "Início" : "Mês " + mes, gx, 11f, 0);
                tpl.endText();
            }

            for (SerieEvolucao s : series) {
                tpl.setColorStroke(s.cor());
                tpl.setLineWidth(1.6f);
                float[] xs = new float[passos + 1];
                float[] ys = new float[passos + 1];
                for (int t = 0; t <= passos; t++) {
                    double frac = t / (double) passos;
                    double val = (aporte > 0 && s.valorFinal() > 0)
                            ? aporte * Math.pow(s.valorFinal() / aporte, frac)
                            : aporte + (s.valorFinal() - aporte) * frac;
                    xs[t] = plotLeft + (float) frac * plotW;
                    ys[t] = plotBottom + (float) ((val - axisMin) / span) * plotH;
                    if (t == 0) tpl.moveTo(xs[t], ys[t]);
                    else tpl.lineTo(xs[t], ys[t]);
                }
                tpl.stroke();

                tpl.setColorFill(s.cor());
                for (int t = 0; t <= passos; t++) {
                    tpl.circle(xs[t], ys[t], t == passos ? 2.5f : 1.9f);
                    tpl.fill();
                }
            }
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao desenhar gráfico de evolução de saldo no PDF", e);
        }
    }

    private static double passoBonito(double bruto) {
        double v = Math.max(1.0, bruto);
        double exp = Math.pow(10.0, Math.floor(Math.log10(v)));
        double norm = v / exp;
        double mult;
        if (norm <= 1.2) mult = 1.0;
        else if (norm <= 2.5) mult = 2.0;
        else if (norm <= 5.5) mult = 5.0;
        else mult = 10.0;
        return mult * exp;
    }

    private static void desenharLegendaItem(PdfTemplate tpl, BaseFont bf, float x, float y, Color cor, String label) {
        tpl.setColorFill(cor);
        tpl.rectangle(x, y - 0.5f, 7f, 7f);
        tpl.fill();
        tpl.beginText();
        tpl.setFontAndSize(bf, 7f);
        tpl.setColorFill(CINZA);
        tpl.showTextAligned(Element.ALIGN_LEFT, label, x + 10f, y, 0);
        tpl.endText();
    }

    // ------------------------------------------------------------------ layout executivo

    private record Documento(Document doc, PdfWriter writer, ByteArrayOutputStream saida) {
    }

    private Documento novoDocumento(String rodape) {
        Document doc = new Document(PageSize.A4, 38, 38, 36, 38);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(doc, saida);
        writer.setPageEvent(new Rodape(rodape));
        doc.open();
        return new Documento(doc, writer, saida);
    }

    private static byte[] fechar(Documento holder) {
        holder.doc().close();
        return holder.saida().toByteArray();
    }

    private static PdfPTable banner(String titulo, String subtitulo, String direita) {
        PdfPTable tbl = new PdfPTable(new float[]{72, 28});
        tbl.setWidthPercentage(100);
        tbl.setSpacingAfter(10);

        Paragraph pEsq = new Paragraph();
        pEsq.add(new Phrase(titulo + "\n", F_TITULO_BANNER));
        pEsq.add(new Phrase(subtitulo, F_SUB_BANNER));

        PdfPCell cEsq = new PdfPCell(pEsq);
        cEsq.setBackgroundColor(MARINHO);
        cEsq.setBorder(Rectangle.BOTTOM);
        cEsq.setBorderColorBottom(MENTA);
        cEsq.setBorderWidthBottom(2.5f);
        cEsq.setPadding(12);
        cEsq.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph pDir = new Paragraph();
        pDir.setAlignment(Element.ALIGN_RIGHT);
        pDir.add(new Phrase("INVESTDECIDE · SAD\n", F_TAG_BANNER));
        pDir.add(new Phrase(direita, F_SUB_BANNER));

        PdfPCell cDir = new PdfPCell(pDir);
        cDir.setBackgroundColor(MARINHO);
        cDir.setBorder(Rectangle.BOTTOM);
        cDir.setBorderColorBottom(MENTA);
        cDir.setBorderWidthBottom(2.5f);
        cDir.setPadding(12);
        cDir.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cDir.setVerticalAlignment(Element.ALIGN_MIDDLE);

        tbl.addCell(cEsq);
        tbl.addCell(cDir);
        return tbl;
    }

    private static PdfPTable kpis(String[][] cards) {
        PdfPTable tbl = new PdfPTable(cards.length);
        tbl.setWidthPercentage(100);
        tbl.setSpacingAfter(10);
        for (String[] card : cards) {
            Paragraph p = new Paragraph();
            p.setLeading(12.5f);
            p.add(new Phrase(card[0] + "\n", F_KPI_LBL));
            p.add(new Phrase(card[1] + "\n", F_KPI_VAL));
            p.add(new Phrase(card[2], F_KPI_SUB));

            PdfPCell c = new PdfPCell(p);
            c.setBackgroundColor(CARD_BG);
            c.setBorderColor(LINHA);
            c.setBorderWidth(0.75f);
            c.setPadding(8);
            tbl.addCell(c);
        }
        return tbl;
    }

    private static PdfPTable caixaGrafico(Image imagem) {
        imagem.scaleToFit(505f, 420f);
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        box.setKeepTogether(true);
        box.setSpacingBefore(3);
        box.setSpacingAfter(6);

        PdfPCell c = new PdfPCell(imagem, true);
        c.setBackgroundColor(Color.WHITE);
        c.setBorderColor(LINHA);
        c.setBorderWidth(0.8f);
        c.setPadding(6);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        box.addCell(c);
        return box;
    }

    private static Paragraph secao(String texto) {
        Paragraph p = new Paragraph(texto, F_SECAO);
        p.setSpacingBefore(9);
        p.setSpacingAfter(4);
        return p;
    }

    private static Paragraph texto(String texto) {
        Paragraph p = new Paragraph(texto, F_TEXTO);
        p.setSpacingAfter(4);
        p.setLeading(12);
        return p;
    }

    private static Paragraph nota(String texto) {
        Paragraph p = new Paragraph(texto, F_NOTA);
        p.setSpacingBefore(3);
        p.setSpacingAfter(5);
        p.setLeading(10);
        return p;
    }

    private static PdfPTable tabela(String[] cabecalho, List<String[]> linhas, float[] larguras,
                                    boolean manterJunto, boolean destacarTop3) {
        PdfPTable tabela = new PdfPTable(larguras.length);
        tabela.setKeepTogether(manterJunto);
        try {
            tabela.setWidths(larguras);
        } catch (Exception e) {
            tabela.setWidthPercentage(100);
        }
        tabela.setWidthPercentage(100);
        tabela.setSpacingBefore(2);
        tabela.setSpacingAfter(7);
        tabela.setHeaderRows(1);

        for (String titulo : cabecalho) {
            PdfPCell celula = new PdfPCell(new Phrase(titulo, F_CABECALHO));
            celula.setBackgroundColor(FUNDO_CABECALHO);
            celula.setBorderColor(LINHA);
            celula.setPadding(4.5f);
            celula.setHorizontalAlignment(Element.ALIGN_LEFT);
            tabela.addCell(celula);
        }
        for (int r = 0; r < linhas.size(); r++) {
            String[] linha = linhas.get(r);
            Color bg = (destacarTop3 && r < 3) ? MENTA_FUNDO : (r % 2 == 1 ? ZEBRA : Color.WHITE);
            for (int c = 0; c < linha.length; c++) {
                PdfPCell celula = new PdfPCell(new Phrase(linha[c] == null ? "" : linha[c],
                        (c == 0 || (destacarTop3 && r == 0)) ? F_CELULA_FORTE : F_CELULA));
                celula.setBackgroundColor(bg);
                celula.setBorderColor(LINHA);
                celula.setPadding(4.2f);
                celula.setVerticalAlignment(Element.ALIGN_MIDDLE);
                tabela.addCell(celula);
            }
        }
        return tabela;
    }

    /** Rodapé com identificação e numeração de páginas. */
    private static final class Rodape extends PdfPageEventHelper {
        private final String texto;

        Rodape(String texto) {
            this.texto = texto;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(texto, F_NOTA), 38, 22, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Página " + writer.getPageNumber(), F_NOTA),
                    PageSize.A4.getWidth() - 38, 22, 0);
        }
    }

    // ------------------------------------------------------------------ formato

    private static String moeda(double valor) {
        return NumberFormat.getCurrencyInstance(BR).format(valor);
    }

    private static String numero(double valor) {
        return String.format(BR, "%.2f", valor);
    }

    private static String hoje() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private static String abrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double valorBruto(ResultadoDecisao resultado, AvaliacaoProduto avaliacao) {
        double anos = resultado.getCliente().getHorizonteMeses() / 12.0;
        return resultado.getCliente().getAporte() * Math.pow(1 + avaliacao.getRetornoBruto() / 100, anos);
    }
}
