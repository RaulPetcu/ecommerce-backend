package com.ecommerce.project.service;

import com.ecommerce.project.payload.StripePaymentDto;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;

public interface StripeService {

    PaymentIntent createPaymentIntentForCart(String email, Long addressId) throws StripeException;
    PaymentIntent verifyPayment(String paymentIntentId, String email, long expectedAmount) throws StripeException;

}