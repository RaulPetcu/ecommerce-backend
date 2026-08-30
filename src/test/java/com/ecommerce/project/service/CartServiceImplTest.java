package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ErrorCode;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.Cart;
import com.ecommerce.project.model.CartItem;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.model.User;
import com.ecommerce.project.payload.CartDTO;
import com.ecommerce.project.payload.CartItemDTO;
import com.ecommerce.project.repositories.CartItemRepository;
import com.ecommerce.project.repositories.CartRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private AuthUtil authUtil;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    private CartServiceImpl cartService;

    private static final String EMAIL = "user@example.com";

    @BeforeEach
    void setUp() {
        cartService = new CartServiceImpl();
        ReflectionTestUtils.setField(cartService, "cartRepository", cartRepository);
        ReflectionTestUtils.setField(cartService, "authUtil", authUtil);
        ReflectionTestUtils.setField(cartService, "productRepository", productRepository);
        ReflectionTestUtils.setField(cartService, "cartItemRepository", cartItemRepository);
        ReflectionTestUtils.setField(cartService, "modelMapper", new ModelMapper());
    }

    // Test data builders
    private Product buildProduct(Long id, String name, int stockQty, double price, double discount, double specialPrice) {
        Product p = new Product();
        p.setProductId(id);
        p.setProductName(name);
        p.setQuantity(stockQty);
        p.setPrice(price);
        p.setDiscount(discount);
        p.setSpecialPrice(specialPrice);
        return p;
    }

    private Cart buildCart(Long cartId, double totalPrice, List<CartItem> items) {
        Cart cart = new Cart();
        cart.setCartId(cartId);
        cart.setTotalPrice(totalPrice);
        cart.setCartItems(items != null ? items : new ArrayList<>());
        return cart;
    }

    private CartItem buildCartItem(Long id, Cart cart, Product product, int qty, double discount, double productPrice) {
        CartItem ci = new CartItem();
        ci.setCartItemId(id);
        ci.setCart(cart);
        ci.setProduct(product);
        ci.setQuantity(qty);
        ci.setDiscount(discount);
        ci.setProductPrice(productPrice);
        return ci;
    }

    private CartItemDTO buildCartItemDTO(Integer qty, Long productId) {
        CartItemDTO dto = new CartItemDTO();
        dto.setQuantity(qty);
        dto.setProductId(productId);
        return dto;
    }

    private void stubCartSaveAssignsId(Long assignedId) {
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart c = invocation.getArgument(0);
            if (c.getCartId() == null) {
                c.setCartId(assignedId);
            }
            return c;
        });
    }

    // addProductToCart
    @Nested
    class AddProductToCartTests {

        @Test
        void throwsResourceNotFoundException_whenProductDoesNotExist() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            Cart existingCart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(existingCart);
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.addProductToCart(99L, 2))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsAPIException_whenProductAlreadyInCart() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            Cart existingCart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(existingCart);

            Product product = buildProduct(10L, "Widget", 5, 100.0, 0.0, 100.0);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));

            CartItem existingItem = buildCartItem(1L, existingCart, product, 1, 0.0, 100.0);
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(existingItem);

            assertThatThrownBy(() -> cartService.addProductToCart(10L, 2))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("already exists in the cart");
        }

        @Test
        void throwsOutOfStock_whenProductQuantityIsZero() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            Cart existingCart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(existingCart);

            Product product = buildProduct(10L, "Widget", 0, 100.0, 0.0, 100.0);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(null);

            APIException ex = catchThrowableOfType(
                    () -> cartService.addProductToCart(10L, 1), APIException.class);

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);
        }

        @Test
        void throwsInsufficientStock_whenRequestedQuantityExceedsStock() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            Cart existingCart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(existingCart);

            Product product = buildProduct(10L, "Widget", 2, 100.0, 0.0, 100.0);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(null);

            APIException ex = catchThrowableOfType(
                    () -> cartService.addProductToCart(10L, 5), APIException.class);

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        void addsProductSuccessfully_reusingExistingCart() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            Cart existingCart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(existingCart);

            Product product = buildProduct(10L, "Widget", 5, 100.0, 10.0, 90.0);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(null);
            stubCartSaveAssignsId(1L);

            CartDTO result = cartService.addProductToCart(10L, 3);

            assertThat(result.getProducts()).hasSize(1);
            assertThat(result.getProducts().get(0).getQuantity()).isEqualTo(3);
            assertThat(existingCart.getTotalPrice()).isEqualTo(270.0); // 90 * 3
            verify(cartItemRepository).save(any(CartItem.class));
            // Cart already existed, so createCart() shouldn't trigger a second, separate save.
            verify(cartRepository, times(1)).save(any(Cart.class));
        }

        @Test
        void createsNewCart_whenUserHasNoExistingCart() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(null);
            when(authUtil.loggedInUser()).thenReturn(mock(User.class));
            stubCartSaveAssignsId(5L);

            Product product = buildProduct(20L, "Gadget", 10, 50.0, 0.0, 50.0);
            when(productRepository.findById(20L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(5L, 20L)).thenReturn(null);

            CartDTO result = cartService.addProductToCart(20L, 1);

            assertThat(result.getCartId()).isEqualTo(5L);
            // One save to persist the brand-new cart, one to persist the updated total.
            verify(cartRepository, times(2)).save(any(Cart.class));
        }
    }

    // getCart
    @Nested
    class GetCartTests {

        @Test
        void throwsResourceNotFoundException_whenCartDoesNotExist() {
            when(cartRepository.findCartByEmailAndCartId(EMAIL, 1L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.getCart(EMAIL, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void returnsCartWithProductsAndCorrectQuantities_whenCartExists() {
            Product product = buildProduct(10L, "Widget", 5, 100.0, 0.0, 100.0);
            Cart cart = buildCart(1L, 300.0, new ArrayList<>());
            CartItem item = buildCartItem(1L, cart, product, 3, 0.0, 100.0);
            cart.setCartItems(new ArrayList<>(List.of(item)));
            when(cartRepository.findCartByEmailAndCartId(EMAIL, 1L)).thenReturn(cart);

            CartDTO result = cartService.getCart(EMAIL, 1L);

            assertThat(result.getProducts()).hasSize(1);
            assertThat(result.getProducts().get(0).getQuantity()).isEqualTo(3);
            assertThat(result.getTotalPrice()).isEqualTo(300.0);
            // Confirms getCart is a read-only view: the JPA-managed Product's stock
            // quantity must never be touched just by reading the cart.
            assertThat(product.getQuantity()).isEqualTo(5);
        }
    }

    // updateProductQuantityInCart
    @Nested
    class UpdateProductQuantityInCartTests {

        @Test
        void throwsResourceNotFoundException_whenUserHasNoCart() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(null);

            assertThatThrownBy(() -> cartService.updateProductQuantityInCart(10L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsResourceNotFoundException_whenProductDoesNotExist() {
            Cart userCart = buildCart(1L, 0.0, new ArrayList<>());
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.updateProductQuantityInCart(10L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsOutOfStock_whenProductStockIsZeroAndIncreasing() {
            Product product = buildProduct(10L, "Widget", 0, 100.0, 0.0, 100.0);
            Cart userCart = buildCart(1L, 0.0, new ArrayList<>());
            CartItem cartItem = buildCartItem(1L, userCart, product, 0, 0.0, 100.0);
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            APIException ex = catchThrowableOfType(
                    () -> cartService.updateProductQuantityInCart(10L, 1), APIException.class);

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);
        }

        /**
         * REGRESSION TEST for a fixed bug: previously, once a product's stock
         * dropped to zero, the OUT_OF_STOCK guard fired regardless of the sign
         * of the delta — a user who already had the item in their cart couldn't
         * even decrease it. The stock guards now only run for positive deltas.
         */
        @Test
        void allowsDecreasingQuantity_whenProductStockHasDroppedToZero() {
            Product product = buildProduct(10L, "Widget", 0, 100.0, 0.0, 100.0);
            Cart userCart = buildCart(1L, 200.0, new ArrayList<>());
            CartItem cartItem = buildCartItem(1L, userCart, product, 2, 0.0, 100.0);
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            CartDTO result = cartService.updateProductQuantityInCart(10L, -1);

            assertThat(result.getProducts().get(0).getQuantity()).isEqualTo(1);
        }

        @Test
        void throwsInsufficientStock_whenResultingQuantityExceedsRawStock() {
            Product product = buildProduct(10L, "Widget", 2, 100.0, 0.0, 100.0);
            Cart userCart = buildCart(1L, 0.0, new ArrayList<>());
            CartItem cartItem = buildCartItem(1L, userCart, product, 0, 0.0, 100.0);
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            APIException ex = catchThrowableOfType(
                    () -> cartService.updateProductQuantityInCart(10L, 5), APIException.class);

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        /**
         * REGRESSION TEST for a fixed bug: previously the insufficient-stock
         * guard compared available stock directly against the incoming delta,
         * never against the quantity that would actually end up in the cart
         * (cartItem.quantity + delta). A small delta could pass the guard even
         * though the resulting cart quantity was well above what was in stock.
         * The guard now compares stock against the resulting total.
         */
        @Test
        void catchesInsufficientStock_whenResultingCartQuantityExceedsAvailableStock() {
            Product product = buildProduct(10L, "Widget", 3, 100.0, 0.0, 100.0); // only 3 in stock
            Cart userCart = buildCart(1L, 500.0, new ArrayList<>());
            CartItem cartItem = buildCartItem(1L, userCart, product, 5, 0.0, 100.0); // already 5 in the cart
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            // Delta of 1 alone looks fine (3 stock is not < 1), but 5 + 1 = 6 is
            // double the 3 units actually available — this must now be rejected.
            APIException ex = catchThrowableOfType(
                    () -> cartService.updateProductQuantityInCart(10L, 1), APIException.class);

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        void throwsResourceNotFoundException_whenCartItemNotFound() {
            Product product = buildProduct(10L, "Widget", 10, 100.0, 0.0, 100.0);
            Cart userCart = buildCart(1L, 0.0, new ArrayList<>());
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.updateProductQuantityInCart(10L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsNegativeQuantity_whenResultingQuantityBelowZero() {
            Product product = buildProduct(10L, "Widget", 10, 100.0, 0.0, 100.0);
            Cart userCart = buildCart(1L, 100.0, new ArrayList<>());
            CartItem cartItem = buildCartItem(1L, userCart, product, 1, 0.0, 100.0);
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            APIException ex = catchThrowableOfType(
                    () -> cartService.updateProductQuantityInCart(10L, -5), APIException.class);

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NEGATIVE_QUANTITY);
        }

        @Test
        void removesItemFromCart_whenResultingQuantityIsZero() {
            Product product = buildProduct(10L, "Widget", 5, 100.0, 0.0, 100.0);
            Cart userCart = buildCart(1L, 200.0, new ArrayList<>());
            CartItem cartItem = buildCartItem(1L, userCart, product, 2, 0.0, 100.0);
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            CartDTO result = cartService.updateProductQuantityInCart(10L, -2);

            assertThat(result.getProducts()).isEmpty();
            verify(cartItemRepository).deleteCartItemByProductIdAndCartId(1L, 10L);
        }

        @Test
        void updatesQuantityAndTotalPrice_whenValidPositiveDelta() {
            Product product = buildProduct(10L, "Widget", 10, 100.0, 0.0, 90.0);
            Cart userCart = buildCart(1L, 180.0, new ArrayList<>()); // 2 * 90 already in cart
            CartItem cartItem = buildCartItem(1L, userCart, product, 2, 0.0, 90.0);
            userCart.setCartItems(new ArrayList<>(List.of(cartItem)));

            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(userCart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(userCart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(cartItem);

            CartDTO result = cartService.updateProductQuantityInCart(10L, 3);

            assertThat(result.getProducts().get(0).getQuantity()).isEqualTo(5);
            assertThat(userCart.getTotalPrice()).isEqualTo(450.0); // 180 + (90 * 3)
            verify(cartItemRepository).save(cartItem);
            verify(cartRepository).save(userCart);
        }
    }

    // deleteProductFromCart
    @Nested
    class DeleteProductFromCartTests {

        @Test
        void throwsResourceNotFoundException_whenCartDoesNotExist() {
            when(cartRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.deleteProductFromCart(1L, 10L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsResourceNotFoundException_whenCartItemDoesNotExist() {
            Cart cart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.deleteProductFromCart(1L, 10L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void deletesItemAndReducesCartTotalPrice() {
            Product product = buildProduct(10L, "Widget", 5, 100.0, 0.0, 100.0);
            Cart cart = buildCart(1L, 300.0, new ArrayList<>());
            CartItem item = buildCartItem(1L, cart, product, 3, 0.0, 100.0);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(item);

            String message = cartService.deleteProductFromCart(1L, 10L);

            assertThat(cart.getTotalPrice()).isEqualTo(0.0); // 300 - (100 * 3)
            assertThat(message).contains("Widget").contains("removed from the cart");
            verify(cartItemRepository).deleteCartItemByProductIdAndCartId(1L, 10L);
            verify(cartRepository).save(cart);
        }
    }

    // updateProductInCarts
    @Nested
    class UpdateProductInCartsTests {

        @Test
        void throwsResourceNotFoundException_whenCartDoesNotExist() {
            when(cartRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.updateProductInCarts(1L, 10L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsResourceNotFoundException_whenProductDoesNotExist() {
            Cart cart = buildCart(1L, 0.0, new ArrayList<>());
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productRepository.findById(10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.updateProductInCarts(1L, 10L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void throwsResourceNotFoundException_whenCartItemDoesNotExist() {
            Cart cart = buildCart(1L, 0.0, new ArrayList<>());
            Product product = buildProduct(10L, "Widget", 5, 100.0, 0.0, 100.0);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.updateProductInCarts(1L, 10L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void recalculatesCartTotalPriceAndPersistsCart_whenProductPriceChanged() {
            // Product's special price has since changed from 100 -> 80
            Product product = buildProduct(10L, "Widget", 5, 100.0, 20.0, 80.0);
            Cart cart = buildCart(1L, 200.0, new ArrayList<>());
            CartItem item = buildCartItem(1L, cart, product, 2, 0.0, 100.0); // stale price on the item
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 10L)).thenReturn(item);

            cartService.updateProductInCarts(1L, 10L);

            // old contribution (100*2=200) removed, new contribution (80*2=160) added
            assertThat(cart.getTotalPrice()).isEqualTo(160.0);
            assertThat(item.getProductPrice()).isEqualTo(80.0);
            // Confirms the previously-missing cartRepository.save() is now present.
            verify(cartRepository).save(cart);
            verify(cartItemRepository).save(item);
        }
    }

    // createOrUpdateCartWithItems
    @Nested
    class CreateOrUpdateCartWithItemsTests {

        @Test
        void createsNewCartAndItems_whenUserHasNoExistingCart() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(null);
            when(authUtil.loggedInUser()).thenReturn(mock(User.class));
            stubCartSaveAssignsId(1L);

            Product p1 = buildProduct(10L, "A", 5, 100.0, 0.0, 100.0);
            Product p2 = buildProduct(20L, "B", 5, 50.0, 0.0, 40.0);
            when(productRepository.findById(10L)).thenReturn(Optional.of(p1));
            when(productRepository.findById(20L)).thenReturn(Optional.of(p2));

            List<CartItemDTO> items = List.of(buildCartItemDTO(2, 10L), buildCartItemDTO(1, 20L));

            String result = cartService.createOrUpdateCartWithItems(items);

            assertThat(result).contains("successfully");
            verify(cartItemRepository, times(2)).save(any(CartItem.class));

            ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
            verify(cartRepository, atLeastOnce()).save(captor.capture());
            // 2*100 + 1*40 = 240
            assertThat(captor.getValue().getTotalPrice()).isEqualTo(240.0);
        }

        @Test
        void clearsExistingCartItems_whenCartAlreadyExists() {
            Cart existingCart = buildCart(1L, 999.0, new ArrayList<>());
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(existingCart);
            when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

            Product product = buildProduct(10L, "A", 5, 100.0, 0.0, 90.0);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));

            cartService.createOrUpdateCartWithItems(List.of(buildCartItemDTO(1, 10L)));

            verify(cartItemRepository).deleteAllByCartId(1L);
            assertThat(existingCart.getTotalPrice()).isEqualTo(90.0);
        }

        @Test
        void throwsResourceNotFoundException_whenAnyProductInListDoesNotExist() {
            when(authUtil.loggedInEmail()).thenReturn(EMAIL);
            when(cartRepository.findCartByEmail(EMAIL)).thenReturn(null);
            when(authUtil.loggedInUser()).thenReturn(mock(User.class));
            stubCartSaveAssignsId(1L);
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    cartService.createOrUpdateCartWithItems(List.of(buildCartItemDTO(1, 99L))))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}