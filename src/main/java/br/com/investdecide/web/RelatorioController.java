package br.com.investdecide.web;

import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
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

    public RelatorioController(RelatorioTributarioService tributario, RelatorioPdfService pdf,
                               CenarioService cenarios, CatalogoService catalogo,
                               DecisaoService decisao, ObjectMapper mapper, RelatorioService historico) {
        this.tributario = tributario;
        this.pdf = pdf;
        this.cenarios = cenarios;
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.mapper = mapper;
        this.historico = historico;
    }

    @GetMapping("/relatorios/tributacao")
    public String tributacao(@RequestParam(name = "taxa", required = false) Double taxa,
                             @RequestParam(name = "meses", required = false) Integer meses,
                             Model model, HttpSession session) {
        Cliente cliente = clienteDaSessao(session);
        double taxaAnual = taxa != null ? taxa : Math.round(cenarios.base().getCdi() * 98) / 100.0;
        int horizonte = meses != null ? meses : cliente.getHorizonteMeses();

        historico.registrar("Eficiência tributária",
                "Comparativo de veículos com e sem reinvestimento da restituição",
                cliente.getNome(), "Tributação", "/relatorios/tributacao.pdf");

        model.addAttribute("relatorio", tributario.comparar(cliente, taxaAnual, horizonte));
        model.addAttribute("cliente", cliente);
        model.addAttribute("taxa", taxaAnual);
        model.addAttribute("meses", horizonte);
        return "tributacao";
    }

    /** Comparativo de alternativas da última simulação (também disponível na rota antiga). */
    @GetMapping({"/relatorios/comparativo.pdf", "/relatorios/simulacao.pdf"})
    public ResponseEntity<?> comparativoPdf(HttpSession session) {
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");
        if (resultado == null) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/simulacao")).build();
        }
        historico.registrar("Comparativo de alternativas", "Documento exportado em PDF",
                resultado.getCliente().getNome(), "Simulação", "/relatorios/comparativo.pdf");
        return arquivo(pdf.comparativo(resultado), "investdecide-comparativo.pdf");
    }

    /** Exporta o comparativo tributário em PDF. */
    @GetMapping("/relatorios/tributacao.pdf")
    public ResponseEntity<?> tributacaoPdf(@RequestParam(name = "taxa", required = false) Double taxa,
                                           @RequestParam(name = "meses", required = false) Integer meses,
                                           HttpSession session) {
        Cliente cliente = clienteDaSessao(session);
        double taxaAnual = taxa != null ? taxa : Math.round(cenarios.base().getCdi() * 98) / 100.0;
        int horizonte = meses != null ? meses : cliente.getHorizonteMeses();
        historico.registrar("Eficiência tributária",
                "Comparativo de veículos com e sem reinvestimento da restituição",
                cliente.getNome(), "Tributação", "/relatorios/tributacao.pdf");
        return arquivo(pdf.tributacao(tributario.comparar(cliente, taxaAnual, horizonte)),
                "investdecide-tributacao.pdf");
    }

    /** Relatório de sensibilidade: cenários econômicos e perturbações de pesos. */
    @GetMapping("/relatorios/sensibilidade.pdf")
    public ResponseEntity<?> sensibilidadePdf(HttpSession session) {
        ResultadoDecisao base = (ResultadoDecisao) session.getAttribute("resultado");
        Cliente cliente = base != null ? base.getCliente() : PagesController.clientePadrao();
        Pesos pesos = base != null ? base.getPesos() : new Pesos();

        List<ResultadoDecisao> porCenario = new ArrayList<>();
        cenarios.todos().forEach(c -> porCenario.add(decisao.avaliar(cliente, catalogo.listar(), c, pesos)));

        List<DecisaoService.Perturbacao> perturbacoes = List.of();
        if (!porCenario.isEmpty() && porCenario.get(0).getRanking().size() > 1) {
            perturbacoes = decisao.perturbarPesos(new ArrayList<>(porCenario.get(0).getRanking()), pesos);
        }
        historico.registrar("Sensibilidade dos cenários",
                "Liderança por cenário e perturbações de +20% nos pesos",
                cliente.getNome(), "Sensibilidade", "/relatorios/sensibilidade.pdf");
        return arquivo(pdf.sensibilidade(porCenario, perturbacoes), "investdecide-sensibilidade.pdf");
    }

    /**
     * Endpoint de diagnóstico usado na validação do trabalho: devolve o resultado do modelo
     * de decisão em JSON, sem depender da renderização das telas.
     */
    @GetMapping("/api/diagnostico")
    @ResponseBody
    public Object diagnostico(HttpSession session) {
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

    private static Cliente clienteDaSessao(HttpSession session) {
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");
        return resultado != null ? resultado.getCliente() : PagesController.clientePadrao();
    }
}
