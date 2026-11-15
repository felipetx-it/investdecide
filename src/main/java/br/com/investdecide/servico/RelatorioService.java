package br.com.investdecide.servico;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Histórico de relatórios gerados. Começa vazio e passa a listar cada documento conforme as
 * simulações e exportações acontecem, com data, hora e cliente.
 */
@Service
public class RelatorioService {

    public record Registro(String id, String titulo, String descricao, String cliente,
                           String categoria, String classe, String geradoEm, String link) {
        public String getId() { return id; }
        public String getTitulo() { return titulo; }
        public String getDescricao() { return descricao; }
        public String getCliente() { return cliente; }
        public String getCategoria() { return categoria; }
        public String getClasse() { return classe; }
        public String getGeradoEm() { return geradoEm; }
        public String getLink() { return link; }
    }

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ObjectMapper mapper;
    private final Path arquivo;
    private final List<Registro> registros = new ArrayList<>();

    public RelatorioService(ObjectMapper mapper, @Value("${investdecide.dados:data}") String pasta) {
        this.mapper = mapper;
        this.arquivo = Path.of(pasta, "relatorios.json");
        carregar();
    }

    public void registrar(String titulo, String descricao, String cliente, String categoria, String link) {
        registros.add(new Registro(UUID.randomUUID().toString(), titulo, descricao,
                cliente, categoria, classeDaCategoria(categoria),
                LocalDateTime.now().format(FORMATO), link));
        salvar();
    }

    public List<Registro> listar() {
        return registros.stream()
                .sorted(Comparator.comparing(Registro::geradoEm).reversed())
                .toList();
    }

    public int total() {
        return registros.size();
    }

    private static String classeDaCategoria(String categoria) {
        return switch (categoria) {
            case "Simulação" -> "tag-blue";
            case "Tributação" -> "tag-green";
            case "Sensibilidade" -> "tag-purple";
            case "Cenários" -> "tag-orange";
            default -> "tag-blue";
        };
    }

    private void carregar() {
        if (!Files.exists(arquivo)) {
            return;
        }
        try {
            Registro[] lidos = mapper.readValue(arquivo.toFile(), Registro[].class);
            registros.addAll(List.of(lidos));
        } catch (IOException e) {
            System.err.println("Não foi possível carregar o histórico de relatórios: " + e.getMessage());
        }
    }

    private void salvar() {
        try {
            Files.createDirectories(arquivo.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(arquivo.toFile(), registros);
        } catch (IOException e) {
            System.err.println("Não foi possível salvar o histórico de relatórios: " + e.getMessage());
        }
    }
}
