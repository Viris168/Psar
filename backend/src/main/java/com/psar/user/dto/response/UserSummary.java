package com.psar.user.dto.response;

import java.util.UUID;

/** Who someone is, for other modules' admin screens (e.g. the actor of an audit row). No internal id. */
public record UserSummary(UUID publicId, String email, String fullName) {
}
