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
import com.ecommerce.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CouponRepository couponRepository;
    
    // InventoryService removed temporarily

    // ========================================================================
    // 1. PLACE ORDER (CHECKOUT)
    // ========================================================================
    @Override
    @Transactional
    public List<Order> placeOrder(Long userId, OrderRequest request) {

        // A. Fetch Cart Data
        CartResponse cartResponse = cartService.getUserCart(userId);
        Map<Long, List<CartItem>> itemsByShop = cartResponse.getItemsByShop();

        if (itemsByShop == null || itemsByShop.isEmpty()) {
            throw new BadRequestException("Cannot place order: Cart is empty");
        }

        // B. Validate Coupon
        Coupon coupon = null;
        if (request.getCouponCode() != null && !request.getCouponCode().trim().isEmpty()) {
            coupon = couponRepository.findByCode(request.getCouponCode())
                    .orElseThrow(() -> new ResourceNotFoundException("Invalid Coupon Code"));

            if (coupon.getValidTo() != null && coupon.getValidTo().isBefore(LocalDate.now())) {
                throw new BadRequestException("Coupon has expired");
            }
            if (couponRepository.isUsedByUser(userId, coupon.getId())) {
                throw new BadRequestException("You have already used this coupon");
            }
        }

        List<Order> createdOrders = new ArrayList<>();
        boolean globalCouponUsed = false;

        // C. Loop through shops to create Split Orders
        for (Map.Entry<Long, List<CartItem>> entry : itemsByShop.entrySet()) {
            Long shopId = entry.getKey();
            List<CartItem> shopItems = entry.getValue();

            // Inventory Check removed here

            // 1. Calculate Total for this shop
            BigDecimal shopTotal = shopItems.stream()
                    .map(item -> item.getPriceAtAdd().multiply(new BigDecimal(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 2. Apply Coupon Logic
            BigDecimal finalAmount = shopTotal;
            boolean couponAppliedToThisOrder = false;

            if (coupon != null) {
                // Case A: Shop-Specific Coupon
                if (coupon.getShopId() != null && coupon.getShopId().equals(shopId)) {
                    if (shopTotal.compareTo(coupon.getMinOrderAmount()) >= 0) {
                        finalAmount = applyDiscount(shopTotal, coupon);
                        couponAppliedToThisOrder = true;
                    }
                }
                // Case B: Global Coupon (Apply once)
                else if (coupon.getShopId() == null && !globalCouponUsed) {
                    if (shopTotal.compareTo(coupon.getMinOrderAmount()) >= 0) {
                        finalAmount = applyDiscount(shopTotal, coupon);
                        couponAppliedToThisOrder = true;
                        globalCouponUsed = true;
                    }
                }
            }

            // 3. Create Order Object
            Order order = new Order();
            order.setUserId(userId);
            order.setShopId(shopId);
            order.setShippingAddress(request.getShippingAddress());
            order.setTotalAmount(finalAmount);
            order.setOrderNumber("ORD-" + System.currentTimeMillis() + "-" + shopId);
            
            // 4. Save Order
            Order savedOrder = orderRepository.save(order);

            // 5. Create Order Items
            List<OrderItem> orderItems = new ArrayList<>();
            for (CartItem ci : shopItems) {
                
                // Inventory Reduction removed here

                BigDecimal lineTotal = ci.getPriceAtAdd().multiply(new BigDecimal(ci.getQuantity()));
                orderItems.add(new OrderItem(
                        savedOrder.getId(),
                        ci.getProductId(),
                        ci.getQuantity(),
                        ci.getPriceAtAdd(),
                        lineTotal
                ));
            }
            orderRepository.saveOrderItems(orderItems);

            // 6. Record Coupon Usage
            if (couponAppliedToThisOrder && coupon != null) {
                couponRepository.recordUsage(userId, coupon.getId(), savedOrder.getId());
            }

            createdOrders.add(savedOrder);
        }

        // D. Clear Cart
        cartService.clearCart(userId);

        return createdOrders;
    }

    // ========================================================================
    // 2. USER HISTORY
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
    // 3. ORDER DETAILS
    // ========================================================================
    @Override
    public OrderResponse getOrderDetails(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + orderId));
        List<OrderItemResponse> items = orderRepository.findItemsByOrderId(orderId);
        return mapToResponse(order, items);
    }

    // ========================================================================
    // 4. CANCEL ORDER (USER)
    // ========================================================================
    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        String status = order.getStatus().toUpperCase();
        if ("SHIPPED".equals(status) || "DELIVERED".equals(status) || "CANCELLED".equals(status) || "RETURNED".equals(status)) {
            throw new BadRequestException("Cannot cancel order. Current status: " + status);
        }

        // Inventory Restore removed here

        orderRepository.updateOrderStatus(orderId, "CANCELLED");
    }
    
    // ========================================================================
    // 5. ADMIN: GET ALL ORDERS
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

        String currentStatus = order.getStatus();
        if ("DELIVERED".equalsIgnoreCase(currentStatus) || "CANCELLED".equalsIgnoreCase(currentStatus)) {
            throw new BadRequestException("Cannot change status of a " + currentStatus + " order.");
        }

        orderRepository.updateOrderStatus(orderId, newStatus.toUpperCase());
        order.setStatus(newStatus.toUpperCase());
        return mapToResponse(order, null);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private BigDecimal applyDiscount(BigDecimal total, Coupon coupon) {
        // FIX: Compare Enum correctly using ==
        if (DiscountType.FLAT == coupon.getDiscountType()) {
            
            BigDecimal result = total.subtract(coupon.getDiscountValue());
            // Ensure total doesn't go negative
            return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
            
        } else {
            // Percentage Logic
            BigDecimal discountAmount = total.multiply(coupon.getDiscountValue())
                    .divide(new BigDecimal(100));
            BigDecimal result = total.subtract(discountAmount);
            return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
        }
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
}
    



