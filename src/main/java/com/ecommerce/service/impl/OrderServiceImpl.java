package com.ecommerce.service.impl;

import com.ecommerce.dto.CartResponse;
import com.ecommerce.dto.OrderItemResponse;
import com.ecommerce.dto.OrderRequest;
import com.ecommerce.dto.OrderResponse;
import com.ecommerce.enums.DiscountType;
import com.ecommerce.exception.BadRequestException;
import com.ecommerce.exception.ResourceNotFoundException;
import com.ecommerce.exception.UnauthorizedException;
import com.ecommerce.model.CartItem;
import com.ecommerce.model.Coupon;
import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.repository.CouponRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.service.CartService;
import com.ecommerce.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OrderServiceImpl - Production-Ready Implementation
 * 
 * Edge Cases Handled:
 * ═══════════════════════════════════════════════════════════════════════════
 * PLACE ORDER:
 *   1. Null user ID
 *   2. Invalid user ID (≤0)
 *   3. Null request object
 *   4. Null shipping address
 *   5. Empty shipping address
 *   6. Shipping address too short (<10 chars)
 *   7. Shipping address too long (>500 chars)
 *   8. Shipping address with only numbers (no letters)
 *   9. Shipping address with only whitespace
 *  10. Empty cart
 *  11. Invalid coupon code
 *  12. Expired coupon
 *  13. Coupon already used by user
 *  14. Coupon minimum order amount not met
 *  15. Coupon not yet valid (future start date)
 *  
 * GET USER ORDERS:
 *  16. Null user ID
 *  17. Invalid user ID (≤0)
 *  
 * GET ORDER DETAILS:
 *  18. Null order ID
 *  19. Invalid order ID (≤0)
 *  20. Order not found
 *  
 * CANCEL ORDER:
 *  21. Null order ID
 *  22. Invalid order ID (≤0)
 *  23. Order not found
 *  24. Order already shipped
 *  25. Order already delivered
 *  26. Order already cancelled
 *  27. Order already returned
 *  28. Unauthorized cancellation (different user)
 *  
 * UPDATE ORDER STATUS (Admin):
 *  29. Null order ID
 *  30. Invalid order ID (≤0)
 *  31. Null/empty status
 *  32. Invalid status value
 *  33. Order not found
 *  34. Cannot change DELIVERED order
 *  35. Cannot change CANCELLED order
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);
    
    // ═══════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════
    
    private static final int MIN_ADDRESS_LENGTH = 10;
    private static final int MAX_ADDRESS_LENGTH = 500;
    private static final List<String> VALID_ORDER_STATUSES = List.of(
        "PLACED", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED", "RETURNED"
    );

    // ═══════════════════════════════════════════════════════════════════════
    // DEPENDENCIES (Constructor Injection)
    // ═══════════════════════════════════════════════════════════════════════
    
    private final CartService cartService;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;

    public OrderServiceImpl(CartService cartService, 
                           OrderRepository orderRepository,
                           CouponRepository couponRepository) {
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. PLACE ORDER (CHECKOUT)
    // ════════════════════════════════════════════════════════════════════════
    
    @Override
    @Transactional
    public List<Order> placeOrder(Long userId, OrderRequest request) {
        log.info("Placing order for user: {}", userId);

        // ─────────────────────────────────────────────────────────────────────
        // VALIDATION
        // ─────────────────────────────────────────────────────────────────────
        
        // Edge Case 1: Null user ID
        if (userId == null) {
            log.error("User ID is null");
            throw new BadRequestException("User ID is required");
        }
        
        // Edge Case 2: Invalid user ID
        if (userId <= 0) {
            log.error("Invalid user ID: {}", userId);
            throw new BadRequestException("Invalid user ID: " + userId);
        }
        
        // Edge Case 3: Null request
        if (request == null) {
            log.error("Order request is null");
            throw new BadRequestException("Order request is required");
        }
        
        // Validate shipping address (Edge Cases 4-9)
        validateShippingAddress(request.getShippingAddress());

        // ─────────────────────────────────────────────────────────────────────
        // FETCH & VALIDATE CART
        // ─────────────────────────────────────────────────────────────────────
        
        CartResponse cartResponse = cartService.getUserCart(userId);
        Map<Long, List<CartItem>> itemsByShop = cartResponse.getItemsByShop();

        // Edge Case 10: Empty cart
        if (itemsByShop == null || itemsByShop.isEmpty()) {
            log.error("Cart is empty for user: {}", userId);
            throw new BadRequestException("Cannot place order: Cart is empty");
        }

        // ─────────────────────────────────────────────────────────────────────
        // VALIDATE COUPON (if provided)
        // ─────────────────────────────────────────────────────────────────────
        
        Coupon coupon = null;
        if (request.getCouponCode() != null && !request.getCouponCode().trim().isEmpty()) {
            coupon = validateAndGetCoupon(request.getCouponCode(), userId);
        }

        // ─────────────────────────────────────────────────────────────────────
        // CREATE ORDERS (Split by Shop)
        // ─────────────────────────────────────────────────────────────────────
        
        List<Order> createdOrders = new ArrayList<>();
        boolean globalCouponUsed = false;

        for (Map.Entry<Long, List<CartItem>> entry : itemsByShop.entrySet()) {
            Long shopId = entry.getKey();
            List<CartItem> shopItems = entry.getValue();

            // 1. Calculate shop total
            BigDecimal shopTotal = calculateShopTotal(shopItems);
            log.debug("Shop {} total: {}", shopId, shopTotal);

            // 2. Apply coupon logic
            BigDecimal finalAmount = shopTotal;
            boolean couponAppliedToThisOrder = false;

            if (coupon != null) {
                CouponApplicationResult result = applyCouponIfApplicable(
                    coupon, shopId, shopTotal, globalCouponUsed
                );
                finalAmount = result.finalAmount;
                couponAppliedToThisOrder = result.applied;
                if (result.applied && coupon.getShopId() == null) {
                    globalCouponUsed = true;
                }
            }

            // 3. Create Order
            Order order = new Order();
            order.setUserId(userId);
            order.setShopId(shopId);
            order.setShippingAddress(request.getShippingAddress().trim());
            order.setTotalAmount(finalAmount.setScale(2, RoundingMode.HALF_UP));
            order.setOrderNumber(generateOrderNumber(shopId));
            order.setStatus("PLACED");
            order.setPaymentStatus("PENDING");

            // 4. Save Order
            Order savedOrder = orderRepository.save(order);
            log.info("Order created: {} for shop: {}", savedOrder.getOrderNumber(), shopId);

            // 5. Create Order Items
            List<OrderItem> orderItems = createOrderItems(savedOrder.getId(), shopItems);
            orderRepository.saveOrderItems(orderItems);
            log.debug("Saved {} order items for order: {}", orderItems.size(), savedOrder.getId());

            // 6. Record Coupon Usage
            if (couponAppliedToThisOrder && coupon != null) {
                couponRepository.recordUsage(userId, coupon.getId(), savedOrder.getId());
                log.info("Coupon {} applied to order {}", coupon.getCode(), savedOrder.getId());
            }

            createdOrders.add(savedOrder);
        }

        // ─────────────────────────────────────────────────────────────────────
        // CLEAR CART
        // ─────────────────────────────────────────────────────────────────────
        
        cartService.clearCart(userId);
        log.info("Cart cleared for user: {}. Created {} orders.", userId, createdOrders.size());

        return createdOrders;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. GET USER ORDERS
    // ════════════════════════════════════════════════════════════════════════
    
    @Override
    public List<OrderResponse> getUserOrders(Long userId) {
        log.info("Fetching orders for user: {}", userId);
        
        // Edge Case 16: Null user ID
        if (userId == null) {
            log.error("User ID is null");
            throw new BadRequestException("User ID is required");
        }
        
        // Edge Case 17: Invalid user ID
        if (userId <= 0) {
            log.error("Invalid user ID: {}", userId);
            throw new BadRequestException("Invalid user ID: " + userId);
        }
        
        List<Order> orders = orderRepository.findByUserId(userId);
        List<OrderResponse> responseList = new ArrayList<>();
        
        for (Order order : orders) {
            responseList.add(mapToResponse(order, null));
        }
        
        log.info("Found {} orders for user: {}", responseList.size(), userId);
        return responseList;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. GET ORDER DETAILS
    // ════════════════════════════════════════════════════════════════════════
    
    @Override
    public OrderResponse getOrderDetails(Long orderId) {
        log.info("Fetching order details for order: {}", orderId);
        
        // Edge Case 18: Null order ID
        if (orderId == null) {
            log.error("Order ID is null");
            throw new BadRequestException("Order ID is required");
        }
        
        // Edge Case 19: Invalid order ID
        if (orderId <= 0) {
            log.error("Invalid order ID: {}", orderId);
            throw new BadRequestException("Invalid order ID: " + orderId);
        }
        
        // Edge Case 20: Order not found
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found: {}", orderId);
                    return new ResourceNotFoundException("Order not found with ID: " + orderId);
                });
        
        List<OrderItemResponse> items = orderRepository.findItemsByOrderId(orderId);
        log.debug("Found {} items for order: {}", items.size(), orderId);
        
        return mapToResponse(order, items);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. CANCEL ORDER (User)
    // ════════════════════════════════════════════════════════════════════════
    
    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        log.info("Cancelling order: {}", orderId);
        
        // Edge Case 21: Null order ID
        if (orderId == null) {
            log.error("Order ID is null");
            throw new BadRequestException("Order ID is required");
        }
        
        // Edge Case 22: Invalid order ID
        if (orderId <= 0) {
            log.error("Invalid order ID: {}", orderId);
            throw new BadRequestException("Invalid order ID: " + orderId);
        }
        
        // Edge Case 23: Order not found
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found: {}", orderId);
                    return new ResourceNotFoundException("Order not found with ID: " + orderId);
                });

        String status = order.getStatus().toUpperCase();
        
        // Edge Case 24: Already shipped
        if ("SHIPPED".equals(status)) {
            log.error("Cannot cancel shipped order: {}", orderId);
            throw new BadRequestException(
                "Cannot cancel order: It has already been shipped. Please wait for delivery and request a return instead."
            );
        }
        
        // Edge Case 25: Already delivered
        if ("DELIVERED".equals(status)) {
            log.error("Cannot cancel delivered order: {}", orderId);
            throw new BadRequestException(
                "Cannot cancel order: It has already been delivered. Please request a return instead."
            );
        }
        
        // Edge Case 26: Already cancelled
        if ("CANCELLED".equals(status)) {
            log.error("Order already cancelled: {}", orderId);
            throw new BadRequestException("Order has already been cancelled");
        }
        
        // Edge Case 27: Already returned
        if ("RETURNED".equals(status)) {
            log.error("Cannot cancel returned order: {}", orderId);
            throw new BadRequestException("Cannot cancel order: It has already been returned");
        }

        // Perform cancellation
        orderRepository.updateOrderStatus(orderId, "CANCELLED");
        log.info("Order {} cancelled successfully", orderId);
        
        // TODO: Restore inventory when InventoryService is integrated
        // TODO: Refund payment if already paid
    }
    
    // ════════════════════════════════════════════════════════════════════════
    // 5. CANCEL ORDER WITH USER VERIFICATION
    // ════════════════════════════════════════════════════════════════════════
    
    /**
     * Cancel order with user authorization check
     * Use this method when you need to verify the user owns the order
     */
    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        log.info("User {} attempting to cancel order: {}", userId, orderId);
        
        // Validate user ID
        if (userId == null || userId <= 0) {
            log.error("Invalid user ID: {}", userId);
            throw new BadRequestException("Invalid user ID");
        }
        
        // Validate and get order
        if (orderId == null || orderId <= 0) {
            log.error("Invalid order ID: {}", orderId);
            throw new BadRequestException("Invalid order ID");
        }
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found: {}", orderId);
                    return new ResourceNotFoundException("Order not found with ID: " + orderId);
                });
        
        // Edge Case 28: Unauthorized cancellation
        if (!order.getUserId().equals(userId)) {
            log.error("User {} not authorized to cancel order {} (belongs to user {})", 
                     userId, orderId, order.getUserId());
            throw new UnauthorizedException("You are not authorized to cancel this order");
        }
        
        // Delegate to main cancel method
        cancelOrder(orderId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. ADMIN: GET ALL ORDERS
    // ════════════════════════════════════════════════════════════════════════
    
    @Override
    public List<OrderResponse> getAllOrders() {
        log.info("Admin fetching all orders");
        
        List<Order> orders = orderRepository.findAll();
        List<OrderResponse> responseList = new ArrayList<>();
        
        for (Order order : orders) {
            responseList.add(mapToResponse(order, null));
        }
        
        log.info("Found {} total orders", responseList.size());
        return responseList;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. ADMIN: UPDATE ORDER STATUS
    // ════════════════════════════════════════════════════════════════════════
    
    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, String newStatus) {
        log.info("Updating order {} status to: {}", orderId, newStatus);
        
        // Edge Case 29: Null order ID
        if (orderId == null) {
            log.error("Order ID is null");
            throw new BadRequestException("Order ID is required");
        }
        
        // Edge Case 30: Invalid order ID
        if (orderId <= 0) {
            log.error("Invalid order ID: {}", orderId);
            throw new BadRequestException("Invalid order ID: " + orderId);
        }
        
        // Edge Case 31: Null/empty status
        if (newStatus == null || newStatus.trim().isEmpty()) {
            log.error("Status is null or empty");
            throw new BadRequestException("Order status is required");
        }
        
        String normalizedStatus = newStatus.trim().toUpperCase();
        
        // Edge Case 32: Invalid status value
        if (!VALID_ORDER_STATUSES.contains(normalizedStatus)) {
            log.error("Invalid order status: {}", newStatus);
            throw new BadRequestException(
                "Invalid order status: " + newStatus + 
                ". Valid statuses are: " + String.join(", ", VALID_ORDER_STATUSES)
            );
        }
        
        // Edge Case 33: Order not found
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found: {}", orderId);
                    return new ResourceNotFoundException("Order not found with ID: " + orderId);
                });

        String currentStatus = order.getStatus().toUpperCase();
        
        // Edge Case 34: Cannot change DELIVERED order
        if ("DELIVERED".equals(currentStatus)) {
            log.error("Cannot update delivered order: {}", orderId);
            throw new BadRequestException(
                "Cannot change status of a delivered order. Use return process instead."
            );
        }
        
        // Edge Case 35: Cannot change CANCELLED order
        if ("CANCELLED".equals(currentStatus)) {
            log.error("Cannot update cancelled order: {}", orderId);
            throw new BadRequestException(
                "Cannot change status of a cancelled order. Please create a new order."
            );
        }

        // Update status
        orderRepository.updateOrderStatus(orderId, normalizedStatus);
        order.setStatus(normalizedStatus);
        
        log.info("Order {} status updated from {} to {}", orderId, currentStatus, normalizedStatus);
        return mapToResponse(order, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ════════════════════════════════════════════════════════════════════════
    
    /**
     * Validate shipping address with comprehensive checks
     */
    private void validateShippingAddress(String address) {
        // Edge Case 4: Null address
        if (address == null) {
            log.error("Shipping address is null");
            throw new BadRequestException("Shipping address is required");
        }
        
        String trimmedAddress = address.trim();
        
        // Edge Case 5 & 9: Empty or whitespace-only address
        if (trimmedAddress.isEmpty()) {
            log.error("Shipping address is empty");
            throw new BadRequestException("Shipping address is required");
        }
        
        // Edge Case 6: Address too short
        if (trimmedAddress.length() < MIN_ADDRESS_LENGTH) {
            log.error("Shipping address too short: {} chars", trimmedAddress.length());
            throw new BadRequestException(
                "Shipping address is too short. Please provide a complete address (minimum " + 
                MIN_ADDRESS_LENGTH + " characters)"
            );
        }
        
        // Edge Case 7: Address too long
        if (trimmedAddress.length() > MAX_ADDRESS_LENGTH) {
            log.error("Shipping address too long: {} chars", trimmedAddress.length());
            throw new BadRequestException(
                "Shipping address is too long. Maximum " + MAX_ADDRESS_LENGTH + " characters allowed"
            );
        }
        
        // Edge Case 8: Address must contain letters (not just numbers)
        if (!trimmedAddress.matches(".*[a-zA-Z].*")) {
            log.error("Shipping address contains no letters: {}", trimmedAddress);
            throw new BadRequestException(
                "Invalid shipping address. Address must contain letters, not just numbers"
            );
        }
        
        log.debug("Shipping address validated: {}", trimmedAddress);
    }
    
    /**
     * Validate and retrieve coupon
     */
    private Coupon validateAndGetCoupon(String couponCode, Long userId) {
        String normalizedCode = couponCode.trim().toUpperCase();
        log.debug("Validating coupon: {}", normalizedCode);
        
        // Edge Case 11: Invalid coupon code
        Coupon coupon = couponRepository.findByCode(normalizedCode)
                .orElseThrow(() -> {
                    log.error("Coupon not found: {}", normalizedCode);
                    return new ResourceNotFoundException("Invalid Coupon Code: " + normalizedCode);
                });
        
        // Edge Case 15: Coupon not yet valid
        if (coupon.getValidFrom() != null && coupon.getValidFrom().isAfter(LocalDate.now())) {
            log.error("Coupon not yet valid: {}", normalizedCode);
            throw new BadRequestException(
                "Coupon is not yet valid. It becomes active on: " + coupon.getValidFrom()
            );
        }
        
        // Edge Case 12: Expired coupon
        if (coupon.getValidTo() != null && coupon.getValidTo().isBefore(LocalDate.now())) {
            log.error("Coupon expired: {}", normalizedCode);
            throw new BadRequestException(
                "Coupon has expired on: " + coupon.getValidTo()
            );
        }
        
        // Edge Case 13: Already used by user
        if (couponRepository.isUsedByUser(userId, coupon.getId())) {
            log.error("Coupon already used by user {}: {}", userId, normalizedCode);
            throw new BadRequestException("You have already used this coupon");
        }
        
        log.debug("Coupon validated successfully: {}", normalizedCode);
        return coupon;
    }
    
    /**
     * Calculate total for shop items
     */
    private BigDecimal calculateShopTotal(List<CartItem> shopItems) {
        return shopItems.stream()
                .map(item -> item.getPriceAtAdd().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Apply coupon if applicable
     */
    private CouponApplicationResult applyCouponIfApplicable(
            Coupon coupon, Long shopId, BigDecimal shopTotal, boolean globalCouponUsed) {
        
        CouponApplicationResult result = new CouponApplicationResult();
        result.finalAmount = shopTotal;
        result.applied = false;
        
        // Case A: Shop-Specific Coupon
        if (coupon.getShopId() != null && coupon.getShopId().equals(shopId)) {
            if (shopTotal.compareTo(coupon.getMinOrderAmount()) >= 0) {
                result.finalAmount = applyDiscount(shopTotal, coupon);
                result.applied = true;
                log.debug("Shop-specific coupon applied. Original: {}, Final: {}", 
                         shopTotal, result.finalAmount);
            } else {
                log.debug("Shop total {} below minimum {} for coupon", 
                         shopTotal, coupon.getMinOrderAmount());
            }
        }
        // Case B: Global Coupon (Apply once)
        else if (coupon.getShopId() == null && !globalCouponUsed) {
            if (shopTotal.compareTo(coupon.getMinOrderAmount()) >= 0) {
                result.finalAmount = applyDiscount(shopTotal, coupon);
                result.applied = true;
                log.debug("Global coupon applied. Original: {}, Final: {}", 
                         shopTotal, result.finalAmount);
            } else {
                log.debug("Shop total {} below minimum {} for global coupon", 
                         shopTotal, coupon.getMinOrderAmount());
            }
        }
        
        return result;
    }
    
    /**
     * Helper class for coupon application result
     */
    private static class CouponApplicationResult {
        BigDecimal finalAmount;
        boolean applied;
    }
    
    /**
     * Apply discount based on coupon type
     */
    private BigDecimal applyDiscount(BigDecimal total, Coupon coupon) {
        BigDecimal result;
        
        if (DiscountType.FLAT == coupon.getDiscountType()) {
            result = total.subtract(coupon.getDiscountValue());
        } else {
            // Percentage discount
            BigDecimal discountAmount = total
                    .multiply(coupon.getDiscountValue())
                    .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
            result = total.subtract(discountAmount);
        }
        
        // Ensure total doesn't go negative
        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
    }
    
    /**
     * Generate unique order number
     */
    private String generateOrderNumber(Long shopId) {
        return "ORD-" + System.currentTimeMillis() + "-" + shopId;
    }
    
    /**
     * Create order items from cart items
     */
    private List<OrderItem> createOrderItems(Long orderId, List<CartItem> shopItems) {
        List<OrderItem> orderItems = new ArrayList<>();
        
        for (CartItem ci : shopItems) {
            BigDecimal lineTotal = ci.getPriceAtAdd()
                    .multiply(new BigDecimal(ci.getQuantity()));
            
            orderItems.add(new OrderItem(
                    orderId,
                    ci.getProductId(),
                    ci.getQuantity(),
                    ci.getPriceAtAdd(),
                    lineTotal
            ));
        }
        
        return orderItems;
    }
    
    /**
     * Map Order entity to OrderResponse DTO
     */
    private OrderResponse mapToResponse(Order order, List<OrderItemResponse> items) {
        OrderResponse dto = new OrderResponse();
        dto.setOrderId(order.getId());
        dto.setOrderNumber(order.getOrderNumber());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setStatus(order.getStatus());
        dto.setPaymentStatus(order.getPaymentStatus());
        dto.setShippingAddress(order.getShippingAddress());
        dto.setCreatedAt(order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
        dto.setItems(items);
        return dto;
    }
}