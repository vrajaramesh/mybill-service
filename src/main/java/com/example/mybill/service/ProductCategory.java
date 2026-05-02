package com.example.mybill.service;

import jakarta.persistence.*;

@Entity
@Table(name = "product_category")
public class ProductCategory {

    @Id
    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "is_online")
    private Boolean isOnline = true;

    // Constructors
    public ProductCategory() {}

    public ProductCategory(String categoryName, Boolean isOnline) {
        this.categoryName = categoryName;
        this.isOnline = isOnline;
    }

    // Getters and Setters
    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public Boolean getIsOnline() {
        return isOnline;
    }

    public void setIsOnline(Boolean isOnline) {
        this.isOnline = isOnline;
    }
}
