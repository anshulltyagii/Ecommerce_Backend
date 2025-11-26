package com.ecommerce.service.impl;

import com.ecommerce.dto.CartResponse;
import com.ecommerce.model.Cart;
import com.ecommerce.model.CartItem;
import com.ecommerce.repository.CartRepository;
import com.ecommerce.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CartServiceImpl implements CartService {

    @Autowired
    private CartRepository cartRepository;
    
    @Autowired
    private JdbcTemplate jdbc; // Direct access to fetch product price quickly

    @Override
    public Cart getOrCreateCart(Long userId) {
        return cartRepository.findByUserId(userId)
                .orElseGet(() -> cartRepository.create(userId));
    }

    @Override
    public CartResponse getUserCart(Long userId) {
        Cart cart = getOrCreateCart(userId);
        List<CartItem> items = cartRepository.findItemsByCartId(cart.getId());
        return buildCartResponse(cart, items);
    }

    @Override
    @Transactional
    public CartItem addToCart(Long userId, Long productId, Integer quantity) {
        Cart cart = getOrCreateCart(userId);

        Optional<CartItem> existingItemOpt = cartRepository.findCartItem(cart.getId(), productId);

        if (existingItemOpt.isPresent()) {
            CartItem existingItem = existingItemOpt.get();
            int newQuantity = existingItem.getQuantity() + quantity;
            cartRepository.updateItemQuantity(existingItem.getId(), newQuantity);
            existingItem.setQuantity(newQuantity);
            return existingItem;
        } else {
            // FETCH REAL PRICE FROM DB
            BigDecimal productPrice = getProductPrice(productId);
            
            CartItem newItem = new CartItem();
            newItem.setCartId(cart.getId());
            newItem.setProductId(productId);
            newItem.setQuantity(quantity);
            newItem.setPriceAtAdd(productPrice); 
            
            return cartRepository.addItem(newItem);
        }
    }

    @Override
    public CartItem updateCartItemQuantity(Long userId, Long cartItemId, Integer quantity) {
        CartItem item = cartRepository.findItemById(cartItemId)
                .orElseThrow(() -> new RuntimeException("Cart item not found"));

        Cart cart = cartRepository.findById(item.getCartId())
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        if (!cart.getUserId().equals(userId)) {
            throw new RuntimeException("Access Denied");
        }

        cartRepository.updateItemQuantity(cartItemId, quantity);
        item.setQuantity(quantity);
        return item;
    }

    @Override
    public void removeFromCart(Long userId, Long cartItemId) {
        Optional<CartItem> itemOpt = cartRepository.findItemById(cartItemId);
        if (itemOpt.isPresent()) {
            CartItem item = itemOpt.get();
            Cart cart = cartRepository.findById(item.getCartId()).orElse(null);
            if (cart != null && cart.getUserId().equals(userId)) {
                cartRepository.removeItem(cartItemId);
            }
        }
    }

    @Override
    public void clearCart(Long userId) {
        Cart cart = getOrCreateCart(userId);
        cartRepository.clearCart(cart.getId());
    }

    @Override
    public CartResponse validateCart(Long userId) {
        return getUserCart(userId);
    }

    // Helper to get price directly using SQL (Bypassing ProductService for now)
    private BigDecimal getProductPrice(Long productId) {
        String sql = "SELECT selling_price FROM products WHERE id = ?";
        try {
            return jdbc.queryForObject(sql, BigDecimal.class, productId);
        } catch (Exception e) {
            throw new RuntimeException("Product not found with ID: " + productId);
        }
    }

    private CartResponse buildCartResponse(Cart cart, List<CartItem> items) {
        CartResponse response = new CartResponse();
        response.setCartId(cart.getId());
        response.setItems(items);
        
        int totalItems = items.stream().mapToInt(CartItem::getQuantity).sum();
        response.setTotalItems(totalItems);
        
        BigDecimal subtotal = items.stream()
                .map(item -> item.getPriceAtAdd().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        response.setSubtotal(subtotal);
        response.setTotal(subtotal);
        
        // Group by Shop for Split Orders
        Map<Long, List<CartItem>> itemsByShop = items.stream()
                .filter(item -> item.getShopId() != null)
                .collect(Collectors.groupingBy(CartItem::getShopId));
        response.setItemsByShop(itemsByShop);
        
        return response;
    }
}