package com.example.mybill.wholesale.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WholesaleWebConfig implements WebMvcConfigurer {

    @Autowired private WholesaleSchemaInterceptor wholesaleSchemaInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(wholesaleSchemaInterceptor).addPathPatterns("/api/wholesale/**");
    }
}
