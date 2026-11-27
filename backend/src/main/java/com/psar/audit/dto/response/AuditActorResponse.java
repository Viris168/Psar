package com.psar.audit.dto.response;

import java.util.UUID;

/** The user who did the action. Never the internal user id. */
public record AuditActorResponse(UUID publicId, String email, String fullName) {
}
