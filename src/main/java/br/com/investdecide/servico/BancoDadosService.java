package br.com.investdecide.servico;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Inicialização do esquema relacional H2 e resolução do usuário autenticado na sessão.
 *
 * <p>Substitui o armazenamento anterior em memória/JSON por tabelas relacionais indexadas por
 * {@code usuario_email}, garantindo isolamento de contas entre diferentes assessores.</p>
 */
@Service
public class BancoDadosService {

    public static final String USUARIO_PADRAO = "consultor@investdecide.com.br";

    private final JdbcTemplate jdbc;

    public BancoDadosService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        inicializarEsquema();
    }

    private void inicializarEsquema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS usuarios (
                    id IDENTITY PRIMARY KEY,
                    email VARCHAR(180) UNIQUE NOT NULL,
                    nome VARCHAR(180) NOT NULL,
                    cargo VARCHAR(120) NOT NULL,
                    senha_hash VARCHAR(255) NOT NULL,
                    criado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS simulacoes_salvas (
                    id VARCHAR(64) PRIMARY KEY,
                    usuario_email VARCHAR(180) NOT NULL,
                    cliente_nome VARCHAR(180) NOT NULL,
                    dados_json CLOB NOT NULL,
                    cenario VARCHAR(60) NOT NULL,
                    recomendada_nome VARCHAR(220),
                    recomendada_score DOUBLE,
                    recomendada_valor_final DOUBLE,
                    recomendada_retorno_liq DOUBLE,
                    total_elegiveis INT,
                    criado_em VARCHAR(40) NOT NULL,
                    ordem_ts BIGINT NOT NULL
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS produtos_manuais (
                    id VARCHAR(64) PRIMARY KEY,
                    usuario_email VARCHAR(180) NOT NULL,
                    dados_json CLOB NOT NULL,
                    criado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS cenarios_usuario (
                    usuario_email VARCHAR(180) PRIMARY KEY,
                    dados_json CLOB NOT NULL,
                    atualizado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS relatorios_gerados (
                    id VARCHAR(64) PRIMARY KEY,
                    usuario_email VARCHAR(180) NOT NULL,
                    titulo VARCHAR(200) NOT NULL,
                    descricao VARCHAR(400) NOT NULL,
                    cliente VARCHAR(180) NOT NULL,
                    categoria VARCHAR(60) NOT NULL,
                    classe VARCHAR(40) NOT NULL,
                    gerado_em VARCHAR(40) NOT NULL,
                    link VARCHAR(255) NOT NULL,
                    nome_arquivo VARCHAR(120),
                    conteudo_pdf BLOB,
                    ordem_ts BIGINT NOT NULL
                )
                """);

        jdbc.execute("ALTER TABLE relatorios_gerados ADD COLUMN IF NOT EXISTS nome_arquivo VARCHAR(120)");
        jdbc.execute("ALTER TABLE relatorios_gerados ADD COLUMN IF NOT EXISTS conteudo_pdf BLOB");
        jdbc.execute("DELETE FROM relatorios_gerados WHERE conteudo_pdf IS NULL");

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_sim_user ON simulacoes_salvas (usuario_email, ordem_ts)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_prod_user ON produtos_manuais (usuario_email)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rel_user ON relatorios_gerados (usuario_email, ordem_ts)");
    }

    /** E-mail normalizado do usuário logado ou conta padrão em execuções diretas de serviço. */
    public String usuarioAtual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String nome = auth.getName();
            if (nome != null && !nome.isBlank()) {
                return nome.trim().toLowerCase();
            }
        }
        return USUARIO_PADRAO;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
