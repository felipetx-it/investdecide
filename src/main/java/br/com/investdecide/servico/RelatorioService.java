package br.com.investdecide.servico;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Histórico imutável de relatórios gerados por usuário, persistido com snapshot em PDF no banco H2.
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

    public record ArquivoRelatorio(String nomeArquivo, byte[] conteudo) {
    }

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final BancoDadosService banco;
    private final JdbcTemplate jdbc;

    public RelatorioService(BancoDadosService banco) {
        this.banco = banco;
        this.jdbc = banco.jdbc();
    }

    public void registrar(String titulo, String descricao, String cliente, String categoria,
                          String nomeArquivo, byte[] conteudoPdf) {
        registrar(titulo, descricao, cliente, categoria, nomeArquivo, conteudoPdf, false);
    }

    public void registrar(String titulo, String descricao, String cliente, String categoria,
                          String nomeArquivo, byte[] conteudoPdf, boolean atualizarRecente) {
        String email = banco.usuarioAtual();
        long agora = System.currentTimeMillis();
        String geradoEm = LocalDateTime.now().format(FORMATO);

        if (atualizarRecente) {
            List<String> recentes = jdbc.query("""
                            SELECT id FROM relatorios_gerados
                            WHERE LOWER(usuario_email) = LOWER(?)
                              AND titulo = ?
                              AND LOWER(cliente) = LOWER(?)
                              AND categoria = ?
                              AND descricao = ?
                              AND ordem_ts >= ?
                            ORDER BY ordem_ts DESC
                            LIMIT 1
                            """,
                    (rs, rowNum) -> rs.getString("id"),
                    email, titulo, cliente, categoria, descricao, agora - 60_000L);
            if (!recentes.isEmpty()) {
                jdbc.update("""
                                UPDATE relatorios_gerados
                                SET descricao = ?, gerado_em = ?, nome_arquivo = ?, conteudo_pdf = ?, ordem_ts = ?
                                WHERE id = ? AND LOWER(usuario_email) = LOWER(?)
                                """,
                        descricao, geradoEm, nomeArquivo, conteudoPdf, agora, recentes.get(0), email);
                return;
            }
        }

        String id = UUID.randomUUID().toString();
        String link = "/relatorios/arquivo/" + id;
        jdbc.update("""
                        INSERT INTO relatorios_gerados
                        (id, usuario_email, titulo, descricao, cliente, categoria, classe, gerado_em, link, nome_arquivo, conteudo_pdf, ordem_ts)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, email, titulo, descricao, cliente, categoria, classeDaCategoria(categoria),
                geradoEm, link, nomeArquivo, conteudoPdf, agora);
    }

    public Optional<ArquivoRelatorio> buscarArquivo(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String email = banco.usuarioAtual();
        List<ArquivoRelatorio> achados = jdbc.query("""
                        SELECT nome_arquivo, conteudo_pdf
                        FROM relatorios_gerados
                        WHERE id = ? AND LOWER(usuario_email) = LOWER(?)
                        """,
                (rs, rowNum) -> new ArquivoRelatorio(
                        rs.getString("nome_arquivo"),
                        rs.getBytes("conteudo_pdf")),
                id, email);
        if (achados.isEmpty() || achados.get(0).conteudo() == null || achados.get(0).conteudo().length == 0) {
            return Optional.empty();
        }
        return Optional.of(achados.get(0));
    }

    public List<Registro> listar() {
        String email = banco.usuarioAtual();
        return jdbc.query("""
                        SELECT id, titulo, descricao, cliente, categoria, classe, gerado_em, link
                        FROM relatorios_gerados
                        WHERE LOWER(usuario_email) = LOWER(?)
                        ORDER BY ordem_ts DESC
                        """,
                (rs, rowNum) -> new Registro(
                        rs.getString("id"),
                        rs.getString("titulo"),
                        rs.getString("descricao"),
                        rs.getString("cliente"),
                        rs.getString("categoria"),
                        rs.getString("classe"),
                        rs.getString("gerado_em"),
                        rs.getString("link")),
                email);
    }

    public int total() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM relatorios_gerados WHERE LOWER(usuario_email) = LOWER(?)",
                Integer.class, banco.usuarioAtual());
        return count == null ? 0 : count;
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
}
