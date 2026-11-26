package com.psar.user.dto.request;

import com.psar.user.entity.Gender;
import com.psar.user.entity.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String fullName,
        @Size(max = 100) String fullNameKh,
        Gender gender,
        @Past LocalDate dateOfBirth,
        @Size(max = 30) String phone,
        @URL @Size(max = 255) String avatarUrl,
        @Size(max = 50) String telegramChatId,
        @NotNull Language language
) {
}
