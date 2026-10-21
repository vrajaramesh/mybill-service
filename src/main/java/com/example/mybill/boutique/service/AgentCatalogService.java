package com.example.mybill.boutique.service;

import com.example.mybill.boutique.dto.*;
import com.example.mybill.boutique.entity.BoutiqueDesign;
import com.example.mybill.boutique.entity.BoutiqueDesignImage;
import com.example.mybill.boutique.entity.CustomisationOption;
import com.example.mybill.boutique.exception.AgentApiException;
import com.example.mybill.boutique.repository.BoutiqueDesignImageRepository;
import com.example.mybill.boutique.repository.BoutiqueDesignRepository;
import com.example.mybill.boutique.repository.CustomisationOptionRepository;
import com.example.mybill.boutique.repository.SizeChartRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Read-only catalog for the agent: designs, their options and the size chart. */
@Service
public class AgentCatalogService {

    static final int MAX_LATEST = 20;

    @Autowired private BoutiqueDesignRepository designRepository;
    @Autowired private FabricCatalogService fabricCatalog;
    @Autowired private BoutiqueDesignImageRepository imageRepository;
    @Autowired private CustomisationOptionRepository optionRepository;
    @Autowired private SizeChartRepository sizeChartRepository;

    /** Active design by code (case-insensitive), or 404. */
    @Transactional(readOnly = true)
    public DesignDetailResponse getDesign(String code) {
        BoutiqueDesign design = designRepository.findByCodeIgnoreCase(code.trim())
            .filter(d -> Boolean.TRUE.equals(d.getIsActive()))
            .orElseThrow(() -> AgentApiException.notFound("DESIGN_NOT_FOUND", "No active design with code " + code));

        List<ImageDto> images = imageRepository.findByDesignIdOrderBySortOrderAscIdAsc(design.getDesignId()).stream()
            .map(i -> new ImageDto(i.getImageUrl(), i.getSortOrder()))
            .toList();

        Map<String, List<OptionDto>> grouped = new LinkedHashMap<>();
        for (CustomisationOption o : optionRepository.findActiveForDesign(design.getDesignId())) {
            grouped.computeIfAbsent(o.getOptionGroup(), g -> new ArrayList<>())
                .add(new OptionDto(o.getOptionId(), o.getName(), PricingService.money(o.getPriceDelta()), o.getDesignId() == null));
        }
        List<OptionGroupDto> groups = grouped.entrySet().stream()
            .map(e -> new OptionGroupDto(e.getKey(), e.getValue()))
            .toList();

        return new DesignDetailResponse(design.getCode(), design.displayName(), design.getGarmentType(),
            design.getDescription(), design.getFabricDescription(), PricingService.money(design.getBasePrice()),
            design.getLeadTimeDays(), PricingService.CURRENCY, images, groups, design.needsExactMeasurements(),
            design.getFabricProductId() == null ? null : fabricCatalog.find(design.getFabricProductId()).orElse(null));
    }

    /** Newest active designs with their first image. limit is clamped to 1..20. */
    @Transactional(readOnly = true)
    public List<DesignSummaryResponse> latest(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LATEST));
        List<BoutiqueDesign> designs = designRepository.findLatestActive(PageRequest.of(0, size));
        if (designs.isEmpty()) return List.of();

        Map<Integer, String> firstImage = imageRepository
            .findByDesignIdInOrderBySortOrderAscIdAsc(designs.stream().map(BoutiqueDesign::getDesignId).toList())
            .stream()
            .collect(Collectors.toMap(BoutiqueDesignImage::getDesignId, BoutiqueDesignImage::getImageUrl, (a, b) -> a));

        return designs.stream()
            .map(d -> new DesignSummaryResponse(d.getCode(), d.displayName(), PricingService.money(d.getBasePrice()),
                PricingService.CURRENCY, firstImage.get(d.getDesignId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SizeChartRow> sizeChart() {
        return sizeChartRepository.findAllByOrderBySortOrderAscSizeIdAsc().stream()
            .map(s -> new SizeChartRow(s.getSizeCode(), s.getBustIn(), s.getWaistIn(), s.getHipIn(),
                s.getShoulderIn(), s.getLengthIn()))
            .toList();
    }

    /** Custom size: not in the chart; our tailor calls the customer after payment to take exact measurements. */
    public static final String CUSTOM_SIZE = "CUSTOM";

    /** Upper-cased size code if it is in the chart (or CUSTOM), else 422 INVALID_SIZE. */
    @Transactional(readOnly = true)
    public String requireSize(String sizeCode) {
        String normalised = sizeCode.trim().toUpperCase(Locale.ROOT);
        if (CUSTOM_SIZE.equals(normalised)) return CUSTOM_SIZE;
        if (!sizeChartRepository.existsBySizeCode(normalised)) {
            List<String> valid = sizeChart().stream().map(SizeChartRow::sizeCode).toList();
            throw AgentApiException.unprocessable("INVALID_SIZE", "Unknown size " + sizeCode,
                Map.of("size_code", sizeCode, "valid_sizes", valid));
        }
        return normalised;
    }
}
