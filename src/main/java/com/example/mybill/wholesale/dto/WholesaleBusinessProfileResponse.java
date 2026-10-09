package com.example.mybill.wholesale.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Everything shown on the Settings screens, plus what is still missing for documents. */
public record WholesaleBusinessProfileResponse(
    Details details,
    Bank bank,
    Upi upi,
    Readiness readiness,
    LocalDateTime updatedAt,
    String updatedBy
) {
    public record Details(String firmName, String address, String city, String stateCode, String stateName,
                          String pinCode, String gstNumber, String phone, String email, String website,
                          String logoUrl, String logoPublicId) {}

    public record Bank(String bankName, String accountName, String accountNumber, String ifsc, String branch) {}

    public record Upi(String upiId, String qrImageUrl, String qrPublicId) {}

    /**
     * documentReady = mandatory profile fields present (documents can be generated).
     * missing = blocking gaps; warnings = optional items that documents will simply omit.
     */
    public record Readiness(boolean documentReady, boolean gstRegistered, boolean bankConfigured, boolean upiConfigured,
                            List<String> missing, List<String> warnings) {}
}
