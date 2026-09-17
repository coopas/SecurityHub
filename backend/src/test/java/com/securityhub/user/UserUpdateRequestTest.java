package com.securityhub.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.securityhub.user.dto.UserUpdateRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Espelha o teste de porta dos fundos de {@code VulnerabilityRequest}: a regra não é "o serviço
 * ignora o e-mail", é "o DTO não tem por onde recebê-lo". O e-mail é o identificador de login e
 * o canal de recuperação, então repontá-lo é uma primitiva de tomada de conta; a ausência do
 * campo é o que impede que um mapeamento distraído a reintroduza.
 */
class UserUpdateRequestTest {

    @Test
    void theUpdatePayloadHasNoEmailFieldAtAll() {
        assertThat(UserUpdateRequest.class.getDeclaredFields())
                .extracting("name")
                .doesNotContain("email", "password", "role", "active", "companyId");
    }

    @Test
    void theUpdatePayloadExposesNoSetterBeyondTheName() {
        assertThat(UserUpdateRequest.class.getMethods())
                .extracting(Method::getName)
                .doesNotContain("setEmail", "setPassword", "setRole", "setActive", "setCompanyId");
    }

    @Test
    void theUpdatePayloadStillCarriesTheName() {
        assertThat(UserUpdateRequest.class.getDeclaredFields())
                .extracting("name")
                .containsExactly("name");
    }
}
