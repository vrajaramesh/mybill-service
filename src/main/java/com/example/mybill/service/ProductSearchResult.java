package com.example.mybill.service;

import java.math.BigDecimal;

public class ProductSearchResult {
    private Integer productId;
    private String productName;
    private BigDecimal sellingPrice;
    private String categoryName;
    private String tags;
    private String firstImageUrl;
    private Double similarity;

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public BigDecimal getSellingPrice() { return sellingPrice; }
    public void setSellingPrice(BigDecimal sellingPrice) { this.sellingPrice = sellingPrice; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getFirstImageUrl() { return firstImageUrl; }
    public void setFirstImageUrl(String firstImageUrl) { this.firstImageUrl = firstImageUrl; }

    public Double getSimilarity() { return similarity; }
    public void setSimilarity(Double similarity) { this.similarity = similarity; }
}
