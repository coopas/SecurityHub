package com.securityhub.shared.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretTokensTest {

    /** O mesmo formato que as CHECKs de V7 exigem em toda coluna token_hash. */
    private static final String HASH_FORMAT = "^[0-9a-f]{64}$";

    @Test
    void randomCarriesThirtyTwoBytesOfEntropy() {
        byte[] material = Base64.getUrlDecoder().decode(SecretTokens.random());

        assertThat(material).hasSize(32);
    }

    @Test
    void randomIsUrlSafeAndUnpadded() {
        String token = SecretTokens.random();

        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void randomNeverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(SecretTokens.random());
        }

        assertThat(seen).hasSize(2000);
    }

    @Test
    void hashMatchesTheFormatTheDatabaseEnforces() {
        assertThat(SecretTokens.hash(SecretTokens.random())).matches(HASH_FORMAT);
    }

    /** Vetor público de SHA-256("abc"): prende o algoritmo, não só o formato. */
    @Test
    void hashIsPlainSha256InLowercaseHex() {
        assertThat(SecretTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashIsDeterministicForTheSameToken() {
        String token = SecretTokens.random();

        assertThat(SecretTokens.hash(token)).isEqualTo(SecretTokens.hash(token));
    }

    @Test
    void hashDiffersForDifferentTokens() {
        assertThat(SecretTokens.hash("um-token")).isNotEqualTo(SecretTokens.hash("outro-token"));
    }

    @Test
    void hashRefusesNull() {
        assertThatThrownBy(() -> SecretTokens.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
