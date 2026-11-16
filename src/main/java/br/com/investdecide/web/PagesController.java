package br.com.investdecide.web;

import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
import br.com.investdecide.servico.BancoDadosService;
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
    private final BancoDadosService banco;

    public PagesController(CatalogoService catalogo, DecisaoService decisao, CenarioService cenarios,
                           DadosMercadoService base, RelatorioService relatorios,
                           SimulacaoSalvaService simulacoesSalvas, BancoDadosService banco) {
        this.catalogo = catalogo;
        this.decisao = decisao;
        this.cenarios = cenarios;
        this.base = base;
        this.relatorios = relatorios;
        this.simulacoesSalvas = simulacoesSalvas;
        this.banco = banco;
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
        String usuarioAtual = banco.usuarioAtual();
        validarSessaoUsuario(session, usuarioAtual);

        List<SimulacaoSalvaService.ResumoSimulacao> salvas = simulacoesSalvas.listar(6);
        ResultadoDecisao resultado = (ResultadoDecisao) session.getAttribute("resultado");

        if (resultado == null) {
            if (!salvas.isEmpty() && !BancoDadosService.USUARIO_PADRAO.equalsIgnoreCase(usuarioAtual)) {
                var ultima = salvas.get(0);
                if (ultima.entrada() != null && ultima.entrada().cliente() != null && ultima.entrada().pesos() != null) {
                    Map<String, ResultadoDecisao> porCenario = avaliarCenarios(
                            ultima.entrada().cliente(), ultima.entrada().pesos());
                    resultado = porCenario.getOrDefault(ultima.cenario(), porCenario.get("Base"));
                    registrarNaSessao(session, usuarioAtual, resultado, porCenario, ultima.id());
                }
            } else if (BancoDadosService.USUARIO_PADRAO.equalsIgnoreCase(usuarioAtual)) {
                Map<String, ResultadoDecisao> porCenario = avaliarCenarios(clientePadrao(), new Pesos());
                resultado = porCenario.get("Base");
                registrarNaSessao(session, usuarioAtual, resultado, porCenario, null);
            }
        }

        model.addAttribute("base", base);
        model.addAttribute("indicadores", cenarios.getIndicadores());
        model.addAttribute("simulacoesSalvas", salvas);

        if (resultado != null) {
            model.addAttribute("cliente", resultado.getCliente());
            model.addAttribute("resultado", resultado);
            model.addAttribute("destaques", resultado.getRanking().stream().limit(3).toList());
            model.addAttribute("top10", resultado.getRanking().stream().limit(10).toList());
            model.addAttribute("grupos", resultado.getGrupos());
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
                                    + String.format(new java.util.Locale("pt", "BR"), "%.2f", resultado.getSilhueta()) + ").")
            ));
        } else {
            model.addAttribute("cliente", null);
            model.addAttribute("resultado", null);
            model.addAttribute("destaques", List.of());
            model.addAttribute("top10", List.of());
            model.addAttribute("grupos", List.of());
            model.addAttribute("chartPontos", List.of());
            model.addAttribute("chartGrupos", List.of());
            model.addAttribute("alertas", List.of());
        }
        return "dashboard";
    }

    private Map<String, ResultadoDecisao> avaliarCenarios(Cliente cliente, Pesos pesos) {
        var produtos = catalogo.listar();
        Map<String, ResultadoDecisao> porCenario = new LinkedHashMap<>();
        for (Cenario c : cenarios.todos()) {
            porCenario.put(c.getNome(), decisao.avaliar(cliente, produtos, c, pesos));
        }
        return porCenario;
    }

    static void validarSessaoUsuario(HttpSession session, String usuarioAtual) {
        if (session == null) {
            return;
        }
        Object dono = session.getAttribute("resultadoUsuario");
        if (dono == null || (usuarioAtual != null && !usuarioAtual.equalsIgnoreCase(String.valueOf(dono)))) {
            limparSessaoDecisao(session);
        }
    }

    static void limparSessaoDecisao(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute("resultado");
        session.removeAttribute("resultadosPorCenario");
        session.removeAttribute("resultadoUsuario");
        session.removeAttribute("simulacaoSalvaId");
    }

    static void registrarNaSessao(HttpSession session, String usuarioAtual, ResultadoDecisao resultado,
                                  Map<String, ResultadoDecisao> porCenario, String simulacaoSalvaId) {
        if (session == null) {
            return;
        }
        session.setAttribute("resultado", resultado);
        session.setAttribute("resultadosPorCenario", porCenario);
        session.setAttribute("resultadoUsuario", usuarioAtual);
        if (simulacaoSalvaId != null) {
            session.setAttribute("simulacaoSalvaId", simulacaoSalvaId);
        } else {
            session.removeAttribute("simulacaoSalvaId");
        }
    }

    static Cliente clienteInicialParaUsuario(String email) {
        Cliente cliente = clientePadrao();
        if (!BancoDadosService.USUARIO_PADRAO.equalsIgnoreCase(email)) {
            cliente.setNome("");
        }
        return cliente;
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
