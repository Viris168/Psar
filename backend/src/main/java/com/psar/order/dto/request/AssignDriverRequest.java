package com.psar.order.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** CITY orders: the driver's user publicId (an active STAFF user with position "Driver"). */
public record AssignDriverRequest(@NotNull UUID driverPublicId) {
}
