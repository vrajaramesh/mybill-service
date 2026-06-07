package com.example.mybill.service;

public class ImageSearchResult {

    private Integer productId;
    private String  productName;
    private String  imageUrl;
    private String  garmentType;
    private String  occasion;
    private String  description;
    private double  similarity;

    public Integer getProductId()    { return productId; }
    public String  getProductName()  { return productName; }
    public String  getImageUrl()     { return imageUrl; }
    public String  getGarmentType()  { return garmentType; }
    public String  getOccasion()     { return occasion; }
    public String  getDescription()  { return description; }
    public double  getSimilarity()   { return similarity; }

    public void setProductId(Integer productId)    { this.productId = productId; }
    public void setProductName(String productName) { this.productName = productName; }
    public void setImageUrl(String imageUrl)       { this.imageUrl = imageUrl; }
    public void setGarmentType(String garmentType) { this.garmentType = garmentType; }
    public void setOccasion(String occasion)       { this.occasion = occasion; }
    public void setDescription(String description) { this.description = description; }
    public void setSimilarity(double similarity)   { this.similarity = similarity; }
}
