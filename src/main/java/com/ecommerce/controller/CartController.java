package com.ecommerce.controller;

import com.ecommerce.dto.CartRequest;
import com.ecommerce.dto.CartResponse;
import com.ecommerce.model.CartItem;
import com.ecommerce.service.CartService;
import jakarta.validation.Valid; // Keep this import for the addItemToCart method
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    @Autowired
    private CartService cartService;

    // READ: Get user's cart
    @GetMapping("/{userId}")
    public ResponseEntity<CartResponse> getCartByUserId(@PathVariable Long userId) {
        CartResponse cartResponse = cartService.getUserCart(userId);
        return ResponseEntity.ok(cartResponse);
    }

    // CREATE: Add an item to the cart
    @PostMapping("/{userId}/items")
    public ResponseEntity<CartItem> addItemToCart(@PathVariable Long userId, @Valid @RequestBody CartRequest cartRequest) {
        CartItem cartItem = cartService.addToCart(userId, cartRequest.getProductId(), cartRequest.getQuantity());
        return new ResponseEntity<>(cartItem, HttpStatus.CREATED);
    }

    // UPDATE: Update item quantity in the cart
    // The @Valid annotation has been REMOVED from this method
    @PutMapping("/{userId}/items/{itemId}")
    public ResponseEntity<CartItem> updateCartItemQuantity(@PathVariable Long userId, @PathVariable Long itemId, @RequestBody CartRequest cartRequest) {
        CartItem updatedItem = cartService.updateCartItemQuantity(userId, itemId, cartRequest.getQuantity());
        return ResponseEntity.ok(updatedItem);
    }

    // DELETE: Remove an item from the cart
    @DeleteMapping("/{userId}/items/{itemId}")
    public ResponseEntity<Void> removeItemFromCart(@PathVariable Long userId, @PathVariable Long itemId) {
        cartService.removeFromCart(userId, itemId);
        return ResponseEntity.noContent().build();
    }

    // DELETE: Clear the entire cart
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> clearCart(@PathVariable Long userId) {
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }
}