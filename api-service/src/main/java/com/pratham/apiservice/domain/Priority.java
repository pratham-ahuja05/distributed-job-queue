package com.pratham.apiservice.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.pratham.apiservice.queue.QueueKeys;

import java.util.Locale;

public enum Priority {
    HIGH(1, QueueKeys.HIGH),
    MEDIUM(2, QueueKeys.MEDIUM),
    LOW(3, QueueKeys.LOW);

    private final int level;
    private final String queue;

    Priority(int level, String queue) {
        this.level = level;
        this.queue = queue;
    }

    public int level() {
        return level;
    }

    public String queue() {
        return queue;
    }

    public static Priority ofLevel(int level) {
        for (Priority p : values()) {
            if (p.level == level) {
                return p;
            }
        }
        throw new IllegalArgumentException("priority must be 1 (HIGH), 2 (MEDIUM) or 3 (LOW), got " + level);
    }

    /** Accepts "HIGH" / "high" / 1 / "1" so the API is forgiving for curl users. */
    @JsonCreator
    public static Priority parse(Object raw) {
        if (raw instanceof Number n) {
            return ofLevel(n.intValue());
        }
        String s = String.valueOf(raw).trim();
        if (s.chars().allMatch(Character::isDigit) && !s.isEmpty()) {
            return ofLevel(Integer.parseInt(s));
        }
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("priority must be HIGH, MEDIUM or LOW, got '" + s + "'");
        }
    }
}
