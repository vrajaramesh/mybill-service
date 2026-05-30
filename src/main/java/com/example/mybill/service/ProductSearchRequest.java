package com.example.mybill.service;

public class ProductSearchRequest {
    private String query;        // text query (colour, event, product name, etc.)
    private String imageBase64;  // reference image search
    private String mimeType;
    private int limit = 10;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getImageBase64() { return imageBase64; }
    public void setImageBase64(String imageBase64) { this.imageBase64 = imageBase64; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}
