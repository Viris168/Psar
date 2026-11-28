package com.psar.notification.listener;

import com.psar.notification.entity.NotificationType;
import com.psar.notification.service.NotificationService;
import com.psar.promotion.dto.response.PromotionAnnouncement;
import com.psar.promotion.event.PromotionBroadcastEvent;
import com.psar.promotion.service.PublicPromotionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Promotion broadcast → one PROMOTION notification for every active customer. Runs after {@code broadcast_at}
 * committed, in its own transaction: a failure is logged and never undoes the broadcast mark (so it isn't sent
 * twice). In-process for now; Phase 9 moves it to RabbitMQ in batches.
 */
@Slf4j
@Component
public class PromotionNotificationListener {

    static final Set<String> RECIPIENT_ROLES = Set.of("CUSTOMER");
    static final String LINK = "/promotions";
    private static final int TITLE_MAX = 150;
    private static final int MESSAGE_MAX = 1000;
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Phnom_Penh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final PublicPromotionService publicPromotionService;
    private final NotificationService notificationService;
    private final TransactionTemplate requiresNew;

    public PromotionNotificationListener(PublicPromotionService publicPromotionService,
                                         NotificationService notificationService,
                                         PlatformTransactionManager transactionManager) {
        this.publicPromotionService = publicPromotionService;
        this.notificationService = notificationService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBroadcast(PromotionBroadcastEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> notify(event.promotionId()));
        } catch (RuntimeException ex) {
            log.error("Promotion broadcast failed for promotion {}", event.promotionId(), ex);
        }
    }

    private void notify(Long promotionId) {
        PromotionAnnouncement promotion = publicPromotionService.announcement(promotionId).orElse(null);
        if (promotion == null || !promotion.active()) {
            log.debug("Promotion {} is gone or inactive; not broadcast", promotionId);
            return;
        }
        int sent = notificationService.notifyRoles(RECIPIENT_ROLES, NotificationType.PROMOTION,
                truncate(promotion.name(), TITLE_MAX), truncate(message(promotion), MESSAGE_MAX), LINK);
        log.info("Promotion {} broadcast to {} customer(s)", promotionId, sent);
    }

    /** e.g. "Big sale. 20% off — until 31 Dec 2026. Use code WELCOME5 at checkout." */
    static String message(PromotionAnnouncement p) {
        StringBuilder text = new StringBuilder();
        if (p.description() != null && !p.description().isBlank()) {
            text.append(p.description().trim()).append(' ');
        }
        text.append(offer(p));
        if (p.minOrderAmount() != null && p.minOrderAmount().signum() > 0) {
            text.append(" on orders from $").append(p.minOrderAmount().toPlainString());
        }
        text.append(" — until ").append(DATE.format(p.endsAt().atZone(SHOP_ZONE))).append('.');
        if (p.couponCode() != null) {
            text.append(" Use code ").append(p.couponCode()).append(" at checkout.");
        }
        return text.toString();
    }

    private static String offer(PromotionAnnouncement p) {
        return switch (p.type()) {
            case "PERCENTAGE" -> p.discountValue().stripTrailingZeros().toPlainString() + "% off";
            case "FIXED_AMOUNT" -> "$" + p.discountValue().toPlainString() + " off";
            case "BUY_X_GET_Y" -> "Buy " + p.buyQuantity() + ", get " + p.getQuantity() + " free";
            case "FREE_DELIVERY" -> "Free delivery";
            default -> "Special offer";
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
