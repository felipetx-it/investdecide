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

    public PagesController(CatalogoService catalogo, DecisaoService decisao, CenarioService cenarios,
                           DadosMercadoService base, RelatorioService relatorios) {
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.cenarios = cenarios;
        this.base = base;
        this.relatorios = relatorios;
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
        model.addAttribute("grupos", resultado.getGrupos());
        model.addAttribute("alertas", List.of(
                Map.of("tipo", "warning", "icone", "bi-exclamation-triangle",
                        "titulo", "Alternativa restrita pelo perfil",
                        "texto", resultado.getAvaliacoes().stream().filter(a -> !a.isElegivel()).count()
                                + " alternativas foram eliminadas pelos filtros de elegibilidade deste cliente."),
                Map.of("tipo", "info", "icone", "bi-diagram-3",
                        "titulo", "Agrupamento recalculado",
                        "texto", resultado.getGrupos().size() + " grupos identificados entre as alternativas elegíveis."),
                Map.of("tipo", "neutral", "icone", "bi-shield-check",
                        "titulo", "Premissas versionadas",
                        "texto", "Cenário base montado com os indicadores de " + cenarios.getIndicadores().getDataReferencia() + ".")
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
