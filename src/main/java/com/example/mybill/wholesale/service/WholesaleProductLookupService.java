package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.entity.WholesaleProduct;
import com.example.mybill.wholesale.repository.WholesaleProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read-only product knowledge for the customer messaging assistant: finds active wholesale products by what a customer
 * typed and exposes only customer-facing fields (never purchase rate / supplier).
 */
@Service
public class WholesaleProductLookupService {

    /** Customer-facing product facts (never purchase rate / supplier). */
    public record ProductInfo(Integer productId, String name, String code, String type, String fabricType, String material,
                              String description, String unit, String hsnCode, BigDecimal gstPct, List<String> colors,
                              List<String> designs, Map<String, String> specifications) {}

    public record Candidate(ProductInfo product, int score) {}

    public enum MatchStatus { RESOLVED, AMBIGUOUS, NOT_FOUND }

    public record Match(MatchStatus status, String query, ProductInfo product, List<ProductInfo> options) {}

    private static final Set<String> NOISE = Set.of(
        "fabric", "fabrics", "cloth", "cloths", "material", "the", "a", "an", "of", "for", "price", "rate", "cost",
        "meter", "meters", "metre", "metres", "mtr", "mtrs", "mts", "m", "piece", "pieces", "pcs", "pc", "kg", "kgs",
        "ki", "ka", "ke", "entha", "enta", "how", "much", "available", "stock", "do", "you", "have", "is", "what");

    @Autowired private WholesaleProductRepository productRepository;

    @Transactional(readOnly = true)
    public Optional<ProductInfo> get(Integer productId) {
        if (productId == null) return Optional.empty();
        return productRepository.findById(productId).filter(p -> Boolean.TRUE.equals(p.getIsActive())).map(WholesaleProductLookupService::info);
    }

    /** Names of active products (alphabetical), given to the language model so it can normalise spellings. */
    @Transactional(readOnly = true)
    public List<String> catalogueNames(int limit) {
        return productRepository.findByIsActiveTrueOrderByProductNameAsc().stream()
            .map(WholesaleProduct::getProductName).limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductInfo> activeProducts() {
        return productRepository.findByIsActiveTrueOrderByProductNameAsc().stream().map(WholesaleProductLookupService::info).toList();
    }

    /** Best active product for a customer's wording: resolved when one product clearly wins. */
    @Transactional(readOnly = true)
    public Match find(String query) {
        List<Candidate> scored = search(query, 5);
        if (scored.isEmpty()) return new Match(MatchStatus.NOT_FOUND, query, null, List.of());
        Candidate top = scored.get(0);
        boolean clearWinner = scored.size() == 1 || scored.get(1).score() <= top.score() - 10;
        if (top.score() >= 60 && clearWinner) return new Match(MatchStatus.RESOLVED, query, top.product(), List.of());
        return new Match(MatchStatus.AMBIGUOUS, query, null, scored.stream().map(Candidate::product).toList());
    }

    /** Active products matching the wording (name, code, type, fabric, material, colours, designs), best first. */
    @Transactional(readOnly = true)
    public List<Candidate> search(String query, int limit) {
        String q = normalize(query);
        if (q.isBlank()) return List.of();
        List<Candidate> scored = new ArrayList<>();
        for (WholesaleProduct p : productRepository.findByIsActiveTrueOrderByProductNameAsc()) {
            int s = score(q, p);
            if (s >= 40) scored.add(new Candidate(info(p), s));
        }
        scored.sort(Comparator.comparingInt(Candidate::score).reversed());
        return scored.stream().limit(limit).toList();
    }

    static int score(String q, WholesaleProduct p) {
        String name = normalize(p.getProductName());
        String code = normalize(p.getProductCode());
        if (q.equals(name) || (!code.isBlank() && q.equals(code))) return 100;
        if (q.length() >= 3 && name.contains(q)) return 85;
        if (name.length() >= 3 && q.contains(name)) return 80;

        List<String> queryTokens = tokens(q).stream().filter(t -> !NOISE.contains(t)).toList();
        if (queryTokens.isEmpty()) return 0;
        List<String> nameTokens = tokens(name);
        List<String> extraTokens = tokens(normalize(p.getProductType()) + " " + normalize(p.getMaterial()) + " "
            + normalize(p.getFabricType()) + " " + normalize(p.getAvailableColors()) + " " + normalize(p.getAvailableDesigns()));

        int matchedQuery = 0;
        Set<String> matchedName = new HashSet<>();
        for (String t : queryTokens) {
            boolean hit = false;
            for (String n : nameTokens) {
                if (similar(t, n)) { hit = true; matchedName.add(n); }
            }
            if (!hit) hit = extraTokens.stream().anyMatch(e -> similar(t, e));
            if (hit) matchedQuery++;
        }
        if (matchedQuery == 0) return 0;
        if (matchedName.isEmpty()) {
            // matched only on type / fabric / colour / design: a weak candidate, never auto-resolved
            return matchedQuery == queryTokens.size() ? 45 : 0;
        }
        double queryCoverage = (double) matchedQuery / queryTokens.size();
        double nameCoverage = (double) matchedName.size() / Math.max(1, nameTokens.size());
        return (int) Math.round(45 * queryCoverage + 35 * nameCoverage);
    }

    /** Equal, a 4+ letter prefix, or a small spelling difference (madras / madrass, check / chek). */
    static boolean similar(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() >= 4 && b.length() >= 4 && (a.startsWith(b) || b.startsWith(a))) return true;
        int max = Math.min(a.length(), b.length()) >= 7 ? 2 : Math.min(a.length(), b.length()) >= 4 ? 1 : 0;
        return max > 0 && Math.abs(a.length() - b.length()) <= max && levenshtein(a, b) <= max;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static List<String> tokens(String s) {
        return s.isBlank() ? List.of() : Arrays.stream(s.split(" ")).filter(t -> !t.isBlank()).toList();
    }

    static ProductInfo info(WholesaleProduct p) {
        return new ProductInfo(p.getWholesaleProductId(), p.getProductName(), p.getProductCode(), p.getProductType(),
            p.getFabricType(), p.getMaterial(), p.getDescription(), p.getUnit(), p.getHsnCode(), p.getGstPct(),
            list(p.getAvailableColors()), list(p.getAvailableDesigns()), specifications(p.getSpecifications()));
    }

    /** "Red, Blue; Green" → [Red, Blue, Green]. */
    static List<String> list(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split("[,;\\n]")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }

    /** One "Name: Value" (or "Name = Value") per line; lines without a separator are kept under "Note n". */
    static Map<String, String> specifications(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        int note = 1;
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            int i = l.indexOf(':') >= 0 ? l.indexOf(':') : l.indexOf('=');
            if (i > 0 && i < l.length() - 1) out.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
            else out.put("Note " + note++, l);
        }
        return out;
    }
}
