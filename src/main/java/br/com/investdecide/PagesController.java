package br.com.investdecide;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;

/**
 * Controller do protótipo visual.
 *
 * <p>Todos os dados são fictícios e servem apenas para demonstrar o fluxo de apoio à decisão:
 * contexto do cliente → alternativas elegíveis → ranking com justificativa → relatório.
 * A lógica de cálculo, a tributação e o escore ainda não estão implementados.</p>
 */
@Controller
public class PagesController {

    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** Entrada demonstrativa: qualquer envio leva ao painel. Não há autenticação real. */
    @PostMapping("/entrar")
    public String entrar() {
        return "redirect:/dashboard";
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("client", clientInFocus());

        model.addAttribute("alerts", List.of(
                alert("warning", "bi-exclamation-triangle",
                        "Alternativa deixou de ser elegível",
                        "Horizonte Multimercado exige carência de 90 dias; a necessidade de liquidez do cliente é de 30 dias."),
                alert("info", "bi-clock-history",
                        "Simulação próxima de expirar",
                        "O comparativo de 18/10 usou o cenário de 15/10. Revalidar as premissas antes de apresentar ao cliente."),
                alert("neutral", "bi-shield-check",
                        "Regra tributária atualizada",
                        "A base de regras passou para a versão 1.3 em 01/10/2026. Simulações anteriores permanecem na versão 1.2.")
        ));

        model.addAttribute("simulations", List.of(
                simulation("18/10/2026", "Ricardo Almeida", "R$ 10.000", "Vita Prev RF PGBL", "R$ 13.370", "Concluída", "status-done"),
                simulation("14/10/2026", "Ricardo Almeida", "R$ 10.000", "Atlas Renda Fixa FIRF", "R$ 13.100", "Revisar", "status-review"),
                simulation("02/10/2026", "Beatriz Nunes", "R$ 45.000", "Horizonte Multimercado", "R$ 58.420", "Concluída", "status-done"),
                simulation("27/09/2026", "Carlos Menezes", "R$ 25.000", "Atlas Renda Fixa FIRF", "R$ 31.900", "Concluída", "status-done")
        ));

        model.addAttribute("products", List.of(
                product("Atlas Renda Fixa FIRF", "Renda fixa", "12,4%", "Baixo", "risk-low",
                        "Conservador", true, "Elegível"),
                product("Horizonte Multimercado", "Multimercado", "14,8%", "Médio", "risk-medium",
                        "Moderado", false, "Carência de 90 dias"),
                product("Vita Prev RF PGBL", "Previdência · PGBL", "11,9%", "Baixo", "risk-low",
                        "Previdência", true, "Elegível")
        ));

        return "dashboard";
    }

    // ----------------------------------------------------------------- simulação

    @GetMapping("/simulacao")
    public String simulacao(Model model) {
        model.addAttribute("client", clientInFocus());

        model.addAttribute("criteria", List.of(
                criterion("Perfil de risco", "até Moderado", "bi-shield-check"),
                criterion("Liquidez necessária", "resgate em até 30 dias", "bi-droplet"),
                criterion("Horizonte", "3 anos", "bi-calendar3"),
                criterion("Aporte mínimo", "R$ 10.000", "bi-cash-coin"),
                criterion("Tributação", "declaração completa", "bi-receipt")
        ));

        model.addAttribute("weights", List.of(
                weight("Retorno líquido projetado", "40%", 40),
                weight("Risco (volatilidade e drawdown)", "25%", 25),
                weight("Eficiência tributária", "20%", 20),
                weight("Liquidez", "15%", 15)
        ));

        model.addAttribute("ranking", List.of(
                ranked(1, "Vita Prev RF PGBL", "Previdência · PGBL", "87", "R$ 13.370,00", "risk-low",
                        "Maior patrimônio líquido no cenário base e melhor eficiência tributária para renda tributável acima de R$ 50 mil.",
                        "Exige declaração completa e não deve ser resgatada antes do horizonte previsto."),
                ranked(2, "Horizonte Multimercado", "Multimercado", "74", "R$ 13.350,00", "risk-medium",
                        "Retorno bruto mais alto e boa diversificação, mas com volatilidade acima da média do perfil.",
                        "Carência de 90 dias conflita com a necessidade de liquidez: aparece apenas como referência."),
                ranked(3, "Atlas Renda Fixa FIRF", "Fundo tradicional", "69", "R$ 13.100,00", "risk-low",
                        "Menor volatilidade e liquidez diária, adequado como reserva de emergência.",
                        "Tributação reduz o resultado líquido frente às demais alternativas.")
        ));

        model.addAttribute("alternativas", List.of(
                alternative("Vita Prev RF PGBL", "Previdência · PGBL", "R$ 14.120", "R$ 610*", "R$ 140",
                        "R$ 13.370", "9,0% a.a.", "Baixo", "risk-low", true, "Elegível", "87", 1, true),
                alternative("Horizonte Multimercado", "Multimercado", "R$ 15.040", "R$ 1.510", "R$ 180",
                        "R$ 13.350", "11,1% a.a.", "Médio", "risk-medium", false, "Carência de 90 dias", "74", 2, false),
                alternative("Atlas Renda Fixa FIRF", "Fundo tradicional", "R$ 14.260", "R$ 1.064", "R$ 96",
                        "R$ 13.100", "9,2% a.a.", "Baixo", "risk-low", true, "Elegível", "69", 3, false),
                alternative("Atlas Ações Brasil", "Ações", "R$ 16.480", "R$ 2.472", "R$ 260",
                        "R$ 13.748", "13,2% a.a.", "Alto", "risk-high", false, "Risco acima do perfil", "61", 4, false),
                alternative("Vita Prev RF VGBL", "Previdência · VGBL", "R$ 13.980", "R$ 380", "R$ 150",
                        "R$ 13.450", "8,8% a.a.", "Baixo", "risk-low", true, "Elegível", "66", 5, false)
        ));

        return "simulacao";
    }

