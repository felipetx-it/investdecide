package br.com.investdecide.servico;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Leitor de CSV simples (separador vírgula, campos entre aspas). */
public final class Csv {

    private Csv() {
    }

    public static List<Map<String, String>> ler(Path caminho) throws IOException {
        if (!Files.exists(caminho)) {
            return List.of();
        }
        String conteudo = Files.readString(caminho, StandardCharsets.UTF_8);
        if (conteudo.startsWith("\uFEFF")) {
            conteudo = conteudo.substring(1);
        }
        String[] linhas = conteudo.split("\\r?\\n");
        if (linhas.length < 2) {
            return List.of();
        }
        String[] cabecalho = campos(linhas[0]);
        List<Map<String, String>> registros = new ArrayList<>();
        for (int i = 1; i < linhas.length; i++) {
            if (linhas[i].isBlank()) {
                continue;
            }
            String[] valores = campos(linhas[i]);
            Map<String, String> registro = new HashMap<>();
            for (int c = 0; c < cabecalho.length && c < valores.length; c++) {
                registro.put(cabecalho[c].trim(), valores[c]);
            }
            registros.add(registro);
        }
        return registros;
    }

    /** Converte a lista em mapa indexado por uma coluna. */
    public static Map<String, Map<String, String>> indexar(List<Map<String, String>> registros, String coluna) {
        Map<String, Map<String, String>> mapa = new LinkedHashMap<>();
        for (Map<String, String> registro : registros) {
            String chave = registro.get(coluna);
            if (chave != null && !chave.isBlank()) {
                mapa.put(chave.trim(), registro);
            }
        }
        return mapa;
    }

    private static String[] campos(String linha) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (aspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    aspas = !aspas;
                }
            } else if (c == ',' && !aspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString());
        return campos.toArray(new String[0]);
    }
}
