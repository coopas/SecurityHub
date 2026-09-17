package com.securityhub.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlugGeneratorTest {

    @Test
    void removesAccentsAndPunctuation() {
        assertThat(SlugGenerator.slugify("Açaí & Segurança Ltda.")).isEqualTo("acai-seguranca-ltda");
    }

    @Test
    void collapsesSeparatorsAndTrimsEdges() {
        assertThat(SlugGenerator.slugify("  ---Acme   Corp---  ")).isEqualTo("acme-corp");
    }

    @Test
    void fallsBackWhenNothingUsableRemains() {
        assertThat(SlugGenerator.slugify("!!!")).isEqualTo("empresa");
    }

    @Test
    void truncatesLongNamesWithoutTrailingSeparator() {
        String slug = SlugGenerator.slugify(String.join(" ", java.util.Collections.nCopies(40, "empresa")));
        assertThat(slug).hasSizeLessThanOrEqualTo(130).doesNotEndWith("-");
    }

    @Test
    void appendsSuffixUntilSlugIsFree() {
        Set<String> taken = new HashSet<>();
        taken.add("acme");
        taken.add("acme-2");

        assertThat(SlugGenerator.uniqueSlug("Acme", taken::contains)).isEqualTo("acme-3");
    }

    @Test
    void keepsBaseSlugWhenAvailable() {
        assertThat(SlugGenerator.uniqueSlug("Acme", slug -> false)).isEqualTo("acme");
    }
}
