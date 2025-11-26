package com.ecommerce.service.impl;

import com.ecommerce.dto.ApiResponse;
import com.ecommerce.dto.CouponRequest;
import com.ecommerce.dto.CouponResponse;
import com.ecommerce.enums.DiscountType;
import com.ecommerce.exception.BadRequestException;
import com.ecommerce.exception.ResourceNotFoundException;
import com.ecommerce.model.Coupon;
import com.ecommerce.repository.CouponRepository;
import com.ecommerce.service.CouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CouponServiceImpl implements CouponService {

    @Autowired
    private CouponRepository couponRepository;

    // ======================================================================
    // PART 1: SNIGDHA'S LOGIC (Creation & Management)
    // ======================================================================

    @Override
    public CouponResponse createCoupon(CouponRequest req) {
        if(couponRepository.findByCode(req.getCode()).isPresent()) {
            throw new BadRequestException("Coupon code already exists!");
        }
        
        Coupon c = new Coupon();
        c.setCode(req.getCode().toUpperCase());
        c.setDiscountType(DiscountType.valueOf(req.getDiscountType()));
        c.setDiscountValue(req.getDiscountValue()); // BigDecimal
        c.setMinOrderAmount(req.getMinOrderAmount()); // BigDecimal
        
        // Parse String Dates to LocalDate
        c.setValidFrom(LocalDate.parse(req.getValidFrom())); 
        c.setValidTo(LocalDate.parse(req.getValidTo()));
        
        c.setShopId(req.getShopId());
        c.setActive(true);
        
        Long id = couponRepository.save(c);
        c.setId(id);
        
        return mapToResponse(c);
    }

    @Override
    public CouponResponse updateCoupon(Long id, CouponRequest req) {
        Coupon existing = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));

        existing.setCode(req.getCode().toUpperCase());
        existing.setDiscountType(DiscountType.valueOf(req.getDiscountType()));
        existing.setDiscountValue(req.getDiscountValue());
        existing.setMinOrderAmount(req.getMinOrderAmount());
        existing.setValidFrom(LocalDate.parse(req.getValidFrom()));
        existing.setValidTo(LocalDate.parse(req.getValidTo()));
        existing.setShopId(req.getShopId());

        couponRepository.update(existing);
        return mapToResponse(existing);
    }

    @Override
    public boolean deleteCoupon(Long id) {
        return couponRepository.softDelete(id);
    }

    @Override
    public CouponResponse getCouponById(Long id) {
        Coupon c = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
        return mapToResponse(c);
    }

    @Override
    public List<CouponResponse> getCouponsByShop(Long shopId) {
        return couponRepository.findByShopId(shopId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<CouponResponse> getAllCoupons() {
        return couponRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ======================================================================
    // PART 2: SAMADRITA'S LOGIC (Validation for Checkout)
    // ======================================================================

    @Override
    public ApiResponse<Coupon> validateCoupon(String code, Long userId, Double cartTotal) {
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid Coupon Code"));

        if (coupon.getValidTo() != null && coupon.getValidTo().isBefore(LocalDate.now())) {
            throw new BadRequestException("Coupon has expired");
        }
        if (couponRepository.isUsedByUser(userId, coupon.getId())) {
            throw new BadRequestException("You have already used this coupon");
        }
        
        BigDecimal total = new BigDecimal(cartTotal);
        if (total.compareTo(coupon.getMinOrderAmount()) < 0) {
            throw new BadRequestException("Minimum order amount of " + coupon.getMinOrderAmount() + " not met.");
        }

        String msg = "Coupon Valid! Discount: " + coupon.getDiscountValue();
        return new ApiResponse<>(true, msg, coupon);
    }

    // ======================================================================
    // HELPER: Internal Mapper (Handles BigDecimal -> Double conversion)
    // ======================================================================
    private CouponResponse mapToResponse(Coupon c) {
        CouponResponse r = new CouponResponse();
        r.setId(c.getId());
        r.setCode(c.getCode());
        r.setDiscountType(c.getDiscountType().name());
        
        // Handle conversion from Entity (BigDecimal) to DTO (Double)
        r.setDiscountValue(c.getDiscountValue() != null ? c.getDiscountValue().doubleValue() : 0.0);
        r.setMinOrderAmount(c.getMinOrderAmount() != null ? c.getMinOrderAmount().doubleValue() : 0.0);
        
        r.setValidFrom(c.getValidFrom());
        r.setValidTo(c.getValidTo());
        r.setActive(c.isActive());
        r.setShopId(c.getShopId());
        return r;
    }
}

