package com.ecommerce.project.controller;

import com.ecommerce.project.config.AppConstants;
import com.ecommerce.project.payload.UserResponse;
import com.ecommerce.project.security.request.SignupRequest;
import com.ecommerce.project.security.response.MessageResponse;
import com.ecommerce.project.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/sellers")
public class AdminSellerController {

    @Autowired
    AuthService authService;

    @PostMapping
    public ResponseEntity<MessageResponse> registerSeller(@Valid @RequestBody SignupRequest request) {
        authService.registerSeller(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Seller registered successfully!"));
    }

    @GetMapping
    public ResponseEntity<UserResponse> getAllSellers(
            @RequestParam(name = "pageNumber", defaultValue = AppConstants.PAGE_NUMBER, required = false) Integer pageNumber) {

        Pageable pageable = PageRequest.of(pageNumber,
                Integer.parseInt(AppConstants.PAGE_SIZE),
                Sort.by(AppConstants.SORT_USERS_BY).descending());

        return ResponseEntity.ok(authService.getAllSellers(pageable));
    }
}