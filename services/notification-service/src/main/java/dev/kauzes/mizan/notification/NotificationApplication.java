package dev.kauzes.mizan.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// Webhook deliveries are made on a schedule. Without this the dispatcher exists, is a bean,
// and never runs -- which every test that drives it directly would happily fail to notice.
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
