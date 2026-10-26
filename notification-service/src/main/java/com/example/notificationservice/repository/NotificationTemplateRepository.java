package com.example.notificationservice.repository;

import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findFirstByEventTypeAndChannelAndLanguageAndIsActiveTrueOrderByVersionDesc(
            String eventType,
            NotificationChannel channel,
            String language
    );

    Optional<NotificationTemplate> findFirstByEventTypeAndChannelAndIsActiveTrueOrderByVersionDesc(
            String eventType,
            NotificationChannel channel
    );
}
