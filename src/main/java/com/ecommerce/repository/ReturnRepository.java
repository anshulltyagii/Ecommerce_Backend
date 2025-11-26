package com.ecommerce.repository;

import com.ecommerce.model.ReturnRequest;
import java.util.List;

public interface ReturnRepository {
    void save(ReturnRequest returnRequest);
    List<ReturnRequest> findByUserId(Long userId); // Join with orders to check user
    boolean existsByOrderId(Long orderId);
}