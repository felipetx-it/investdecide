package br.com.investdecide.servico;

import br.com.investdecide.dominio.Cenario;
import br.com.investdecide.dominio.IndicadoresMercado;
import br.com.investdecide.dominio.Shocks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta os cenários econômicos.
 *
 * <p>O cenário base é construído a partir dos indicadores de mercado (curva DI, inflação
 * implícita, dólar futuro e expectativa de Ibovespa). Os cenários otimista e pessimista são
 * obtidos aplicando choques configuráveis sobre o base — a mesma lógica serve para um cenário
 * personalizado salvo pelo usuário.</p>
 */
@Service
public class CenarioService {

    private final ObjectMapper mapper;
    private final Path arquivo;

    private IndicadoresMercado indicadores = new IndicadoresMercado();
    private Shocks otimista = new Shocks(1.00, -0.50, 3.00, 12.00);
    private Shocks pessimista = new Shocks(-1.50, 1.50, 6.00, -15.00);
    private Shocks personalizado = new Shocks(0.50, 0.50, 0.00, 0.00);

    public CenarioService(ObjectMapper mapper, @Value("${investdecide.dados:data}") String pasta) {
        this.mapper = mapper;
        this.arquivo = Path.of(pasta, "cenarios.json");
        carregar();
    }

    public Cenario base() {
        return new Cenario("Base",
                "Indicadores de mercado de " + indicadores.getDataReferencia(),
                indicadores.getCurvaDi(),
                indicadores.getInflacaoImplicita(),
                indicadores.getDolarFuturo(),
                indicadores.getIbovespaEsperado());
    }

    public Cenario otimista() {
        return aplicar(otimista, "Otimista", "Choques favoráveis sobre o cenário base");
    }

    public Cenario pessimista() {
        return aplicar(pessimista, "Pessimista", "Choques adversos sobre o cenário base");
    }

    public Cenario personalizado() {
        return aplicar(personalizado, "Personalizado", "Choques definidos pelo usuário");
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

    private Cenario aplicar(Shocks choque, String nome, String descricao) {
        return new Cenario(nome, descricao,
                indicadores.getCurvaDi() + choque.getCdi(),
                indicadores.getInflacaoImplicita() + choque.getIpca(),
                indicadores.getDolarFuturo() + choque.getDolar(),
                indicadores.getIbovespaEsperado() + choque.getIbovespa());
    }

    // ------------------------------------------------------------- persistência

    public void atualizar(IndicadoresMercado novosIndicadores, Shocks novoOtimista,
                          Shocks novoPessimista, Shocks novoPersonalizado) {
        this.indicadores = novosIndicadores;
        this.otimista = novoOtimista;
        this.pessimista = novoPessimista;
        this.personalizado = novoPersonalizado;
        salvar();
    }

    private void carregar() {
        if (!Files.exists(arquivo)) {
            return;
        }
        try {
            Map<?, ?> dados = mapper.readValue(arquivo.toFile(), Map.class);
            if (dados.get("indicadores") != null) {
                indicadores = mapper.convertValue(dados.get("indicadores"), IndicadoresMercado.class);
            }
            if (dados.get("otimista") != null) {
                otimista = mapper.convertValue(dados.get("otimista"), Shocks.class);
            }
            if (dados.get("pessimista") != null) {
                pessimista = mapper.convertValue(dados.get("pessimista"), Shocks.class);
            }
            if (dados.get("personalizado") != null) {
                personalizado = mapper.convertValue(dados.get("personalizado"), Shocks.class);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Não foi possível carregar cenarios.json: " + e.getMessage());
        }
    }

    private void salvar() {
        try {
            Files.createDirectories(arquivo.getParent());
            Map<String, Object> dados = new LinkedHashMap<>();
            dados.put("indicadores", indicadores);
            dados.put("otimista", otimista);
            dados.put("pessimista", pessimista);
            dados.put("personalizado", personalizado);
            mapper.writerWithDefaultPrettyPrinter().writeValue(arquivo.toFile(), dados);
        } catch (IOException e) {
            System.err.println("Não foi possível salvar cenarios.json: " + e.getMessage());
        }
    }

    public IndicadoresMercado getIndicadores() { return indicadores; }
    public Shocks getOtimista() { return otimista; }
    public Shocks getPessimista() { return pessimista; }
    public Shocks getPersonalizado() { return personalizado; }
}
