package com.ecommerce.service.impl;

import com.ecommerce.dto.ReturnRequestDTO;
import com.ecommerce.model.Order;
import com.ecommerce.model.ReturnRequest;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.ReturnRepository;
import com.ecommerce.service.ReturnService;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReturnServiceImpl implements ReturnService {

    @Autowired
    private ReturnRepository returnRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Override
    public void requestReturn(Long userId, ReturnRequestDTO requestDto) {
        // 1. Validate Order exists
        Order order = orderRepository.findById(requestDto.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 2. Validate Order belongs to User
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("Access Denied: Not your order");
        }

        // 3. Validate Status (Must be DELIVERED)
        if (!"DELIVERED".equalsIgnoreCase(order.getStatus())) {
            throw new RuntimeException("Cannot return items. Order status is: " + order.getStatus());
        }

        // 4. Check if return already requested
        if (returnRepository.existsByOrderId(order.getId())) {
            throw new RuntimeException("Return already requested for this order");
        }

        // 5. Create Request
        ReturnRequest rr = new ReturnRequest();
        rr.setOrderId(order.getId());
        rr.setReason(requestDto.getReason());
        
        returnRepository.save(rr);
    }
    
 // ... existing ...

    @Override
    public List<ReturnRequest> getUserReturnRequests(Long userId) {
        // We already have this method in the Repository!
        return returnRepository.findByUserId(userId);
    }
}