package br.com.investdecide.web;

import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.BancoDadosService;
import br.com.investdecide.servico.CatalogoService;
import br.com.investdecide.servico.CenarioService;
import br.com.investdecide.servico.DecisaoService;
import br.com.investdecide.servico.RelatorioPdfService;
import br.com.investdecide.servico.RelatorioService;
import br.com.investdecide.servico.RelatorioTributarioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Relatórios: comparativo de alternativas, eficiência tributária e sensibilidade. */
@Controller
public class RelatorioController {

    private final RelatorioTributarioService tributario;
    private final RelatorioPdfService pdf;
    private final CenarioService cenarios;
    private final CatalogoService catalogo;
    private final DecisaoService decisao;
    private final ObjectMapper mapper;
    private final RelatorioService historico;
    private final BancoDadosService banco;

    public RelatorioController(RelatorioTributarioService tributario, RelatorioPdfService pdf,
                               CenarioService cenarios, CatalogoService catalogo,
                               DecisaoService decisao, ObjectMapper mapper,
                               RelatorioService historico, BancoDadosService banco) {
        this.tributario = tributario;
        this.pdf = pdf;
        this.cenarios = cenarios;
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.mapper = mapper;
        this.historico = historico;
        this.banco = banco;
    }

    @GetMapping("/relatorios/tributacao")
    public String tributacao(@RequestParam(name = "taxa", required = false) Double taxa,
                             @RequestParam(name = "meses", required = false) Integer meses,
                             Model model, HttpSession session) {
        PagesController.validarSessaoUsuario(session, banco.usuarioAtual());
        Cliente cliente = clienteDaSessao(session);
        double taxaAnual = taxa != null ? taxa : Math.round(cenarios.base().getCdi() * 98) / 100.0;
        int horizonte = meses != null ? meses : cliente.getHorizonteMeses();
        var rel = tributario.comparar(cliente, taxaAnual, horizonte);

        historico.registrar("Eficiência tributária",
                String.format(java.util.Locale.forLanguageTag("pt-BR"),
                        "Comparativo (%.2f%% a.a. · %d meses)", taxaAnual, horizonte),
                cliente.getNome(), "Tributação", "investdecide-tributacao.pdf", pdf.tributacao(rel));

        model.addAttribute("relatorio", rel);
        model.addAttribute("cliente", cliente);
        model.addAttribute("taxa", taxaAnual);
        model.addAttribute("meses", horizonte);
        return "tributacao";
    }

