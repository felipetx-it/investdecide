package br.com.investdecide.web;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.GrupoProdutos;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.CatalogoService;
import br.com.investdecide.servico.CenarioService;
import br.com.investdecide.servico.DecisaoService;
import br.com.investdecide.servico.RelatorioService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fluxo principal: formulário do cliente, cálculo do índice e ranking completo. */
@Controller
public class SimulacaoController {

    private static final List<String> CENARIOS_DA_SIMULACAO = List.of("Base", "Otimista", "Pessimista");

    private final CatalogoService catalogo;
    private final DecisaoService decisao;
    private final CenarioService cenarios;
    private final RelatorioService relatorios;

    public SimulacaoController(CatalogoService catalogo, DecisaoService decisao, CenarioService cenarios,
                                RelatorioService relatorios) {
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.cenarios = cenarios;
        this.relatorios = relatorios;
    }

    @GetMapping("/simulacao")
    public String formulario(Model model) {
        model.addAttribute("cliente", PagesController.clientePadrao());
        model.addAttribute("pesos", new Pesos());
        model.addAttribute("cenarios", cenarios.todos());
        model.addAttribute("cenarioSelecionado", "Base");
        model.addAttribute("indicadores", cenarios.getIndicadores());
        return "simulacao";
    }

    @PostMapping("/simulacao")
    public String calcular(@ModelAttribute("cliente") Cliente cliente,
                           @ModelAttribute("pesos") Pesos pesos,
                           @RequestParam(name = "cenario", defaultValue = "Base") String cenario,
                           Model model, HttpSession session) {
        String erro = validar(cliente, pesos);
        if (erro != null) {
            model.addAttribute("erro", erro);
            model.addAttribute("cliente", cliente);
            model.addAttribute("pesos", pesos);
            model.addAttribute("cenarios", cenarios.todos());
            model.addAttribute("cenarioSelecionado", cenario);
            model.addAttribute("indicadores", cenarios.getIndicadores());
            return "simulacao";
        }

        // Calcula os três cenários de uma vez, para permitir troca sem recalcular.
        Map<String, ResultadoDecisao> porCenario = new LinkedHashMap<>();
        for (Cenario c : List.of(cenarios.base(), cenarios.otimista(), cenarios.pessimista())) {
            porCenario.put(c.getNome(), decisao.avaliar(cliente, catalogo.listar(), c, pesos));
        }
        session.setAttribute("resultadosPorCenario", porCenario);

        ResultadoDecisao base = porCenario.get("Base");
        session.setAttribute("resultado", base);
        relatorios.registrar("Comparativo de alternativas",
                "Simulação com " + base.getTotalElegiveis() + " alternativas elegíveis",
                cliente.getNome(), "Simulação", "/relatorios/comparativo.pdf");

        return montarResultado(model, cliente, pesos, "Base", base);
    }

    /** Reabre o resultado da última simulação (sem voltar ao formulário). */
    @GetMapping("/simulacao/resultado")
    public String resultado(@RequestParam(name = "cenario", defaultValue = "Base") String cenario,
                            Model model, HttpSession session) {
        @SuppressWarnings("unchecked")
        Map<String, ResultadoDecisao> porCenario = (Map<String, ResultadoDecisao>) session.getAttribute("resultadosPorCenario");
        if (porCenario == null || porCenario.isEmpty()) {
            return "redirect:/simulacao";
        }
        ResultadoDecisao escolhido = porCenario.getOrDefault(cenario, porCenario.get("Base"));
        return montarResultado(model, escolhido.getCliente(), escolhido.getPesos(),
                escolhido.getCenario().getNome(), escolhido);
    }

    private String montarResultado(Model model, Cliente cliente, Pesos pesos, String cenario,
                                   ResultadoDecisao resultado) {
        model.addAttribute("resultado", resultado);
        model.addAttribute("destaques", resultado.getRanking().stream().limit(3).toList());
        model.addAttribute("cliente", cliente);
        model.addAttribute("pesos", pesos);
        model.addAttribute("cenarioSelecionado", cenario);
        model.addAttribute("cenariosDisponiveis", CENARIOS_DA_SIMULACAO);
        return "resultado";
    }

    @GetMapping("/simulacao/ranking")
    public String ranking(@RequestParam(name = "ordem", defaultValue = "score") String ordem,
                          Model model, HttpSession session) {
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");
        if (resultado == null) {
            return "redirect:/simulacao";
        }
        List<AvaliacaoProduto> rankingOrdenado = new ArrayList<>(resultado.getRanking());
        rankingOrdenado.sort(comparador(ordem));

        model.addAttribute("resultado", resultado);
        model.addAttribute("rankingOrdenado", rankingOrdenado);
        model.addAttribute("ordem", ordem);
        model.addAttribute("rotuloOrdem", rotuloOrdem(ordem));
        model.addAttribute("grupos", ordenarGrupos(resultado.getGrupos(), ordem));
        return "ranking";
    }

    private static String validar(Cliente cliente, Pesos pesos) {
        if (cliente.getAporte() <= 0) {
            return "O valor do aporte deve ser maior que zero.";
        }
        if (cliente.getHorizonteMeses() < 12) {
            return "O horizonte mínimo da simulação é de 12 meses.";
        }
        if (cliente.getRendaBruta() < 0 || cliente.getDeducoes() < 0) {
            return "Renda e deduções não podem ser negativas.";
        }
        if (pesos.getSoma() <= 0) {
            return "A soma dos pesos precisa ser maior que zero.";
        }
        return null;
    }

    private static String rotuloOrdem(String ordem) {
        return switch (ordem) {
            case "nome" -> "nome (A–Z)";
            case "retorno" -> "retorno líquido";
            case "risco" -> "risco (menor primeiro)";
            case "liquidez" -> "liquidez (menor prazo)";
            case "tributo" -> "carga tributária";
            default -> "índice";
        };
    }

    private static List<GrupoProdutos> ordenarGrupos(List<GrupoProdutos> grupos, String ordem) {
        Comparator<AvaliacaoProduto> comparador = comparador(ordem);
        List<GrupoProdutos> saida = new ArrayList<>();
        for (GrupoProdutos grupo : grupos) {
            GrupoProdutos copia = new GrupoProdutos(grupo.getNome(), grupo.getDescricao());
            List<AvaliacaoProduto> itens = new ArrayList<>(grupo.getItens());
            itens.sort(comparador);
            itens.forEach(copia::adicionar);
            saida.add(copia);
        }
        return saida;
    }

    private static Comparator<AvaliacaoProduto> comparador(String ordem) {
        return switch (ordem) {
            case "nome" -> Comparator.comparing(AvaliacaoProduto::getNome);
            case "retorno" -> Comparator.comparingDouble(AvaliacaoProduto::getRetornoLiquido).reversed();
            case "risco" -> Comparator.comparingInt(AvaliacaoProduto::getRisco);
            case "liquidez" -> Comparator.comparingInt(AvaliacaoProduto::getLiquidezDias);
            case "tributo" -> Comparator.comparingDouble(AvaliacaoProduto::getCargaTributaria);
            default -> Comparator.comparingDouble(AvaliacaoProduto::getScore).reversed();
        };
    }
}
