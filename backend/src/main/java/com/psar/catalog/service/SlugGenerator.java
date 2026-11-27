package com.psar.catalog.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * URL slugs: lowercase ASCII words joined by hyphens ({@code "Fresh Milk 1L"} -> {@code fresh-milk-1l}).
 * Pure logic, no Spring: the caller passes the "already taken?" check.
 */
public final class SlugGenerator {

    /** Leaves room for a "-NNN" suffix inside the smallest slug column (categories: 120). */
    static final int MAX_BASE_LENGTH = 100;

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+|-+$)");
    private static final Pattern LATIN_LETTER = Pattern.compile("[a-z]");

    private SlugGenerator() {
    }

    /**
     * Slug for {@code text}; when it has no Latin letters (e.g. a Khmer-only name) the slug is made from
     * {@code fallback} instead (a product's SKU). Returns {@code null} when neither gives a usable slug.
     */
    public static String slugify(String text, String fallback) {
        String slug = toSlug(text);
        if (slug != null && LATIN_LETTER.matcher(slug).find()) {
            return slug;
        }
        return toSlug(fallback);
    }

    /** Appends -2, -3, ... until {@code taken} says the slug is free. */
    public static String unique(String base, Predicate<String> taken) {
        if (!taken.test(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + "-" + n;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    private static String toSlug(String text) {
        if (text == null) {
            return null;
        }
        String ascii = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String slug = NON_ALNUM.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = EDGE_HYPHENS.matcher(slug).replaceAll("");
        if (slug.length() > MAX_BASE_LENGTH) {
            slug = EDGE_HYPHENS.matcher(slug.substring(0, MAX_BASE_LENGTH)).replaceAll("");
        }
        return slug.isEmpty() ? null : slug;
    }
}
