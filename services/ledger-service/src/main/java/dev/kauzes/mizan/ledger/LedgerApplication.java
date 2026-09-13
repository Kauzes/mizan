package dev.kauzes.mizan.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// The integrity check runs on a timer now, so that the books not balancing is something the
// platform says rather than something somebody has to think to ask.
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
