package com.example.mybill.wholesale.repository;

import com.example.mybill.wholesale.entity.WholesaleProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WholesaleProductImageRepository extends JpaRepository<WholesaleProductImage, Integer> {

    Optional<WholesaleProductImage> findByImageIdAndProduct_WholesaleProductId(Integer imageId, Integer wholesaleProductId);
}
