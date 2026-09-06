package com.tradingbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Shoonya Trading Bot REST Application Main Entrypoint. */
@SpringBootApplication
@EnableScheduling
public class ShoonyaTradingBotApp {

    public static void main(String[] args) {
        SpringApplication.run(ShoonyaTradingBotApp.class, args);
    }
}
