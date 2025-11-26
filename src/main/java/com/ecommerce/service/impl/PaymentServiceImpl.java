package com.ecommerce.service.impl;

import com.ecommerce.dto.PaymentRequest;
import com.ecommerce.model.Order;
import com.ecommerce.model.Payment;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.PaymentRepository;
import com.ecommerce.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentServiceImpl implements PaymentService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Override
    @Transactional
    public Payment processPayment(PaymentRequest request) {
        // 1. Validate Order Exists
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 2. Validate Amount Matches
        if (order.getTotalAmount().compareTo(request.getAmount()) != 0) {
            throw new RuntimeException("Payment amount mismatch! Expected: " + order.getTotalAmount());
        }

        // 3. Save Payment Record
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setAmount(request.getAmount());
        payment.setMethod(request.getMethod());
        payment.setTxnReference(request.getTxnReference());
        payment.setStatus("SUCCESS"); // Simulating success

        Payment savedPayment = paymentRepository.save(payment);

        // 4. Update Order Status in DB
        orderRepository.updatePaymentStatus(order.getId(), "PAID", "CONFIRMED");

        return savedPayment;
    }
}