package com.ecommerce.project.repositories;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ecommerce.project.model.Order;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o")
    Double getTotalRevenue();

    @Query("""
    SELECT o FROM Order o
    WHERE o.orderId = :orderId
      AND EXISTS (SELECT oi FROM OrderItem oi
                  WHERE oi.order = o AND oi.product.user.userId = :sellerId)
    """)
    Optional<Order> findByIdAndSellerId(@Param("orderId") Long orderId, @Param("sellerId") Long sellerId);

    @Query(value = """
        SELECT o FROM Order o
        WHERE EXISTS (SELECT oi FROM OrderItem oi
                      WHERE oi.order = o
                        AND oi.product.user.userId = :sellerId)
        """,
            countQuery = """
        SELECT COUNT(o) FROM Order o
        WHERE EXISTS (SELECT oi FROM OrderItem oi
                      WHERE oi.order = o
                        AND oi.product.user.userId = :sellerId)
        """)
    Page<Order> findOrdersBySellerId(@Param("sellerId") Long sellerId, Pageable pageable);
}