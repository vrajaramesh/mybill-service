package com.example.mybill.service;

import jakarta.persistence.*;

@Entity
@Table(name = "product_sub_category",
       uniqueConstraints = @UniqueConstraint(columnNames = {"sub_cat_name", "category_name"}))
public class ProductSubCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "sub_cat_name", nullable = false, length = 100)
    private String subCatName;

    @ManyToOne
    @JoinColumn(name = "category_name", nullable = false)
    private ProductCategory category;

    @Column(name = "is_online")
    private Boolean isOnline = true;

    public ProductSubCategory() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getSubCatName() { return subCatName; }
    public void setSubCatName(String subCatName) { this.subCatName = subCatName; }

    public ProductCategory getCategory() { return category; }
    public void setCategory(ProductCategory category) { this.category = category; }

    public Boolean getIsOnline() { return isOnline; }
    public void setIsOnline(Boolean isOnline) { this.isOnline = isOnline; }
}
