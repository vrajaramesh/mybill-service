package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.BoutiqueDesignImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BoutiqueDesignImageRepository extends JpaRepository<BoutiqueDesignImage, Integer> {

    List<BoutiqueDesignImage> findByDesignIdOrderBySortOrderAscIdAsc(Integer designId);

    List<BoutiqueDesignImage> findByDesignIdInOrderBySortOrderAscIdAsc(Collection<Integer> designIds);
}
