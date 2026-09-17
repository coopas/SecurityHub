package com.securityhub.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.securityhub.user.dto.UserUpdateRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Mirrors the back-door test of {@code VulnerabilityRequest}: the rule is not "the service ignores
 * the e-mail", it is "the DTO has no way to receive it". The e-mail is the login identifier and the
 * recovery channel, so repointing it is an account-takeover primitive; the absence of the field is
 * what stops a careless mapping from reintroducing it.
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
