package br.com.investdecide.servico;

import br.com.investdecide.dominio.AvaliacaoProduto;
import br.com.investdecide.dominio.Cliente;
import br.com.investdecide.dominio.Pesos;
import br.com.investdecide.dominio.ResultadoDecisao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência relacional das simulações executadas por cada usuário.
 */
@Service
public class SimulacaoSalvaService {

    public record EntradaSimulacao(Cliente cliente, Pesos pesos, String cenario) {
    }

    public record ResumoSimulacao(String id, String clienteNome, String cenario,
                                  String recomendadaNome, double recomendadaScore,
                                  double recomendadaValorFinal, double recomendadaRetornoLiq,
                                  int totalElegiveis, String criadoEm,
                                  EntradaSimulacao entrada) {
    }

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final BancoDadosService banco;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SimulacaoSalvaService(BancoDadosService banco, ObjectMapper mapper) {
        this.banco = banco;
        this.jdbc = banco.jdbc();
        this.mapper = mapper;
    }

    public String salvar(Cliente cliente, Pesos pesos, ResultadoDecisao resultado) {
        String id = "sim-" + UUID.randomUUID();
        String email = banco.usuarioAtual();
        String cenario = resultado.getCenario() != null ? resultado.getCenario().getNome() : "Base";
        AvaliacaoProduto rec = resultado.getRecomendada();
        String json;
        try {
            json = mapper.writeValueAsString(new EntradaSimulacao(cliente, pesos, cenario));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Erro ao serializar simulação: " + e.getMessage(), e);
        }

        jdbc.update("""
                        INSERT INTO simulacoes_salvas (
                            id, usuario_email, cliente_nome, dados_json, cenario,
                            recomendada_nome, recomendada_score, recomendada_valor_final,
                            recomendada_retorno_liq, total_elegiveis, criado_em, ordem_ts
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                email,
                cliente.getNome() == null || cliente.getNome().isBlank() ? "Cliente" : cliente.getNome().trim(),
                json,
                cenario,
                rec != null ? rec.getNome() : "Sem elegíveis",
                rec != null ? rec.getScore() : 0.0,
                rec != null ? rec.getValorFinal() : 0.0,
                rec != null ? rec.getRetornoLiquido() : 0.0,
                resultado.getTotalElegiveis(),
                LocalDateTime.now().format(FORMATO),
                System.currentTimeMillis());
        return id;
    }

    public List<ResumoSimulacao> listar() {
        return listar(20);
    }

    public List<ResumoSimulacao> listar(int limite) {
        String email = banco.usuarioAtual();
        return jdbc.query("""
                        SELECT id, cliente_nome, cenario, dados_json, recomendada_nome,
                               recomendada_score, recomendada_valor_final, recomendada_retorno_liq,
                               total_elegiveis, criado_em
                        FROM simulacoes_salvas
                        WHERE LOWER(usuario_email) = LOWER(?)
                        ORDER BY ordem_ts DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> {
                    EntradaSimulacao entrada = deserializar(rs.getString("dados_json"));
                    return new ResumoSimulacao(
                            rs.getString("id"),
                            rs.getString("cliente_nome"),
                            rs.getString("cenario"),
                            rs.getString("recomendada_nome"),
                            rs.getDouble("recomendada_score"),
                            rs.getDouble("recomendada_valor_final"),
                            rs.getDouble("recomendada_retorno_liq"),
                            rs.getInt("total_elegiveis"),
                            rs.getString("criado_em"),
                            entrada);
                },
                email, limite);
    }

    public Optional<EntradaSimulacao> buscar(String id) {
        String email = banco.usuarioAtual();
        List<EntradaSimulacao> lista = jdbc.query("""
                        SELECT dados_json FROM simulacoes_salvas
                        WHERE id = ? AND LOWER(usuario_email) = LOWER(?)
                        """,
                (rs, rowNum) -> deserializar(rs.getString("dados_json")),
                id, email);
        return lista.isEmpty() ? Optional.empty() : Optional.ofNullable(lista.get(0));
    }

    public void excluir(String id) {
        String email = banco.usuarioAtual();
        jdbc.update("DELETE FROM simulacoes_salvas WHERE id = ? AND LOWER(usuario_email) = LOWER(?)", id, email);
    }

    public int total() {
        String email = banco.usuarioAtual();
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM simulacoes_salvas WHERE LOWER(usuario_email) = LOWER(?)",
                Integer.class, email);
        return c == null ? 0 : c;
    }

    private EntradaSimulacao deserializar(String json) {
        try {
            return mapper.readValue(json, EntradaSimulacao.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
