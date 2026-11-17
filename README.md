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

# 2. liquidez de resgate no portal de fundos da XP (grava liquidez_fundos.csv)
python scripts/xp_liquidez.py

# 3. sistema
mvn spring-boot:run
```

Acesse `http://localhost:8080` 

> **Primeira execução a partir de um clone limpo:** o banco H2 é criado vazio e as contas de
> demonstração só existem quando a senha é informada. Para entrar, crie uma conta em
> `/cadastro` ou defina as variáveis antes de subir o sistema:
>
> ```powershell
> $env:INVESTDECIDE_SEED_CONSULTOR_SENHA="defina-uma-senha"
> $env:INVESTDECIDE_SEED_ANALISTA_SENHA="defina-uma-senha"
> mvn spring-boot:run
> ```
>
> As contas de seed são criadas apenas se ainda não existirem; se o banco já tiver a conta,
> a senha informada é ignorada.

## Autenticação e persistência multiusuário

- Login por e-mail e senha em `/login` e criação de novas contas em `/cadastro` (Spring Security, BCrypt, sessão + CSRF).
- Todas as rotas da aplicação exigem autenticação, exceto `/login`, `/cadastro` e os arquivos estáticos.
- Logout em `POST /logout`.
- Banco relacional **H2 embarcado** (`jdbc:h2:file:./data/db/investdecide`) via Spring JDBC (`BancoDadosService`): cada conta mantém de forma isolada suas **simulações salvas** (reabríveis em `/simulacao/salva/{id}`), **produtos manuais** (CDB, LCI e LCA), **cenário personalizado** e **histórico de relatórios emitidos**.
- Por segurança (repositório público), as credenciais das contas de demonstração são enviadas em privado aos avaliadores pelo autor.

## Base de dados

`scripts/cvm_xp.py` e `scripts/xp_liquidez.py` montam a base a partir de fontes públicas:

1. **Cadastro da CVM** (`registro_fundo_classe.zip`): nome, classe ANBIMA, tributação de longo
   prazo, situação e patrimônio líquido.
2. **Lista de fundos distribuídos pela XP** (arquivo TSV com nome comercial): o casamento é
   aproximado por tokens do nome, com limiar de similaridade — o resultado fica no campo
   `score_match`, com desduplicação por CNPJ e alinhamento de previdência.
3. **Informe diário da CVM** (últimos N meses): cotas, patrimônio e cotistas, com filtragem de
   subclasses RCVM 175 e saltos espúrios de cota → retorno mensal composto e volatilidade.
4. **BCB/SGS + proxy de ações**: CDI, IPCA, dólar e fator mensal de renda variável.
5. **Liquidez combinada XP + Extrato CVM** (`scripts/xp_liquidez.py`): cotização e liquidação de resgate
   coletadas na API do portal de fundos da XP (331 fundos casados por CNPJ e nome) e complementadas
   pelo arquivo público `extrato_fi.csv` da CVM (`QT_DIA_CONVERSAO_COTA` e `QT_DIA_PAGTO_COTA`, 172 fundos,
   cobrindo majoritariamente planos de previdência que a XP não lista na vitrine pública de fundos).
   No catálogo de 530 fundos, **503 (94,9%)** possuem liquidez real verificada (`liquidez_fundos.csv`);
   no universo ativo de avaliação, **147 de 151 alternativas** têm liquidez real (107 XP + 40 CVM,
   com origem identificada como `XP`, `CVM` ou `est.` na interface e nos PDFs).

Saídas em `data/processed/`: `catalogo_fundos.csv`, `fundos_mensal.csv`, `fundos_diario.csv`,
`fatores_macro.csv` e `liquidez_fundos.csv`. O sistema carrega esses arquivos na inicialização
(`DadosMercadoService`) e usa o universo dos **150 maiores fundos por patrimônio**
(`investdecide.universo`) somado aos títulos privados cadastrados pelo usuário.

## Modelo de decisão

1. **Projeção.** Regressão fatorial regularizada (Ridge ancorado por classe) dos retornos mensais
   contra CDI, IPCA, dólar e proxy de Ibovespa (janela padrão de 24 meses), aplicada aos fatores do
   cenário escolhido para estimar o retorno anual.
2. **Resultado líquido e come-cotas.** Fundos abertos de renda fixa e multimercado não isentos seguem
   a tabela regressiva combinada com **simulação do come-cotas semestral (15% em maio e novembro)**
   sobre o ganho acumulado, capturando a perda de capitalização composta frente a **CDBs** (tributados
   apenas no vencimento); fundos de ações a 15%; fundos incentivados (FI-Infra) e LCI/LCA isentos;
   fundos de previdência avaliados automaticamente entre VGBL e PGBL segundo a declaração de IR e o
   regime (progressivo ou regressivo) do cliente. O comparativo completo dos 5 veículos (com e sem
   reinvestimento da restituição do PGBL) fica em `/relatorios/tributacao`.
