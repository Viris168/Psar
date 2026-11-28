package com.psar.promotion.job;

import com.psar.promotion.repository.PromotionRepository;
import com.psar.promotion.service.AdminPromotionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Every 10 minutes: promotions saved with {@code broadcast_on_start} that have started (and not ended) and were
 * not announced yet are broadcast to every active customer. Finds the ids; each one is broadcast in its own
 * transaction ({@link AdminPromotionService#launch}), where {@code broadcast_at} makes it happen once even if a
 * manager broadcasts at the same moment. ShedLock: only one instance runs it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionLaunchJob {

    private final PromotionRepository promotionRepository;
    private final AdminPromotionService adminPromotionService;
    private final Clock clock;

    /** ShedLock only locks void methods, so the work with its count is in {@link #launchStartedPromotions()}. */
    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "PromotionLaunchJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        launchStartedPromotions();
    }

    /** Returns how many promotions were broadcast (tests call this directly; scheduling is off there). */
    public int launchStartedPromotions() {
        List<Long> ids = promotionRepository.findIdsToLaunch(clock.instant());
        int launched = 0;
        for (Long id : ids) {
            try {
                if (adminPromotionService.launch(id)) {
                    launched++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not broadcast promotion {}", id, ex);
            }
        }
        if (launched > 0) {
            log.info("Launch job broadcast {} promotion(s)", launched);
        }
        return launched;
    }
}
