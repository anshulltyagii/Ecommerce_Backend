package com.ecommerce.controller;

import com.ecommerce.dto.ReturnRequestDTO;
import com.ecommerce.model.ReturnRequest;
import com.ecommerce.service.ReturnService;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    @Autowired
    private ReturnService returnService;

    @PostMapping("/{userId}")
    public ResponseEntity<String> requestReturn(@PathVariable Long userId, @RequestBody ReturnRequestDTO request) {
        returnService.requestReturn(userId, request);
        return ResponseEntity.ok("Return requested successfully");
    }
    
 // ... existing code ...

 // GET: View my return history
 @GetMapping("/{userId}")
 public ResponseEntity<List<ReturnRequest>> getUserReturns(@PathVariable Long userId) {
     // Note: In real app, check if logged-in user matches userId
     List<ReturnRequest> requests = returnService.getUserReturnRequests(userId);
     return ResponseEntity.ok(requests);
 }
}