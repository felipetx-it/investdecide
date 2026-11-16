package br.com.investdecide.web;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.GrupoProdutos;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.BancoDadosService;
import br.com.investdecide.servico.CatalogoService;
import br.com.investdecide.servico.CenarioService;
import br.com.investdecide.servico.DecisaoService;
import br.com.investdecide.servico.RelatorioPdfService;
import br.com.investdecide.servico.RelatorioService;
import br.com.investdecide.servico.SimulacaoSalvaService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fluxo principal: formulário do cliente, cálculo do índice, persistência e ranking completo. */
@Controller
public class SimulacaoController {

    private static final List<String> CENARIOS_DA_SIMULACAO = List.of("Base", "Otimista", "Pessimista", "Personalizado");

    private final CatalogoService catalogo;
    private final DecisaoService decisao;
    private final CenarioService cenarios;
    private final RelatorioService relatorios;
    private final RelatorioPdfService pdf;
    private final SimulacaoSalvaService simulacoesSalvas;
    private final BancoDadosService banco;

    public SimulacaoController(CatalogoService catalogo, DecisaoService decisao, CenarioService cenarios,
                                RelatorioService relatorios, RelatorioPdfService pdf,
                                SimulacaoSalvaService simulacoesSalvas, BancoDadosService banco) {
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.cenarios = cenarios;
        this.relatorios = relatorios;
        this.pdf = pdf;
        this.simulacoesSalvas = simulacoesSalvas;
        this.banco = banco;
    }

    @GetMapping("/simulacao")
    public String formulario(Model model, HttpSession session) {
        String usuarioAtual = banco.usuarioAtual();
        PagesController.validarSessaoUsuario(session, usuarioAtual);
        ResultadoDecisao atual = (ResultadoDecisao) session.getAttribute("resultado");
        if (atual != null) {
            model.addAttribute("cliente", atual.getCliente());
            model.addAttribute("pesos", atual.getPesos());
            model.addAttribute("cenarioSelecionado", atual.getCenario().getNome());
        } else {
            model.addAttribute("cliente", PagesController.clienteInicialParaUsuario(usuarioAtual));
            model.addAttribute("pesos", new Pesos());
            model.addAttribute("cenarioSelecionado", "Base");
        }
        model.addAttribute("cenarios", cenarios.todos());
        model.addAttribute("indicadores", cenarios.getIndicadores());
        model.addAttribute("simulacoesSalvas", simulacoesSalvas.listar(8));
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
            model.addAttribute("simulacoesSalvas", simulacoesSalvas.listar(8));
            return "simulacao";
        }

        if (cliente.getNome() == null || cliente.getNome().isBlank()) {
            cliente.setNome("Cliente");
        }

        // Calcula todos os cenários disponíveis (Base, Otimista, Pessimista e Personalizado).
        Map<String, ResultadoDecisao> porCenario = avaliarCenarios(cliente, pesos);
        ResultadoDecisao escolhido = porCenario.getOrDefault(cenario, porCenario.get("Base"));
        String idSalvo = simulacoesSalvas.salvar(cliente, pesos, escolhido);
        PagesController.registrarNaSessao(session, banco.usuarioAtual(), escolhido, porCenario, idSalvo);

        relatorios.registrar("Comparativo de alternativas",
                "Simulação (" + escolhido.getCenario().getNome() + ") · "
                        + String.format(java.util.Locale.forLanguageTag("pt-BR"), "R$ %,.2f", cliente.getAporte())
                        + " · " + escolhido.getTotalElegiveis() + " elegíveis",
                cliente.getNome(), "Simulação", "investdecide-comparativo.pdf",
                pdf.comparativo(escolhido));