3. **Elegibilidade (restrições duras).** Risco acima do perfil, liquidez insuficiente,
   carência maior que o horizonte e aporte abaixo do mínimo eliminam a alternativa antes do
   índice.
4. **Agrupamento.** k-médias++ (retorno líquido, risco, liquidez e carga tributária padronizados),
   com fusão de grupos de um item e validação pelo **coeficiente de silhueta**.
5. **Índice multicritério.** Critérios normalizados (min-max) e somados com os pesos
   informados; teste de sensibilidade com perturbação de +20% em cada peso e alternância entre os
   cenários Base, Otimista, Pessimista e Personalizado.
6. **Saída e visualização.** Shortlist com as três melhores alternativas, ranking completo por grupo,
   gráficos interativos em **Chart.js** (dispersão risco × retorno, composição SAW, comparativo
   tributário e cenários) e exportação para o cliente em PDF.

## Rotas

| Rota | Tela |
|---|---|
| `/login` e `/cadastro` | Autenticação e criação de conta multiusuário |
| `/dashboard` | Panorama do cliente padrão, simulações salvas, grupos e gráficos |
| `/simulacao` e `/simulacao/salva/{id}` | Formulário do cliente, reabertura de simulações e execução do cálculo |
| `POST /simulacao` | Calcula e persiste o resultado por cenário |
| `/simulacao/ranking` | Ranking completo, com ordenação e grupos |
| `/produtos` | Cadastro privado (por usuário) de CDB, LCI e LCA |
| `/cenarios` | Indicadores de mercado e choques dos cenários |
| `/relatorios` | Central de relatórios e histórico emitido |
| `/relatorios/tributacao` | Comparativo tributário de 5 veículos (CDB, Fundo RF LP, LCI/LCA, PGBL, VGBL) |
| `/relatorios/comparativo.pdf` | Relatório executivo comparativo com gráfico vetorial Top 8 |
| `/relatorios/tributacao.pdf` | Relatório tributário para cliente com comparativo gráfico |
| `/relatorios/sensibilidade.pdf` | Relatório de cenários e sensibilidade com 2 gráficos vetoriais |
| `/api/diagnostico` | JSON da última simulação (validação) |

## Relatórios em PDF

Gerados no servidor com **OpenPDF** (`com.github.librepdf:openpdf:1.3.30`) em layout executivo voltado ao
envio para clientes finais: cabeçalho institucional, cartões de KPI, tabelas com destaque para a shortlist,
**4 gráficos vetoriais nativos** (Top 8 SAW, comparativo tributário com come-cotas, premissas macroeconômicas
por cenário e impacto patrimonial no horizonte), cabeçalho repetido na quebra de página e rodapé numerado.

## Estrutura

```text
src/main/java/br/com/investdecide/
  config/      SecurityConfig (autenticação multiusuário)
  dominio/     Produto, Cliente, Cenario, Pesos, AvaliacaoProduto, ResultadoDecisao...
  servico/     BancoDadosService, UsuarioService, SimulacaoSalvaService,
               DadosMercadoService, CatalogoService, AnaliticaService (regressão + k-médias),
               TributacaoService (come-cotas + regimes), DecisaoService, CenarioService,
               RelatorioService, RelatorioPdfService, RelatorioTributarioService
  web/         ContaController, PagesController, ProdutoController, SimulacaoController,
               CenarioController, RelatorioController, UsuarioControllerAdvice
scripts/cvm_xp.py       ingestão CVM + BCB filtrada pela lista da XP
scripts/xp_liquidez.py  liquidez real de resgate via API da XP + CVM extrato_fi.csv
```

## Limitações conhecidas

- **Liquidez residual:** 503 de 530 fundos do catálogo (331 XP + 172 CVM `extrato_fi.csv`) possuem prazo
  verificado de cotização e liquidação; os poucos fundos não localizados em nenhuma das duas fontes (4 no
  universo ativo) utilizam estimativa por classe ANBIMA e exibem o marcador `est.`.
- **Come-cotas e IOF:** o come-cotas semestral de 15% já é modelado em fluxo discreto semestral sobre a
  taxa projetada constante (sem simular choques estocásticos intra-semestre nem alíquota de 20% de curto prazo);
  o IOF regressivo (<30 dias) segue desconsiderado dado o horizonte mínimo de 12 meses.
- **Taxa de administração:** o cadastro novo (RCVM 175) não traz a taxa para todos os fundos; a rentabilidade
  histórica da CVM, porém, já é divulgada líquida da taxa de administração.
- **Histórico e banco embarcado:** a janela padrão de regressão adota 24 meses com regularização Ridge, e
  a persistência utiliza banco H2 em arquivo local (pronta para migração de datasource para PostgreSQL em produção).
