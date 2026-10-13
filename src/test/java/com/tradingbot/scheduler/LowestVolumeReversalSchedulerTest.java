package com.tradingbot.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tradingbot.service.LowestVolumeReversalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the LVR scheduler, focused on the 09:25 NSE OI spurts job. */
class LowestVolumeReversalSchedulerTest {

    @Test
    @DisplayName("09:25 job delegates to the NSE OI spurt augmentation")
    void testScheduledNseOiSpurtAugmentationDelegatesToService() {
        LowestVolumeReversalService service = mock(LowestVolumeReversalService.class);
        LowestVolumeReversalScheduler scheduler = new LowestVolumeReversalScheduler(service, null);
        scheduler.setSchedulerEnabled(true);

        scheduler.scheduledNseOiSpurtAugmentation();

        verify(service).runNseOiSpurtAugmentation();
    }

    @Test
    @DisplayName("Disabled scheduler skips the 09:25 augmentation entirely")
    void testScheduledNseOiSpurtAugmentationRespectsSchedulerFlag() {
        LowestVolumeReversalService service = mock(LowestVolumeReversalService.class);
        LowestVolumeReversalScheduler scheduler = new LowestVolumeReversalScheduler(service, null);
        scheduler.setSchedulerEnabled(false);

        scheduler.scheduledNseOiSpurtAugmentation();

        verify(service, never()).runNseOiSpurtAugmentation();
    }

    @Test
    @DisplayName("Scheduler flag defaults and round-trips")
    void testSchedulerEnabledAccessor() {
        LowestVolumeReversalScheduler scheduler =
                new LowestVolumeReversalScheduler(mock(LowestVolumeReversalService.class), null);
        assertThat(scheduler.isSchedulerEnabled()).isTrue();
        scheduler.setSchedulerEnabled(false);
        assertThat(scheduler.isSchedulerEnabled()).isFalse();
    }
}
