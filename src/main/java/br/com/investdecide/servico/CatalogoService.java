package br.com.investdecide.servico;

import br.com.investdecide.dominio.Indexador;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.TipoProduto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Catálogo de fundos distribuídos pela XP (base CVM + liquidez XP/CVM) combinado com os
 * produtos de renda fixa bancária (CDB, LCI e LCA) cadastrados por cada usuário em banco H2.
 *
 * <p>Classificação de risco: derivada da volatilidade anualizada dos últimos meses
 * (≤3% → 1; ≤8% → 2; ≤15% → 3; ≤25% → 4; acima → 5). A liquidez de resgate vem do portal de
 * fundos da XP e do Extrato da CVM ({@code scripts/xp_liquidez.py}); só quando o fundo não
 * aparece em nenhuma das fontes ela é inferida por classe ANBIMA (D+1 a D+30).</p>
 */
@Service
public class CatalogoService {

    private final List<Produto> fundos = new ArrayList<>();
    private final ObjectMapper mapper;
    private final Path arquivo;
    private final BancoDadosService banco;
    private final JdbcTemplate jdbc;

    /** Universo de análise: os maiores fundos por patrimônio, para manter a tela utilizável. */
    public CatalogoService(DadosMercadoService dados, ObjectMapper mapper, BancoDadosService banco,
                           @Value("${investdecide.dados:data}") String pastaDados,
                           @Value("${investdecide.universo:150}") int universo) {
        this.mapper = mapper;
        this.banco = banco;
        this.jdbc = banco.jdbc();
        this.arquivo = Path.of(pastaDados, "produtos-manuais.json");

        Set<String> nomes = new HashSet<>();
        dados.catalogo().values().stream()
                .sorted(Comparator.comparingDouble(DadosMercadoService.FundoReal::plMedio).reversed())
                .limit(universo)
                .forEach(fundo -> fundos.add(converter(fundo, nomes)));
        fundos.sort(Comparator.comparing(Produto::getNome));

        migrarManuaisJsonSeNecessario();
    }

    private Produto converter(DadosMercadoService.FundoReal fundo, Set<String> nomes) {
        Produto produto = new Produto();
        produto.setId(fundo.cnpj());
        produto.setChaveSerie(fundo.cnpj());
        produto.setNome(nomeUnico(fundo, nomes));
        produto.setEmissor(fundo.gestor());
        TipoProduto tipo = classificar(fundo);
        produto.setTipo(tipo);
        produto.setRisco(classificarRisco(fundo.volMedia()));
        produto.setLiquidezDias(liquidezDias(fundo, tipo));
        produto.setLiquidezFonte(fundo.liquidezDias() >= 0
                ? (fundo.liquidezFonte() == null || fundo.liquidezFonte().isBlank() ? "XP" : fundo.liquidezFonte())
                : "estimada");
        produto.setLiquidezDetalhe(fundo.liquidezDias() >= 0
                ? fundo.cotizacaoResgate() + " · " + fundo.liquidacaoResgate()
                : "");
        produto.setIsentoIr(isFundoIncentivado(fundo, tipo));
        produto.setMinimo(1_000);
        produto.setVolMensal(fundo.volMedia() / Math.sqrt(12));
        produto.setAlphaMensal(fundo.retornoMedio());
        produto.setTaxaAdm(0);
        return produto;
    }

    private static String nomeUnico(DadosMercadoService.FundoReal fundo, Set<String> nomes) {
        String nome = fundo.nome() == null || fundo.nome().isBlank() ? fundo.cnpj() : fundo.nome();
        if (nomes.add(nome)) {
            return nome;
        }
        String alternativa = nome + " (" + fundo.cnpj().substring(Math.max(0, fundo.cnpj().length() - 4)) + ")";
        nomes.add(alternativa);
        return alternativa;
    }

    private static TipoProduto classificar(DadosMercadoService.FundoReal fundo) {
        String tipo = fundo.tipo() == null ? "" : fundo.tipo().toLowerCase();
        String classe = fundo.classeAnbima() == null ? "" : fundo.classeAnbima().toLowerCase();
        String nome = fundo.nome() == null ? "" : fundo.nome().toLowerCase();
        if (tipo.startsWith("previd") || classe.contains("previd") || nome.contains("previd")) {
            return TipoProduto.PREVIDENCIA_VGBL;
        }
        if (classe.contains("ações") || classe.contains("acoes")) {
            return TipoProduto.FUNDO_ACOES;
        }
        if (classe.contains("multimercado") || classe.contains("cambial")) {
            return TipoProduto.FUNDO_MULTI;
        }
        return TipoProduto.FUNDO_RF;
    }

    /**
     * Prazo de resgate em dias corridos. Usa a cotização + liquidação informadas pela XP quando
     * o fundo está no portal; caso contrário, mantém a estimativa por classe ANBIMA.
     */
    private static int liquidezDias(DadosMercadoService.FundoReal fundo, TipoProduto tipo) {
        if (fundo.liquidezDias() >= 0) {
            return fundo.liquidezDias();
        }
        if (tipo.isPrevidencia()) {
            return 30;
        }
        String classe = fundo.classeAnbima() == null ? "" : fundo.classeAnbima().toLowerCase();
        String nome = fundo.nome() == null ? "" : fundo.nome().toLowerCase();
        if (classe.contains("simples") || classe.contains("soberano") || classe.contains("referenciado")
                || nome.contains(" di ") || nome.contains("liquidez") || nome.contains("soberano")
                || nome.contains("simples") || nome.contains("selic")) {
            return 1;
        }
        if (classe.contains("duração baixa") || classe.contains("duracao baixa")
                || classe.contains("curto prazo") || nome.contains("curto prazo")) {
            return 5;
        }
        if (tipo == TipoProduto.FUNDO_RF) {
            return 15;
        }
        if (tipo == TipoProduto.FUNDO_MULTI
                && (classe.contains("juros e moedas") || classe.contains("baixo risco"))) {
            return 15;
        }
        return 30;
    }

