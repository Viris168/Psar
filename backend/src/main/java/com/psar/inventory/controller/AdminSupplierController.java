package com.psar.inventory.controller;

import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.request.SupplierRequest;
import com.psar.inventory.dto.response.SupplierResponse;
import com.psar.inventory.service.SupplierService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin - suppliers")
@RestController
@RequestMapping("/api/admin/suppliers")
@RequiredArgsConstructor
public class AdminSupplierController {

    private final SupplierService supplierService;

    @Operation(summary = "List suppliers (q matches name, phone or email; sort by name or createdAt)")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public PageResponse<SupplierResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return supplierService.list(q, active, pageable);
    }

    @Operation(summary = "Get one supplier")
    @GetMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public SupplierResponse get(@PathVariable UUID publicId) {
        return supplierService.get(publicId);
    }

    @Operation(summary = "Create a supplier")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public SupplierResponse create(@Valid @RequestBody SupplierRequest request) {
        return supplierService.create(request);
    }

    @Operation(summary = "Update a supplier (set active = true to restore a deleted one)")
    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public SupplierResponse update(@PathVariable UUID publicId, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(publicId, request);
    }

    @Operation(summary = "Soft delete a supplier (refused while it has pending purchase orders)")
    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void delete(@PathVariable UUID publicId) {
        supplierService.delete(publicId);
    }
}
