package com.psar.messaging.event;

/**
 * Marker for domain events (e.g. OrderPaidEvent, OrderDeliveredEvent).
 *
 * Rules:
 *  - Events are Java records that carry IDs only; consumers reload fresh data.
 *  - routingKey() matches the RabbitMQ routing key, e.g. "order.paid".
 *  - Phases 1-8: published with ApplicationEventPublisher and handled with
 *    @TransactionalEventListener(phase = AFTER_COMMIT).
 *  - Phase 9: written to outbox_events in the same transaction and relayed to RabbitMQ.
 */
public interface DomainEvent {

    String routingKey();
}