    /** Comparativo de alternativas da última simulação (também disponível na rota antiga). */
    @GetMapping({"/relatorios/comparativo.pdf", "/relatorios/simulacao.pdf"})
    public ResponseEntity<?> comparativoPdf(HttpSession session) {
        PagesController.validarSessaoUsuario(session, banco.usuarioAtual());
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");
        if (resultado == null) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/simulacao")).build();
        }
        byte[] bytes = pdf.comparativo(resultado);
        historico.registrar("Comparativo de alternativas",
                "Simulação (" + resultado.getCenario().getNome() + ") · "
                        + String.format(java.util.Locale.forLanguageTag("pt-BR"), "R$ %,.2f", resultado.getCliente().getAporte())
                        + " · " + resultado.getTotalElegiveis() + " elegíveis",
                resultado.getCliente().getNome(), "Simulação", "investdecide-comparativo.pdf", bytes, true);
        return arquivo(bytes, "investdecide-comparativo.pdf");
    }

    /** Exporta o comparativo tributário em PDF. */
    @GetMapping("/relatorios/tributacao.pdf")
    public ResponseEntity<?> tributacaoPdf(@RequestParam(name = "taxa", required = false) Double taxa,
                                           @RequestParam(name = "meses", required = false) Integer meses,
                                           HttpSession session) {
        PagesController.validarSessaoUsuario(session, banco.usuarioAtual());
        Cliente cliente = clienteDaSessao(session);
        double taxaAnual = taxa != null ? taxa : Math.round(cenarios.base().getCdi() * 98) / 100.0;
        int horizonte = meses != null ? meses : cliente.getHorizonteMeses();
        byte[] bytes = pdf.tributacao(tributario.comparar(cliente, taxaAnual, horizonte));
        historico.registrar("Eficiência tributária",
                String.format(java.util.Locale.forLanguageTag("pt-BR"),
                        "Comparativo (%.2f%% a.a. · %d meses)", taxaAnual, horizonte),
                cliente.getNome(), "Tributação", "investdecide-tributacao.pdf", bytes, true);
        return arquivo(bytes, "investdecide-tributacao.pdf");
    }

    /** Relatório de sensibilidade: cenários econômicos e perturbações de pesos. */
    @GetMapping("/relatorios/sensibilidade.pdf")
    public ResponseEntity<?> sensibilidadePdf(HttpSession session) {
        PagesController.validarSessaoUsuario(session, banco.usuarioAtual());
        ResultadoDecisao base = (ResultadoDecisao) session.getAttribute("resultado");
        Cliente cliente = clienteDaSessao(session);
        Pesos pesos = base != null ? base.getPesos() : new Pesos();

        List<ResultadoDecisao> porCenario = new ArrayList<>();
        cenarios.todos().forEach(c -> porCenario.add(decisao.avaliar(cliente, catalogo.listar(), c, pesos)));

        List<DecisaoService.Perturbacao> perturbacoes = List.of();
        if (!porCenario.isEmpty() && porCenario.get(0).getRanking().size() > 1) {
            perturbacoes = decisao.perturbarPesos(new ArrayList<>(porCenario.get(0).getRanking()), pesos);
        }
        byte[] bytes = pdf.sensibilidade(porCenario, perturbacoes);
        historico.registrar("Sensibilidade dos cenários",
                String.format(java.util.Locale.forLanguageTag("pt-BR"),
                        "Cenários e pesos (R$ %,.2f · %d meses)", cliente.getAporte(), cliente.getHorizonteMeses()),
                cliente.getNome(), "Sensibilidade", "investdecide-sensibilidade.pdf", bytes, true);
        return arquivo(bytes, "investdecide-sensibilidade.pdf");
    }

    /** Abre o snapshot imutável em PDF salvo no momento da criação daquele relatório histórico. */
    @GetMapping("/relatorios/arquivo/{id}")
    public ResponseEntity<?> abrirSnapshot(@org.springframework.web.bind.annotation.PathVariable String id) {
        return historico.buscarArquivo(id)
                .<ResponseEntity<?>>map(arq -> arquivo(
                        arq.conteudo(),
                        arq.nomeArquivo() != null && !arq.nomeArquivo().isBlank() ? arq.nomeArquivo() : "investdecide-relatorio.pdf"))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/relatorios")).build());
    }

    /**
     * Endpoint de diagnóstico usado na validação do trabalho: devolve o resultado do modelo
     * de decisão em JSON, sem depender da renderização das telas.
     */
    @GetMapping("/api/diagnostico")
    @ResponseBody
    public Object diagnostico(HttpSession session) {
        PagesController.validarSessaoUsuario(session, banco.usuarioAtual());
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");
        if (resultado == null) {
            return java.util.Map.of("erro", "Nenhuma simulação na sessão.");
        }
        try {
            return mapper.readValue(mapper.writeValueAsString(resultado), Object.class);
        } catch (Exception e) {
            return java.util.Map.of("erro", e.getMessage());
        }
    }

    private static ResponseEntity<byte[]> arquivo(byte[] conteudo, String nome) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(nome).build());
        headers.setContentLength(conteudo.length);
        return new ResponseEntity<>(conteudo, headers, HttpStatus.OK);
    }

    private Cliente clienteDaSessao(HttpSession session) {
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");
        if (resultado != null && resultado.getCliente() != null) {
            return resultado.getCliente();
        }
        Cliente cliente = PagesController.clienteInicialParaUsuario(banco.usuarioAtual());
        if (cliente.getNome() == null || cliente.getNome().isBlank()) {
            cliente.setNome("Simulação avulsa");
        }
        return cliente;
    }
}
