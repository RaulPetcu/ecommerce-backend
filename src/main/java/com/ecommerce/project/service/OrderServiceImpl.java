package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ErrorCode;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.*;
import com.ecommerce.project.payload.OrderDTO;
import com.ecommerce.project.payload.OrderItemDTO;
import com.ecommerce.project.payload.OrderResponse;
import com.ecommerce.project.repositories.*;
import com.ecommerce.project.util.AuthUtil;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    StripeService stripeService;

    @Autowired
    CartRepository cartRepository;

    @Autowired
    AddressRepository addressRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    CartService cartService;

    @Autowired
    ModelMapper modelMapper;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    AuthUtil authUtil;

    @Override
    @Transactional
    public OrderDTO placeOrder(String emailId, Long addressId, String paymentIntentId) {
        Cart cart = cartRepository.findCartByEmail(emailId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", emailId);
        }

        List<CartItem> cartItems = new ArrayList<>(cart.getCartItems());
        if (cartItems.isEmpty()) {
            throw new APIException(ErrorCode.CART_EMPTY, "Cart is empty");
        }

        Address address = addressRepository.findByAddressIdAndUser(addressId, authUtil.loggedInUser())
                .orElseThrow(() -> new ResourceNotFoundException("Address", "addressId", addressId));

        if (paymentRepository.existsByPgPaymentId(paymentIntentId)) {
            throw new APIException(ErrorCode.PAYMENT_FAILED, "Payment already used");
        }

        long expectedAmount = StripeServiceImpl.toMinorUnits(cart.getTotalPrice());
        PaymentIntent paymentIntent;
        try {
            paymentIntent = stripeService.verifyPayment(paymentIntentId, emailId, expectedAmount);
        } catch (StripeException e) {
            throw new APIException(ErrorCode.PAYMENT_FAILED, "Could not verify payment with Stripe");
        }

        Order order = new Order();
        order.setEmail(emailId);
        order.setOrderDate(LocalDate.now());
        order.setTotalAmount(cart.getTotalPrice());
        order.setOrderStatus("Accepted");
        order.setAddress(address);

        Payment payment = new Payment(
                "online",
                paymentIntent.getId(),
                paymentIntent.getStatus(),
                "Verified with Stripe",
                "Stripe"
        );
        payment.setOrder(order);
        payment = paymentRepository.save(payment);
        order.setPayment(payment);

        Order savedOrder = orderRepository.save(order);

        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(cartItem.getProduct());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setDiscount(cartItem.getDiscount());
            orderItem.setOrderedProductPrice(cartItem.getProductPrice());
            orderItem.setOrder(savedOrder);
            orderItems.add(orderItem);
        }

        orderItems = orderItemRepository.saveAll(orderItems);

        cartItems.forEach(item -> {
            int quantity = item.getQuantity();
            Product product = item.getProduct();
            product.setQuantity(product.getQuantity() - quantity);
            productRepository.save(product);
            cartService.deleteProductFromCart(cart.getCartId(), item.getProduct().getProductId());
        });

        OrderDTO orderDTO = modelMapper.map(savedOrder, OrderDTO.class);
        orderItems.forEach(item -> orderDTO.getOrderItems().add(modelMapper.map(item, OrderItemDTO.class)));
        orderDTO.setAddressId(addressId);

        return orderDTO;
    }

    @Override
    public OrderResponse getAllOrders(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
        Page<Order> pageOrders = orderRepository.findAll(pageDetails);
        List<Order> orders = pageOrders.getContent();
        List<OrderDTO> orderDTOs = orders.stream()
                .map(order -> modelMapper.map(order, OrderDTO.class))
                .toList();
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageOrders.getNumber());
        orderResponse.setPageSize(pageOrders.getSize());
        orderResponse.setTotalElements(pageOrders.getTotalElements());
        orderResponse.setTotalPages(pageOrders.getTotalPages());
        orderResponse.setLastPage(pageOrders.isLast());
        return orderResponse;
    }

    @Override
    public OrderDTO updateOrder(Long orderId, String status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order","orderId",orderId));
        order.setOrderStatus(status);
        orderRepository.save(order);
        return modelMapper.map(order, OrderDTO.class);
    }

    @Override
    public OrderResponse getAllSellerOrders(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);

        Long sellerId = authUtil.loggedInUserId();

        Page<Order> pageOrders = orderRepository.findOrdersBySellerId(sellerId, pageDetails);

        List<OrderDTO> orderDTOs = pageOrders.getContent().stream()
                .map(order -> toSellerOrderDTO(order, sellerId))
                .toList();

        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageOrders.getNumber());
        orderResponse.setPageSize(pageOrders.getSize());
        orderResponse.setTotalElements(pageOrders.getTotalElements());
        orderResponse.setTotalPages(pageOrders.getTotalPages());
        orderResponse.setLastPage(pageOrders.isLast());
        return orderResponse;
    }

    // returns an orderDto having only the seller's items.
    private OrderDTO toSellerOrderDTO(Order order, Long sellerId) {
        OrderDTO dto = modelMapper.map(order, OrderDTO.class);

        List<OrderItem> sellerItems = order.getOrderItems().stream()
                .filter(item -> item.getProduct() != null
                        && item.getProduct().getUser() != null
                        && sellerId.equals(item.getProduct().getUser().getUserId()))
                .toList();

        dto.setOrderItems(sellerItems.stream()
                .map(item -> modelMapper.map(item, OrderItemDTO.class))
                .collect(Collectors.toList()));
        dto.setTotalAmount(sellerItems.stream()
                .mapToDouble(item -> item.getOrderedProductPrice() * item.getQuantity())
                .sum());
        dto.setPayment(null);
        return dto;
    }

    @Override
    public OrderDTO updateOrderAsSeller(Long orderId, String status) {
        Long sellerId = authUtil.loggedInUserId();
        Order order = orderRepository.findByIdAndSellerId(orderId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "orderId", orderId));
        order.setOrderStatus(status);
        return modelMapper.map(orderRepository.save(order), OrderDTO.class);
    }


}