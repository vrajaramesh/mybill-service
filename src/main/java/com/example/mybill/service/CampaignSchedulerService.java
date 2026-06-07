package com.example.mybill.service;

import com.example.mybill.dto.Firm;
import com.example.mybill.repository.FirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Hermes scheduled campaign triggers.
 * Delegates all work to specialised services — this class is pure orchestration.
 *
 * Requires @EnableScheduling on MybillServiceApplication (already added in Phase 1).
 * All cron times are server-local.
 */
@Service
public class CampaignSchedulerService {

    @Autowired private HermesAgentService       hermesAgent;
    @Autowired private DeadStockCampaignService  deadStockService;
    @Autowired private ExecutiveReportService    execReportService;
    @Autowired private FirmRepository            firmRepository;

    @Value("${hermes.enabled:true}")
    private boolean hermesEnabled;

    @Value("${hermes.owner.phone:}")
    private String ownerPhone;

    // ── Every Monday 9am — new arrivals + dead stock ─────────────────────────

    @Scheduled(cron = "0 0 9 * * MON")
    public void weeklyNewArrivalsAndDeadStock() {
        if (!hermesEnabled) return;

        for (Firm firm : activeFirms()) {
            try {
                System.out.println("[Hermes] Weekly campaigns starting for " + firm.getFirmCode());
                hermesAgent.triggerNewArrivalCampaign(
                    firm.getSchemaName(), firm.getFirmName(), firm.getFirmCode());
                deadStockService.run(
                    firm.getSchemaName(), firm.getFirmName(), firm.getFirmCode());
            } catch (Exception e) {
                System.err.println("[Hermes] Weekly error for " + firm.getFirmCode() + ": " + e.getMessage());
            }
        }
    }

    // ── Every morning 8am — executive report to owner ────────────────────────

    @Scheduled(cron = "0 0 8 * * *")
    public void dailyExecutiveReport() {
        if (!hermesEnabled || ownerPhone == null || ownerPhone.isBlank()) return;

        for (Firm firm : activeFirms()) {
            execReportService.send(
                firm.getSchemaName(), firm.getFirmName(), firm.getFirmCode(), ownerPhone);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private List<Firm> activeFirms() {
        return firmRepository.findAll()
            .stream().filter(f -> Boolean.TRUE.equals(f.getIsActive())).toList();
    }
}
