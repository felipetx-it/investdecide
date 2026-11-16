package br.com.investdecide.web;

import br.com.investdecide.servico.UsuarioService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Cadastro de novas contas de consultores/usuários. */
@Controller
public class ContaController {

    private final UsuarioService usuarios;

    public ContaController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/cadastro")
    public String telaCadastro() {
        return "cadastro";
    }

    @PostMapping("/cadastro")
    public String cadastrar(@RequestParam String nome,
                            @RequestParam(required = false, defaultValue = "Consultor de investimentos") String cargo,
                            @RequestParam String email,
                            @RequestParam String senha,
                            Model model) {
        try {
            usuarios.registrar(nome, cargo, email, senha);
            return "redirect:/login?cadastrado";
        } catch (IllegalArgumentException e) {
            model.addAttribute("erro", e.getMessage());
            model.addAttribute("nome", nome);
            model.addAttribute("cargo", cargo);
            model.addAttribute("email", email);
            return "cadastro";
        }
    }
}
