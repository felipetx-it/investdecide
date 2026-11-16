package br.com.investdecide.servico;

import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.IndicadoresMercado;
import br.com.investdecide.dominio.Shocks;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Monta os cenários econômicos com persistência relacional por usuário no H2.
 *
 * <p>O cenário base é construído a partir dos indicadores de mercado (curva DI, inflação
 * implícita, dólar futuro e expectativa de Ibovespa). Os cenários otimista, pessimista e
 * personalizado aplicam choques configuráveis por usuário sobre essa base.</p>
 */
@Service
public class CenarioService {

    private record ConfigCenarios(IndicadoresMercado indicadores, Shocks otimista,
                                  Shocks pessimista, Shocks personalizado) {
    }

    private final ObjectMapper mapper;
    private final Path arquivo;
    private final BancoDadosService banco;
    private final JdbcTemplate jdbc;

    public CenarioService(ObjectMapper mapper, BancoDadosService banco,
                          @Value("${investdecide.dados:data}") String pasta) {
        this.mapper = mapper;
        this.banco = banco;
        this.jdbc = banco.jdbc();
        this.arquivo = Path.of(pasta, "cenarios.json");
        migrarJsonSeNecessario();
    }

    public Cenario base() {
        ConfigCenarios cfg = carregarDoUsuario();
        IndicadoresMercado ind = cfg.indicadores();
        return new Cenario("Base",
                "Indicadores de mercado de " + ind.getDataReferencia(),
                ind.getCurvaDi(),
                ind.getInflacaoImplicita(),
                ind.getDolarFuturo(),
                ind.getIbovespaEsperado());
    }

    public Cenario otimista() {
        ConfigCenarios cfg = carregarDoUsuario();
        return aplicar(cfg.indicadores(), cfg.otimista(), "Otimista", "Choques favoráveis sobre o cenário base");
    }

    public Cenario pessimista() {
        ConfigCenarios cfg = carregarDoUsuario();
        return aplicar(cfg.indicadores(), cfg.pessimista(), "Pessimista", "Choques adversos sobre o cenário base");
    }

    public Cenario personalizado() {
        ConfigCenarios cfg = carregarDoUsuario();
        return aplicar(cfg.indicadores(), cfg.personalizado(), "Personalizado", "Choques definidos pelo usuário");
    }

    public Cenario porNome(String nome) {
        if (nome == null) {
            return base();
        }
        return switch (nome) {
            case "Otimista" -> otimista();
            case "Pessimista" -> pessimista();
            case "Personalizado" -> personalizado();
            default -> base();
        };
    }

    public List<Cenario> todos() {
        return List.of(base(), otimista(), pessimista(), personalizado());
    }

    private Cenario aplicar(IndicadoresMercado ind, Shocks choque, String nome, String descricao) {
        return new Cenario(nome, descricao,
                ind.getCurvaDi() + choque.getCdi(),
                ind.getInflacaoImplicita() + choque.getIpca(),
                ind.getDolarFuturo() + choque.getDolar(),
                ind.getIbovespaEsperado() + choque.getIbovespa());
    }

    // ------------------------------------------------------------- persistência SQL por usuário

    public void atualizar(IndicadoresMercado novosIndicadores, Shocks novoOtimista,
                          Shocks novoPessimista, Shocks novoPersonalizado) {
        ConfigCenarios cfg = new ConfigCenarios(novosIndicadores, novoOtimista, novoPessimista, novoPersonalizado);
        salvarParaUsuario(banco.usuarioAtual(), cfg);
    }

    private ConfigCenarios carregarDoUsuario() {
        String email = banco.usuarioAtual();
        List<String> rows = jdbc.query(
                "SELECT dados_json FROM cenarios_usuario WHERE LOWER(usuario_email) = LOWER(?)",
                (rs, rowNum) -> rs.getString("dados_json"),
                email);
        if (!rows.isEmpty()) {
            try {
                return mapper.readValue(rows.get(0), ConfigCenarios.class);
            } catch (JsonProcessingException ignored) {
            }
        }
        return padrao();
    }

    private ConfigCenarios padrao() {
        if (Files.exists(arquivo)) {
            try {
                return mapper.readValue(arquivo.toFile(), ConfigCenarios.class);
            } catch (IOException ignored) {
            }
        }
        return new ConfigCenarios(
                new IndicadoresMercado(),
                new Shocks(1.00, -0.50, 3.00, 12.00),
                new Shocks(-1.50, 1.50, 6.00, -15.00),
                new Shocks(0.50, 0.50, 0.00, 0.00));
    }

    private void migrarJsonSeNecessario() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM cenarios_usuario", Integer.class);
        if ((count == null || count == 0) && Files.exists(arquivo)) {
            salvarParaUsuario(BancoDadosService.USUARIO_PADRAO, padrao());
        }
    }

    private void salvarParaUsuario(String email, ConfigCenarios cfg) {
        try {
            String json = mapper.writeValueAsString(cfg);
            jdbc.update("""
                    MERGE INTO cenarios_usuario (usuario_email, dados_json, atualizado_em)
                    KEY (usuario_email) VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, email.toLowerCase(), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Erro ao salvar cenários: " + e.getMessage(), e);
        }
    }

    public IndicadoresMercado getIndicadores() { return carregarDoUsuario().indicadores(); }
    public Shocks getOtimista() { return carregarDoUsuario().otimista(); }
    public Shocks getPessimista() { return carregarDoUsuario().pessimista(); }
    public Shocks getPersonalizado() { return carregarDoUsuario().personalizado(); }
}
