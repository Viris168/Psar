package com.psar.user.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.user.dto.request.CreateStaffRequest;
import com.psar.user.dto.request.UpdateUserRoleRequest;
import com.psar.user.dto.request.UpdateUserStatusRequest;
import com.psar.user.dto.response.UserResponse;
import com.psar.user.entity.Role;
import com.psar.user.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin - users")
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService userAdminService;

    @Operation(summary = "List accounts, optionally filtered by role and email search")
    @GetMapping
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return userAdminService.list(pageable, role, q);
    }

    @Operation(summary = "Get one account")
    @GetMapping("/{publicId}")
    public UserResponse get(@PathVariable UUID publicId) {
        return userAdminService.get(publicId);
    }

    @Operation(summary = "Create a STAFF, MANAGER or ADMIN account")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateStaffRequest request) {
        return userAdminService.createStaff(request);
    }

    @Operation(summary = "Change an account's role")
    @PatchMapping("/{publicId}/role")
    public UserResponse changeRole(@AuthenticationPrincipal AuthenticatedUser me,
                                   @PathVariable UUID publicId,
                                   @Valid @RequestBody UpdateUserRoleRequest request) {
        return userAdminService.changeRole(me, publicId, request);
    }

    @Operation(summary = "Enable or disable an account (disabling logs it out everywhere)")
    @PatchMapping("/{publicId}/status")
    public UserResponse changeStatus(@AuthenticationPrincipal AuthenticatedUser me,
                                     @PathVariable UUID publicId,
                                     @Valid @RequestBody UpdateUserStatusRequest request) {
        return userAdminService.changeStatus(me, publicId, request);
    }
}
