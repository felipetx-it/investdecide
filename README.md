# InvestDecide — Sistema de Apoio à Decisão para seleção de fundos

Sistema web que apoia a escolha de **onde aplicar um aporte** de um cliente, comparando os
fundos de investimento e de previdência **distribuídos pela XP Investimentos**.

O sistema não é um painel: ele **elimina alternativas por restrição** (perfil, liquidez,
horizonte, aporte mínimo), **projeta o resultado líquido** de cada uma com imposto, **agrupa as
semelhantes**, **ordena por um índice multicritério** e devolve um **conjunto curto de
alternativas** com justificativa, ressalvas e teste de robustez.

## Como executar

Pré-requisitos: JDK 17+, Maven 3.6+ e Python 3.10+ (apenas para atualizar a base).

```bash
# 1. base de dados (CVM + BCB). Rode uma vez; os arquivos ficam em data/processed
python scripts/cvm_xp.py --lista caminho/para/fundos-xp.txt --meses 12

# 2. sistema
mvn spring-boot:run
```

Acesse `http://localhost:8080` 

## Autenticação

- Login por e-mail e senha em `/login` (Spring Security, sessão + CSRF).
- Todas as rotas exigem autenticação, exceto `/login` e os arquivos estáticos.
- Logout em `POST /logout`.

## Base de dados

`scripts/cvm_xp.py` monta a base a partir de fontes públicas:

1. **Cadastro da CVM** (`registro_fundo_classe.zip`): nome, classe ANBIMA, tributação de longo
   prazo, situação, patrimônio.
2. **Lista de fundos distribuídos pela XP** (arquivo TSV com nome comercial): o casamento é
   aproximado por tokens do nome, com limiar de similaridade — o resultado fica no campo
   `score_match`.
3. **Informe diário da CVM** (últimos N meses): cotas, patrimônio e cotistas → retorno mensal
   e volatilidade.
4. **BCB/SGS**: CDI, IPCA e dólar mensais.

Saídas em `data/processed/`: `catalogo_fundos.csv`, `fundos_mensal.csv`, `fundos_diario.csv`
e `fatores_macro.csv`. O sistema carrega esses arquivos na inicialização
(`DadosMercadoService`) e usa o universo dos **150 maiores fundos por patrimônio**
(`investdecide.universo`) para manter a análise utilizável.

## Modelo de decisão

1. **Projeção.** Regressão MQO dos retornos mensais do fundo contra CDI, IPCA e dólar
   (12 meses de histórico), aplicada aos fatores do cenário escolhido para estimar o retorno
   anual.
2. **Resultado líquido.** IR pela tabela regressiva de fundos; previdência tratada como VGBL
   (IR sobre o rendimento). O comparativo PGBL × VGBL × CDB × LCI/LCA fica no relatório
   tributário.
3. **Elegibilidade (restrições duras).** Risco acima do perfil, liquidez insuficiente,
   carência maior que o horizonte e aporte abaixo do mínimo eliminam a alternativa antes do
   índice.
4. **Agrupamento.** k-médias (retorno líquido, risco, liquidez e carga tributária padronizados),
   com fusão de grupos de um item.
5. **Índice multicritério.** Critérios normalizados (min-max) e somados com os pesos
   informados; teste de sensibilidade com perturbação de +20% em cada peso.
6. **Saída em shortlist.** As três melhores alternativas, com o mesmo destaque visual, mais o
   ranking completo por grupo.

## Rotas

| Rota | Tela |
|---|---|
| `/login` | Autenticação |
| `/dashboard` | Panorama do cliente padrão: top 3, grupos e alertas |
| `/simulacao` | Formulário: cliente, tributação, pesos e cenário |
| `POST /simulacao` | Calcula e exibe o resultado |
| `/simulacao/ranking` | Ranking completo, com ordenação e grupos |
| `/cenarios` | Indicadores de mercado e choques dos cenários |
| `/relatorios` | Central de relatórios |
| `/relatorios/tributacao` | Comparativo tributário (CDB, LCI/LCA, PGBL, VGBL) |
| `/relatorios/comparativo.pdf` | Relatório do comparativo de alternativas |
| `/relatorios/tributacao.pdf` | Relatório de eficiência tributária |
| `/relatorios/sensibilidade.pdf` | Relatório de sensibilidade |
| `/api/diagnostico` | JSON da última simulação (validação) |

## Relatórios em PDF

Gerados no servidor com **OpenPDF** (`com.github.librepdf:openpdf:1.3.30`): tabelas, cabeçalho
repetido na quebra de página, rodapé numerado e data de emissão.

## Estrutura

```text
src/main/java/br/com/investdecide/
  config/      SecurityConfig (autenticação)
  dominio/     Produto, Cliente, Cenario, Pesos, AvaliacaoProduto, ResultadoDecisao...
  servico/     DadosMercadoService, CatalogoService, AnaliticaService (regressão + k-médias),
               TributacaoService, DecisaoService (índice + elegibilidade), CenarioService,
               RelatorioPdfService, RelatorioTributarioService
  web/         PagesController, SimulacaoController, CenarioController, RelatorioController
scripts/cvm_xp.py    ingestão CVM + BCB filtrada pela lista da XP
```

## Limitações conhecidas

- **Liquidez:** a CVM não informa o prazo de resgate; o sistema assume D+30 para todos os
  fundos e sinaliza essa premissa na tela.
- **Taxa de administração:** o cadastro novo (RCVM 175) não traz a taxa; o campo aparece como
  não informado para parte dos fundos.
- **Previdência:** os fundos de previdência são tratados como VGBL; a comparação com PGBL e o
  efeito de reinvestir a restituição ficam no relatório tributário.
- **Histórico:** 12 meses permitem regressão, mas com pouca potência estatística; ampliar para
  24–36 meses melhora os betas.
- **Casamento de nomes:** a lista da XP usa nome comercial truncado; fundos sem correspondência
  ficam fora do universo (o campo `score_match` documenta a qualidade do casamento).
