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