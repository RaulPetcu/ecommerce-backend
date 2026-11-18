package com.ecommerce.project.repositories;

import com.ecommerce.project.model.Category;
import com.ecommerce.project.model.Product;
import com.ecommerce.project.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    Page<Product> findByCategoryAndActiveTrueOrderByPriceAsc(Category category, Pageable pageDetails);

    Page<Product> findByProductNameLikeIgnoreCaseAndActiveTrue(String keyword, Pageable pageDetails);

    Page<Product> findByUser(User user, Pageable pageDetails);

    Optional<Product> findByProductIdAndUser(Long productId, User current);

    @Modifying
    @Query("""
    UPDATE Product p
    SET p.quantity = p.quantity - :qty
    WHERE p.productId = :productId
      AND p.active = true
      AND p.quantity >= :qty
    """)
    int decrementStock(@Param("productId") Long productId, @Param("qty") int qty);
}