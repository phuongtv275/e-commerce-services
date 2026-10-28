package com.example.notificationservice.config;

import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateDataSeederTests {

    @Mock
    private NotificationTemplateRepository notificationTemplateRepository;

    @InjectMocks
    private NotificationTemplateDataSeeder seeder;

    @Test
    @DisplayName("Positive: Seeds templates when repository count is 0")
    @SuppressWarnings("unchecked")
    void testRun_WhenEmpty_SeedsTemplates() {
        when(notificationTemplateRepository.count()).thenReturn(0L);

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<List<NotificationTemplate>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationTemplateRepository, times(1)).saveAll(captor.capture());

        List<NotificationTemplate> savedTemplates = captor.getValue();
        assertThat(savedTemplates).isNotEmpty();
        assertThat(savedTemplates).extracting(NotificationTemplate::getEventType)
                .contains(
                        "user.registered",
                        "order.status-changed",
                        "order.cancelled",
                        "payment.succeeded",
                        "payment.refund-completed",
                        "payment.failed",
                        "user.password-reset-requested"
                );
    }

    @Test
    @DisplayName("Negative: Does not seed templates when repository already has templates (idempotent)")
    void testRun_WhenNotEmpty_SkipsSeeding() {
        when(notificationTemplateRepository.count()).thenReturn(5L);

        seeder.run(new DefaultApplicationArguments());

        verify(notificationTemplateRepository, never()).saveAll(any());
    }
}
