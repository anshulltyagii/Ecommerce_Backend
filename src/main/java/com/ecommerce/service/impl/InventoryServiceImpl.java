package com.ecommerce.service.impl;

import com.ecommerce.dto.InventoryResponse;
import com.ecommerce.exception.BadRequestException;
import com.ecommerce.exception.ResourceNotFoundException;
import com.ecommerce.model.Inventory;
import com.ecommerce.repository.InventoryRepository;
import com.ecommerce.service.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryServiceImpl implements InventoryService {

	private final InventoryRepository inventoryRepo;

	public InventoryServiceImpl(InventoryRepository inventoryRepo) {
		this.inventoryRepo = inventoryRepo;
	}

// ------------------------------------------------------------
// GET INVENTORY BY PRODUCT
// ------------------------------------------------------------
	@Override
	public InventoryResponse getInventory(Long productId) {
		Inventory inv = inventoryRepo.findByProductId(productId)
				.orElseThrow(() -> new ResourceNotFoundException("Inventory not found for product: " + productId));

		return mapToResponse(inv);
	}

// ------------------------------------------------------------
// CREATE OR INITIALIZE INVENTORY
// ------------------------------------------------------------
	@Override
	@Transactional
	public InventoryResponse createOrInitInventory(Long productId, int quantity) {

		if (quantity < 0) {
			throw new BadRequestException("Initial quantity cannot be negative");
		}

// if exists â†’ update, else create
		Inventory existing = inventoryRepo.findByProductId(productId).orElse(null);

		if (existing == null) {
			inventoryRepo.createInventory(productId, quantity);
		} else {
			existing.setQuantity(quantity);
			existing.setReserved(0);
			inventoryRepo.update(existing);
		}

		return getInventory(productId);
	}

// ------------------------------------------------------------
// ADD STOCK
// ------------------------------------------------------------
	@Override
	@Transactional
	public InventoryResponse addStock(Long productId, int quantity) {

		if (quantity <= 0) {
			throw new BadRequestException("Quantity must be > 0");
		}

		ensureInventoryExists(productId);

		boolean success = inventoryRepo.increaseStock(productId, quantity);
		if (!success) {
			throw new BadRequestException("Failed to increase stock");
		}

		return getInventory(productId);
	}

// ------------------------------------------------------------
// DECREASE STOCK
// ------------------------------------------------------------
	@Override
	@Transactional
	public InventoryResponse decreaseStock(Long productId, int quantity) {

		if (quantity <= 0) {
			throw new BadRequestException("Quantity must be > 0");
		}

		ensureInventoryExists(productId);

		boolean success = inventoryRepo.decreaseStock(productId, quantity);
		if (!success) {
			throw new BadRequestException("Not enough stock to decrease");
		}

		return getInventory(productId);
	}

// ------------------------------------------------------------
// RESERVE STOCK FOR CHECKOUT
// ------------------------------------------------------------
	@Override
	@Transactional
	public InventoryResponse reserveStock(Long productId, int quantity) {

		if (quantity <= 0) {
			throw new BadRequestException("Quantity must be > 0");
		}

		ensureInventoryExists(productId);

		boolean success = inventoryRepo.reserveStock(productId, quantity);
		if (!success) {
			throw new BadRequestException("Not enough available stock to reserve");
		}

		return getInventory(productId);
	}

// ------------------------------------------------------------
// RELEASE RESERVED STOCK
// ------------------------------------------------------------
	@Override
	@Transactional
	public InventoryResponse releaseReserved(Long productId, int quantity) {

		if (quantity <= 0) {
			throw new BadRequestException("Quantity must be > 0");
		}

		ensureInventoryExists(productId);

		boolean success = inventoryRepo.releaseReservedStock(productId, quantity);
		if (!success) {
			throw new BadRequestException("Not enough reserved stock to release");
		}

		return getInventory(productId);
	}

// ------------------------------------------------------------
// CONSUME RESERVED ON ORDER CONFIRM
// ------------------------------------------------------------
	@Override
	@Transactional
	public InventoryResponse consumeReservedOnOrder(Long productId, int quantity) {

		if (quantity <= 0) {
			throw new BadRequestException("Quantity must be > 0");
		}

		ensureInventoryExists(productId);

		boolean success = inventoryRepo.consumeReservedOnOrder(productId, quantity);
		if (!success) {
			throw new BadRequestException("Failed to consume reserved stock (check reserved and quantity)");
		}

		return getInventory(productId);
	}

// ------------------------------------------------------------
// HELPER METHODS
// ------------------------------------------------------------
	private void ensureInventoryExists(Long productId) {
		if (inventoryRepo.findByProductId(productId).isEmpty()) {
			throw new ResourceNotFoundException("Inventory not found for product: " + productId);
		}
	}

	private InventoryResponse mapToResponse(Inventory inv) {
		InventoryResponse resp = new InventoryResponse();
		resp.setProductId(inv.getProductId());
		resp.setQuantity(inv.getQuantity());
		resp.setReserved(inv.getReserved());
		resp.setAvailable(inv.getAvailable());
		return resp;
	}
}