        return montarResultado(model, cliente, pesos, escolhido.getCenario().getNome(), escolhido, porCenario);
    }

    /** Reabre uma simulação persistida no banco H2 da conta do usuário autenticado. */
    @GetMapping("/simulacao/salva/{id}")
    public String abrirSalva(@PathVariable String id, Model model, HttpSession session) {
        var opt = simulacoesSalvas.buscar(id);
        if (opt.isEmpty() || opt.get().cliente() == null || opt.get().pesos() == null) {
            return "redirect:/dashboard";
        }
        var entrada = opt.get();
        Map<String, ResultadoDecisao> porCenario = avaliarCenarios(entrada.cliente(), entrada.pesos());
        ResultadoDecisao escolhido = porCenario.getOrDefault(entrada.cenario(), porCenario.get("Base"));
        PagesController.registrarNaSessao(session, banco.usuarioAtual(), escolhido, porCenario, id);
        return montarResultado(model, entrada.cliente(), entrada.pesos(), escolhido.getCenario().getNome(), escolhido, porCenario);
    }

    @PostMapping("/simulacao/salva/{id}/excluir")
    public String excluirSalva(@PathVariable String id,
                               @RequestParam(name = "origem", defaultValue = "/dashboard") String origem,
                               HttpSession session) {
        simulacoesSalvas.excluir(id);
        if (id.equals(session.getAttribute("simulacaoSalvaId")) || simulacoesSalvas.total() == 0) {
            PagesController.limparSessaoDecisao(session);
        }
        return "redirect:" + ("/simulacao".equals(origem) ? "/simulacao" : "/dashboard");
    }

    /** Reabre o resultado da última simulação (sem voltar ao formulário). */
    @GetMapping("/simulacao/resultado")
    public String resultado(@RequestParam(name = "cenario", defaultValue = "Base") String cenario,
                            Model model, HttpSession session) {
        String usuarioAtual = banco.usuarioAtual();
        PagesController.validarSessaoUsuario(session, usuarioAtual);
        @SuppressWarnings("unchecked")
        Map<String, ResultadoDecisao> porCenario = (Map<String, ResultadoDecisao>) session.getAttribute("resultadosPorCenario");
        if (porCenario == null || porCenario.isEmpty()) {
            return "redirect:/simulacao";
        }
        ResultadoDecisao escolhido = porCenario.getOrDefault(cenario, porCenario.get("Base"));
        PagesController.registrarNaSessao(session, usuarioAtual, escolhido, porCenario,
                (String) session.getAttribute("simulacaoSalvaId"));
        return montarResultado(model, escolhido.getCliente(), escolhido.getPesos(),
                escolhido.getCenario().getNome(), escolhido, porCenario);
    }

    private Map<String, ResultadoDecisao> avaliarCenarios(Cliente cliente, Pesos pesos) {
        var produtos = catalogo.listar();
        Map<String, ResultadoDecisao> porCenario = new LinkedHashMap<>();
        for (Cenario c : cenarios.todos()) {
            porCenario.put(c.getNome(), decisao.avaliar(cliente, produtos, c, pesos));
        }
        return porCenario;
    }

    private String montarResultado(Model model, Cliente cliente, Pesos pesos, String cenario,
                                   ResultadoDecisao resultado, Map<String, ResultadoDecisao> porCenario) {
        model.addAttribute("resultado", resultado);
        model.addAttribute("destaques", resultado.getRanking().stream().limit(3).toList());
        model.addAttribute("top8", resultado.getRanking().stream().limit(8).toList());
        model.addAttribute("cliente", cliente);
        model.addAttribute("pesos", pesos);
        model.addAttribute("cenarioSelecionado", cenario);
        model.addAttribute("cenariosDisponiveis", CENARIOS_DA_SIMULACAO);
        model.addAttribute("porCenario", porCenario != null ? porCenario : Map.of());
        model.addAttribute("chartTop8", resultado.getRanking().stream().limit(8)
                .map(a -> Map.<String, Object>of(
                        "nome", a.getNome().length() > 28 ? a.getNome().substring(0, 26) + "…" : a.getNome(),
                        "score", Math.round(a.getScore() * 10.0) / 10.0,
                        "retorno", Math.round(a.getRetornoLiquido() * 100.0) / 100.0,
                        "valor", Math.round(a.getValorFinal() * 100.0) / 100.0))
                .toList());
        List<Map<String, Object>> serieCenarios = new ArrayList<>();
        if (porCenario != null) {
            porCenario.forEach((nome, res) -> {
                var lider = res.getRecomendada();
                serieCenarios.add(Map.of(
                        "cenario", nome,
                        "lider", lider != null ? lider.getNome() : "Sem elegíveis",
                        "valor", lider != null ? Math.round(lider.getValorFinal() * 100.0) / 100.0 : 0.0,
                        "retorno", lider != null ? Math.round(lider.getRetornoLiquido() * 100.0) / 100.0 : 0.0));
            });
        }
        model.addAttribute("chartCenarios", serieCenarios);
        return "resultado";
    }

    @GetMapping("/simulacao/ranking")
    public String ranking(@RequestParam(name = "ordem", defaultValue = "score") String ordem,
                          Model model, HttpSession session) {
        PagesController.validarSessaoUsuario(session, banco.usuarioAtual());
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
