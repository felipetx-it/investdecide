package br.com.investdecide.web;

import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.CatalogoService;
import br.com.investdecide.servico.CenarioService;
import br.com.investdecide.servico.DadosMercadoService;
import br.com.investdecide.servico.RelatorioService;
import br.com.investdecide.servico.DecisaoService;
import br.com.investdecide.servico.SimulacaoSalvaService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Telas institucionais: login, dashboard e central de relatórios. */
@Controller
public class PagesController {

    private final CatalogoService catalogo;
    private final DecisaoService decisao;
    private final CenarioService cenarios;
    private final DadosMercadoService base;
    private final RelatorioService relatorios;
    private final SimulacaoSalvaService simulacoesSalvas;

    public PagesController(CatalogoService catalogo, DecisaoService decisao, CenarioService cenarios,
                           DadosMercadoService base, RelatorioService relatorios,
                           SimulacaoSalvaService simulacoesSalvas) {
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.cenarios = cenarios;
        this.base = base;
        this.relatorios = relatorios;
        this.simulacoesSalvas = simulacoesSalvas;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, HttpSession session) {
        Cliente cliente = clientePadrao();
        Pesos pesos = new Pesos();
        Cenario cenario = cenarios.base();
        ResultadoDecisao resultado = decisao.avaliar(cliente, catalogo.listar(), cenario, pesos);

        if (session.getAttribute("resultado") == null) {
            Map<String, ResultadoDecisao> porCenario = new LinkedHashMap<>();
            for (Cenario c : cenarios.todos()) {
                porCenario.put(c.getNome(), c.getNome().equals("Base")
                        ? resultado
                        : decisao.avaliar(cliente, catalogo.listar(), c, pesos));
            }
            session.setAttribute("resultadosPorCenario", porCenario);
            session.setAttribute("resultado", resultado);
        }

        model.addAttribute("cliente", cliente);
        model.addAttribute("resultado", resultado);
        model.addAttribute("base", base);
        model.addAttribute("indicadores", cenarios.getIndicadores());
        model.addAttribute("destaques", resultado.getRanking().stream().limit(3).toList());
        model.addAttribute("top10", resultado.getRanking().stream().limit(10).toList());
        model.addAttribute("grupos", resultado.getGrupos());
        model.addAttribute("simulacoesSalvas", simulacoesSalvas.listar(6));
        model.addAttribute("chartPontos", resultado.getRanking().stream()
                .map(a -> Map.<String, Object>of(
                        "nome", a.getNome(),
                        "x", Math.round(a.getVolatilidade() * 100.0) / 100.0,
                        "y", Math.round(a.getRetornoLiquido() * 100.0) / 100.0,
                        "grupo", a.getGrupo(),
                        "score", Math.round(a.getScore() * 10.0) / 10.0))
                .toList());
        model.addAttribute("chartGrupos", resultado.getGrupos().stream()
                .map(g -> Map.<String, Object>of(
                        "nome", g.getNome(),
                        "retorno", Math.round(g.getRetornoMedio() * 100.0) / 100.0,
                        "carga", Math.round(g.getCargaMedia() * 100.0) / 100.0,
                        "qtd", g.getQuantidade()))
                .toList());
        model.addAttribute("alertas", List.of(
                Map.of("tipo", "warning", "icone", "bi-exclamation-triangle",
                        "titulo", "Alternativa restrita pelo perfil",
                        "texto", resultado.getAvaliacoes().stream().filter(a -> !a.isElegivel()).count()
                                + " alternativas foram eliminadas pelos filtros de elegibilidade deste cliente."),
                Map.of("tipo", "info", "icone", "bi-diagram-3",
                        "titulo", "Agrupamento recalculado",
                        "texto", resultado.getGrupos().size() + " grupos identificados (silhueta "
                                + String.format(new java.util.Locale("pt", "BR"), "%.2f", resultado.getSilhueta()) + ")."),
                Map.of("tipo", "neutral", "icone", "bi-database-check",
                        "titulo", "Liquidez XP + CVM e banco H2",
                        "texto", "Base de " + cenarios.getIndicadores().getDataReferencia()
                                + " · come-cotas semestral ativo · " + simulacoesSalvas.total() + " simulação(ões) salva(s).")
        ));
        return "dashboard";
    }

    @GetMapping("/relatorios")
    public String relatorios(Model model) {
        model.addAttribute("relatorios", relatorios.listar());
        return "relatorios";
    }

    static Cliente clientePadrao() {
        Cliente cliente = new Cliente();
        cliente.setNome("Ricardo Almeida");
        cliente.setPerfil(br.com.investdecide.dominio.PerfilRisco.MODERADO);
        cliente.setAporte(10_000);
        cliente.setHorizonteMeses(36);
        cliente.setLiquidezDias(30);
        cliente.setObjetivo("Fazer crescer");
        cliente.setRendaBruta(180_000);
        cliente.setDeducoes(18_000);
        cliente.setModelo(Cliente.ModeloDeclaracao.COMPLETA);
        cliente.setRegime(Cliente.RegimePrevidencia.REGRESSIVA);
        return cliente;
    }
}
