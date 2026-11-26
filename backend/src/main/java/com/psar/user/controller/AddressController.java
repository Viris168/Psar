package com.psar.user.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.user.dto.request.AddressRequest;
import com.psar.user.dto.response.AddressResponse;
import com.psar.user.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** A customer's own delivery addresses. Someone else's address is a 404. */
@Tag(name = "Addresses")
@RestController
@RequestMapping("/api/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "List my addresses (default first, then newest; sort parameter is ignored)")
    @GetMapping
    public PageResponse<AddressResponse> list(@AuthenticationPrincipal AuthenticatedUser me,
                                              @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return addressService.list(me, pageable);
    }

    @Operation(summary = "Get one of my addresses")
    @GetMapping("/{publicId}")
    public AddressResponse get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId) {
        return addressService.get(me, publicId);
    }

    @Operation(summary = "Add an address (max 10; the first one becomes the default)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@AuthenticationPrincipal AuthenticatedUser me,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.create(me, request);
    }

    @Operation(summary = "Replace one of my addresses")
    @PutMapping("/{publicId}")
    public AddressResponse update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.update(me, publicId, request);
    }

    @Operation(summary = "Delete one of my addresses (the newest remaining becomes default)")
    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId) {
        addressService.delete(me, publicId);
    }

    @Operation(summary = "Make one of my addresses the default")
    @PutMapping("/{publicId}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId) {
        return addressService.setDefault(me, publicId);
    }
}
