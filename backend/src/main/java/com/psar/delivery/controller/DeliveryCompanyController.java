package com.psar.delivery.controller;

import com.psar.common.web.PageResponse;
import com.psar.delivery.dto.response.DeliveryCompanyResponse;
import com.psar.delivery.service.DeliveryCompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery companies")
@RestController
@RequestMapping("/api/delivery-companies")
@RequiredArgsConstructor
public class DeliveryCompanyController {

    private final DeliveryCompanyService deliveryCompanyService;

    @Operation(summary = "List active delivery companies (for choosing a PROVINCE courier)")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<DeliveryCompanyResponse> listActive(
            @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return deliveryCompanyService.listActive(pageable);
    }
}
