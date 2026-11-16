package br.com.investdecide.servico;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Cadastro e autenticação multiusuário persistidos na tabela {@code usuarios}.
 *
 * <p>As contas iniciais de demonstração só são criadas quando a respectiva senha é
 * informada por configuração ({@code investdecide.seed.*.senha}), normalmente via
 * variáveis de ambiente. Sem a senha, a conta é ignorada — nenhuma senha fica no código.</p>
 */
@Service
public class UsuarioService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    public record PerfilUsuario(String email, String nome, String cargo, String iniciais) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder codificador;
    private final String senhaConsultor;
    private final String senhaAnalista;
    private final String senhaTeste;

    public UsuarioService(BancoDadosService banco, PasswordEncoder codificador,
                          @Value("${investdecide.seed.consultor.senha:}") String senhaConsultor,
                          @Value("${investdecide.seed.analista.senha:}") String senhaAnalista,
                          @Value("${investdecide.seed.teste.senha:}") String senhaTeste) {
        this.jdbc = banco.jdbc();
        this.codificador = codificador;
        this.senhaConsultor = senhaConsultor;
        this.senhaAnalista = senhaAnalista;
        this.senhaTeste = senhaTeste;
        semearContasIniciais();
    }

    private void semearContasIniciais() {
        garantirUsuario(
                BancoDadosService.USUARIO_PADRAO,
                "Mariana Costa",
                "Assessora de investimentos",
                senhaConsultor
        );
        garantirUsuario(
                "analista@investdecide.com.br",
                "Lucas Ferreira",
                "Analista de alocação",
                senhaAnalista
        );
        garantirUsuario(
                "teste@investdecide.com.br",
                "Usuário de Teste",
                "Avaliador",
                senhaTeste
        );
    }

    private void garantirUsuario(String email, String nome, String cargo, String senhaPura) {
        if (senhaPura == null || senhaPura.isBlank()) {
            log.info("Senha inicial não configurada para {}; a conta não será criada.", email);
            return;
        }
        if (senhaPura.length() < 6) {
            log.warn("Senha inicial de {} tem menos de 6 caracteres; a conta não será criada.", email);
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usuarios WHERE LOWER(email) = LOWER(?)",
                Integer.class, email);
        if (count == null || count == 0) {
            jdbc.update(
                    "INSERT INTO usuarios (email, nome, cargo, senha_hash) VALUES (?, ?, ?, ?)",
                    email.toLowerCase(Locale.ROOT), nome, cargo, codificador.encode(senhaPura));
            log.info("Conta inicial criada: {}", email);
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("Informe o e-mail de acesso.");
        }
        String emailNorm = username.trim().toLowerCase(Locale.ROOT);
        List<UserDetails> encontrados = jdbc.query(
                "SELECT email, senha_hash FROM usuarios WHERE LOWER(email) = ?",
                (rs, rowNum) -> User.withUsername(rs.getString("email"))
                        .password(rs.getString("senha_hash"))
                        .roles("CONSULTOR")
                        .build(),
                emailNorm);
        if (encontrados.isEmpty()) {
            throw new UsernameNotFoundException("Usuário não encontrado: " + emailNorm);
        }
        return encontrados.get(0);
    }

    public PerfilUsuario registrar(String nome, String cargo, String email, String senha) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Informe seu nome completo.");
        }
        if (email == null || !email.contains("@") || email.isBlank()) {
            throw new IllegalArgumentException("Informe um e-mail válido.");
        }
        if (senha == null || senha.length() < 6) {
            throw new IllegalArgumentException("A senha deve ter pelo menos 6 caracteres.");
        }
        String emailNorm = email.trim().toLowerCase(Locale.ROOT);
        Integer existe = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usuarios WHERE LOWER(email) = ?",
                Integer.class, emailNorm);
        if (existe != null && existe > 0) {
            throw new IllegalArgumentException("Já existe uma conta cadastrada com este e-mail.");
        }
        String cargoLimpo = (cargo == null || cargo.isBlank()) ? "Consultor de investimentos" : cargo.trim();
        jdbc.update(
                "INSERT INTO usuarios (email, nome, cargo, senha_hash) VALUES (?, ?, ?, ?)",
                emailNorm, nome.trim(), cargoLimpo, codificador.encode(senha));
        return perfil(emailNorm, nome.trim(), cargoLimpo);
    }

    public PerfilUsuario buscarPerfil(String email) {
        if (email == null || email.isBlank()) {
            return perfil(BancoDadosService.USUARIO_PADRAO, "Mariana Costa", "Assessora de investimentos");
        }
        String emailNorm = email.trim().toLowerCase(Locale.ROOT);
        List<PerfilUsuario> lista = jdbc.query(
                "SELECT email, nome, cargo FROM usuarios WHERE LOWER(email) = ?",
                (rs, rowNum) -> perfil(rs.getString("email"), rs.getString("nome"), rs.getString("cargo")),
                emailNorm);
        if (!lista.isEmpty()) {
            return lista.get(0);
        }
        return perfil(emailNorm, emailNorm, "Consultor de investimentos");
    }

    public int totalUsuarios() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class);
        return total == null ? 0 : total;
    }

    public void excluirPorEmail(String email) {
        if (email != null && !BancoDadosService.USUARIO_PADRAO.equalsIgnoreCase(email)) {
            limparDadosPorEmail(email);
            jdbc.update("DELETE FROM usuarios WHERE LOWER(email) = LOWER(?)", email);
        }
    }

    /** Remove simulações, produtos manuais, cenários e relatórios de uma conta mantendo o login. */
    public void limparDadosPorEmail(String email) {
        if (email != null && !email.isBlank()) {
            jdbc.update("DELETE FROM simulacoes_salvas WHERE LOWER(usuario_email) = LOWER(?)", email);
            jdbc.update("DELETE FROM produtos_manuais WHERE LOWER(usuario_email) = LOWER(?)", email);
            jdbc.update("DELETE FROM cenarios_usuario WHERE LOWER(usuario_email) = LOWER(?)", email);
            jdbc.update("DELETE FROM relatorios_gerados WHERE LOWER(usuario_email) = LOWER(?)", email);
        }
    }

    private static PerfilUsuario perfil(String email, String nome, String cargo) {
        return new PerfilUsuario(email, nome, cargo, calcularIniciais(nome));
    }

    private static String calcularIniciais(String nome) {
        if (nome == null || nome.isBlank()) {
            return "ID";
        }
        String[] partes = nome.trim().split("\\s+");
        if (partes.length == 1) {
            return partes[0].substring(0, Math.min(2, partes[0].length())).toUpperCase(Locale.ROOT);
        }
        return (partes[0].substring(0, 1) + partes[partes.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }
}
