package com.psar.user.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Stored as the lowercase code ('en', 'km'), see {@link LanguageConverter}. */
@Getter
@RequiredArgsConstructor
public enum Language {
    EN("en"),
    KM("km");

    private final String code;

    public static Language fromCode(String code) {
        for (Language language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unknown language code: " + code);
    }
}
