package com.ecommerce.service.impl;

import com.ecommerce.dto.WishlistResponse;
import com.ecommerce.model.WishlistItem;
import com.ecommerce.repository.WishlistRepository;
import com.ecommerce.service.WishlistService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WishlistServiceImpl implements WishlistService {

    @Autowired
	private  WishlistRepository wishlistRepository;


    @Override
    public WishlistResponse addToWishlist(Long userId, Long productId) {

        WishlistItem item = wishlistRepository.findItem(userId, productId).orElse(null);

        if (item != null)
            return toResponse(item);

        WishlistItem saved = wishlistRepository.add(userId, productId);

        return toResponse(saved);
    }

    @Override
    public boolean removeFromWishlist(Long userId, Long productId) {
        return wishlistRepository.remove(userId, productId);
    }

    @Override
    public List<WishlistResponse> getUserWishlist(Long userId) {
        return wishlistRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private WishlistResponse toResponse(WishlistItem w) {
        WishlistResponse r = new WishlistResponse();
        r.setId(w.getId());
        r.setUserId(w.getUserId());
        r.setProductId(w.getProductId());
        r.setAddedAt(w.getAddedAt());
        return r;
    }

}