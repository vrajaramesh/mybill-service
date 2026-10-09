package com.example.mybill.wholesale.service;

import com.example.mybill.wholesale.entity.*;
import com.example.mybill.wholesale.exception.WholesaleException;
import com.example.mybill.wholesale.repository.WholesaleInventoryLedgerRepository;
import com.example.mybill.wholesale.repository.WholesaleInventorySettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONLY way wholesale stock changes. Each movement:
 * <ol>
 *   <li>updates wholesale_products.available_quantity with one atomic {@code UPDATE … RETURNING} (row-locked, so
 *       concurrent movements of a product are serialised and the returned balance is exact);</li>
 *   <li>refuses to go below zero unless "allow negative stock" is switched on;</li>
 *   <li>appends a ledger row with its source document and the resulting balance.</li>
 * </ol>
 * Runs inside the caller's transaction (MANDATORY): if any movement of a document fails, all of them — and the
 * document change — roll back. Retail stock is never touched.
 */
@Service
public class WholesaleInventoryService {

    @PersistenceContext private EntityManager entityManager;
    @Autowired private WholesaleInventoryLedgerRepository ledgerRepository;
    @Autowired private WholesaleInventorySettingsRepository settingsRepository;

    /** One stock movement; exactly one of quantityIn / quantityOut is positive. */
    public record Movement(WholesaleProduct product, WholesaleInventoryTransactionType type, String referenceType,
                           Integer referenceId, String referenceNumber, BigDecimal quantityIn, BigDecimal quantityOut,
                           BigDecimal rate, LocalDate date, String notes, Long reversesLedgerId) {

        public static Movement in(WholesaleProduct p, WholesaleInventoryTransactionType type, String refType, Integer refId,
                                  String refNumber, BigDecimal qty, BigDecimal rate, LocalDate date, String notes) {
            return new Movement(p, type, refType, refId, refNumber, qty, BigDecimal.ZERO, rate, date, notes, null);
        }

        public static Movement out(WholesaleProduct p, WholesaleInventoryTransactionType type, String refType, Integer refId,
                                   String refNumber, BigDecimal qty, BigDecimal rate, LocalDate date, String notes) {
            return new Movement(p, type, refType, refId, refNumber, BigDecimal.ZERO, qty, rate, date, notes, null);
        }
    }

    public boolean allowNegativeStock() {
        return settingsRepository.findById(WholesaleInventorySettings.SINGLETON_ID)
            .map(s -> Boolean.TRUE.equals(s.getAllowNegativeStock())).orElse(false);
    }

    /**
     * Posts all movements of one document. If any would make stock negative (and that is not allowed), nothing is
     * kept: a 409 lists every shortage and the surrounding transaction rolls back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(List<Movement> movements, String username) {
        if (movements.isEmpty()) return;
        boolean allowNegative = allowNegativeStock();
        entityManager.flush();
        List<String> shortages = new ArrayList<>();
        for (Movement m : movements) {
            if (m.referenceType() == null || m.referenceId() == null) {
                throw new IllegalStateException("Every stock movement needs a source document");
            }
            BigDecimal delta = m.quantityIn().subtract(m.quantityOut());
            if (delta.signum() == 0) continue;
            BigDecimal balance = applyDelta(m.product().getWholesaleProductId(), delta, allowNegative || delta.signum() > 0);
            if (balance == null) {
                shortages.add(m.product().getProductName() + " (needs " + plain(m.quantityOut()) + " " + m.product().getUnit()
                    + ", available " + plain(currentQuantity(m.product().getWholesaleProductId())) + ")");
                continue;
            }
            ledgerRepository.save(new WholesaleInventoryLedgerEntry(m.product(), m.type(), m.referenceType(), m.referenceId(),
                m.referenceNumber(), m.reversesLedgerId(), m.quantityIn(), m.quantityOut(), balance, m.rate(), m.date(),
                m.notes(), username));
        }
        if (!shortages.isEmpty()) {
            throw WholesaleException.conflict("Not enough wholesale stock: " + String.join("; ", shortages)
                + ". Negative wholesale stock is not allowed (Wholesale Stock → settings).");
        }
    }

    /**
     * Undoes every not-yet-reversed movement of a source document with opposite REVERSAL rows (same quantities,
     * pointing at the originals). Returns how many movements were reversed (0 when the document never moved stock
     * or was posted before the ledger existed).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reverseDocument(String referenceType, Integer referenceId, String referenceNumber, LocalDate date,
                               String notes, String username) {
        List<Movement> reversals = new ArrayList<>();
        for (WholesaleInventoryLedgerEntry e : ledgerRepository.findByReferenceTypeAndReferenceIdOrderByLedgerIdAsc(referenceType, referenceId)) {
            if (e.getTransactionType() == WholesaleInventoryTransactionType.REVERSAL || ledgerRepository.existsByReversesLedgerId(e.getLedgerId())) {
                continue;
            }
            reversals.add(new Movement(e.getProduct(), WholesaleInventoryTransactionType.REVERSAL, referenceType, referenceId,
                referenceNumber, e.getQuantityOut(), e.getQuantityIn(), e.getRate(), date, notes, e.getLedgerId()));
        }
        post(reversals, username);
        return reversals.size();
    }

    public boolean hasMovements(Integer productId) {
        return ledgerRepository.existsByProduct_WholesaleProductId(productId);
    }

    private BigDecimal applyDelta(Integer productId, BigDecimal delta, boolean unconditional) {
        String sql = "UPDATE wholesale_products SET available_quantity = available_quantity + :delta, updated_at = NOW() "
            + "WHERE wholesale_product_id = :id" + (unconditional ? "" : " AND available_quantity + :delta >= 0")
            + " RETURNING available_quantity";
        List<?> rows = entityManager.createNativeQuery(sql)
            .setParameter("delta", delta).setParameter("id", productId)
            .getResultList();
        return rows.isEmpty() ? null : new BigDecimal(rows.get(0).toString());
    }

    private BigDecimal currentQuantity(Integer productId) {
        List<?> rows = entityManager.createNativeQuery(
                "SELECT available_quantity FROM wholesale_products WHERE wholesale_product_id = :id")
            .setParameter("id", productId).getResultList();
        return rows.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rows.get(0).toString());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
