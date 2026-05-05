package com.example.bankingrisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class BankingRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingRiskApplication.class, args);
    }
}
