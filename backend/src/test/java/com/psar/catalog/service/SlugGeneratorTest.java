package com.psar.catalog.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlugGeneratorTest {

    @Test
    void slugify_latinName_lowercaseWithHyphens() {
        assertThat(SlugGenerator.slugify("Fresh Milk 1L", "SKU-1")).isEqualTo("fresh-milk-1l");
    }

    @Test
    void slugify_punctuationAndAccents_asciiOnly() {
        assertThat(SlugGenerator.slugify("  Café & Crème -- Brûlée!  ", "X")).isEqualTo("cafe-creme-brulee");
    }

    @Test
    void slugify_khmerOnlyName_usesFallback() {
        assertThat(SlugGenerator.slugify("ទឹកដោះគោ", "MILK-001")).isEqualTo("milk-001");
    }

    @Test
    void slugify_digitsOnlyName_usesFallback() {
        assertThat(SlugGenerator.slugify("100", "SKU-100")).isEqualTo("sku-100");
    }

    @Test
    void slugify_mixedKhmerAndLatin_keepsLatinPart() {
        assertThat(SlugGenerator.slugify("ទឹក Water 500ml", "W")).isEqualTo("water-500ml");
    }

    @Test
    void slugify_veryLongName_truncatedWithoutTrailingHyphen() {
        String slug = SlugGenerator.slugify("word ".repeat(60), "X");
        assertThat(slug).hasSizeLessThanOrEqualTo(SlugGenerator.MAX_BASE_LENGTH).doesNotEndWith("-");
    }

    @Test
    void unique_freeSlug_returnedAsIs() {
        assertThat(SlugGenerator.unique("milk", s -> false)).isEqualTo("milk");
    }

    @Test
    void unique_takenSlugs_addsNextNumber() {
        Set<String> taken = Set.of("milk", "milk-2", "milk-3");
        assertThat(SlugGenerator.unique("milk", taken::contains)).isEqualTo("milk-4");
    }
}
