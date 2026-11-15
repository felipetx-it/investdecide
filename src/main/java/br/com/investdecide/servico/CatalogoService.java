package br.com.investdecide.servico;

import br.com.investdecide.dominio.Indexador;
import br.com.investdecide.dominio.Produto;
import br.com.investdecide.dominio.TipoProduto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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
 * Catálogo de fundos distribuídos pela XP, montado a partir da base real da CVM
 * (ver {@code scripts/cvm_xp.py}).
 *
 * <p>Classificação de risco: derivada da volatilidade anualizada dos últimos meses
 * (≤3% → 1; ≤8% → 2; ≤15% → 3; ≤25% → 4; acima → 5). A CVM não informa liquidez nem taxa de
 * administração para todas as classes; o sistema assume liquidez D+30 e sinaliza a ausência
 * da taxa.</p>
 */
@Service
public class CatalogoService {

    private final List<Produto> fundos = new ArrayList<>();
    private final List<Produto> manuais = new ArrayList<>();
    private final ObjectMapper mapper;
    private final Path arquivo;

    /** Universo de análise: os maiores fundos por patrimônio, para manter a tela utilizável. */
    public CatalogoService(DadosMercadoService dados, ObjectMapper mapper,
                           @Value("${investdecide.dados:data}") String pastaDados,
                           @Value("${investdecide.universo:150}") int universo) {
        this.mapper = mapper;
        this.arquivo = Path.of(pastaDados, "produtos-manuais.json");

        Set<String> nomes = new HashSet<>();
        dados.catalogo().values().stream()
                .sorted(Comparator.comparingDouble(DadosMercadoService.FundoReal::plMedio).reversed())
                .limit(universo)
                .forEach(fundo -> fundos.add(converter(fundo, nomes)));
        fundos.sort(Comparator.comparing(Produto::getNome));

        carregarManuais();
    }

    private Produto converter(DadosMercadoService.FundoReal fundo, Set<String> nomes) {
        Produto produto = new Produto();
        produto.setId(fundo.cnpj());
        produto.setChaveSerie(fundo.cnpj());
        produto.setNome(nomeUnico(fundo, nomes));
        produto.setEmissor(fundo.gestor());
        produto.setTipo(classificar(fundo));
        produto.setRisco(classificarRisco(fundo.volMedia()));
        produto.setLiquidezDias(30);
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
        if (fundo.tipo() != null && fundo.tipo().toLowerCase().startsWith("previd")) {
            return TipoProduto.PREVIDENCIA_VGBL;
        }
        String classe = fundo.classeAnbima() == null ? "" : fundo.classeAnbima().toLowerCase();
        if (classe.contains("ações") || classe.contains("acoes")) {
            return TipoProduto.FUNDO_ACOES;
        }
        if (classe.contains("multimercado") || classe.contains("cambial")) {
            return TipoProduto.FUNDO_MULTI;
        }
        return TipoProduto.FUNDO_RF;
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
        todos.addAll(manuais);
        todos.sort(Comparator.comparing(Produto::getNome));
        return todos;
    }

    public List<Produto> manuais() {
        return List.copyOf(manuais);
    }

    public Optional<Produto> buscar(String id) {
        return listar().stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public int tamanho() {
        return fundos.size() + manuais.size();
    }

    /** Cadastro manual de CDB, LCI e LCA (persistido em JSON). */
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
        if (produto.getId() == null || produto.getId().isBlank()) {
            produto.setId("manual-" + UUID.randomUUID());
            manuais.add(produto);
        } else {
            Produto existente = buscar(produto.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
            if (!existente.isManual()) {
                throw new IllegalArgumentException("Fundos da prateleira não podem ser editados.");
            }
            manuais.set(manuais.indexOf(existente), produto);
        }
        salvar();
        return produto;
    }

    public void excluir(String id) {
        Produto produto = buscar(id).orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
        if (!produto.isManual()) {
            throw new IllegalArgumentException("Apenas produtos cadastrados manualmente podem ser excluídos.");
        }
        manuais.remove(produto);
        salvar();
    }

    private void carregarManuais() {
        if (!Files.exists(arquivo)) {
            return;
        }
        try {
            Produto[] registros = mapper.readValue(arquivo.toFile(), Produto[].class);
            for (Produto produto : registros) {
                produto.setManual(true);
                manuais.add(produto);
            }
        } catch (IOException e) {
            System.err.println("Não foi possível carregar produtos manuais: " + e.getMessage());
        }
    }

    private void salvar() {
        try {
            Files.createDirectories(arquivo.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(arquivo.toFile(), manuais);
        } catch (IOException e) {
            System.err.println("Não foi possível salvar produtos manuais: " + e.getMessage());
        }
    }
}
