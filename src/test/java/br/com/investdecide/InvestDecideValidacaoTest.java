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
import br.com.investdecide.servico.RelatorioService.Registro;
import br.com.investdecide.servico.RelatorioTributarioService;
import br.com.investdecide.servico.RelatorioTributarioService.LinhaTributaria;
import br.com.investdecide.servico.RelatorioTributarioService.Relatorio;
import br.com.investdecide.servico.SimulacaoSalvaService;
import br.com.investdecide.servico.SimulacaoSalvaService.ResumoSimulacao;
import br.com.investdecide.servico.TributacaoService;
import br.com.investdecide.servico.TributacaoService.ResultadoTributario;
import br.com.investdecide.servico.UsuarioService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
    @Autowired private UsuarioService usuarioService;
    @Autowired private SimulacaoSalvaService simulacaoSalvaService;
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
        return autenticarComo("consultor@investdecide.com.br", "Invest@2026");
    }

    private MockHttpSession autenticarComo(String email, String senha) throws Exception {
        MvcResult getLogin = mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) getLogin.getRequest().getSession(true);
        String token = extrairCsrf(getLogin.getResponse().getContentAsString());

        MockHttpServletRequestBuilder loginPost = post("/login")
            .session(session)
            .param("email", email)
            .param("senha", senha);
        if (token != null) {
            loginPost.param("_csrf", token);
        }

        MvcResult postResult = mockMvc.perform(loginPost)
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String redirect = postResult.getResponse().getRedirectedUrl();
        assertNotNull(redirect);
        assertFalse(redirect.contains("error"), "Login falhou ao autenticar usuario de teste (" + email + "): " + redirect);
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

        MvcResult dashGet = mockMvc.perform(get("/dashboard").session(session))
            .andExpect(status().isOk())
            .andReturn();
        String dashHtml = dashGet.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(dashHtml.contains("Ricardo Almeida"), "Dashboard inicial do consultor deve abrir com Ricardo Almeida");
        assertFalse(dashHtml.contains("Base real da CVM"), "Caixa Base real da CVM deve ser removida do dashboard");
        assertFalse(dashHtml.contains("Banco H2 SQL"), "Chip Banco H2 SQL deve ser removido da topbar");
        assertFalse(dashHtml.contains("bi-bell"), "Icone de notificacao da topbar deve ser removido");
        assertFalse(dashHtml.contains("Liquidez XP + CVM e banco H2"),
            "Caixa de alerta de liquidez/banco deve ser removida do dashboard");
        assertFalse(dashHtml.contains("come-cotas semestral ativo"),
            "Texto de alerta come-cotas/simulacoes deve ser removido do dashboard");

        MvcResult simGet = mockMvc.perform(get("/simulacao").session(session))
            .andExpect(status().isOk())
            .andReturn();
        String simHtml = simGet.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(simHtml.contains("Base real da CVM"), "Caixa Base real da CVM deve ser removida da tela de simulacao");
        String csrfSim = extrairCsrf(simHtml);

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
        String resHtml = persRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(resHtml.contains("O sistema apresenta um conjunto curto de alternativas"),
            "Caixa de shortlist note deve ser removida do resultado");
        assertFalse(resHtml.contains("Ordenação inicial alfabética"),
            "Subtitulo de ordenacao inicial deve ser removido de Alternativas por grupo");
        assertFalse(resHtml.contains("Índice Multicritério SAW"),
            "Sigla SAW deve ser removida do titulo Top 8");
        assertTrue(resHtml.contains("/relatorios/tributacao"),
            "Botao de relatorio tributario deve constar no topo do resultado");
        ResultadoDecisao resPers = (ResultadoDecisao) persRes.getRequest().getSession(false).getAttribute("resultado");
        assertNotNull(resPers);
        assertEquals("Personalizado", resPers.getCenario().getNome());

        mockMvc.perform(get("/simulacao/ranking").session(session))
            .andExpect(status().isOk());

        MvcResult cenGet = mockMvc.perform(get("/cenarios").session(session))
            .andExpect(status().isOk())
            .andReturn();
        String cenHtml = cenGet.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(cenHtml.contains("Por que choques e não valores fixos?"),
            "Caixa explicativa de choques deve ser removida de Cenarios");

        mockMvc.perform(get("/relatorios/tributacao").session(session))
            .andExpect(status().isOk());

        Files.createDirectories(Path.of("target"));
        for (String rotaPdf : List.of("/relatorios/comparativo.pdf", "/relatorios/tributacao.pdf", "/relatorios/sensibilidade.pdf")) {
            byte[] pdf = mockMvc.perform(get(rotaPdf).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
            assertTrue(pdf.length > 500, "PDF vazio em " + rotaPdf);
            String header = new String(pdf, 0, Math.min(4, pdf.length), StandardCharsets.US_ASCII);
            assertEquals("%PDF", header, "Cabecalho PDF invalido em " + rotaPdf);
            String nomeArquivo = rotaPdf.substring(rotaPdf.lastIndexOf('/') + 1);
            Files.write(Path.of("target", "preview_" + nomeArquivo), pdf);
        }

        // Dashboard deve passar a refletir a ultima simulacao executada na sessao do consultor
        MvcResult simNovaGet = mockMvc.perform(get("/simulacao").session(session))
            .andExpect(status().isOk())
            .andReturn();
        String csrfNova = extrairCsrf(simNovaGet.getResponse().getContentAsString());
        MockHttpServletRequestBuilder novaPost = post("/simulacao")
            .session(session)
            .param("nome", "Carlos Eduardo")
            .param("perfil", "MODERADO")
            .param("aporte", "95000")
            .param("horizonteMeses", "36")
            .param("liquidezDias", "30")
            .param("objetivo", "Fazer crescer")
            .param("rendaBruta", "180000")
            .param("deducoes", "15000")
            .param("modelo", "COMPLETA")
            .param("regime", "REGRESSIVA")
            .param("retorno", "40")
            .param("risco", "25")
            .param("tributacao", "20")
            .param("liquidez", "15")
            .param("cenario", "Otimista");
        if (csrfNova != null) {
            novaPost.param("_csrf", csrfNova);
        }
        MvcResult novaPostRes = mockMvc.perform(novaPost).andExpect(status().isOk()).andReturn();
        ResultadoDecisao resCarlos = (ResultadoDecisao) novaPostRes.getRequest().getSession(false).getAttribute("resultado");
        String simCarlosId = (String) novaPostRes.getRequest().getSession(false).getAttribute("simulacaoSalvaId");

        MvcResult dashAtualizado = mockMvc.perform(get("/dashboard").session(session))
            .andExpect(status().isOk())
            .andReturn();
        Cliente cliDash = (Cliente) dashAtualizado.getModelAndView().getModel().get("cliente");
        assertNotNull(cliDash);
        assertEquals("Carlos Eduardo", cliDash.getNome(), "Dashboard deve refletir o cliente da ultima simulacao");
        @SuppressWarnings("unchecked")
        List<AvaliacaoProduto> top3Dash = (List<AvaliacaoProduto>) dashAtualizado.getModelAndView().getModel().get("destaques");
        assertNotNull(top3Dash);
        assertFalse(top3Dash.isEmpty());
        assertEquals(resCarlos.getRanking().get(0).getNome(), top3Dash.get(0).getNome(),
            "Top 3 do dashboard deve acompanhar o ranking da ultima simulacao");

        // Remove a simulacao temporaria da sessao de validacao
        if (simCarlosId != null) {
            MvcResult dashComForm = mockMvc.perform(get("/dashboard").session(session)).andExpect(status().isOk()).andReturn();
            String csrfDel = extrairCsrf(dashComForm.getResponse().getContentAsString());
            MockHttpServletRequestBuilder delReq = post("/simulacao/salva/" + simCarlosId + "/excluir").session(session);
            if (csrfDel != null) {
                delReq.param("_csrf", csrfDel);
            }
            mockMvc.perform(delReq).andExpect(status().is3xxRedirection());
        }
    }

    @Test
    void validarLiquidezComeCotasEMultiusuarioBanco() throws Exception {
        // 1) Cobertura de liquidez real (XP + CVM) no universo ativo
        List<Produto> produtos = catalogoService.listar();
        long mercado = produtos.stream().filter(p -> !p.isManual()).count();
        long fonteXp = produtos.stream().filter(p -> "XP".equals(p.getLiquidezFonte())).count();
        long fonteCvm = produtos.stream().filter(p -> "CVM".equals(p.getLiquidezFonte())).count();
        assertTrue(mercado >= 150, "Universo de mercado deve ter 150 fundos: " + mercado);
        assertTrue((fonteXp + fonteCvm) >= 140,
            "Pelo menos 140 dos 150 fundos devem possuir liquidez real (XP + CVM), encontrado: " + (fonteXp + fonteCvm));
        long prevCvm = produtos.stream()
            .filter(p -> p.getTipo().isPrevidencia() && "CVM".equals(p.getLiquidezFonte()))
            .count();
        assertTrue(prevCvm >= 30, "Fundos de previdencia devem ser cobertos via CVM extrato_fi: " + prevCvm);

        // 2) Come-cotas semestral (Fundo RF LP vs CDB em 36m com retorno positivo)
        Cliente cliente = criarRicardoAlmeida();
        Produto cdb = new Produto();
        cdb.setId("TEST_CDB");
        cdb.setNome("CDB Teste");
        cdb.setTipo(TipoProduto.CDB);

        Produto fundoRf = new Produto();
        fundoRf.setId("TEST_FUNDO_RF");
        fundoRf.setNome("Fundo RF Teste");
        fundoRf.setTipo(TipoProduto.FUNDO_RF);
        fundoRf.setIsentoIr(false);

        double bruto = 204907.89;
        ResultadoTributario resCdb = tributacaoService.calcular(cdb, cliente, 150000.0, bruto, 36);
        ResultadoTributario resFundo = tributacaoService.calcular(fundoRf, cliente, 150000.0, bruto, 36);
        assertTrue(resFundo.valorLiquido() < resCdb.valorLiquido(),
            "Come-cotas semestral deve reduzir valor liquido do Fundo RF frente ao CDB de mesmo retorno bruto");
        assertTrue(resFundo.imposto() > resCdb.imposto(),
            "Come-cotas semestral deve gerar imposto efetivo maior que CDB com recolhimento apenas no vencimento");

        Relatorio relTrib = relatorioTributarioService.comparar(cliente, 10.96, 36);
        assertEquals(5, relTrib.getLinhas().size(), "Relatorio tributario deve comparar os 5 veiculos");
        assertTrue(relTrib.getLinhas().stream().anyMatch(l -> l.getNome().contains("CDB")));
        assertTrue(relTrib.getLinhas().stream().anyMatch(l -> l.getNome().contains("come-cotas")));

        // 3) Persistencia relacional multiusuario em banco H2 e isolamento entre contas
        String emailTeste = "teste.auditoria@investdecide.com.br";
        String emailVirgem = "teste@investdecide.com.br";
        usuarioService.excluirPorEmail(emailTeste);
        usuarioService.limparDadosPorEmail(emailVirgem);
        try {
            MvcResult cadGet = mockMvc.perform(get("/cadastro"))
                .andExpect(status().isOk())
                .andReturn();
            String csrfCad = extrairCsrf(cadGet.getResponse().getContentAsString());

            MockHttpServletRequestBuilder cadPost = post("/cadastro")
                .session((MockHttpSession) cadGet.getRequest().getSession(false))
                .param("nome", "Ana Teste Auditoria")
                .param("cargo", "Consultora")
                .param("email", emailTeste)
                .param("senha", "SenhaForte123");
            if (csrfCad != null) {
                cadPost.param("_csrf", csrfCad);
            }
            mockMvc.perform(cadPost)
                .andExpect(status().is3xxRedirection());

            MockHttpSession sessionTeste = autenticarComo(emailTeste, "SenhaForte123");

            MvcResult simGet = mockMvc.perform(get("/simulacao").session(sessionTeste))
                .andExpect(status().isOk())
                .andReturn();
            String csrfSim = extrairCsrf(simGet.getResponse().getContentAsString());
            MockHttpServletRequestBuilder simPost = post("/simulacao")
                .session(sessionTeste)
                .param("nome", "Cliente Isolamento Teste")
                .param("perfil", "MODERADO")
                .param("aporte", "120000")
                .param("horizonteMeses", "36")
                .param("liquidezDias", "30")
                .param("objetivo", "Teste multiusuario")
                .param("rendaBruta", "180000")
                .param("deducoes", "12000")
                .param("modelo", "COMPLETA")
                .param("regime", "REGRESSIVA")
                .param("retorno", "40")
                .param("risco", "25")
                .param("tributacao", "20")
                .param("liquidez", "15")
                .param("cenario", "Base");
            if (csrfSim != null) {
                simPost.param("_csrf", csrfSim);
            }
            mockMvc.perform(simPost).andExpect(status().isOk());
            mockMvc.perform(get("/relatorios/tributacao").session(sessionTeste)).andExpect(status().isOk());

            MvcResult sim2Get = mockMvc.perform(get("/simulacao").session(sessionTeste))
                .andExpect(status().isOk())
                .andReturn();
            String csrfSim2 = extrairCsrf(sim2Get.getResponse().getContentAsString());
            MockHttpServletRequestBuilder sim2Post = post("/simulacao")
                .session(sessionTeste)
                .param("nome", "Segundo Cliente Snapshots")
                .param("perfil", "MODERADO")
                .param("aporte", "280000")
                .param("horizonteMeses", "48")
                .param("liquidezDias", "30")
                .param("objetivo", "Teste historico imutavel")
                .param("rendaBruta", "260000")
                .param("deducoes", "18000")
                .param("modelo", "COMPLETA")
                .param("regime", "REGRESSIVA")
                .param("retorno", "40")
                .param("risco", "25")
                .param("tributacao", "20")
                .param("liquidez", "15")
                .param("cenario", "Base");
            if (csrfSim2 != null) {
                sim2Post.param("_csrf", csrfSim2);
            }
            mockMvc.perform(sim2Post).andExpect(status().isOk());
            mockMvc.perform(get("/relatorios/tributacao").session(sessionTeste)).andExpect(status().isOk());

            MvcResult relRes = mockMvc.perform(get("/relatorios").session(sessionTeste))
                .andExpect(status().isOk())
                .andReturn();
            @SuppressWarnings("unchecked")
            List<Registro> relsTeste = (List<Registro>) relRes.getModelAndView().getModel().get("relatorios");
            assertNotNull(relsTeste);
            Registro comp1 = relsTeste.stream()
                .filter(r -> "Simulação".equals(r.getCategoria()) && "Cliente Isolamento Teste".equals(r.getCliente()))
                .findFirst().orElseThrow();
            Registro comp2 = relsTeste.stream()
                .filter(r -> "Simulação".equals(r.getCategoria()) && "Segundo Cliente Snapshots".equals(r.getCliente()))
                .findFirst().orElseThrow();
            Registro trib1 = relsTeste.stream()
                .filter(r -> "Tributação".equals(r.getCategoria()) && "Cliente Isolamento Teste".equals(r.getCliente()))
                .findFirst().orElseThrow();
            Registro trib2 = relsTeste.stream()
                .filter(r -> "Tributação".equals(r.getCategoria()) && "Segundo Cliente Snapshots".equals(r.getCliente()))
                .findFirst().orElseThrow();

            assertNotEquals(comp1.getLink(), comp2.getLink());
            assertNotEquals(trib1.getLink(), trib2.getLink());

            byte[] pdfComp1 = mockMvc.perform(get(comp1.getLink()).session(sessionTeste))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            byte[] pdfComp2 = mockMvc.perform(get(comp2.getLink()).session(sessionTeste))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            byte[] pdfTrib1 = mockMvc.perform(get(trib1.getLink()).session(sessionTeste))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            byte[] pdfTrib2 = mockMvc.perform(get(trib2.getLink()).session(sessionTeste))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

            assertFalse(Arrays.equals(pdfComp1, pdfComp2),
                "Nova simulacao nao pode sobrescrever o PDF comparativo historico anterior");
            assertFalse(Arrays.equals(pdfTrib1, pdfTrib2),
                "Nova simulacao nao pode sobrescrever o PDF tributario historico anterior");

            // Verifica conta virgem teste@investdecide.com.br (sem simulacoes, relatorios, produtos nem cliente em foco)
            MockHttpSession sessionVirgem = autenticarComo(emailVirgem, "3u@Ygz9wmqwr8NYCmnPuG@d%h");
            MvcResult dashVirgem = mockMvc.perform(get("/dashboard").session(sessionVirgem))
                .andExpect(status().isOk())
                .andReturn();
            assertNull(dashVirgem.getModelAndView().getModel().get("cliente"),
                "Conta virgem nao deve contextualizar cliente no dashboard");
            assertNull(dashVirgem.getModelAndView().getModel().get("resultado"),
                "Conta virgem nao deve possuir resultado pre-calculado no dashboard");
            @SuppressWarnings("unchecked")
            List<ResumoSimulacao> salvasVirgem = (List<ResumoSimulacao>) dashVirgem.getModelAndView().getModel().get("simulacoesSalvas");
            assertTrue(salvasVirgem == null || salvasVirgem.isEmpty(), "Conta virgem nao deve possuir simulacoes salvas");
            String htmlVirgem = dashVirgem.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(htmlVirgem.contains("Nenhum cliente em análise"), "Dashboard virgem deve exibir estado vazio");
            assertFalse(htmlVirgem.contains("Ricardo Almeida"), "Dashboard virgem nao deve mencionar Ricardo Almeida");

            MvcResult simFormVirgem = mockMvc.perform(get("/simulacao").session(sessionVirgem))
                .andExpect(status().isOk())
                .andReturn();
            Cliente cliFormVirgem = (Cliente) simFormVirgem.getModelAndView().getModel().get("cliente");
            assertNotNull(cliFormVirgem);
            assertTrue(cliFormVirgem.getNome() == null || cliFormVirgem.getNome().isBlank(),
                "Formulario inicial da conta virgem nao deve preencher nome de exemplo");

            MvcResult relVirgem = mockMvc.perform(get("/relatorios").session(sessionVirgem))
                .andExpect(status().isOk())
                .andReturn();
            @SuppressWarnings("unchecked")
            List<Registro> listaRelVirgem = (List<Registro>) relVirgem.getModelAndView().getModel().get("relatorios");
            assertTrue(listaRelVirgem == null || listaRelVirgem.isEmpty(), "Conta virgem nao deve possuir relatorios salvos");

            MvcResult prodVirgem = mockMvc.perform(get("/produtos").session(sessionVirgem))
                .andExpect(status().isOk())
                .andReturn();
            @SuppressWarnings("unchecked")
            List<Produto> manuaisVirgem = (List<Produto>) prodVirgem.getModelAndView().getModel().get("manuais");
            assertTrue(manuaisVirgem == null || manuaisVirgem.isEmpty(), "Conta virgem nao deve possuir produtos manuais salvos");

            MvcResult dashTeste = mockMvc.perform(get("/dashboard").session(sessionTeste))
                .andExpect(status().isOk())
                .andReturn();
            Cliente cliDashTeste = (Cliente) dashTeste.getModelAndView().getModel().get("cliente");
            assertNotNull(cliDashTeste);
            assertEquals("Segundo Cliente Snapshots", cliDashTeste.getNome(),
                "Dashboard deve refletir a ultima simulacao executada nesta conta");
            @SuppressWarnings("unchecked")
            List<ResumoSimulacao> salvasTeste = (List<ResumoSimulacao>) dashTeste.getModelAndView().getModel().get("simulacoesSalvas");
            assertNotNull(salvasTeste);
            assertFalse(salvasTeste.isEmpty(), "Usuario recem-cadastrado deve ter sua simulacao persistida no banco H2");

            // Reabrir uma simulacao salva mais antiga tambem deve atualizar o dashboard para ela
            String simId = salvasTeste.get(salvasTeste.size() - 1).id();
            mockMvc.perform(get("/simulacao/salva/" + simId).session(sessionTeste))
                .andExpect(status().isOk());
            MvcResult dashReaberta = mockMvc.perform(get("/dashboard").session(sessionTeste))
                .andExpect(status().isOk())
                .andReturn();
            Cliente cliReaberta = (Cliente) dashReaberta.getModelAndView().getModel().get("cliente");
            assertNotNull(cliReaberta);
            assertEquals("Cliente Isolamento Teste", cliReaberta.getNome(),
                "Dashboard deve refletir a simulacao reaberta pelo usuario");

            MvcResult prodGet = mockMvc.perform(get("/produtos").session(sessionTeste))
                .andExpect(status().isOk())
                .andReturn();
            String csrfProd = extrairCsrf(prodGet.getResponse().getContentAsString());
            MockHttpServletRequestBuilder prodPost = post("/produtos")
                .session(sessionTeste)
                .param("nome", "CDB Exclusivo Teste Isolamento")
                .param("tipo", "CDB")
                .param("emissor", "Banco Teste")
                .param("indexador", "CDI")
                .param("percentualIndexador", "118")
                .param("vencimentoMeses", "24")
                .param("liquidezDias", "0")
                .param("risco", "2")
                .param("minimo", "1000");
            if (csrfProd != null) {
                prodPost.param("_csrf", csrfProd);
            }
            mockMvc.perform(prodPost).andExpect(status().is3xxRedirection());

            MockHttpSession sessionConsultor = autenticar();
            MvcResult dashConsultor = mockMvc.perform(get("/dashboard").session(sessionConsultor))
                .andExpect(status().isOk())
                .andReturn();
            @SuppressWarnings("unchecked")
            List<ResumoSimulacao> salvasConsultor = (List<ResumoSimulacao>) dashConsultor.getModelAndView().getModel().get("simulacoesSalvas");
            assertTrue(salvasConsultor == null || salvasConsultor.stream().noneMatch(s -> simId.equals(s.id())),
                "Simulacao de um usuario nao pode vazar para outro usuario");

            MvcResult prodConsultor = mockMvc.perform(get("/produtos").session(sessionConsultor))
                .andExpect(status().isOk())
                .andReturn();
            @SuppressWarnings("unchecked")
            List<Produto> manuaisConsultor = (List<Produto>) prodConsultor.getModelAndView().getModel().get("manuais");
            assertTrue(manuaisConsultor == null || manuaisConsultor.stream().noneMatch(p -> "CDB Exclusivo Teste Isolamento".equals(p.getNome())),
                "Produto manual de um usuario nao pode aparecer para outro usuario");
        } finally {
            usuarioService.excluirPorEmail(emailTeste);
            usuarioService.limparDadosPorEmail(emailVirgem);
        }
    }

    @Test
    void validarReadmeSemCredenciais() throws Exception {
        Path readme = Path.of("README.md");
        assertTrue(Files.exists(readme), "README.md deve existir na raiz de investdecide-prototipo");
        String texto = Files.readString(readme, StandardCharsets.UTF_8);
        assertFalse(texto.contains("Invest@2026"), "README.md nao deve expor senha");
        assertFalse(texto.contains("consultor@investdecide.com.br"), "README.md nao deve expor usuario de login");
        assertFalse(texto.contains("teste@investdecide.com.br"), "README.md nao deve expor usuario de teste");
        assertFalse(texto.contains("3u@Ygz9wmqwr8NYCmnPuG@d%h"), "README.md nao deve expor senha de teste");
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
        long xpCount = produtos.stream().filter(p -> "XP".equals(p.getLiquidezFonte())).count();
        long cvmCount = produtos.stream().filter(p -> "CVM".equals(p.getLiquidezFonte())).count();
        long estCount = produtos.stream().filter(p -> !"XP".equals(p.getLiquidezFonte()) && !"CVM".equals(p.getLiquidezFonte())).count();
        sb.append(String.format("LIQUIDEZ_ATIVOS: total=%d | XP=%d | CVM=%d | estimada=%d%n",
            produtos.size(), xpCount, cvmCount, estCount));

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
