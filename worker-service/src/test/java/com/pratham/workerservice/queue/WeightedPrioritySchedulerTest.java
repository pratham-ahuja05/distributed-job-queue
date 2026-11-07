package com.pratham.workerservice.queue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightedPrioritySchedulerTest {

    @Test
    void preferredQueueFollowsSixThreeOneWeights() {
        WeightedPriorityScheduler scheduler = new WeightedPriorityScheduler();
        Map<String, Integer> preferred = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            preferred.merge(scheduler.nextOrder().get(0), 1, Integer::sum);
        }
        assertThat(preferred).containsEntry(QueueKeys.HIGH, 600)
                .containsEntry(QueueKeys.MEDIUM, 300)
                .containsEntry(QueueKeys.LOW, 100);
    }

    @Test
    void everyOrderStillContainsAllQueuesWithFallbackInPriorityOrder() {
        WeightedPriorityScheduler scheduler = new WeightedPriorityScheduler();
        for (int i = 0; i < 10; i++) {
            List<String> order = scheduler.nextOrder();
            assertThat(order).containsExactlyInAnyOrder(QueueKeys.HIGH, QueueKeys.MEDIUM, QueueKeys.LOW);
            List<String> fallback = order.subList(1, 3);
            assertThat(QueueKeys.BY_PRIORITY.indexOf(fallback.get(0)))
                    .isLessThan(QueueKeys.BY_PRIORITY.indexOf(fallback.get(1)));
        }
    }

    @Test
    void queueForRejectsUnknownPriority() {
        assertThat(QueueKeys.queueFor(1)).isEqualTo(QueueKeys.HIGH);
        assertThatThrownBy(() -> QueueKeys.queueFor(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueKeys.queueFor(4)).isInstanceOf(IllegalArgumentException.class);
    }
}
