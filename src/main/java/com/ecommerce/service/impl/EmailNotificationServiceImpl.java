package com.ecommerce.service.impl;

import com.ecommerce.dto.EmailNotificationResponse;
import com.ecommerce.dto.EmailSendRequest;
import com.ecommerce.exception.BadRequestException;
import com.ecommerce.exception.ResourceNotFoundException;
import com.ecommerce.model.EmailNotification;
import com.ecommerce.repository.EmailNotificationRepository;
import com.ecommerce.service.EmailNotificationService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmailNotificationServiceImpl implements EmailNotificationService {

    private final EmailNotificationRepository emailNotificationRepo;

    public EmailNotificationServiceImpl(EmailNotificationRepository emailNotificationRepo) {
        this.emailNotificationRepo = emailNotificationRepo;
    }

    // ------------------------------------------------------------
    // CREATE EMAIL ENTRY â†’ status = PENDING â†’ try to send â†’ update status
    // ------------------------------------------------------------
    @Override
    public EmailNotificationResponse sendEmail(Long userId, EmailSendRequest request) {

        // Validation
        if (request.getSubject() == null || request.getSubject().trim().isEmpty()) {
            throw new BadRequestException("Subject cannot be empty");
        }

        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new BadRequestException("Message cannot be empty");
        }

        // Create Notification Object
        EmailNotification notification = new EmailNotification();
        notification.setUserId(userId);
        notification.setSubject(request.getSubject());
        notification.setMessage(request.getMessage());
        notification.setStatus("PENDING");   // default

        // Save to database
        Long id = emailNotificationRepo.save(notification);
        notification.setId(id);

        // --------------------------------------------------------
        // SIMULATED EMAIL SENDING (REAL SMTP WILL COME LATER)
        // --------------------------------------------------------
        boolean emailSentSuccessfully = true; // change later if needed

        if (emailSentSuccessfully) {
            emailNotificationRepo.updateStatus(id, "SENT");
            notification.setStatus("SENT");
        } else {
            emailNotificationRepo.updateStatus(id, "FAILED");
            notification.setStatus("FAILED");
        }

        return mapToResponse(notification);
    }

    // ------------------------------------------------------------
    // MANUAL STATUS UPDATE
    // ------------------------------------------------------------
    @Override
    public boolean updateStatus(Long notificationId, String status) {

        if (!status.equals("PENDING") && !status.equals("SENT") && !status.equals("FAILED")) {
            throw new BadRequestException("Invalid status: " + status);
        }

        return emailNotificationRepo.updateStatus(notificationId, status);
    }

    // ------------------------------------------------------------
    // GET A SINGLE EMAIL NOTIFICATION ENTRY
    // ------------------------------------------------------------
    @Override
    public EmailNotificationResponse getNotificationById(Long id) {

        EmailNotification notification = emailNotificationRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email notification not found"));

        return mapToResponse(notification);
    }

    // ------------------------------------------------------------
    // GET ALL EMAILS OF A USER
    // ------------------------------------------------------------
    @Override
    public List<EmailNotificationResponse> getAllByUser(Long userId) {

        return emailNotificationRepo.findAllByUser(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------
    // ADMIN FEATURE â†’ FETCH ALL EMAIL NOTIFICATIONS
    // ------------------------------------------------------------
    @Override
    public List<EmailNotificationResponse> getAllNotifications() {

        return emailNotificationRepo.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------
    // PRIVATE MAPPER: ENTITY â†’ DTO
    // ------------------------------------------------------------
    private EmailNotificationResponse mapToResponse(EmailNotification e) {

        EmailNotificationResponse dto = new EmailNotificationResponse();

        dto.setId(e.getId());
        dto.setUserId(e.getUserId());
        dto.setSubject(e.getSubject());
        dto.setMessage(e.getMessage());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());

        return dto;
    }
}