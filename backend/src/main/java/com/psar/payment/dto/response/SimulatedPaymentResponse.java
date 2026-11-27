package com.psar.payment.dto.response;

/**
 * Dev / demo "simulate payment": what the confirmation did and the payment's status afterwards.
 *
 * @param outcome APPLIED, DUPLICATE, LATE, …
 * @param status  the payment's status, e.g. SUCCESS
 */
public record SimulatedPaymentResponse(String outcome, String status) {
}
