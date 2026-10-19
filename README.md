# InvestDecide — Protótipo

Protótipo acadêmico de um Sistema de Apoio à Decisão (SAD) para comparar alternativas de investimento para um aporte específico de um cliente.

## O que está incluído

- **Login:** tela demonstrativa; qualquer e-mail/senha preenchidos levam ao dashboard.
- **Dashboard — cockpit do cliente:** cliente em foco (perfil, horizonte, liquidez, objetivo), alertas do que mudou desde a última análise, indicadores de decisão, projeção do aporte e alternativas com a **elegibilidade** de cada produto.
- **Nova simulação:** contexto do cliente, critérios de elegibilidade aplicados, **ranking com justificativa** (posição, escore, motivo e ressalva), comparativo com coluna de situação e filtro "mostrar apenas elegíveis".
- **Relatórios:** biblioteca com busca, filtro por categoria, cliente e **exportação em PDF** pela impressão do navegador.
- Navegação entre as três áreas e ligações entre dashboard, simulação e relatórios.
- Interface com Thymeleaf, Bootstrap e estilos próprios.

## Tecnologias

- Java 17+
- Spring Boot 3.5.16
- Spring Web MVC
- Thymeleaf
- Bootstrap 5 (CDN)
- Chart.js (CDN)
- Driver PostgreSQL incluído para a etapa de integração
- Python 3.10+ para o script de ingestão de dados (`scripts/cvm_fundos.py`)

O projeto usa Spring Boot 3.5.16, que requer Java 17 ou superior conforme a documentação oficial: https://docs.spring.io/spring-boot/3.5/system-requirements.html.

## Por que Thymeleaf + Bootstrap (e não React)

O sistema é um fluxo autenticado, orientado a formulários, tabelas e relatórios — o cenário em que a renderização no servidor é mais simples e mais barata de manter. A decisão está registrada aqui porque será cobrada na apresentação:

| Critério | Thymeleaf + Bootstrap (escolhido) | React + TypeScript |
|---|---|---|
| Curva de aprendizado | Baixa: HTML, CSS e o modelo do Spring | Alta: componentes, estado, build, rotas, autenticação via token |
| Custo de manutenção | Um projeto, um deploy (jar) | Dois projetos, CORS, variáveis de ambiente, dois deploys |
| Adequação ao domínio | Formulários, tabelas e relatórios renderizados no servidor | Melhor para telas muito interativas, tempo real e apps móveis |
| Integração com Spring | Nativa (`th:each`, validação, segurança de sessão) | Exige API REST + cliente separado |
| Prazo do semestre | Entrega o fluxo completo com o tempo disponível | Risco alto de a interface consumir o tempo do motor de decisão |

A recomendação é **manter Thymeleaf + Bootstrap** e, se precisar de interatividade pontual (filtros, atualização parcial de ranking), usar **HTMX** ou JavaScript puro — que já é o caso do filtro "mostrar apenas elegíveis". Chart.js continua atendendo aos gráficos. Se no futuro a interface precisar virar aplicativo, os mesmos controllers podem expor JSON e o React entra sem refazer o backend.

## Como executar

1. Instale um JDK 17 ou superior e Maven 3.6.3+.
2. Abra um terminal na pasta que contém este README.
3. Execute:

   ```bash
   mvn spring-boot:run
   ```

4. Acesse `http://localhost:8080`.
5. Clique em **Entrar na plataforma** (os dados já aparecem preenchidos). O sistema abre o dashboard.

Também é possível importar a pasta como projeto Maven no IntelliJ IDEA, Eclipse ou VS Code com extensão Java.

## Telas e rotas

| Tela | Endereço |
|---|---|
| Login | `/login` |
| Dashboard (cockpit do cliente) | `/dashboard` |
| Nova simulação | `/simulacao` |
| Relatórios | `/relatorios` |

## Dados: CVM e Banco Central

O script `scripts/cvm_fundos.py` monta a base analítica a partir de fontes públicas e gratuitas:

