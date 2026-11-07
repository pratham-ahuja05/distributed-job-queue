package com.pratham.apiservice.domain;

import com.pratham.apiservice.queue.QueueKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriorityTest {

    @Test
    void parsesNamesNumbersAndNumericStrings() {
        assertThat(Priority.parse("high")).isEqualTo(Priority.HIGH);
        assertThat(Priority.parse(" Medium ")).isEqualTo(Priority.MEDIUM);
        assertThat(Priority.parse(3)).isEqualTo(Priority.LOW);
        assertThat(Priority.parse("1")).isEqualTo(Priority.HIGH);
    }

    @Test
    void rejectsUnknownValues() {
        assertThatThrownBy(() -> Priority.parse("urgent")).hasMessageContaining("HIGH, MEDIUM or LOW");
        assertThatThrownBy(() -> Priority.parse(0)).hasMessageContaining("1 (HIGH)");
        assertThatThrownBy(() -> Priority.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsToQueuesAndTerminalStatuses() {
        assertThat(Priority.HIGH.queue()).isEqualTo(QueueKeys.HIGH);
        assertThat(Priority.LOW.level()).isEqualTo(3);
        assertThat(JobStatus.SUCCESS.isTerminal()).isTrue();
        assertThat(JobStatus.DLQ.isTerminal()).isTrue();
        assertThat(JobStatus.RETRYING.isTerminal()).isFalse();
    }
}
