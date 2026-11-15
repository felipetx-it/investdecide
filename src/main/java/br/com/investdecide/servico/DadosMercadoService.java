package br.com.investdecide.servico;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base de dados real do sistema: catálogo de fundos distribuídos pela XP, retornos mensais
 * e fatores macro (CDI, IPCA e dólar), gerados por {@code scripts/cvm_xp.py}.
 *
 * <p>Os arquivos ficam em {@code data/processed}. Se ainda não existirem, o sistema sobe com
 * catálogo vazio e exibe o aviso na tela inicial.</p>
 */
@Service
public class DadosMercadoService {

    public record FundoReal(String cnpj, String nome, String tipo, String classeAnbima, String tribLp,
                            double plMedio, double volMedia, double retornoMedio, int meses,
                            String gestor, String administrador) {
    }

    private final Path pasta;
    private final List<String> meses = new ArrayList<>();
    private final List<double[]> fatores = new ArrayList<>();
    private final Map<String, double[]> retornos = new LinkedHashMap<>();
    private final Map<String, FundoReal> catalogo = new LinkedHashMap<>();
    private String mensagem = "Base não carregada.";

    public DadosMercadoService(@Value("${investdecide.dados:data}") String pastaDados) {
        this.pasta = Path.of(pastaDados, "processed");
        carregar();
    }

    private void carregar() {
        try {
            carregarFatores();
            carregarCatalogo();
            carregarRetornos();
            mensagem = String.format("%d fundos e %d meses de histórico", catalogo.size(), meses.size());
        } catch (IOException e) {
            mensagem = "Falha ao ler a base: " + e.getMessage();
        }
    }

    private void carregarFatores() throws IOException {
        List<Map<String, String>> registros = Csv.ler(pasta.resolve("fatores_macro.csv"));
        registros.sort((a, b) -> a.get("ano_mes").compareTo(b.get("ano_mes")));
        for (Map<String, String> registro : registros) {
            String mes = registro.get("ano_mes");
            String cdiStr = registro.get("cdi_mes");
            String ipcaStr = registro.get("ipca_mes");
            if (mes == null || mes.isBlank()
                    || cdiStr == null || cdiStr.isBlank()
                    || ipcaStr == null || ipcaStr.isBlank()) {
                continue;
            }
            meses.add(mes);
            fatores.add(new double[]{
                    numero(cdiStr),
                    numero(ipcaStr),
                    numero(registro.get("dolar_ret")),
                    numero(registro.get("ibov_ret"))
            });
        }
    }

    private void carregarCatalogo() throws IOException {
        for (Map<String, String> registro : Csv.ler(pasta.resolve("catalogo_fundos.csv"))) {
            String cnpj = registro.get("cnpj");
            if (cnpj == null || cnpj.isBlank()) {
                continue;
            }
            String nome = registro.getOrDefault("nome_xp", registro.getOrDefault("nome_cvm", cnpj))
                    .replace("...", "").trim();
            catalogo.put(cnpj, new FundoReal(
                    cnpj,
                    nome,
                    registro.getOrDefault("tipo_xp", "Fundo"),
                    registro.getOrDefault("classe_anbima", ""),
                    registro.getOrDefault("trib_lp", ""),
                    numero(registro.get("pl_medio")),
                    numero(registro.get("vol_media")),
                    numero(registro.get("retorno_medio")),
                    (int) numero(registro.get("meses")),
                    registro.getOrDefault("gestor", ""),
                    registro.getOrDefault("administrador", "")));
        }
    }

    private void carregarRetornos() throws IOException {
        Map<String, Integer> indiceMes = new LinkedHashMap<>();
        for (int i = 0; i < meses.size(); i++) {
            indiceMes.put(meses.get(i), i);
        }
        for (Map<String, String> registro : Csv.ler(pasta.resolve("fundos_mensal.csv"))) {
            String cnpj = registro.get("cnpj");
            Integer indice = indiceMes.get(registro.get("ano_mes"));
            if (cnpj == null || indice == null) {
                continue;
            }
            double[] serie = retornos.computeIfAbsent(cnpj, k -> serieVazia());
            serie[indice] = numero(registro.get("retorno_mes"));
        }
    }

    private double[] serieVazia() {
        double[] serie = new double[meses.size()];
        java.util.Arrays.fill(serie, Double.NaN);
        return serie;
    }

    private static double numero(String valor) {
        if (valor == null || valor.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(valor.replace(",", "."));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ consulta

    public boolean disponivel() {
        return !catalogo.isEmpty() && !meses.isEmpty();
    }

    public List<String> meses() {
        return List.copyOf(meses);
    }

    public double[] fatoresDoMes(int indice) {
        return fatores.get(indice);
    }

    /** Série de retornos mensais alinhada aos meses; NaN quando o fundo não tem o mês. */
    public double[] serie(String cnpj) {
        return retornos.getOrDefault(cnpj, new double[0]);
    }

    public Map<String, FundoReal> catalogo() {
        return catalogo;
    }

    public String getMensagem() {
        return mensagem;
    }

    public int getTotalMeses() {
        return meses.size();
    }
}
