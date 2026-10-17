package com.example.mybill.boutique.repository;

import com.example.mybill.boutique.entity.SizeChartEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SizeChartRepository extends JpaRepository<SizeChartEntry, Integer> {

    List<SizeChartEntry> findAllByOrderBySortOrderAscSizeIdAsc();

    boolean existsBySizeCode(String sizeCode);
}
