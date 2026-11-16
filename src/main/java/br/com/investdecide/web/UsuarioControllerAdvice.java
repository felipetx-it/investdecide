package br.com.investdecide.web;

import br.com.investdecide.servico.BancoDadosService;
import br.com.investdecide.servico.UsuarioService;
import br.com.investdecide.servico.UsuarioService.PerfilUsuario;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Disponibiliza o perfil do usuário autenticado para o layout (sidebar e topbar). */
@ControllerAdvice
public class UsuarioControllerAdvice {

    private final BancoDadosService banco;
    private final UsuarioService usuarios;

    public UsuarioControllerAdvice(BancoDadosService banco, UsuarioService usuarios) {
        this.banco = banco;
        this.usuarios = usuarios;
    }

    @ModelAttribute("usuarioAtual")
    public PerfilUsuario usuarioAtual() {
        return usuarios.buscarPerfil(banco.usuarioAtual());
    }
}
