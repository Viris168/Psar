package com.psar.order.repository;

import com.psar.order.entity.OrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory> findAllByOrderIdOrderByIdAsc(Long orderId);
}
