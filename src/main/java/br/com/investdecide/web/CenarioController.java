package br.com.investdecide.web;

import br.com.investdecide.dominio.CenarioForm;
import br.com.investdecide.servico.CenarioService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Configuração dos cenários: indicadores de mercado e choques dos cenários alternativos. */
@Controller
public class CenarioController {

    private final CenarioService cenarios;

    public CenarioController(CenarioService cenarios) {
        this.cenarios = cenarios;
    }

    @GetMapping("/cenarios")
    public String tela(Model model, @RequestParam(name = "ok", required = false) String ok) {
        CenarioForm form = new CenarioForm();
        form.setIndicadores(cenarios.getIndicadores());
        form.setOtimista(cenarios.getOtimista().copia());
        form.setPessimista(cenarios.getPessimista().copia());
        form.setPersonalizado(cenarios.getPersonalizado().copia());

        model.addAttribute("form", form);
        model.addAttribute("lista", cenarios.todos());
        model.addAttribute("ok", ok);
        return "cenarios";
    }

    @PostMapping("/cenarios")
    public String salvar(@ModelAttribute("form") CenarioForm form) {
        cenarios.atualizar(form.getIndicadores(), form.getOtimista(), form.getPessimista(), form.getPersonalizado());
        return "redirect:/cenarios?ok=Cenários atualizados com sucesso.";
    }
}