    // ---------------------------------------------------------------- relatórios

    @GetMapping("/relatorios")
    public String relatorios(Model model) {
        model.addAttribute("relatorios", List.of(
                report("Comparativo de alternativas",
                        "Retorno líquido, custos, tributação e elegibilidade por produto",
                        "18 out. 2026", "Simulação", "tag-blue", "Disponível", "Ricardo Almeida"),
                report("Sensibilidade por cenário",
                        "Impacto de juros, bolsa e inflação no patrimônio projetado",
                        "17 out. 2026", "Cenários", "tag-purple", "Disponível", "Ricardo Almeida"),
                report("Resumo de eficiência tributária",
                        "Comparativo entre fundo tradicional, PGBL e VGBL",
                        "15 out. 2026", "Tributação", "tag-green", "Disponível", "Beatriz Nunes"),
                report("Evolução patrimonial estimada",
                        "Projeção do aporte ao longo do horizonte selecionado",
                        "12 out. 2026", "Projeção", "tag-orange", "Disponível", "Carlos Menezes")
        ));
        return "relatorios";
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> clientInFocus() {
        return Map.of(
                "name", "Ricardo Almeida",
                "initials", "RA",
                "profile", "Moderado",
                "horizon", "3 anos",
                "liquidity", "Até 30 dias",
                "objective", "Fazer crescer",
                "since", "cliente desde 2021",
                "patrimony", "R$ 480 mil",
                "lastReview", "suitability revisado em 02/09/2026"
        );
    }

    private Map<String, Object> alert(String kind, String icon, String title, String text) {
        return Map.of("kind", kind, "icon", icon, "title", title, "text", text);
    }

    private Map<String, Object> simulation(String date, String client, String amount,
                                           String highlight, String net, String status, String statusClass) {
        return Map.of("date", date, "client", client, "amount", amount, "highlight", highlight,
                "net", net, "status", status, "statusClass", statusClass);
    }

    private Map<String, Object> product(String name, String type, String ret, String risk,
                                        String riskClass, String tag, boolean eligible, String eligibleLabel) {
        return Map.of("name", name, "type", type, "return", ret, "risk", risk, "riskClass", riskClass,
                "tag", tag, "eligible", eligible, "eligibleLabel", eligibleLabel);
    }

    private Map<String, Object> criterion(String label, String value, String icon) {
        return Map.of("label", label, "value", value, "icon", icon);
    }

    private Map<String, Object> weight(String label, String pct, int bar) {
        return Map.of("label", label, "pct", pct, "bar", bar);
    }

    private Map<String, Object> ranked(int position, String name, String type, String score, String net,
                                       String riskClass, String why, String caveat) {
        return Map.of("position", position, "name", name, "type", type, "score", score, "net", net,
                "riskClass", riskClass, "why", why, "caveat", caveat);
    }

    private Map<String, Object> alternative(String name, String type, String gross, String taxes, String costs,
                                            String net, String ret, String risk, String riskClass,
                                            boolean eligible, String eligibleLabel, String score,
                                            int rank, boolean recommended) {
        return Map.ofEntries(
                Map.entry("name", name),
                Map.entry("type", type),
                Map.entry("gross", gross),
                Map.entry("taxes", taxes),
                Map.entry("costs", costs),
                Map.entry("net", net),
                Map.entry("return", ret),
                Map.entry("risk", risk),
                Map.entry("riskClass", riskClass),
                Map.entry("eligible", eligible),
                Map.entry("eligibleLabel", eligibleLabel),
                Map.entry("score", score),
                Map.entry("rank", rank),
                Map.entry("recommended", recommended)
        );
    }

    private Map<String, Object> report(String title, String description, String date, String type,
                                       String typeClass, String status, String client) {
        return Map.of("title", title, "description", description, "date", date, "type", type,
                "typeClass", typeClass, "status", status, "client", client);
    }
}
