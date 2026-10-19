# InvestDecide — Protótipo visual

Protótipo acadêmico de um Sistema de Apoio à Decisão (SAD) para comparar alternativas de investimento para um aporte específico de um cliente.

## O que está incluído

- **Login:** tela demonstrativa; qualquer e-mail/senha preenchidos levam ao dashboard.
- **Dashboard:** indicadores fictícios, gráfico de cenários e produtos em observação.
- **Nova simulação:** formulário de perfil/objetivo/tributação com resultado comparativo estático.
- **Relatórios:** biblioteca fictícia com busca e filtro por categoria no navegador.
- Navegação entre as três áreas principais e links entre fluxo de dashboard, simulação e relatórios.
- Interface com Thymeleaf, Bootstrap e estilos próprios.

## Tecnologias

- Java 17+
- Spring Boot 3.5.16
- Spring Web MVC
- Thymeleaf
- Bootstrap 5 (carregado por CDN)
- Chart.js (gráfico do dashboard, carregado por CDN)
- Driver PostgreSQL incluído para uma futura etapa de integração

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
| Dashboard | `/dashboard` |
| Nova simulação | `/simulacao` |
| Relatórios | `/relatorios` |

## Avisos importantes sobre o protótipo

- Não há autenticação real, autorização nem cadastro de usuários.
- Os dados e os indicadores são fictícios; a simulação não recalcula os resultados informados no formulário.
- Os botões de exportação/geração de relatório exibem mensagens de demonstração ou são placeholders.
- O PostgreSQL ainda não é acessado. O driver está no `pom.xml` e `application-postgres.properties.example` registra as propriedades de referência. Para conectar de verdade, será necessário adicionar Spring Data JPA ou Spring JDBC, criar o banco/tabelas, configurar credenciais em ambiente seguro e substituir os dados fictícios por repositórios.
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
    js/reports.js
```

## Próximas etapas sugeridas

1. Definir as entidades de cliente, produto, simulação e resultado.
2. Integrar PostgreSQL com Spring Data JPA e configurar validações.
3. Implementar cálculos de projeção e custo/tributação com premissas documentadas e testes automatizados.
4. Transformar o formulário em entrada real para o motor de decisão e registrar as premissas/versões utilizadas.
5. Adicionar autenticação e autorização antes de armazenar qualquer dado real.
