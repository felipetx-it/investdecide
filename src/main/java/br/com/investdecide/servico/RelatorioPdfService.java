package br.com.investdecide.servico;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.GrupoProdutos;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.DecisaoService.Perturbacao;
import br.com.investdecide.servico.RelatorioTributarioService.LinhaTributaria;
import br.com.investdecide.servico.RelatorioTributarioService.Relatorio;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
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
 * Relatórios em PDF gerados com OpenPDF.
 *
 * <p>Três documentos: comparativo de alternativas, eficiência tributária e sensibilidade
 * (cenários e pesos). Todos carregam as premissas e a data de emissão, para que a decisão
 * possa ser auditada depois.</p>
 */
@Service
public class RelatorioPdfService {

    private static final Locale BR = new Locale("pt", "BR");
    private static final Color TINTA = new Color(29, 43, 64);
    private static final Color CINZA = new Color(110, 122, 135);
    private static final Color LINHA = new Color(226, 232, 240);
    private static final Color FUNDO_CABECALHO = new Color(238, 242, 247);

    private static final Font F_TITULO = new Font(Font.HELVETICA, 18, Font.BOLD, TINTA);
    private static final Font F_SUBTITULO = new Font(Font.HELVETICA, 9, Font.NORMAL, CINZA);
    private static final Font F_SECAO = new Font(Font.HELVETICA, 11, Font.BOLD, TINTA);
    private static final Font F_TEXTO = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(45, 58, 74));
    private static final Font F_NOTA = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, CINZA);
    private static final Font F_CABECALHO = new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(70, 82, 96));
    private static final Font F_CELULA = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(45, 58, 74));
    private static final Font F_CELULA_FORTE = new Font(Font.HELVETICA, 8, Font.BOLD, TINTA);

    // ------------------------------------------------------------------ documentos

    public byte[] comparativo(ResultadoDecisao resultado) {
        Documento holder = novoDocumento("InvestDecide · comparativo de alternativas");
        Document doc = holder.doc();
        doc.add(new Paragraph("Comparativo de alternativas", F_TITULO));
        doc.add(subtitulo("Cliente: " + resultado.getCliente().getNome()
                + "   ·   Aporte: " + moeda(resultado.getCliente().getAporte())
                + "   ·   Horizonte: " + resultado.getCliente().getHorizonteMeses() + " meses"));
        doc.add(subtitulo("Cenário: " + resultado.getCenario().getNome()
                + "   ·   Emitido em: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        doc.add(subtitulo("Renda bruta: " + moeda(resultado.getCliente().getRendaBruta())
                + "   ·   Deduções: " + moeda(resultado.getCliente().getDeducoes())
                + "   ·   Base: " + moeda(resultado.getCliente().getBaseCalculo())
                + "   ·   Declaração: " + resultado.getCliente().getModelo().name().toLowerCase()
                + "   ·   Regime: " + resultado.getCliente().getRegime().name().toLowerCase()));

        doc.add(secao("Premissas do cálculo"));
        doc.add(texto(resultado.getPremissas()));
        doc.add(texto("Alternativas eliminadas por restrição: "
                + (resultado.getTotalAlternativas() - resultado.getTotalElegiveis())
                + " de " + resultado.getTotalAlternativas() + ". Foram aplicados os critérios de risco, liquidez, "
                + "carência e aporte mínimo antes do cálculo do índice."));

        doc.add(secao("Alternativas recomendadas (top 3)"));
        List<String[]> top = new ArrayList<>();
        int limite = Math.min(3, resultado.getRanking().size());
        for (int i = 0; i < limite; i++) {
            AvaliacaoProduto item = resultado.getRanking().get(i);
            top.add(new String[]{
                    (i + 1) + "º",
                    item.getNome(),
                    numero(item.getScore()) + " / 100",
                    numero(item.getRetornoLiquido()) + "% a.a.",
                    moeda(item.getValorFinal())
            });
        }
        doc.add(tabela(new String[]{"#", "Alternativa", "Índice", "Retorno líq.", "Valor final"},
                top, new float[]{6, 40, 15, 17, 22}, true));
        doc.add(nota("O sistema apresenta um conjunto curto de alternativas para apoiar a decisão; "
                + "não elege uma única opção nem substitui o julgamento do profissional."));

        doc.add(secao("Alternativas por grupo"));
        List<String[]> grupos = new ArrayList<>();
        for (GrupoProdutos grupo : resultado.getGrupos()) {
            grupos.add(new String[]{
                    grupo.getNome(),
                    String.valueOf(grupo.getQuantidade()),
                    numero(grupo.getRetornoMedio()) + "%",
                    numero(grupo.getRiscoMedio()),
                    numero(grupo.getCargaMedia()) + "%"
            });
        }
        doc.add(tabela(new String[]{"Grupo", "Alternativas", "Retorno médio", "Risco médio", "Carga média"},
                grupos, new float[]{44, 14, 16, 13, 13}, true));

        doc.add(secao("Classificação completa das alternativas elegíveis"));
        List<String[]> linhas = new ArrayList<>();
        for (AvaliacaoProduto item : resultado.getRanking()) {
            linhas.add(new String[]{
                    String.valueOf(item.getPosicao()),
                    item.getNome(),
                    item.getGrupo(),
                    numero(item.getRetornoLiquido()) + "%",
                    String.valueOf(item.getRisco()),
                    item.getLiquidezDias() == 0 ? "D+0" : "D+" + item.getLiquidezDias(),
                    numero(item.getCargaTributaria()) + "%",
                    numero(item.getScore()),
                    moeda(item.getValorFinal())
            });
        }
        doc.add(tabela(new String[]{"#", "Alternativa", "Grupo", "Retorno", "Risco", "Liquidez", "Carga", "Índice", "Valor final"},
                linhas, new float[]{5, 27, 17, 10, 7, 9, 8, 8, 13}));

        if (resultado.getRanking().size() > 0) {
            doc.add(secao("Justificativa e robustez"));
            doc.add(texto("Justificativa da 1ª opção: " + resultado.getRanking().get(0).getJustificativa()));
            doc.add(texto("Ressalva: " + resultado.getRanking().get(0).getRessalva()));
            doc.add(texto("Sensibilidade dos pesos: " + resultado.getSensibilidade()));
        }

        doc.add(nota("Documento gerado pelo InvestDecide a partir da base pública da CVM. "
                + "Não constitui recomendação de investimento."));
        return fechar(holder);
    }

    public byte[] tributacao(Relatorio relatorio) {
        Documento holder = novoDocumento("InvestDecide · eficiência tributária");
        Document doc = holder.doc();
        doc.add(new Paragraph("Eficiência tributária", F_TITULO));
        doc.add(subtitulo("Cliente: " + relatorio.getCliente().getNome()
                + "   ·   Renda bruta: " + moeda(relatorio.getCliente().getRendaBruta())
                + "   ·   Deduções: " + moeda(relatorio.getCliente().getDeducoes())));
        doc.add(subtitulo("Aporte: " + moeda(relatorio.getCliente().getAporte())
                + "   ·   Rentabilidade bruta: " + numero(relatorio.getTaxaAnual()) + "% a.a."
                + "   ·   Horizonte: " + relatorio.getMeses() + " meses"
                + "   ·   Emitido em: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));

        doc.add(secao("Comparativo entre veículos"));
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
        doc.add(tabela(new String[]{"Veículo", "Regime", "Valor bruto", "IR", "Benefício", "Sem reinvestir", "Com reinvestimento"},
                linhas, new float[]{20, 18, 13, 11, 11, 13, 14}));

        doc.add(secao("Efeito de reinvestir a restituição do PGBL"));
        doc.add(texto("Resultado: " + moeda(relatorio.getGanhoReinvestimento()) + ". "
                + relatorio.getExplicacaoReinvestimento()));
        doc.add(texto(relatorio.getConclusao()));

        doc.add(secao("Premissas e limitações"));
        doc.add(texto("PGBL: dedução limitada a 12% da renda bruta tributável, restrita ao modelo completo; "
                + "no resgate o IR incide sobre principal e rendimento."));
        doc.add(texto("VGBL: IR apenas sobre o rendimento. Fundos e CDB: tabela regressiva. "
                + "LCI/LCA: isentos para pessoa física."));
        doc.add(texto("Come-cotas não modelado; IOF ignorado (horizonte mínimo de 12 meses); "
                + "tabela de IRPF parametrizada e sujeita a atualização anual."));
        doc.add(nota("Documento gerado pelo InvestDecide. Não constitui recomendação de investimento."));
        return fechar(holder);
    }

    /**
     * Relatório de sensibilidade: como a liderança muda entre cenários e quando os pesos variam.
     */
    public byte[] sensibilidade(List<ResultadoDecisao> porCenario, List<Perturbacao> perturbacoes) {
        Documento holder = novoDocumento("InvestDecide · sensibilidade dos cenários");
        Document doc = holder.doc();
        ResultadoDecisao base = porCenario.isEmpty() ? null : porCenario.get(0);

        doc.add(new Paragraph("Sensibilidade dos cenários", F_TITULO));
        if (base != null) {
            doc.add(subtitulo("Cliente: " + base.getCliente().getNome()
                    + "   ·   Aporte: " + moeda(base.getCliente().getAporte())
                    + "   ·   Horizonte: " + base.getCliente().getHorizonteMeses() + " meses"));
        }
        doc.add(subtitulo("Emitido em: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));

        doc.add(secao("Como o resultado muda com o cenário econômico"));
        List<String[]> linhas = new ArrayList<>();
        for (ResultadoDecisao resultado : porCenario) {
            AvaliacaoProduto lider = resultado.getRecomendada();
            linhas.add(new String[]{
                    resultado.getCenario().getNome(),
                    numero(resultado.getCenario().getCdi()) + "%",
                    numero(resultado.getCenario().getIpca()) + "%",
                    numero(resultado.getCenario().getDolar()) + "%",
                    lider == null ? "—" : lider.getNome(),
                    lider == null ? "—" : numero(lider.getScore()),
                    lider == null ? "—" : moeda(lider.getValorFinal())
            });
        }
        doc.add(tabela(new String[]{"Cenário", "CDI", "IPCA", "Dólar", "Alternativa líder", "Índice", "Valor final"},
                linhas, new float[]{12, 9, 9, 9, 30, 9, 16}, true));
        doc.add(texto("O cenário base é montado a partir dos indicadores de mercado (curva DI, inflação "
                + "implícita, dólar futuro e Ibovespa esperado). Os cenários otimista, pessimista e "
                + "personalizado aplicam choques configuráveis sobre essa base."));

        if (!porCenario.isEmpty() && porCenario.size() > 1) {
            doc.add(secao("Efeito no valor final da alternativa líder do cenário base"));
            AvaliacaoProduto liderBase = base == null ? null : base.getRecomendada();
            if (liderBase != null) {
                List<String[]> valores = new ArrayList<>();
                for (ResultadoDecisao resultado : porCenario) {
                    AvaliacaoProduto mesma = resultado.getAvaliacoes().stream()
                            .filter(a -> a.getProduto().getId().equals(liderBase.getProduto().getId()))
                            .findFirst().orElse(null);
                    valores.add(new String[]{
                            resultado.getCenario().getNome(),
                            mesma == null ? "—" : moeda(valorBruto(base, mesma)),
                            mesma == null ? "—" : moeda(mesma.getValorFinal()),
                            mesma == null ? "—" : numero(mesma.getScore())
                    });
                }
                doc.add(tabela(new String[]{"Cenário", "Valor bruto projetado", "Valor líquido", "Índice"},
                        valores, new float[]{22, 26, 26, 26}, true));
            }
        }

        doc.add(secao("Sensibilidade aos pesos do índice"));
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
                    pesos, new float[]{26, 40, 34}, true));
            boolean estavel = perturbacoes.stream().noneMatch(Perturbacao::mudou);
            doc.add(texto(estavel
                    ? "A recomendação é estável: nenhuma das perturbações de +20% troca a alternativa líder."
                    : "A recomendação é sensível aos pesos: ao menos uma perturbação troca a alternativa líder, "
                    + "o que deve ser discutido com o decisor antes da escolha final."));
        }

        doc.add(nota("Documento gerado pelo InvestDecide. Cenários são hipóteses de análise, "
                + "não previsões; não constitui recomendação de investimento."));
        return fechar(holder);
    }

    // ------------------------------------------------------------------ construção

    private record Documento(Document doc, ByteArrayOutputStream saida) {
    }

    private Documento novoDocumento(String rodape) {
        Document doc = new Document(PageSize.A4, 45, 45, 50, 40);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(doc, saida);
        writer.setPageEvent(new Rodape(rodape));
        doc.open();
        return new Documento(doc, saida);
    }

    private static byte[] fechar(Documento holder) {
        holder.doc().close();
        return holder.saida().toByteArray();
    }

    private static Paragraph secao(String texto) {
        Paragraph p = new Paragraph(texto, F_SECAO);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        return p;
    }

    private static Paragraph subtitulo(String texto) {
        Paragraph p = new Paragraph(texto, F_SUBTITULO);
        p.setSpacingAfter(3);
        return p;
    }

    private static Paragraph texto(String texto) {
        Paragraph p = new Paragraph(texto, F_TEXTO);
        p.setSpacingAfter(5);
        p.setLeading(12);
        return p;
    }

    private static Paragraph nota(String texto) {
        Paragraph p = new Paragraph(texto, F_NOTA);
        p.setSpacingBefore(10);
        p.setLeading(10);
        return p;
    }

    private static PdfPTable tabela(String[] cabecalho, List<String[]> linhas, float[] larguras) {
        return tabela(cabecalho, linhas, larguras, false);
    }

    private static PdfPTable tabela(String[] cabecalho, List<String[]> linhas, float[] larguras,
                                    boolean manterJunto) {
        PdfPTable tabela = new PdfPTable(larguras.length);
        tabela.setKeepTogether(manterJunto);
        try {
            tabela.setWidths(larguras);
        } catch (Exception e) {
            tabela.setWidthPercentage(100);
        }
        tabela.setWidthPercentage(100);
        tabela.setSpacingBefore(2);
        tabela.setSpacingAfter(8);
        tabela.setHeaderRows(1);

        for (String titulo : cabecalho) {
            PdfPCell celula = new PdfPCell(new Phrase(titulo, F_CABECALHO));
            celula.setBackgroundColor(FUNDO_CABECALHO);
            celula.setBorderColor(LINHA);
            celula.setPadding(4);
            celula.setHorizontalAlignment(Element.ALIGN_LEFT);
            tabela.addCell(celula);
        }
        for (String[] linha : linhas) {
            for (int c = 0; c < linha.length; c++) {
                PdfPCell celula = new PdfPCell(new Phrase(linha[c] == null ? "" : linha[c],
                        c == 0 ? F_CELULA_FORTE : F_CELULA));
                celula.setBorderColor(LINHA);
                celula.setPadding(4);
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
                    new Phrase(texto, F_NOTA), 45, 26, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Página " + writer.getPageNumber(), F_NOTA),
                    PageSize.A4.getWidth() - 45, 26, 0);
        }
    }

    // ------------------------------------------------------------------ formato

    private static String moeda(double valor) {
        return NumberFormat.getCurrencyInstance(BR).format(valor);
    }

    private static String numero(double valor) {
        return String.format(BR, "%.2f", valor);
    }

    private static double valorBruto(ResultadoDecisao resultado, AvaliacaoProduto avaliacao) {
        double anos = resultado.getCliente().getHorizonteMeses() / 12.0;
        return resultado.getCliente().getAporte() * Math.pow(1 + avaliacao.getRetornoBruto() / 100, anos);
    }
}
