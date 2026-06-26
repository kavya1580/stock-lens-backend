package com.stockdashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/** NEW FILE — wasn't among what you shared, every Spring Boot app needs one. */
@SpringBootApplication
@EnableCaching
public class StockDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockDashboardApplication.class, args);
    }
}
