package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.ResourceAlreadyExistsException;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.Cart;
import com.ecommerce.project.model.Category;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.model.User;
import com.ecommerce.project.payload.ProductDTO;
import com.ecommerce.project.payload.ProductResponse;
import com.ecommerce.project.repositories.CartRepository;
import com.ecommerce.project.repositories.CategoryRepository;
import com.ecommerce.project.repositories.ProductRepository;
import com.ecommerce.project.util.AuthUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartService cartService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private FileService fileService;

    @Mock
    private AuthUtil authUtil;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl();
        ReflectionTestUtils.setField(productService, "cartRepository", cartRepository);
        ReflectionTestUtils.setField(productService, "cartService", cartService);
        ReflectionTestUtils.setField(productService, "productRepository", productRepository);
        ReflectionTestUtils.setField(productService, "categoryRepository", categoryRepository);
        ReflectionTestUtils.setField(productService, "fileService", fileService);
        ReflectionTestUtils.setField(productService, "authUtil", authUtil);

        ReflectionTestUtils.setField(productService, "modelMapper", new ModelMapper());

        ReflectionTestUtils.setField(productService, "path", "images/");
        ReflectionTestUtils.setField(productService, "imageBaseUrl", "http://localhost:8080/images");
    }

    // addProduct
    @Nested
    class AddProduct {

        @Test
        @DisplayName("throws when category does not exist")
        void throwsWhenCategoryNotFound() {
            when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.addProduct(99L, new ProductDTO()))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when an ACTIVE product with the same name already exists in the category")
        void throwsWhenActiveDuplicateExists() {
            Product existingActive = new Product();
            existingActive.setProductName("Laptop");
            existingActive.setActive(true);

            Category category = new Category();
            category.setCategoryId(1L);
            category.setProducts(new ArrayList<>(List.of(existingActive)));

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

            ProductDTO input = new ProductDTO();
            input.setProductName("Laptop");

            assertThatThrownBy(() -> productService.addProduct(1L, input))
                    .isInstanceOf(ResourceAlreadyExistsException.class);

            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("allows the same name when the existing product with that name is inactive")
        void allowsSameNameWhenExistingIsInactive() {
            Product existingInactive = new Product();
            existingInactive.setProductName("Laptop");
            existingInactive.setActive(false);

            Category category = new Category();
            category.setCategoryId(1L);
            category.setProducts(new ArrayList<>(List.of(existingInactive)));

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
            when(authUtil.loggedInUser()).thenReturn(new User("seller1", "seller1@example.com", "encodedPw"));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductDTO input = new ProductDTO();
            input.setProductName("Laptop");
            input.setPrice(100.0);
            input.setDiscount(0.0);

            ProductDTO result = productService.addProduct(1L, input);

            assertThat(result.getProductName()).isEqualTo("Laptop");
            verify(productRepository).save(any(Product.class));
        }

        @Test
        @DisplayName("saves a new product with default image, computed special price, category and seller set")
        void savesNewProductSuccessfully() {
            Category category = new Category();
            category.setCategoryId(1L);
            category.setProducts(new ArrayList<>());

            User seller = new User("seller1", "seller1@example.com", "encodedPw");

            ProductDTO input = new ProductDTO();
            input.setProductName("Laptop");
            input.setDescription("A nice laptop");
            input.setQuantity(10);
            input.setPrice(1000.0);
            input.setDiscount(10.0);

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
            when(authUtil.loggedInUser()).thenReturn(seller);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductDTO result = productService.addProduct(1L, input);

            assertThat(result.getProductName()).isEqualTo("Laptop");
            assertThat(result.getSpecialPrice()).isEqualTo(900.0); // 1000 - 10%

            verify(productRepository).save(argThat(p ->
                    "default.jpg".equals(p.getImage())
                            && p.getCategory() == category
                            && p.getUser() == seller));
        }
    }

    // updateProduct
    @Nested
    class UpdateProduct {

        @Test
        @DisplayName("throws when product does not exist")
        void throwsWhenProductNotFound() {
            when(productRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(1L, new ProductDTO()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("updates fields, recalculates special price, and syncs every cart holding the product")
        void updatesFieldsAndSyncsCarts() {
            Product existing = new Product();
            existing.setProductId(1L);
            existing.setProductName("Old Name");
            existing.setPrice(100.0);
            existing.setDiscount(0.0);

            ProductDTO update = new ProductDTO();
            update.setProductName("New Name");
            update.setDescription("New description");
            update.setQuantity(5);
            update.setDiscount(20.0);
            update.setPrice(200.0);

            Cart cart1 = new Cart();
            cart1.setCartId(10L);
            Cart cart2 = new Cart();
            cart2.setCartId(20L);

            when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
            when(cartRepository.findCartsByProductId(1L)).thenReturn(List.of(cart1, cart2));

            ProductDTO result = productService.updateProduct(1L, update);

            assertThat(result.getProductName()).isEqualTo("New Name");
            assertThat(result.getSpecialPrice()).isEqualTo(160.0); // 200 - 20%

            verify(cartService).updateProductInCarts(10L, 1L);
            verify(cartService).updateProductInCarts(20L, 1L);
        }

        @Test
        @DisplayName("does not touch cartService when the product is not in any cart")
        void doesNotTouchCartsWhenNoneFound() {
            Product existing = new Product();
            existing.setProductId(1L);

            when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
            when(cartRepository.findCartsByProductId(1L)).thenReturn(List.of());

            productService.updateProduct(1L, new ProductDTO());

            verify(cartService, never()).updateProductInCarts(anyLong(), anyLong());
        }
    }

    // deleteProduct
    @Nested
    class DeleteProduct {

        @Test
        @DisplayName("throws when product does not exist")
        void throwsWhenProductNotFound() {
            when(productRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.deleteProduct(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("soft-deletes the product and removes it from every cart holding it")
        void softDeletesAndRemovesFromCarts() {
            Product existing = new Product();
            existing.setProductId(1L);
            existing.setActive(true);

            Cart cart = new Cart();
            cart.setCartId(5L);

            when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(cartRepository.findCartsByProductId(1L)).thenReturn(List.of(cart));
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            productService.deleteProduct(1L);

            assertThat(existing.isActive()).isFalse();
            verify(cartService).deleteProductFromCart(5L, 1L);
            verify(productRepository).save(existing);
        }

        @Test
        @DisplayName("does not touch cartService when the product is not in any cart")
        void doesNotTouchCartsWhenNoneFound() {
            Product existing = new Product();
            existing.setProductId(1L);

            when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(cartRepository.findCartsByProductId(1L)).thenReturn(List.of());
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            productService.deleteProduct(1L);

            verify(cartService, never()).deleteProductFromCart(anyLong(), anyLong());
        }
    }

    // updateProductImage
    @Nested
    class UpdateProductImage {

        @Test
        @DisplayName("throws when product does not exist")
        void throwsWhenProductNotFound() {
            when(productRepository.findById(1L)).thenReturn(Optional.empty());
            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> productService.updateProductImage(1L, file))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("uploads the file via fileService and persists the returned filename")
        void uploadsAndUpdatesImage() {
            Product existing = new Product();
            existing.setProductId(1L);
            existing.setImage("default.jpg");

            MultipartFile file = mock(MultipartFile.class);

            when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(fileService.uploadImage("images/", file)).thenReturn("laptop-123.jpg");
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            ProductDTO result = productService.updateProductImage(1L, file);

            assertThat(result.getImage()).isEqualTo("laptop-123.jpg");
            verify(fileService).uploadImage("images/", file);
        }
    }

    // searchByCategory
    @Nested
    class SearchByCategory {

        @Test
        @DisplayName("throws when category does not exist")
        void throwsWhenCategoryNotFound() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.searchByCategory(1L, 0, 10, "price", "asc"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("returns products with fully-qualified image URLs")
        void returnsProductsWithFullImageUrls() {
            Category category = new Category();
            category.setCategoryId(1L);

            Product product = new Product();
            product.setProductId(1L);
            product.setProductName("Laptop");
            product.setImage("laptop.jpg");

            Page<Product> page = new PageImpl<>(List.of(product));

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
            when(productRepository.findByCategoryAndActiveTrueOrderByPriceAsc(eq(category), any(Pageable.class)))
                    .thenReturn(page);

            ProductResponse response = productService.searchByCategory(1L, 0, 10, "price", "asc");

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getImage())
                    .isEqualTo("http://localhost:8080/images/laptop.jpg");
        }
    }

    // searchProductByKeyword
    @Nested
    class SearchProductByKeyword {

        @Test
        @DisplayName("returns products with fully-qualified image URLs, consistent with the other listing methods")
        void returnsProductsWithFullImageUrls() {
            Product product = new Product();
            product.setProductId(1L);
            product.setProductName("Laptop");
            product.setImage("laptop.jpg");

            Page<Product> page = new PageImpl<>(List.of(product));

            when(productRepository.findByProductNameLikeIgnoreCaseAndActiveTrue(eq("%laptop%"), any(Pageable.class)))
                    .thenReturn(page);

            ProductResponse response = productService.searchProductByKeyword("laptop", 0, 10, "price", "asc");

            assertThat(response.getContent().get(0).getImage())
                    .isEqualTo("http://localhost:8080/images/laptop.jpg");
        }
    }

    // constructImageUrl (private) — exercised directly, not just via a wrapper
    @Nested
    class ConstructImageUrl {

        @Test
        @DisplayName("appends a slash when the base URL has no trailing slash")
        void appendsSlashWhenMissing() {
            ReflectionTestUtils.setField(productService, "imageBaseUrl", "http://localhost:8080/images");

            String url = ReflectionTestUtils.invokeMethod(productService, "constructImageUrl", "laptop.jpg");

            assertThat(url).isEqualTo("http://localhost:8080/images/laptop.jpg");
        }

        @Test
        @DisplayName("does not double the slash when the base URL already ends with one")
        void doesNotDoubleSlash() {
            ReflectionTestUtils.setField(productService, "imageBaseUrl", "http://localhost:8080/images/");

            String url = ReflectionTestUtils.invokeMethod(productService, "constructImageUrl", "laptop.jpg");

            assertThat(url).isEqualTo("http://localhost:8080/images/laptop.jpg");
        }
    }
}