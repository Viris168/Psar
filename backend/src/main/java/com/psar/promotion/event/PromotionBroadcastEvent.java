package com.psar.promotion.event;

/**
 * A promotion is to be announced to every active customer: a manager pressed "broadcast", or
 * PromotionLaunchJob found it just started with {@code broadcast_on_start}. Published in the transaction that set
 * {@code broadcast_at} (so it happens once); listen AFTER_COMMIT. Ids only.
 */
public record PromotionBroadcastEvent(Long promotionId) {
}
