package br.com.investdecide;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("products", List.of(
            Map.of("name", "Atlas Renda Fixa FIRF", "type", "Renda fixa", "return", "12,4%", "risk", "Baixo", "riskClass", "risk-low", "tag", "Conservador"),
            Map.of("name", "Horizonte Multimercado", "type", "Multimercado", "return", "14,8%", "risk", "Médio", "riskClass", "risk-medium", "tag", "Moderado"),
            Map.of("name", "Vita Prev RF PGBL", "type", "Previdência · PGBL", "return", "11,9%", "risk", "Baixo", "riskClass", "risk-low", "tag", "Previdência")
        ));
        return "dashboard";
    }

    @GetMapping("/simulacao")
    public String simulacao(Model model) {
        model.addAttribute("alternativas", List.of(
            Map.of("name", "Atlas Renda Fixa FIRF", "type", "Fundo tradicional", "gross", "R$ 14.260", "taxes", "R$ 1.064", "costs", "R$ 96", "net", "R$ 13.100", "return", "9,2% a.a.", "risk", "Baixo", "riskClass", "risk-low", "recommended", false),
            Map.of("name", "Vita Prev RF PGBL", "type", "Previdência · PGBL", "gross", "R$ 14.120", "taxes", "R$ 610*", "costs", "R$ 140", "net", "R$ 13.370", "return", "9,0% a.a.", "risk", "Baixo", "riskClass", "risk-low", "recommended", true),
            Map.of("name", "Horizonte Multimercado", "type", "Fundo tradicional", "gross", "R$ 15.040", "taxes", "R$ 1.510", "costs", "R$ 180", "net", "R$ 13.350", "return", "11,1% a.a.", "risk", "Médio", "riskClass", "risk-medium", "recommended", false)
        ));
        return "simulacao";
    }

    @GetMapping("/relatorios")
    public String relatorios(Model model) {
        model.addAttribute("relatorios", List.of(
            Map.of("title", "Comparativo de alternativas", "description", "Retorno líquido, custos e tributação por produto", "date", "18 out. 2026", "type", "Simulação", "typeClass", "tag-blue", "status", "Disponível"),
            Map.of("title", "Sensibilidade por cenário", "description", "Impacto de juros, bolsa e inflação no patrimônio projetado", "date", "17 out. 2026", "type", "Cenários", "typeClass", "tag-purple", "status", "Disponível"),
            Map.of("title", "Resumo de eficiência tributária", "description", "Exemplo comparativo entre fundo tradicional e previdência", "date", "15 out. 2026", "type", "Tributação", "typeClass", "tag-green", "status", "Disponível"),
            Map.of("title", "Evolução patrimonial estimada", "description", "Projeção do aporte ao longo do horizonte selecionado", "date", "12 out. 2026", "type", "Projeção", "typeClass", "tag-orange", "status", "Disponível")
        ));
        return "relatorios";
    }
}
