package dev.kauzes.mizan.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * How a password is stored.
 *
 * <p>bcrypt salts every hash and is deliberately slow, which is the whole point: the cost of
 * one check is nothing to a person signing in and is what makes guessing a stolen table
 * expensive. The cost factor is configuration because the right value rises with hardware,
 * and it is recorded inside each stored hash, so raising it later leaves the hashes written
 * before the change still verifiable.
 *
 * <p>Tests run at the same cost the service does. A hash cheap enough to make the suite fast
 * would be a hash that proves nothing about the one a merchant's password gets.
 */
@Configuration
public class PasswordConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${mizan.identity.password-cost:12}") int cost) {
        return new BCryptPasswordEncoder(cost);
    }
}
