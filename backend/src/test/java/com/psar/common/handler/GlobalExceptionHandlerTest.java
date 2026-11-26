package com.psar.common.handler;

import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.user.dto.request.AddressRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {

        @GetMapping("/missing")
        void missing() {
            throw NotFoundException.of("Address");
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email is already registered");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/addresses/{publicId}")
        void byId(@PathVariable UUID publicId) {
        }

        @PostMapping("/addresses")
        void create(@Valid @RequestBody AddressRequest request) {
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void handleApi_notFound_returns404WithCode() throws Exception {
        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Address not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void handleApi_specificConflictCode_usesThatCode() throws Exception {
        mockMvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void handleUnexpected_anyException_returns500WithoutLeakingMessage() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Something went wrong"));
    }

    @Test
    void handleTypeMismatch_invalidUuid_returns400() throws Exception {
        mockMvc.perform(get("/addresses/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details.publicId").exists());
    }

    @Test
    void handleBodyValidation_cityWithoutPin_returns400WithFieldDetails() throws Exception {
        String body = """
                {"receiverName": "Dara", "phone": "012345678",
                 "deliveryType": "CITY", "province": "Phnom Penh"}
                """;
        mockMvc.perform(post("/addresses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.cityLocationValid")
                        .value("CITY addresses need a map pin (latitude and longitude)"));
    }

    @Test
    void handleUnreadable_unknownEnumValue_returns400NamingTheField() throws Exception {
        String body = """
                {"receiverName": "Dara", "phone": "012345678",
                 "deliveryType": "BOAT", "province": "Phnom Penh"}
                """;
        mockMvc.perform(post("/addresses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details.deliveryType").value("Invalid value: BOAT"));
    }
}
