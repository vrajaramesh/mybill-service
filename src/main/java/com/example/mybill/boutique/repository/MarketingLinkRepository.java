package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.MarketingLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketingLinkRepository extends JpaRepository<MarketingLink, Integer> {

    Optional<MarketingLink> findBySourceTypeAndSourceId(String sourceType, String sourceId);

    Optional<MarketingLink> findBySourceTypeAndPermalink(String sourceType, String permalink);

    List<MarketingLink> findAllByOrderByActiveDescCreatedAtDescLinkIdDesc();
}
