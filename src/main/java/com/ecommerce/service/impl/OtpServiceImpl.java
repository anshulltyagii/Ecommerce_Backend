package com.ecommerce.service.impl;

import com.ecommerce.dto.OtpRequest;
import com.ecommerce.dto.OtpVerifyRequest;
import com.ecommerce.exception.BadRequestException;
import com.ecommerce.exception.ResourceNotFoundException;
import com.ecommerce.model.OtpCode;
import com.ecommerce.dto.EmailSendRequest;
import com.ecommerce.repository.OtpCodeRepository;
import com.ecommerce.service.EmailNotificationService;
import com.ecommerce.service.OtpService;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
public class OtpServiceImpl implements OtpService {

    private final OtpCodeRepository otpRepo;
    private final EmailNotificationService emailService;

    public OtpServiceImpl(OtpCodeRepository otpRepo, EmailNotificationService emailService) {
        this.otpRepo = otpRepo;
        this.emailService = emailService;
    }

    // ------------------------------------------------------------
    // GENERATE OTP
    // ------------------------------------------------------------
    @Override
    public String generateOtp(OtpRequest request) {

        if (request.getIdentifier() == null || request.getIdentifier().trim().isEmpty()) {
            throw new BadRequestException("Identifier cannot be empty");
        }

        String identifier = request.getIdentifier().trim();

        // Remove all old OTPs for this identifier
        otpRepo.deleteOldOtps(identifier);

        // Generate random 4-digit OTP
        String otp = String.valueOf(1000 + new Random().nextInt(9000));

        OtpCode otpCode = new OtpCode();
        otpCode.setIdentifier(identifier);
        otpCode.setOtpCode(otp);
        otpCode.setUsed(false);
        otpCode.setExpiresAt(LocalDateTime.now().plusMinutes(5));  // 5 minute validity

        Long otpId = otpRepo.save(otpCode);

        // ------------------------------------------------------------
        // SEND OTP TO EMAIL (via EmailNotification module)
        // ------------------------------------------------------------
        EmailSendRequest emailReq = new EmailSendRequest();
        emailReq.setSubject("Your OTP Code");
        emailReq.setMessage("Your OTP is: " + otp + "\n\nValid for 5 minutes.");

        // email sending (simulated as SENT)
        emailService.sendEmail(0L, emailReq); // userId = 0 because OTP can be for anyone

        return "OTP sent successfully to: " + identifier;
    }

    // ------------------------------------------------------------
    // VERIFY OTP
    // ------------------------------------------------------------
    @Override
    public String verifyOtp(OtpVerifyRequest request) {

        if (request.getIdentifier() == null || request.getIdentifier().isEmpty()) {
            throw new BadRequestException("Identifier cannot be empty");
        }
        if (request.getOtp() == null || request.getOtp().isEmpty()) {
            throw new BadRequestException("OTP cannot be empty");
        }

        String identifier = request.getIdentifier();
        String otpInput = request.getOtp();

        // Fetch latest valid OTP
        OtpCode otpRecord = otpRepo.findLatestValidOtp(identifier)
                .orElseThrow(() -> new ResourceNotFoundException("OTP not found or expired"));

        // Check if OTP is expired
        if (otpRecord.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("OTP expired. Please request a new one.");
        }

        // Check match
        if (!otpRecord.getOtpCode().equals(otpInput)) {
            throw new BadRequestException("Invalid OTP");
        }

        // Mark as used
        otpRepo.markOtpUsed(otpRecord.getId());

        return "OTP verified successfully!";
    }
}