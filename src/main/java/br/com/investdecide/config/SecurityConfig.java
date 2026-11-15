package br.com.investdecide.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Autenticação por e-mail e senha.
 *
 * <p>Nesta versão o usuário é mantido em memória (um consultor). A troca para uma tabela de
 * usuários no PostgreSQL exige apenas substituir o {@link UserDetailsService} por um
 * repositório JPA — o restante da configuração permanece.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filtro(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(autorizacao -> autorizacao
                        .requestMatchers("/css/**", "/js/**", "/img/**", "/login", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(formulario -> formulario
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("senha")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login?erro")
                        .permitAll())
                .logout(saida -> saida
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?saida")
                        .permitAll());
        return http.build();
    }

    @Bean
    public UserDetailsService usuarios(PasswordEncoder codificador) {
        UserDetails consultor = User.withUsername("consultor@investdecide.com.br")
                .password(codificador.encode("Invest@2026"))
                .roles("CONSULTOR")
                .build();
        return new InMemoryUserDetailsManager(consultor);
    }

    @Bean
    public PasswordEncoder codificador() {
        return new BCryptPasswordEncoder();
    }
}
