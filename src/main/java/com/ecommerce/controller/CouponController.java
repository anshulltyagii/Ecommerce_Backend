package com.ecommerce.controller;

import com.ecommerce.dto.ApiResponse;
import com.ecommerce.dto.CouponRequest;
import com.ecommerce.dto.CouponResponse; // Import DTO
import com.ecommerce.model.Coupon;
import com.ecommerce.service.CouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    @Autowired
    private CouponService couponService;

    // --- SNIGDHA (ADMIN/SHOPKEEPER) ---
    
    @PostMapping("/create")
    // FIX 1: Change generic type from <Coupon> to <CouponResponse>
    public ResponseEntity<ApiResponse<CouponResponse>> createCoupon(@RequestBody CouponRequest request) {
        CouponResponse c = couponService.createCoupon(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Coupon created successfully", c));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteCoupon(@PathVariable Long id) {
        couponService.deleteCoupon(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Coupon deactivated"));
    }
    
    @GetMapping("/all")
    // FIX 2: Change generic type from <Coupon> to <CouponResponse>
    public ResponseEntity<List<CouponResponse>> listCoupons() {
        return ResponseEntity.ok(couponService.getAllCoupons());
    }

    // --- SAMADRITA (USER) ---

    // Keep this as Coupon because validateCoupon still returns Entity in the Interface
    // (Or you can update validateCoupon to return DTO later, but this works for now)
    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<Coupon>> validate(
            @RequestParam String code, 
            @RequestParam Long userId,
            @RequestParam Double cartTotal) {
        
        return ResponseEntity.ok(couponService.validateCoupon(code, userId, cartTotal));
    }
}