```bash
# 24 meses de informe diário + cadastro + fatores macro (CDI, IPCA, dólar)
python scripts/cvm_fundos.py tudo --meses 24 --limite 120

# reprocessar sem baixar nada (útil para ajustar filtros)
python scripts/cvm_fundos.py processar --meses 24 --limite 120

# universo pequeno, para testar rápido
python scripts/cvm_fundos.py tudo --meses 6 --limite 20 --min-pl 10000000

# teste offline, com uma base sintética no formato da CVM
python scripts/_fixture/gerar.py
python scripts/cvm_fundos.py processar --inicio 2026-01 --fim 2026-02 \
       --raw-dir scripts/_fixture/raw --out-dir scripts/_fixture/out --sem-fatores --min-pl 0 --limite 0
```

Saídas em `data/processed/`:

| Arquivo | Conteúdo |
|---|---|
| `catalogo_fundos.csv` | um registro por fundo: taxa de administração, classe ANBIMA, tributação de longo prazo, público-alvo, situação, PL e estatísticas do período |
| `fundos_diario.csv` | painel diário (cota, PL, captação, resgate, cotistas) |
| `fundos_mensal.csv` | retorno mensal, volatilidade anualizada, PL médio, captação líquida e retorno acumulado |
| `fatores_macro.csv` | CDI mensal, IPCA e dólar |
| `qualidade.json` | meses faltantes, percentual de nulos, fundos sem taxa de administração |

Detalhes que importam para a modelagem:

- **Defasagem:** o informe diário é publicado com um dia útil de atraso (art. 59 da ICVM 555); o arquivo do mês corrente é atualizado diariamente e os meses anteriores, semanalmente.
- **Cota líquida:** `VL_QUOTA` já vem descontada da taxa de administração. Para simular outra taxa, ajuste pela diferença — não desconte a taxa duas vezes.
- **RCVM 175:** desde out/2024 o arquivo `cad_fi.csv` lista apenas fundos não adaptados; os adaptados estão em `registro_fundo_classe.zip`. O script lê os dois e reconcilia pelo CNPJ.
- **Previdência:** PGBL/VGBL são planos supervisionados pela SUSEP. Confirme no cadastro se os produtos que você quer usar aparecem na base da CVM; se não, mantenha esse lado com dados simulados e declare a limitação.

O esquema para carregar as saídas no PostgreSQL está em `db/schema.sql`.

## Avisos importantes sobre o protótipo

- Não há autenticação real, autorização nem cadastro de usuários.
- Os dados e os indicadores são fictícios; o formulário da simulação ainda não recalcula os resultados (o motor de decisão está previsto para a próxima etapa).
- Os botões de exportação usam a impressão do navegador; a geração de PDF no servidor virá com o relatório funcional.
- O PostgreSQL ainda não é acessado. O driver está no `pom.xml` e `application-postgres.properties.example` registra as propriedades de referência. Para conectar de verdade, será necessário adicionar Spring Data JPA ou Spring JDBC, criar o banco/tabelas e substituir os dados fictícios por repositórios.
- A lógica de tributação e os resultados apresentados não foram validados como cálculo financeiro ou tributário. Não usar o protótipo para orientar investimentos reais.
- Bootstrap, ícones e Chart.js são carregados por CDN; conexão à internet é necessária para esses recursos visuais.

## Estrutura do projeto

```text
src/main/java/br/com/investdecide/
  InvestDecideApplication.java
  PagesController.java
src/main/resources/
  application.properties
  application-postgres.properties.example
  templates/
    login.html
    dashboard.html
    simulacao.html
    relatorios.html
    fragments/
      sidebar.html
      topbar.html
  static/
    css/app.css
    js/app.js
    js/dashboard.js
    js/simulacao.js
    js/reports.js
scripts/
  cvm_fundos.py        # ingestão CVM + BCB
db/
  schema.sql           # tabelas e view para o PostgreSQL
```

## Próximas etapas sugeridas

1. Definir as entidades de cliente, produto, simulação e resultado.
2. Integrar PostgreSQL com Spring Data JPA e configurar validações.
3. Implementar o motor de decisão: filtros de elegibilidade, escore com pesos e ranking explicado.
4. Implementar as três funções analíticas (sumarização, agrupamento e regressão) sobre a base gerada por `cvm_fundos.py`.
5. Registrar premissas, versão das regras tributárias e data de referência em cada simulação.
6. Adicionar autenticação e autorização antes de armazenar qualquer dado real.
