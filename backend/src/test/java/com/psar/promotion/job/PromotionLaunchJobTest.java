package com.psar.promotion.job;

import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import com.psar.support.IntegrationTest;
import com.psar.support.PromotionFixtures;
import com.psar.support.SystemFixtures;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionLaunchJobTest extends IntegrationTest {

    @Autowired
    private PromotionLaunchJob job;
    @Autowired
    private PromotionFixtures promotions;
    @Autowired
    private SystemFixtures system;

    @Test
    void launchJob_startedPromotion_broadcastOnce() {
        User customer = system.user(Role.CUSTOMER);
        Promotion promotion = promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> {
            p.setStartDate(clock.instant().plus(Duration.ofHours(1)));
            p.setBroadcastOnStart(true);
        });

        job.launchStartedPromotions();
        assertThat(notes(customer, promotion)).isEmpty();
        assertThat(promotions.reload(promotion).getBroadcastAt()).isNull();

        clock.advance(Duration.ofHours(2));
        job.launchStartedPromotions();
        assertThat(notes(customer, promotion)).hasSize(1);
        assertThat(promotions.reload(promotion).getBroadcastAt()).isNotNull();

        job.launchStartedPromotions();
        assertThat(notes(customer, promotion)).hasSize(1);
    }

    @Test
    void launchJob_withoutFlagOrInactive_notBroadcast() {
        User customer = system.user(Role.CUSTOMER);
        Promotion noFlag = promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> { });
        Promotion inactive = promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> {
            p.setBroadcastOnStart(true);
            p.setActive(false);
        });

        job.launchStartedPromotions();

        assertThat(notes(customer, noFlag)).isEmpty();
        assertThat(notes(customer, inactive)).isEmpty();
        assertThat(promotions.reload(inactive).getBroadcastAt()).isNull();
    }

    private List<Notification> notes(User user, Promotion promotion) {
        return system.notifications(user, NotificationType.PROMOTION).stream()
                .filter(n -> n.getTitle().equals(promotion.getName()))
                .toList();
    }
}
