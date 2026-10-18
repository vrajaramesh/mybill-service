package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueOrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BoutiqueOrderPaymentRepository extends JpaRepository<BoutiqueOrderPayment, Integer> {

    Optional<BoutiqueOrderPayment> findByRazorpayPaymentLinkId(String linkId);

    Optional<BoutiqueOrderPayment> findFirstByOrderIdOrderByCreatedAtDescPaymentIdDesc(Integer orderId);

    List<BoutiqueOrderPayment> findByOrderIdInOrderByCreatedAtDescPaymentIdDesc(Collection<Integer> orderIds);

    long countByOrderId(Integer orderId);

    /** Links still open in our DB that are old enough that a webhook should have arrived if they were paid. */
    List<BoutiqueOrderPayment> findByStatusAndCreatedAtBeforeAndExpiresAtAfter(String status, LocalDateTime createdBefore,
                                                                              LocalDateTime expiresAfter);
}