    private static boolean isFundoIncentivado(DadosMercadoService.FundoReal fundo, TipoProduto tipo) {
        if (tipo != TipoProduto.FUNDO_RF) {
            return false;
        }
        String texto = ((fundo.nome() == null ? "" : fundo.nome()) + " "
                + (fundo.classeAnbima() == null ? "" : fundo.classeAnbima())).toLowerCase();
        return texto.contains("infra") || texto.contains("incentivad");
    }

    private static int classificarRisco(double volatilidadeAnual) {
        if (volatilidadeAnual <= 3) return 1;
        if (volatilidadeAnual <= 8) return 2;
        if (volatilidadeAnual <= 15) return 3;
        if (volatilidadeAnual <= 25) return 4;
        return 5;
    }

    public List<Produto> listar() {
        List<Produto> todos = new ArrayList<>(fundos);
        todos.addAll(manuais());
        todos.sort(Comparator.comparing(Produto::getNome));
        return todos;
    }

    public List<Produto> manuais() {
        String email = banco.usuarioAtual();
        return jdbc.query(
                "SELECT dados_json FROM produtos_manuais WHERE LOWER(usuario_email) = LOWER(?) ORDER BY criado_em DESC",
                (rs, rowNum) -> deserializarProduto(rs.getString("dados_json")),
                email)
                .stream()
                .filter(p -> p != null)
                .toList();
    }

    public Optional<Produto> buscar(String id) {
        return listar().stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public int tamanho() {
        return fundos.size() + manuais().size();
    }

    /** Cadastro manual de CDB, LCI e LCA (persistido em banco H2 por usuário). */
    public Produto salvarManual(Produto produto) {
        if (produto.getNome() == null || produto.getNome().isBlank()) {
            throw new IllegalArgumentException("Informe o nome do produto.");
        }
        if (produto.getTipo() == null || !produto.getTipo().isRendaFixaBancaria()) {
            throw new IllegalArgumentException("O cadastro manual aceita apenas CDB, LCI ou LCA.");
        }
        if (produto.getVencimentoMeses() == null || produto.getVencimentoMeses() <= 0) {
            throw new IllegalArgumentException("Informe o vencimento em meses.");
        }
        if (produto.getRisco() < 1 || produto.getRisco() > 5) {
            throw new IllegalArgumentException("O risco deve estar entre 1 e 5.");
        }

        produto.setManual(true);
        produto.setIsentoIr(produto.getTipo() == TipoProduto.LCI || produto.getTipo() == TipoProduto.LCA);
        if (produto.getTipo() == TipoProduto.CDB) {
            produto.setIndexador(produto.getIndexador() == null ? Indexador.CDI : produto.getIndexador());
        }
        String email = banco.usuarioAtual();
        if (produto.getId() == null || produto.getId().isBlank()) {
            produto.setId("manual-" + UUID.randomUUID());
            jdbc.update(
                    "INSERT INTO produtos_manuais (id, usuario_email, dados_json) VALUES (?, ?, ?)",
                    produto.getId(), email, serializarProduto(produto));
        } else {
            Produto existente = buscar(produto.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
            if (!existente.isManual()) {
                throw new IllegalArgumentException("Fundos da prateleira não podem ser editados.");
            }
            jdbc.update(
                    "UPDATE produtos_manuais SET dados_json = ? WHERE id = ? AND LOWER(usuario_email) = LOWER(?)",
                    serializarProduto(produto), produto.getId(), email);
        }
        return produto;
    }

    public void excluir(String id) {
        Produto produto = buscar(id).orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
        if (!produto.isManual()) {
            throw new IllegalArgumentException("Apenas produtos cadastrados manualmente podem ser excluídos.");
        }
        jdbc.update("DELETE FROM produtos_manuais WHERE id = ? AND LOWER(usuario_email) = LOWER(?)",
                id, banco.usuarioAtual());
    }

    private void migrarManuaisJsonSeNecessario() {
        Integer existentes = jdbc.queryForObject("SELECT COUNT(*) FROM produtos_manuais", Integer.class);
        if ((existentes != null && existentes > 0) || !Files.exists(arquivo)) {
            return;
        }
        try {
            Produto[] registros = mapper.readValue(arquivo.toFile(), Produto[].class);
            for (Produto produto : registros) {
                produto.setManual(true);
                if (produto.getId() == null || produto.getId().isBlank()) {
                    produto.setId("manual-" + UUID.randomUUID());
                }
                jdbc.update(
                        "MERGE INTO produtos_manuais (id, usuario_email, dados_json) KEY (id) VALUES (?, ?, ?)",
                        produto.getId(), BancoDadosService.USUARIO_PADRAO, serializarProduto(produto));
            }
        } catch (IOException e) {
            System.err.println("Não foi possível migrar produtos manuais: " + e.getMessage());
        }
    }

    private String serializarProduto(Produto p) {
        try {
            return mapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Erro ao serializar produto: " + e.getMessage(), e);
        }
    }

    private Produto deserializarProduto(String json) {
        try {
            Produto p = mapper.readValue(json, Produto.class);
            p.setManual(true);
            return p;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
