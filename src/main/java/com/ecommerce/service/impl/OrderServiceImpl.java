package com.ecommerce.service.impl;

import com.ecommerce.dto.CartResponse;
import com.ecommerce.dto.OrderItemResponse;
import com.ecommerce.dto.OrderRequest;
import com.ecommerce.dto.OrderResponse;
import com.ecommerce.enums.DiscountType;
import com.ecommerce.exception.BadRequestException;
import com.ecommerce.exception.ResourceNotFoundException;
import com.ecommerce.model.CartItem;
import com.ecommerce.model.Coupon;
import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.repository.CouponRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.service.CartService;
import com.ecommerce.service.InventoryService;
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
 * DEFINITIVE OrderServiceImpl
 * 
 * Inventory Flow:
 * 1. CHECKOUT      → reserveStock()
 * 2. CONFIRMED     → consumeReservedOnOrder()
 * 3. CANCEL        → releaseReserved() (if unpaid) OR addStock() (if paid)
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final CartService cartService;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final InventoryService inventoryService;

    public OrderServiceImpl(CartService cartService,
                           OrderRepository orderRepository,
                           CouponRepository couponRepository,
                           InventoryService inventoryService) {
        this.cartService = cartService;
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.inventoryService = inventoryService;
    }

    // ========================================================================
    // 1. PLACE ORDER (CHECKOUT)
    // ========================================================================
    @Override
    @Transactional
    public List<Order> placeOrder(Long userId, OrderRequest request) {
        log.info("Placing order for userId: {}", userId);

        // A. Fetch Cart
        CartResponse cartResponse = cartService.getUserCart(userId);
        Map<Long, List<CartItem>> itemsByShop = cartResponse.getItemsByShop();

        if (itemsByShop == null || itemsByShop.isEmpty()) {
            throw new BadRequestException("Cannot place order: Cart is empty");
        }

        // B. Validate Address
        if (request.getShippingAddress() == null || request.getShippingAddress().trim().isEmpty()) {
            throw new BadRequestException("Shipping address is required");
        }

        // C. Validate Coupon
        Coupon coupon = null;
        if (request.getCouponCode() != null && !request.getCouponCode().trim().isEmpty()) {
            coupon = validateCoupon(request.getCouponCode(), userId);
        }

        List<Order> createdOrders = new ArrayList<>();
        List<ReservedItem> allReservedItems = new ArrayList<>(); 
        boolean globalCouponUsed = false;

        try {
            // D. Process Each Shop
            for (Map.Entry<Long, List<CartItem>> entry : itemsByShop.entrySet()) {
                Long shopId = entry.getKey();
                List<CartItem> shopItems = entry.getValue();

                // *** RESERVE INVENTORY ***
                for (CartItem item : shopItems) {
                    log.debug("Reserving {} units of product {} for shop {}", item.getQuantity(), item.getProductId(), shopId);
                    inventoryService.reserveStock(item.getProductId(), item.getQuantity());
                    allReservedItems.add(new ReservedItem(item.getProductId(), item.getQuantity()));
                }

                // Calculate Totals & Coupons
                BigDecimal shopTotal = calculateShopTotal(shopItems);
                BigDecimal finalAmount = shopTotal;
                boolean couponAppliedToThisOrder = false;

                if (coupon != null) {
                    CouponResult couponResult = applyCouponToShop(coupon, shopId, shopTotal, globalCouponUsed);
                    if (couponResult.applied) {
                        finalAmount = couponResult.discountedAmount;
                        couponAppliedToThisOrder = true;
                        if (coupon.getShopId() == null) {
                            globalCouponUsed = true;
                        }
                    }
                }

                // Create & Save Order
                Order order = createOrder(userId, shopId, finalAmount, request.getShippingAddress());
                Order savedOrder = orderRepository.save(order);

                // Create & Save Order Items
                List<OrderItem> orderItems = createOrderItems(savedOrder.getId(), shopItems);
                orderRepository.saveOrderItems(orderItems);

                // Record Coupon Usage
                if (couponAppliedToThisOrder && coupon != null) {
                    couponRepository.recordUsage(userId, coupon.getId(), savedOrder.getId());
                }

                createdOrders.add(savedOrder);
            }

            // E. Clear Cart
            cartService.clearCart(userId);
            return createdOrders;

        } catch (Exception e) {
            // ROLLBACK RESERVATIONS ON FAILURE
            log.error("Order placement failed, rolling back reservations", e);
            for (ReservedItem reserved : allReservedItems) {
                try {
                    inventoryService.releaseReserved(reserved.productId, reserved.quantity);
                } catch (Exception rollbackEx) {
                    log.error("Failed to release reservation for product {}", reserved.productId);
                }
            }
            throw e; 
        }
    }

    // ========================================================================
    // 2. GET HISTORY
    // ========================================================================
    @Override
    public List<OrderResponse> getUserOrders(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        List<OrderResponse> responseList = new ArrayList<>();
        for (Order order : orders) {
            responseList.add(mapToResponse(order, null));
        }
        return responseList;
    }

    // ========================================================================
    // 3. GET DETAILS
    // ========================================================================
    @Override
    public OrderResponse getOrderDetails(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + orderId));
        List<OrderItemResponse> items = orderRepository.findItemsByOrderId(orderId);
        return mapToResponse(order, items);
    }

    // ========================================================================
    // 4. CANCEL ORDER
    // ========================================================================
    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        log.info("Cancelling order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        String status = order.getStatus() != null ? order.getStatus().toUpperCase() : "PLACED";
        String paymentStatus = order.getPaymentStatus() != null ? order.getPaymentStatus().toUpperCase() : "PENDING";

        if ("SHIPPED".equals(status) || "DELIVERED".equals(status) || "CANCELLED".equals(status) || "RETURNED".equals(status)) {
            throw new BadRequestException("Cannot cancel order. Current status: " + status);
        }

        List<OrderItemResponse> items = orderRepository.findItemsByOrderId(orderId);

        // LOGIC: If Paid -> Refund Stock. If Reserved -> Release.
        if ("CONFIRMED".equals(status) || "PAID".equals(paymentStatus)) {
            for (OrderItemResponse item : items) {
                inventoryService.addStock(item.getProductId(), item.getQuantity());
            }
        } else {
            for (OrderItemResponse item : items) {
                inventoryService.releaseReserved(item.getProductId(), item.getQuantity());
            }
        }

        orderRepository.updateOrderStatus(orderId, "CANCELLED");
    }

    // ========================================================================
    // 5. ADMIN: GET ALL
    // ========================================================================
    @Override
    public List<OrderResponse> getAllOrders() {
        List<Order> orders = orderRepository.findAll();
        List<OrderResponse> responseList = new ArrayList<>();
        for (Order order : orders) {
            responseList.add(mapToResponse(order, null));
        }
        return responseList;
    }

    // ========================================================================
    // 6. ADMIN: UPDATE STATUS
    // ========================================================================
    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, String newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        String currentStatus = order.getStatus() != null ? order.getStatus().toUpperCase() : "PLACED";
        String targetStatus = newStatus.toUpperCase();

        validateStatusTransition(currentStatus, targetStatus);

        // CONSUME STOCK ON CONFIRMATION
        if ("CONFIRMED".equals(targetStatus) && !"CONFIRMED".equals(currentStatus)) {
            List<OrderItemResponse> items = orderRepository.findItemsByOrderId(orderId);
            for (OrderItemResponse item : items) {
                inventoryService.consumeReservedOnOrder(item.getProductId(), item.getQuantity());
            }
        }

        // RESTORE STOCK ON RETURN
        if ("RETURNED".equals(targetStatus)) {
            List<OrderItemResponse> items = orderRepository.findItemsByOrderId(orderId);
            for (OrderItemResponse item : items) {
                inventoryService.addStock(item.getProductId(), item.getQuantity());
            }
        }

        orderRepository.updateOrderStatus(orderId, targetStatus);
        order.setStatus(targetStatus);
        return mapToResponse(order, null);
    }

    // ========================================================================
    // PRIVATE HELPER METHODS
    // ========================================================================

    private Coupon validateCoupon(String couponCode, Long userId) {
        Coupon coupon = couponRepository.findByCode(couponCode)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid Coupon Code"));

        // FIX: use isActive() instead of getIsActive()
        if (!coupon.isActive()) {
            throw new BadRequestException("Coupon is no longer active");
        }

        LocalDate today = LocalDate.now();
        if (coupon.getValidFrom() != null && today.isBefore(coupon.getValidFrom())) {
            throw new BadRequestException("Coupon is not yet valid");
        }
        if (coupon.getValidTo() != null && today.isAfter(coupon.getValidTo())) {
            throw new BadRequestException("Coupon has expired");
        }
        if (couponRepository.isUsedByUser(userId, coupon.getId())) {
            throw new BadRequestException("You have already used this coupon");
        }
        return coupon;
    }

    private BigDecimal calculateShopTotal(List<CartItem> items) {
        return items.stream()
                .map(item -> item.getPriceAtAdd().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CouponResult applyCouponToShop(Coupon coupon, Long shopId, BigDecimal shopTotal, boolean globalCouponAlreadyUsed) {
        CouponResult result = new CouponResult();
        result.applied = false;
        result.discountedAmount = shopTotal;

        if (shopTotal.compareTo(coupon.getMinOrderAmount()) < 0) {
            return result;
        }

        if (coupon.getShopId() != null) {
            if (coupon.getShopId().equals(shopId)) {
                result.discountedAmount = applyDiscount(shopTotal, coupon);
                result.applied = true;
            }
        }
        else if (!globalCouponAlreadyUsed) {
            result.discountedAmount = applyDiscount(shopTotal, coupon);
            result.applied = true;
        }
        return result;
    }

    private BigDecimal applyDiscount(BigDecimal total, Coupon coupon) {
        BigDecimal result;
        if (DiscountType.FLAT == coupon.getDiscountType()) {
            result = total.subtract(coupon.getDiscountValue());
        } else {
            BigDecimal discountAmount = total
                    .multiply(coupon.getDiscountValue())
                    .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
            result = total.subtract(discountAmount);
        }
        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
    }

    private Order createOrder(Long userId, Long shopId, BigDecimal amount, String shippingAddress) {
        Order order = new Order();
        order.setUserId(userId);
        order.setShopId(shopId);
        order.setTotalAmount(amount);
        order.setShippingAddress(shippingAddress);
        order.setOrderNumber("ORD-" + System.currentTimeMillis() + "-" + shopId);
        order.setStatus("PLACED");
        order.setPaymentStatus("PENDING");
        return order;
    }

    private List<OrderItem> createOrderItems(Long orderId, List<CartItem> cartItems) {
        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem ci : cartItems) {
            BigDecimal lineTotal = ci.getPriceAtAdd().multiply(new BigDecimal(ci.getQuantity()));
            orderItems.add(new OrderItem(orderId, ci.getProductId(), ci.getQuantity(), ci.getPriceAtAdd(), lineTotal));
        }
        return orderItems;
    }

    private void validateStatusTransition(String current, String target) {
        // Basic validation logic
        if ("CANCELLED".equals(current) || "RETURNED".equals(current)) {
            throw new BadRequestException("Cannot change status of " + current + " order");
        }
        // You can add more strict transitions here if needed
    }

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

    private static class ReservedItem {
        Long productId;
        int quantity;
        ReservedItem(Long productId, int quantity) { this.productId = productId; this.quantity = quantity; }
    }

    private static class CouponResult {
        boolean applied;
        BigDecimal discountedAmount;
    }
}