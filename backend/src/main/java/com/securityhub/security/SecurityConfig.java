package com.securityhub.security;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private static final int BCRYPT_STRENGTH = 12;

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/password-reset/**",
            "/api/v1/invitations/accept",
            // Só a saúde, e ela precisa continuar aqui.
            //
            // Os endpoints de gestão mudaram para a porta própria de `management.server.port`,
            // e é tentador concluir que estes matchers viraram letra morta — que o contexto
            // filho da porta de gestão não passa por esta cadeia. Não é o que acontece no Boot
            // 2.7: o contexto filho herda o `springSecurityFilterChain` do pai, e esta cadeia
            // vale nas duas portas. Verificado na pilha do compose: sem esta entrada,
            // /actuator/health/readiness na porta 9090 responde 401, o healthcheck do contêiner
            // nunca fica saudável e o frontend, que depende dele, nunca sobe.
            //
            // `/actuator/info` e `/actuator/metrics` ficam de fora: caem em
            // `anyRequest().authenticated()`. Isso não é o controle — qualquer usuário de
            // qualquer papel tem um token válido. O controle é o bind em loopback da porta de
            // gestão no docker-compose.yml. O que esta cadeia garante é a outra metade, e a que
            // fechou o vazamento: na porta do tenant não existe handler nenhum de /actuator, de
            // modo que mesmo autenticado a resposta é 404.
            //
            // A saúde ser pública não reabre nada: `show-details: never` faz o corpo ser apenas
            // UP ou DOWN, e na porta do tenant não há handler para servi-la.
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors().configurationSource(corsConfigurationSource())
                .and()
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
                .and()
                .headers()
                .frameOptions().deny()
                .contentTypeOptions().and()
                .httpStrictTransportSecurity().includeSubDomains(true).maxAgeInSeconds(31536000)
                .and().and()
                .authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .antMatchers(PUBLIC_ENDPOINTS).permitAll()
                .anyRequest().authenticated()
                .and()
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = corsProperties.getAllowedOrigins();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(Arrays.asList("X-Request-Id", "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
