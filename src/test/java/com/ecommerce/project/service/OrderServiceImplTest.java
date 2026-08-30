package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ErrorCode;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.Cart;
import com.ecommerce.project.model.CartItem;
import com.ecommerce.project.model.Order;
import com.ecommerce.project.model.OrderItem;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.model.User;
import com.ecommerce.project.payload.OrderDTO;
import com.ecommerce.project.payload.OrderItemDTO;
import com.ecommerce.project.payload.OrderResponse;
import com.ecommerce.project.repositories.AddressRepository;
import com.ecommerce.project.repositories.CartRepository;
import com.ecommerce.project.repositories.OrderItemRepository;
import com.ecommerce.project.repositories.OrderRepository;
import com.ecommerce.project.repositories.PaymentRepository;
import com.ecommerce.project.repositories.ProductRepository;
import com.ecommerce.project.util.AuthUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private CartRepository cartRepository;
    @Mock private AddressRepository addressRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private CartService cartService;
    @Mock private ModelMapper modelMapper;
    @Mock private ProductRepository productRepository;
    @Mock private AuthUtil authUtil;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "cartRepository", cartRepository);
        ReflectionTestUtils.setField(orderService, "addressRepository", addressRepository);
        ReflectionTestUtils.setField(orderService, "orderItemRepository", orderItemRepository);
        ReflectionTestUtils.setField(orderService, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(orderService, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(orderService, "cartService", cartService);
        ReflectionTestUtils.setField(orderService, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(orderService, "productRepository", productRepository);
        ReflectionTestUtils.setField(orderService, "authUtil", authUtil);
    }

    // ---------- helpers ----------

    private Product buildProduct(long productId, int quantity) {
        Product product = new Product();
        ReflectionTestUtils.setField(product, "productId", productId);
        product.setQuantity(quantity);
        return product;
    }

    private CartItem buildCartItem(Product product, int quantity, double price, double discount) {
        CartItem cartItem = new CartItem();
        cartItem.setProduct(product);
        cartItem.setQuantity(quantity);
        cartItem.setProductPrice(price);
        cartItem.setDiscount(discount);
        return cartItem;
    }

    private Cart buildCart(long cartId, double totalPrice, CartItem... items) {
        Cart cart = new Cart();
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        cart.setTotalPrice(totalPrice);
        ReflectionTestUtils.setField(cart, "cartItems", new ArrayList<>(List.of(items)));
        return cart;
    }

    // ---------- placeOrder ----------

    @Nested
    class PlaceOrderTests {

        @Test
        void cartNotFound_throwsResourceNotFoundException() {
            when(cartRepository.findCartByEmail("missing@example.com")).thenReturn(null);

            assertThatThrownBy(() ->
                    orderService.placeOrder("missing@example.com", 1L, "Card", "PG", "pay_1", "SUCCESS", "ok"))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(addressRepository, orderRepository, paymentRepository,
                    orderItemRepository, productRepository, cartService);
        }

        @Test
        void emptyCart_throwsApiExceptionWithCartEmptyErrorCode() {
            Cart cart = buildCart(1L, 0.0); // no items

            when(cartRepository.findCartByEmail("buyer@example.com")).thenReturn(cart);

            assertThatThrownBy(() ->
                    orderService.placeOrder("buyer@example.com", 1L, "Card", "PG", "pay_1", "SUCCESS", "ok"))
                    .isInstanceOf(APIException.class)
                    .satisfies(ex -> assertThat(((APIException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.CART_EMPTY));

            verifyNoInteractions(addressRepository, orderRepository, paymentRepository,
                    orderItemRepository, productRepository, cartService);
        }

        @Test
        void addressNotFound_throwsResourceNotFoundException() {
            Product product = buildProduct(100L, 5);
            CartItem cartItem = buildCartItem(product, 1, 50.0, 0.0);
            Cart cart = buildCart(1L, 50.0, cartItem);

            when(cartRepository.findCartByEmail("buyer@example.com")).thenReturn(cart);
            when(addressRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    orderService.placeOrder("buyer@example.com", 99L, "Card", "PG", "pay_1", "SUCCESS", "ok"))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(orderRepository, paymentRepository, orderItemRepository,
                    productRepository, cartService);
        }

        @Test
        void success_reducesStockAndClearsCartAndReturnsPopulatedDto() {
            String email = "buyer@example.com";
            Long addressId = 10L;

            Product product = buildProduct(100L, 10);
            CartItem cartItem = buildCartItem(product, 3, 50.0, 5.0);
            Cart cart = buildCart(1L, 150.0, cartItem);

            when(cartRepository.findCartByEmail(email)).thenReturn(cart);
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(mock(com.ecommerce.project.model.Address.class)));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderItemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
            when(cartService.deleteProductFromCart(anyLong(), anyLong())).thenReturn("removed");

            OrderDTO stubOrderDto = new OrderDTO();
            ReflectionTestUtils.setField(stubOrderDto, "orderItems", new ArrayList<OrderItemDTO>());
            when(modelMapper.map(any(Order.class), eq(OrderDTO.class))).thenReturn(stubOrderDto);
            when(modelMapper.map(any(OrderItem.class), eq(OrderItemDTO.class))).thenReturn(new OrderItemDTO());

            OrderDTO result = orderService.placeOrder(email, addressId, "Card", "Razorpay", "pay_1", "SUCCESS", "ok");

            // Stock was decremented by the ordered quantity and persisted
            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(productCaptor.capture());
            assertThat(productCaptor.getValue().getQuantity()).isEqualTo(7);

            // Cart item was removed after the order was placed
            verify(cartService).deleteProductFromCart(1L, 100L);

            // Order was built with the expected core fields
            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(orderCaptor.capture());
            Order savedOrder = orderCaptor.getValue();
            assertThat(savedOrder.getEmail()).isEqualTo(email);
            assertThat(savedOrder.getOrderStatus()).isEqualTo("Accepted");
            assertThat(savedOrder.getTotalAmount()).isEqualTo(150.0);

            // Order item carried over the cart item's price/quantity/discount
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<OrderItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
            verify(orderItemRepository).saveAll(itemsCaptor.capture());
            OrderItem savedItem = itemsCaptor.getValue().get(0);
            assertThat(savedItem.getQuantity()).isEqualTo(3);
            assertThat(savedItem.getDiscount()).isEqualTo(5.0);
            assertThat(savedItem.getOrderedProductPrice()).isEqualTo(50.0);

            // Returned DTO reflects the address and order items
            assertThat(result.getAddressId()).isEqualTo(addressId);
            assertThat(result.getOrderItems()).hasSize(1);
        }
    }

    // ---------- updateOrder ----------

    @Nested
    class UpdateOrderTests {

        @Test
        void orderNotFound_throwsResourceNotFoundException() {
            when(orderRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.updateOrder(1L, "Shipped"))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(orderRepository, never()).save(any());
        }

        @Test
        void success_updatesStatusAndPersists() {
            Order order = new Order();
            order.setOrderStatus("Accepted");

            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderRepository.save(order)).thenReturn(order);

            OrderDTO stubDto = new OrderDTO();
            when(modelMapper.map(order, OrderDTO.class)).thenReturn(stubDto);

            OrderDTO result = orderService.updateOrder(1L, "Shipped");

            assertThat(order.getOrderStatus()).isEqualTo("Shipped");
            verify(orderRepository).save(order);
            assertThat(result).isSameAs(stubDto);
        }
    }

    // ---------- getAllSellerOrders ----------

    @Nested
    class GetAllSellerOrdersTests {

        @Test
        void returnsOnlyOrdersContainingTheSellersProducts() {
            User seller = new User();
            ReflectionTestUtils.setField(seller, "userId", 1L);

            User otherSeller = new User();
            ReflectionTestUtils.setField(otherSeller, "userId", 2L);

            // Order belonging to the logged-in seller -> should be included
            Product sellerProduct = new Product();
            sellerProduct.setUser(seller);
            OrderItem sellerItem = new OrderItem();
            sellerItem.setProduct(sellerProduct);
            Order sellerOrder = new Order();
            ReflectionTestUtils.setField(sellerOrder, "orderItems", new ArrayList<>(List.of(sellerItem)));

            // Order belonging to a different seller -> must be excluded
            Product otherProduct = new Product();
            otherProduct.setUser(otherSeller);
            OrderItem otherItem = new OrderItem();
            otherItem.setProduct(otherProduct);
            Order otherOrder = new Order();
            ReflectionTestUtils.setField(otherOrder, "orderItems", new ArrayList<>(List.of(otherItem)));

            // Order item with a null product -> defensive branch, must not throw, must be excluded
            OrderItem nullProductItem = new OrderItem();
            Order nullProductOrder = new Order();
            ReflectionTestUtils.setField(nullProductOrder, "orderItems", new ArrayList<>(List.of(nullProductItem)));

            // Product with a null user -> defensive branch, must not throw, must be excluded
            Product noUserProduct = new Product();
            OrderItem noUserItem = new OrderItem();
            noUserItem.setProduct(noUserProduct);
            Order noUserOrder = new Order();
            ReflectionTestUtils.setField(noUserOrder, "orderItems", new ArrayList<>(List.of(noUserItem)));

            Page<Order> page = new PageImpl<>(List.of(sellerOrder, otherOrder, nullProductOrder, noUserOrder));

            when(authUtil.loggedInUser()).thenReturn(seller);
            when(orderRepository.findAll(any(Pageable.class))).thenReturn(page);

            OrderDTO sellerOrderDto = new OrderDTO();
            when(modelMapper.map(eq(sellerOrder), eq(OrderDTO.class))).thenReturn(sellerOrderDto);

            OrderResponse response = orderService.getAllSellerOrders(0, 10, "orderId", "asc");

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0)).isSameAs(sellerOrderDto);
        }

        @Test
        void noMatchingOrders_returnsEmptyContent() {
            User seller = new User();
            ReflectionTestUtils.setField(seller, "userId", 1L);

            User otherSeller = new User();
            ReflectionTestUtils.setField(otherSeller, "userId", 2L);

            Product otherProduct = new Product();
            otherProduct.setUser(otherSeller);
            OrderItem otherItem = new OrderItem();
            otherItem.setProduct(otherProduct);
            Order otherOrder = new Order();
            ReflectionTestUtils.setField(otherOrder, "orderItems", new ArrayList<>(List.of(otherItem)));

            Page<Order> page = new PageImpl<>(List.of(otherOrder));

            when(authUtil.loggedInUser()).thenReturn(seller);
            when(orderRepository.findAll(any(Pageable.class))).thenReturn(page);

            OrderResponse response = orderService.getAllSellerOrders(0, 10, "orderId", "asc");

            assertThat(response.getContent()).isEmpty();
        }
    }
}