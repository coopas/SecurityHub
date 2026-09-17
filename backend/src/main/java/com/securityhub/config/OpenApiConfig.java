package com.securityhub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI securityHubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SecurityHub API")
                        .version("1.0.0")
                        .description("Gestão de ativos e vulnerabilidades de segurança. "
                                + "Todos os recursos de domínio são isolados por empresa: o "
                                + "identificador da empresa vem sempre do token, nunca da requisição.")
                        .contact(new Contact().name("SecurityHub"))
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Informe o accessToken devolvido por POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
