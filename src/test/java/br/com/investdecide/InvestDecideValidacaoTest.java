package br.com.investdecide;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Cliente.ModeloDeclaracao;
import br.com.investdecide.dominio.Cliente.RegimePrevidencia;
import br.com.investdecide.dominio.GrupoProdutos;
import br.com.investdecide.dominio.PerfilRisco;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.dominio.TipoProduto;
import br.com.investdecide.servico.AnaliticaService;
import br.com.investdecide.servico.CatalogoService;
import br.com.investdecide.servico.CenarioService;
import br.com.investdecide.servico.DadosMercadoService;
import br.com.investdecide.servico.DecisaoService;
import br.com.investdecide.servico.DecisaoService.Perturbacao;
import br.com.investdecide.servico.RelatorioPdfService;
import br.com.investdecide.servico.RelatorioTributarioService;
import br.com.investdecide.servico.RelatorioTributarioService.LinhaTributaria;
import br.com.investdecide.servico.RelatorioTributarioService.Relatorio;
import br.com.investdecide.servico.TributacaoService;
import br.com.investdecide.servico.TributacaoService.ResultadoTributario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class InvestDecideValidacaoTest {

    @Autowired private CatalogoService catalogoService;
    @Autowired private DadosMercadoService dadosMercadoService;
    @Autowired private AnaliticaService analiticaService;
    @Autowired private TributacaoService tributacaoService;
    @Autowired private RelatorioTributarioService relatorioTributarioService;
    @Autowired private CenarioService cenarioService;
    @Autowired private DecisaoService decisaoService;
    @Autowired private RelatorioPdfService relatorioPdfService;
    @Autowired private MockMvc mockMvc;

    private Cliente criarRicardoAlmeida() {
        Cliente c = new Cliente();
        c.setNome("Ricardo Almeida");
        c.setPerfil(PerfilRisco.MODERADO);
        c.setAporte(150000.0);
        c.setHorizonteMeses(36);
        c.setLiquidezDias(30);
        c.setObjetivo("Aposentadoria e acumulação de patrimônio");
        c.setRendaBruta(220000.0);
        c.setDeducoes(18000.0);
        c.setModelo(ModeloDeclaracao.COMPLETA);
        c.setRegime(RegimePrevidencia.REGRESSIVA);
        return c;
    }

    private String extrairCsrf(String html) {
        Matcher m1 = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("value=\"([^\"]+)\"[^>]*name=\"_csrf\"").matcher(html);
        return m2.find() ? m2.group(1) : null;
    }

    private MockHttpSession autenticar() throws Exception {
        MvcResult getLogin = mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) getLogin.getRequest().getSession(true);
        String token = extrairCsrf(getLogin.getResponse().getContentAsString());

        MockHttpServletRequestBuilder loginPost = post("/login")
            .session(session)
            .param("email", "consultor@investdecide.com.br")
            .param("senha", "Invest@2026");
        if (token != null) {
            loginPost.param("_csrf", token);
        }

        MvcResult postResult = mockMvc.perform(loginPost)
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String redirect = postResult.getResponse().getRedirectedUrl();
        assertNotNull(redirect);
        assertFalse(redirect.contains("error"), "Login falhou ao autenticar usuario de teste: " + redirect);
        return (MockHttpSession) postResult.getRequest().getSession(false);
    }

    @Test
    void validarCatalogoESeries() {
        List<Produto> produtos = catalogoService.listar();
        assertFalse(produtos.isEmpty(), "Catalogo nao deve estar vazio");
        assertTrue(dadosMercadoService.disponivel(), "Dados de mercado CVM/BCB devem estar disponiveis");
        assertTrue(dadosMercadoService.getTotalMeses() >= 36, "Base macro deve possuir ao menos 36 meses completos");

        Cenario base = cenarioService.base();
        for (Produto p : produtos) {
            double vol = analiticaService.volatilidadeAnual(p);
            assertTrue(vol >= 0.0 && vol <= 60.0,
                "Volatilidade fora do intervalo esperado para " + p.getNome() + ": " + vol);
            double proj = analiticaService.retornoProjetado(p, base, 36);
            assertTrue(proj >= -50.0 && proj <= 150.0,
                "Projecao fora do intervalo [-50%, 150%] para " + p.getNome() + ": " + proj);
            if (p.getRisco() <= 4) {
                assertTrue(proj >= -50.0 && proj <= 60.0,
                    "Projecao fora de [-50%, 60%] para produto risco <= 4 (" + p.getNome() + "): " + proj);
            }
        }
    }

    @Test
    void validarTributacaoEPrevidencia() {
        Cliente clienteProg = criarRicardoAlmeida();
        clienteProg.setRegime(RegimePrevidencia.PROGRESSIVA);

        double aliqProg = tributacaoService.aliquotaPrevidencia(clienteProg, 36);
        assertTrue(aliqProg >= 15.0, "Aliquota progressiva de previdencia deve refletir tabela marginal >= 15%: " + aliqProg);
        assertEquals(27.5, aliqProg, 0.01, "Base de calculo 202k deve cair na faixa de 27,5%");

        Produto fundoLp = catalogoService.listar().stream()
            .filter(p -> p.getTipo() == TipoProduto.FUNDO_RF && !p.isIsentoIr())
            .findFirst()
            .orElseThrow();

        ResultadoTributario resNeg = tributacaoService.calcular(fundoLp, clienteProg, 100000.0, -5.0, 36);
        assertTrue(resNeg.cargaSobreGanho() > 0.0,
            "Produto com retorno negativo deve preservar aliquota nominal de IR, nunca 0%: " + resNeg.cargaSobreGanho());
        assertEquals(15.0, resNeg.cargaSobreGanho(), 0.01);

        Produto fundoAcoes = new Produto();
        fundoAcoes.setId("TEST_ACOES");
        fundoAcoes.setNome("Fundo Acoes Teste");
        fundoAcoes.setTipo(TipoProduto.FUNDO_ACOES);
        ResultadoTributario resAcoes = tributacaoService.calcular(fundoAcoes, clienteProg, 100000.0, 12.0, 3);
        assertEquals(15.0, resAcoes.cargaSobreGanho(), 0.01, "Fundo de acoes deve ter aliquota fixa de 15%");

        List<Produto> produtos = catalogoService.listar();
        long prevNoCatalogo = produtos.stream().filter(p -> p.getTipo().isPrevidencia()).count();
        assertTrue(prevNoCatalogo > 0, "Catalogo deve conter produtos de previdencia");

        Cliente completa = criarRicardoAlmeida();
        completa.setModelo(ModeloDeclaracao.COMPLETA);
        ResultadoDecisao decCompleta = decisaoService.avaliar(completa, produtos, cenarioService.base(), new Pesos());
        List<AvaliacaoProduto> prevCompleta = decCompleta.getRanking().stream()
            .filter(a -> a.getProduto().getTipo().isPrevidencia())
            .toList();
        assertFalse(prevCompleta.isEmpty(), "Deve existir previdencia elegivel para Ricardo Almeida");
        assertTrue(prevCompleta.stream().allMatch(a ->
                a.getTipoRotulo().contains("VGBL") && a.getBeneficioFiscal() == 0.0),
            "Para aporte unico 150k em 36m (IR 30% > 27,5% e acima do teto 12%), VGBL otimiza o valor liquido mesmo em COMPLETA");

        List<TipoProduto> tiposOriginaisAntes = produtos.stream().map(Produto::getTipo).toList();

        // Cliente previdenciario de longo prazo dentro do teto dedutivel de 12% da renda (R$ 26.400)
        Cliente prevLongoPrazo = criarRicardoAlmeida();
        prevLongoPrazo.setAporte(24000.0);
        prevLongoPrazo.setHorizonteMeses(120);
        prevLongoPrazo.setModelo(ModeloDeclaracao.COMPLETA);
        ResultadoDecisao decPgbl = decisaoService.avaliar(prevLongoPrazo, produtos, cenarioService.base(), new Pesos());
        List<AvaliacaoProduto> prevPgbl = decPgbl.getRanking().stream()
            .filter(a -> a.getProduto().getTipo().isPrevidencia() && a.getRetornoBruto() > 0)
            .toList();
        assertFalse(prevPgbl.isEmpty());
        assertTrue(prevPgbl.stream().allMatch(a ->
                a.getTipoRotulo().contains("PGBL") && a.getBeneficioFiscal() > 0.0),
            "Declaracao COMPLETA com aporte dentro de 12% da renda em 120m deve selecionar PGBL com beneficio fiscal positivo");

        Cliente simplificada = criarRicardoAlmeida();
        simplificada.setAporte(24000.0);
        simplificada.setHorizonteMeses(120);
        simplificada.setModelo(ModeloDeclaracao.SIMPLIFICADA);
        ResultadoDecisao decSimp = decisaoService.avaliar(simplificada, produtos, cenarioService.base(), new Pesos());
        List<AvaliacaoProduto> prevSimp = decSimp.getRanking().stream()
            .filter(a -> a.getProduto().getTipo().isPrevidencia())
            .toList();
        assertFalse(prevSimp.isEmpty());
        assertTrue(prevSimp.stream().allMatch(a ->
                a.getTipoRotulo().contains("VGBL") && a.getBeneficioFiscal() == 0.0),
            "Declaracao SIMPLIFICADA deve selecionar modalidade VGBL sem beneficio de deducao PGBL");

        List<TipoProduto> tiposOriginaisDepois = produtos.stream().map(Produto::getTipo).toList();
        assertEquals(tiposOriginaisAntes, tiposOriginaisDepois,
            "Avaliacao decisoria nao pode mutar TipoProduto dos objetos do catalogo");
    }

    @Test
    void validarDecisaoESilhueta() {
        Cliente cliente = criarRicardoAlmeida();
        List<Produto> produtos = catalogoService.listar();
        Pesos pesos = new Pesos();

        ResultadoDecisao baseRes = decisaoService.avaliar(cliente, produtos, cenarioService.base(), pesos);
        assertEquals(4, baseRes.getGrupos().size(), "K-Means deve formar 4 grupos");
        assertTrue(baseRes.getSilhueta() > 0.0 && baseRes.getSilhueta() <= 1.0,
            "Coeficiente de silhueta deve estar em (0, 1]: " + baseRes.getSilhueta());

        for (Cenario cen : cenarioService.todos()) {
            ResultadoDecisao res = decisaoService.avaliar(cliente, produtos, cen, pesos);
            assertFalse(res.getRanking().isEmpty(), "Ranking vazio no cenario " + cen.getNome());
            assertTrue(res.getRanking().get(0).getScore() > 0.0,
                "Score do lider deve ser positivo no cenario " + cen.getNome());
        }
    }

    @Test
    void validarRotasWebEPdf() throws Exception {
        MockHttpSession session = autenticar();

        mockMvc.perform(get("/dashboard").session(session))
            .andExpect(status().isOk());

        MvcResult simGet = mockMvc.perform(get("/simulacao").session(session))
            .andExpect(status().isOk())
            .andReturn();
        String csrfSim = extrairCsrf(simGet.getResponse().getContentAsString());

        MockHttpServletRequestBuilder simPost = post("/simulacao")
            .session(session)
            .param("nome", "Ricardo Almeida")
            .param("perfil", "MODERADO")
            .param("aporte", "150000")
            .param("horizonteMeses", "36")
            .param("liquidezDias", "30")
            .param("objetivo", "Aposentadoria e acumulacao de patrimonio")
            .param("rendaBruta", "220000")
            .param("deducoes", "18000")
            .param("modelo", "COMPLETA")
            .param("regime", "REGRESSIVA")
            .param("retorno", "35")
            .param("risco", "25")
            .param("tributacao", "20")
            .param("liquidez", "20")
            .param("cenario", "Otimista");
        if (csrfSim != null) {
            simPost.param("_csrf", csrfSim);
        }

        MvcResult otimistaPost = mockMvc.perform(simPost)
            .andExpect(status().isOk())
            .andExpect(model().attribute("cenarioSelecionado", "Otimista"))
            .andReturn();
        ResultadoDecisao resOtimista = (ResultadoDecisao) otimistaPost.getRequest().getSession(false).getAttribute("resultado");
        assertNotNull(resOtimista);
        assertEquals("Otimista", resOtimista.getCenario().getNome());

        MvcResult persRes = mockMvc.perform(get("/simulacao/resultado?cenario=Personalizado").session(session))
            .andExpect(status().isOk())
            .andExpect(model().attribute("cenarioSelecionado", "Personalizado"))
            .andReturn();
        ResultadoDecisao resPers = (ResultadoDecisao) persRes.getRequest().getSession(false).getAttribute("resultado");
        assertNotNull(resPers);
        assertEquals("Personalizado", resPers.getCenario().getNome());

        mockMvc.perform(get("/simulacao/ranking").session(session))
            .andExpect(status().isOk());

        mockMvc.perform(get("/cenarios").session(session))
            .andExpect(status().isOk());

        mockMvc.perform(get("/relatorios/tributacao").session(session))
            .andExpect(status().isOk());

        for (String rotaPdf : List.of("/relatorios/comparativo.pdf", "/relatorios/tributacao.pdf", "/relatorios/sensibilidade.pdf")) {
            byte[] pdf = mockMvc.perform(get(rotaPdf).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
            assertTrue(pdf.length > 500, "PDF vazio em " + rotaPdf);
            String header = new String(pdf, 0, Math.min(4, pdf.length), StandardCharsets.US_ASCII);
            assertEquals("%PDF", header, "Cabecalho PDF invalido em " + rotaPdf);
        }
    }

    @Test
    void validarReadmeSemCredenciais() throws Exception {
        Path readme = Path.of("README.md");
        assertTrue(Files.exists(readme), "README.md deve existir na raiz de investdecide-prototipo");
        String texto = Files.readString(readme, StandardCharsets.UTF_8);
        assertFalse(texto.contains("Invest@2026"), "README.md nao deve expor senha");
        assertFalse(texto.contains("consultor@investdecide.com.br"), "README.md nao deve expor usuario de login");
    }

    @Test
    void imprimirDadosArtigo() {
        Locale.setDefault(Locale.US);
        Cliente cliente = criarRicardoAlmeida();
        List<Produto> produtos = catalogoService.listar();
        Pesos pesos = new Pesos();

        ResultadoDecisao base = decisaoService.avaliar(cliente, produtos, cenarioService.base(), pesos);

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DADOS_ARTIGO_INICIO ===\n");
        sb.append(String.format("UNIVERSO: totalAlternativas=%d | totalElegiveis=%d | silhuetaGeral=%.4f | diferencaTop2=%.2f%n",
            base.getTotalAlternativas(), base.getTotalElegiveis(), base.getSilhueta(), base.getDiferencaParaSegunda()));

        sb.append("PREMISSAS: ").append(base.getPremissas()).append("\n");
        sb.append("SENSIBILIDADE_RESUMO: ").append(base.getSensibilidade()).append("\n");

        sb.append("\n-- TOP 10 CENARIO BASE --\n");
        List<AvaliacaoProduto> rank = base.getRanking();
        for (int i = 0; i < Math.min(10, rank.size()); i++) {
            AvaliacaoProduto a = rank.get(i);
            sb.append(String.format("#%d | %s | tipo=%s | risco=%d | liq=D+%d | retBruto=%.2f | retLiq=%.2f | carga=%.2f | benef=%.2f | vol=%.2f | valFinal=%.2f | score=%.2f | grupo=%s%n",
                a.getPosicao(), a.getNome(), a.getTipoRotulo(), a.getRisco(), a.getLiquidezDias(),
                a.getRetornoBruto(), a.getRetornoLiquido(), a.getCargaTributaria(), a.getBeneficioFiscal(),
                a.getVolatilidade(), a.getValorFinal(), a.getScore(), a.getGrupo()));
        }

        sb.append("\n-- GRUPOS K-MEANS --\n");
        for (GrupoProdutos g : base.getGrupos()) {
            sb.append(String.format("%s | qtd=%d | retMedio=%.2f | riscoMedio=%.2f | cargaMedia=%.2f | silhueta=%.4f%n",
                g.getNome(), g.getQuantidade(), g.getRetornoMedio(), g.getRiscoMedio(), g.getCargaMedia(), g.getSilhuetaMedia()));
        }

        sb.append("\n-- CENARIOS --\n");
        for (Cenario cen : cenarioService.todos()) {
            ResultadoDecisao res = decisaoService.avaliar(cliente, produtos, cen, pesos);
            AvaliacaoProduto top1 = res.getRanking().get(0);
            AvaliacaoProduto top2 = res.getRanking().size() > 1 ? res.getRanking().get(1) : null;
            sb.append(String.format("%s | CDI=%.2f | IPCA=%.2f | Dolar=%.2f | Ibov=%.2f | Top1=%s (%s) | retLiq=%.2f | valFinal=%.2f | score=%.2f | Top2=%s | diff=%.2f%n",
                cen.getNome(), cen.getCdi(), cen.getIpca(), cen.getDolar(), cen.getIbovespa(),
                top1.getNome(), top1.getTipoRotulo(), top1.getRetornoLiquido(), top1.getValorFinal(), top1.getScore(),
                top2 != null ? top2.getNome() : "-", res.getDiferencaParaSegunda()));
        }

        sb.append("\n-- PERTURBACAO PESOS (+20%) --\n");
        for (Perturbacao p : decisaoService.perturbarPesos(base.getRanking(), pesos)) {
            sb.append(String.format("%s | lider=%s | mudou=%s%n", p.criterio(), p.lider(), p.mudou()));
        }

        sb.append("\n-- COMPARACAO MODELOS PREVIDENCIA (Top1 Prev: 36m/150k vs 120m/24k) --\n");
        Produto topPrev = base.getRanking().stream()
            .map(AvaliacaoProduto::getProduto)
            .filter(p -> p.getTipo().isPrevidencia())
            .findFirst()
            .orElse(null);
        if (topPrev != null) {
            for (int[] cenPrev : new int[][]{{36, 150000}, {120, 24000}}) {
                int hMeses = cenPrev[0];
                double ap = cenPrev[1];
                for (ModeloDeclaracao mod : List.of(ModeloDeclaracao.COMPLETA, ModeloDeclaracao.SIMPLIFICADA)) {
                    for (RegimePrevidencia reg : List.of(RegimePrevidencia.REGRESSIVA, RegimePrevidencia.PROGRESSIVA)) {
                        Cliente alt = criarRicardoAlmeida();
                        alt.setHorizonteMeses(hMeses);
                        alt.setAporte(ap);
                        alt.setModelo(mod);
                        alt.setRegime(reg);
                        ResultadoDecisao decAlt = decisaoService.avaliar(alt, List.of(topPrev), cenarioService.base(), pesos);
                        AvaliacaoProduto av = decAlt.getRanking().get(0);
                        sb.append(String.format("%s | h=%dm | aporte=%.0f | mod=%s | reg=%s | rotulo=%s | retLiq=%.2f | carga=%.2f | benef=%.2f | valFinal=%.2f%n",
                            av.getNome(), hMeses, ap, mod, reg, av.getTipoRotulo(), av.getRetornoLiquido(), av.getCargaTributaria(), av.getBeneficioFiscal(), av.getValorFinal()));
                    }
                }
            }
        }

        sb.append("\n-- RELATORIO TRIBUTARIO (Taxa Top1 Recomendada) --\n");
        double taxaBase = base.getRecomendada().getRetornoBruto();
        Relatorio rel = relatorioTributarioService.comparar(cliente, taxaBase, 36);
        sb.append(String.format("TaxaAnual=%.2f%% | Aporte=%.2f | GanhoReinvest=%.2f%n",
            rel.getTaxaAnual(), cliente.getAporte(), rel.getGanhoReinvestimento()));
        for (LinhaTributaria l : rel.getLinhas()) {
            sb.append(String.format("%s | %s | bruto=%.2f | imp=%.2f | benef=%.2f | liqSem=%.2f | liqCom=%.2f%n",
                l.getNome(), l.getRegime(), l.getValorBruto(), l.getImposto(), l.getBeneficio(), l.getLiquidoSem(), l.getLiquidoCom()));
        }
        sb.append("=== DADOS_ARTIGO_FIM ===\n");

        System.out.println(sb);
    }
}
