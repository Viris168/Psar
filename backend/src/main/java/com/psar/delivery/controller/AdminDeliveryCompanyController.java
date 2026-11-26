package com.psar.delivery.controller;

import com.psar.common.web.PageResponse;
import com.psar.delivery.dto.request.DeliveryCompanyRequest;
import com.psar.delivery.dto.response.DeliveryCompanyResponse;
import com.psar.delivery.service.DeliveryCompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - delivery companies")
@RestController
@RequestMapping("/api/admin/delivery-companies")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDeliveryCompanyController {

    private final DeliveryCompanyService deliveryCompanyService;

    @Operation(summary = "List all delivery companies, active and inactive")
    @GetMapping
    public PageResponse<DeliveryCompanyResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return deliveryCompanyService.listAll(pageable);
    }

    @Operation(summary = "Get one delivery company")
    @GetMapping("/{id}")
    public DeliveryCompanyResponse get(@PathVariable Long id) {
        return deliveryCompanyService.get(id);
    }

    @Operation(summary = "Create a delivery company")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryCompanyResponse create(@Valid @RequestBody DeliveryCompanyRequest request) {
        return deliveryCompanyService.create(request);
    }

    @Operation(summary = "Update a delivery company (set active = true to restore a deleted one)")
    @PutMapping("/{id}")
    public DeliveryCompanyResponse update(@PathVariable Long id, @Valid @RequestBody DeliveryCompanyRequest request) {
        return deliveryCompanyService.update(id, request);
    }

    @Operation(summary = "Soft delete a delivery company (sets active = false)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        deliveryCompanyService.delete(id);
    }
}
