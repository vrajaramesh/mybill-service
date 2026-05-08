package com.example.mybill.service;

import java.math.BigDecimal;

public class GstReportRow {
    private int year;
    private int month;
    private String monthName;
    private String startingBillNumber;
    private String endingBillNumber;
    private long totalBills;
    private BigDecimal totalTaxableAmount;
    private BigDecimal totalGstAmount;
    private BigDecimal sgst;
    private BigDecimal cgst;

    public GstReportRow() {}

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }

    public String getMonthName() { return monthName; }
    public void setMonthName(String monthName) { this.monthName = monthName; }

    public String getStartingBillNumber() { return startingBillNumber; }
    public void setStartingBillNumber(String startingBillNumber) { this.startingBillNumber = startingBillNumber; }

    public String getEndingBillNumber() { return endingBillNumber; }
    public void setEndingBillNumber(String endingBillNumber) { this.endingBillNumber = endingBillNumber; }

    public long getTotalBills() { return totalBills; }
    public void setTotalBills(long totalBills) { this.totalBills = totalBills; }

    public BigDecimal getTotalTaxableAmount() { return totalTaxableAmount; }
    public void setTotalTaxableAmount(BigDecimal totalTaxableAmount) { this.totalTaxableAmount = totalTaxableAmount; }

    public BigDecimal getTotalGstAmount() { return totalGstAmount; }
    public void setTotalGstAmount(BigDecimal totalGstAmount) { this.totalGstAmount = totalGstAmount; }

    public BigDecimal getSgst() { return sgst; }
    public void setSgst(BigDecimal sgst) { this.sgst = sgst; }

    public BigDecimal getCgst() { return cgst; }
    public void setCgst(BigDecimal cgst) { this.cgst = cgst; }
}
