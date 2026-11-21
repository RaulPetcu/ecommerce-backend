package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ErrorCode;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.*;
import com.ecommerce.project.payload.StripePaymentDto;
import com.ecommerce.project.repositories.AddressRepository;
import com.ecommerce.project.repositories.CartRepository;
import com.ecommerce.project.util.AuthUtil;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.CustomerSearchResult;
import com.stripe.model.PaymentIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerSearchParams;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Transactional
@RequiredArgsConstructor
public class StripeServiceImpl implements StripeService {

    @Value("${stripe.currency:usd}")
    private final String currency;

    @Value("${stripe.secret.key}")
    private final String stripeApiKey;

    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final AuthUtil authUtil;

    @PostConstruct
    public void init(){
        Stripe.apiKey = stripeApiKey;
    }

    @Override
    public PaymentIntent createPaymentIntentForCart(String email, Long addressId) throws StripeException {
        Cart cart = cartRepository.findCartByEmail(email);
        if (cart == null || cart.getCartItems().isEmpty()) {
            throw new APIException(ErrorCode.CART_EMPTY, "Cart is empty");
        }

        for (CartItem item : cart.getCartItems()) {
            Product product = item.getProduct();
            if (!product.isActive() || product.getQuantity() == 0) {
                throw new APIException(ErrorCode.OUT_OF_STOCK,
                        product.getProductName() + " is no longer available");
            }
            if (item.getQuantity() > product.getQuantity()) {
                throw new APIException(ErrorCode.INSUFFICIENT_STOCK,
                        "Only " + product.getQuantity() + " left of " + product.getProductName());
            }
        }

        User user = authUtil.loggedInUser();
        Address address = addressRepository.findByAddressIdAndUser(addressId, user)
                .orElseThrow(() -> new ResourceNotFoundException("Address", "addressId", addressId));

        Customer customer = findOrCreateCustomer(user, address);

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(cart.getTotalPrice()))
                .setCurrency(currency)
                .setCustomer(customer.getId())
                .setDescription("Order for " + email)
                .putMetadata("userEmail", email)
                .putMetadata("cartId", cart.getCartId().toString())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                .build();
        return PaymentIntent.create(params);
    }

    private Customer findOrCreateCustomer(User user, Address address) throws StripeException {
        CustomerSearchParams searchParams = CustomerSearchParams.builder()
                .setQuery("email:'" + user.getEmail() + "'")
                .build();
        CustomerSearchResult customers = Customer.search(searchParams);

        if (!customers.getData().isEmpty()) {
            return customers.getData().get(0);
        }

        CustomerCreateParams customerParams = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getUserName())
                .setAddress(
                        CustomerCreateParams.Address.builder()
                                .setLine1(address.getStreet())
                                .setCity(address.getCity())
                                .setState(address.getState())
                                .setPostalCode(address.getPincode())
                                .setCountry(address.getCountry())
                                .build()
                )
                .build();
        return Customer.create(customerParams);
    }
    public static long toMinorUnits(Double price) {
        return BigDecimal.valueOf(price).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Override
    public PaymentIntent verifyPayment(String paymentIntentId, String email, long expectedAmount)
            throws StripeException {
        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId); // server-to-server, uses the secret key

        if (!"succeeded".equals(pi.getStatus()))
            throw new APIException(ErrorCode.PAYMENT_FAILED, "Payment not completed");
        if (!email.equals(pi.getMetadata().get("userEmail")))
            throw new APIException(ErrorCode.PAYMENT_FAILED, "Payment does not belong to this user");
        if (pi.getAmount() != expectedAmount || !currency.equalsIgnoreCase(pi.getCurrency()))
            throw new APIException(ErrorCode.PAYMENT_FAILED, "Payment amount mismatch");
        return pi;
    }
}