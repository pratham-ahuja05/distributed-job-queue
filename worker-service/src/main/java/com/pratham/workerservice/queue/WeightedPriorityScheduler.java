package com.pratham.workerservice.queue;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides which queue a worker should try first on each poll.
 *
 * Strict priority (always HIGH, then MEDIUM, then LOW) starves LOW forever under sustained
 * HIGH traffic. Instead we cycle a 10-slot schedule weighted 6:3:1, so when every queue is
 * non-empty HIGH gets ~60% of pulls, MEDIUM ~30% and LOW ~10%. If the preferred queue is
 * empty we fall through in strict priority order, so no capacity is wasted.
 */
@Component
public class WeightedPriorityScheduler {

    private static final int[] SLOTS = {1, 2, 1, 3, 1, 2, 1, 1, 2, 1}; // 6x HIGH, 3x MEDIUM, 1x LOW

    private final AtomicLong cursor = new AtomicLong();

    /** Queue keys in the order they should be tried for the next pop. */
    public List<String> nextOrder() {
        int preferred = SLOTS[(int) (cursor.getAndIncrement() % SLOTS.length)];
        List<String> order = new ArrayList<>(3);
        order.add(QueueKeys.queueFor(preferred));
        for (int p = 1; p <= 3; p++) {
            if (p != preferred) order.add(QueueKeys.queueFor(p));
        }
        return order;
    }
}
