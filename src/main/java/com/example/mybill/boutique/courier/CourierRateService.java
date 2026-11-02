package com.example.mybill.boutique.courier;

import com.example.mybill.boutique.dto.PriceBreakdown;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Delivery charge from the courier's live rate:
 *
 *   contents  = sum(weight per metre / piece x metres / pieces)      (products.ship_weight_grams, designs for stitched;
 *                                                                     delhivery.default-weight-grams = 150 when not set)
 *   cover     = the smallest standard cover that takes the contents  (delhivery.covers)
 *   parcel    = contents + cover weight
 *   chargeable= max(parcel, volumetric = L x B x H / divisor)
 *   charge    = Delhivery prepaid cost for the pincode, rounded UP to the next delhivery.round-to (Rs 10)
 *
 * Free delivery above the threshold (PricingService) still applies. Whenever the rate can't be worked out (Delhivery not
 * set up, no pincode yet, API error) the flat charge stays, so ordering never breaks.
 */
@Service
public class CourierRateService {

    private static final Logger log = LoggerFactory.getLogger(CourierRateService.class);

    @Autowired private DelhiveryClient delhivery;
    @PersistenceContext private EntityManager em;

    /** name:LxBxH(cm):cover grams:max contents grams, smallest first. */
    @Value("${delhivery.covers:Small:30x25x3:40:700,Medium:40x30x6:70:2500,Large:50x40x12:120:8000}")
    private String coversSpec;
    @Value("${delhivery.volumetric-divisor:5000}") private int volumetricDivisor;
    @Value("${delhivery.round-to:10}") private int roundTo;
    /** grams per metre / piece used for a product or design with no shipping weight. */
    @Value("${delhivery.default-weight-grams:150}") private int defaultWeightGrams;

    public record Cover(String name, int lengthCm, int breadthCm, int heightCm, int coverGrams, int maxContentsGrams) {}

    /** The parcel a set of contents goes out in. */
    public record ParcelPlan(int contentsGrams, Cover cover, int parcelGrams, int chargeableGrams) {
        public DelhiveryClient.Parcel parcel() {
            return new DelhiveryClient.Parcel(parcelGrams, cover.lengthCm(), cover.breadthCm(), cover.heightCm());
        }
    }

    public record Rated(PriceBreakdown breakdown, ParcelPlan plan, boolean live) {}

    public List<Cover> covers() {
        List<Cover> out = new ArrayList<>();
        for (String part : coversSpec.split(",")) {
            String[] f = part.trim().split(":");
            if (f.length != 4) continue;
            String[] d = f[1].toLowerCase(Locale.ROOT).split("x");
            if (d.length != 3) continue;
            try {
                out.add(new Cover(f[0].trim(), Integer.parseInt(d[0].trim()), Integer.parseInt(d[1].trim()),
                    Integer.parseInt(d[2].trim()), Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim())));
            } catch (NumberFormatException ignored) {
                log.warn("Ignoring bad cover in delhivery.covers: {}", part);
            }
        }
        out.sort(Comparator.comparingInt(Cover::maxContentsGrams));
        return out;
    }

    public Optional<Cover> cover(String name) {
        return covers().stream().filter(c -> c.name().equalsIgnoreCase(name == null ? "" : name.trim())).findFirst();
    }

    /** Smallest cover that takes the contents (the largest one if nothing is big enough). */
    public ParcelPlan plan(int contentsGrams, Cover forced) {
        List<Cover> all = covers();
        Cover cover = forced != null ? forced : all.stream().filter(c -> c.maxContentsGrams() >= contentsGrams).findFirst()
            .orElse(all.isEmpty() ? new Cover("Default", 30, 25, 5, 50, Integer.MAX_VALUE) : all.get(all.size() - 1));
        int parcel = contentsGrams + cover.coverGrams();
        int volumetric = (int) Math.ceil(cover.lengthCm() * cover.breadthCm() * cover.heightCm() * 1000.0 / volumetricDivisor);
        return new ParcelPlan(contentsGrams, cover, parcel, Math.max(parcel, volumetric));
    }

    /** Weight per metre / piece, the default when not filled in. */
    public int weightOrDefault(Integer grams) {
        return grams == null || grams <= 0 ? defaultWeightGrams : grams;
    }

    /** grams per metre / piece of each product (absent = not filled in; see weightOrDefault). */
    public Map<Integer, Integer> productWeights(Collection<Integer> productIds) {
        Map<Integer, Integer> out = new HashMap<>();
        if (productIds == null || productIds.isEmpty()) return out;
        for (Object r : em.createNativeQuery("SELECT product_id, ship_weight_grams FROM products WHERE product_id IN (?1)")
                .setParameter(1, new HashSet<>(productIds)).getResultList()) {
            Object[] a = (Object[]) r;
            if (a[1] != null && ((Number) a[1]).intValue() > 0) out.put(((Number) a[0]).intValue(), ((Number) a[1]).intValue());
        }
        return out;
    }

    /** Contents weight of goods lines (productId -> quantity in metres / pieces); default weight for unset products. */
    public Integer contentsGrams(Map<Integer, BigDecimal> quantities) {
        Map<Integer, Integer> weights = productWeights(quantities.keySet());
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> e : quantities.entrySet()) {
            Integer w = weights.get(e.getKey());
            if (w == null) log.info("COURIER_WEIGHT_DEFAULT product={} ({}g used)", e.getKey(), defaultWeightGrams);
            total = total.add(BigDecimal.valueOf(weightOrDefault(w)).multiply(e.getValue()));
        }
        return total.setScale(0, RoundingMode.CEILING).intValue();
    }

    /**
     * The breakdown with the live courier charge for the pincode, when it can be worked out; else unchanged (flat).
     * contentsGrams null = weight unknown.
     */
    public Rated rate(PriceBreakdown b, String pincode, Integer contentsGrams) {
        if (contentsGrams == null || contentsGrams <= 0) return new Rated(b, null, false);
        ParcelPlan plan = plan(contentsGrams, null);
        boolean free = b.deliveryCharge() != null && b.deliveryCharge().signum() == 0;
        if (free || pincode == null || pincode.isBlank() || !delhivery.enabled()) return new Rated(b, plan, false);
        Optional<BigDecimal> cost = delhivery.shippingCost(pincode.trim(), plan.chargeableGrams());
        if (cost.isEmpty()) return new Rated(b, plan, false);
        BigDecimal step = BigDecimal.valueOf(Math.max(roundTo, 1));
        BigDecimal charge = cost.get().divide(step, 0, RoundingMode.CEILING).multiply(step).setScale(2, RoundingMode.HALF_UP);
        PriceBreakdown rated = new PriceBreakdown(b.basePrice(), b.options(), b.optionsTotal(), b.unitPrice(), b.qty(),
            b.subtotal(), charge, b.freeDeliveryAbove(), b.subtotal().add(charge).setScale(2, RoundingMode.HALF_UP), b.currency());
        log.info("COURIER_RATE pincode={} contents={}g parcel={}g chargeable={}g cover={} cost={} charge={}",
            pincode, contentsGrams, plan.parcelGrams(), plan.chargeableGrams(), plan.cover().name(), cost.get(), charge);
        return new Rated(rated, plan, true);
    }
}
