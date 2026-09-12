package dev.kauzes.mizan.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Settlement: the difference between having taken money and having been paid.
 *
 * <p>Its own service, for the reason ADR 0037 gives: it is about days rather than about
 * payments, it grows to hold statements and reconciliation, and everything it needs already
 * crosses the wire as an event.
 */
@SpringBootApplication
@EnableScheduling
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
