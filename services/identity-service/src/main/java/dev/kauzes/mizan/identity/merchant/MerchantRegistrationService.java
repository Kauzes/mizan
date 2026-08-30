package dev.kauzes.mizan.identity.merchant;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.user.UserAccount;
import dev.kauzes.mizan.identity.user.UserAccountRepository;
import dev.kauzes.mizan.identity.user.UserResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening an account, and reading back what opening one created. */
@Service
public class MerchantRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(MerchantRegistrationService.class);

    private final MerchantRepository merchants;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public MerchantRegistrationService(
            MerchantRepository merchants,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder) {
        this.merchants = merchants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates the merchant and its first user together. One transaction, because a merchant
     * nobody can sign in to is an account that can only be repaired by hand.
     */
    @Transactional
    public RegistrationResponse register(RegisterMerchantRequest request) {
        Merchant merchant = merchants.save(new Merchant(request.merchantName().trim()));

        UserAccount owner =
                new UserAccount(
                        merchant,
                        request.email(),
                        passwordEncoder.encode(request.password()),
                        request.fullName().trim(),
                        Role.OWNER);

        try {
            // Flushed here rather than at commit so the unique constraint answers inside this
            // try. Asking whether the email is taken before inserting would answer for the
            // moment before the insert, not for the insert, and two registrations racing would
            // both be told it was free.
            users.saveAndFlush(owner);
        } catch (DataIntegrityViolationException taken) {
            // The address is the only thing a caller could collide on, and repeating it back
            // tells them nothing they did not just send.
            throw new ConflictException("An account already exists for that email address.");
        }

        log.info("registered merchant {} with owner {}", merchant.id(), owner.id());
        return new RegistrationResponse(MerchantResponse.of(merchant), UserResponse.of(owner));
    }

    @Transactional(readOnly = true)
    public MerchantResponse find(UUID merchantId) {
        return merchants
                .findById(merchantId)
                .map(MerchantResponse::of)
                .orElseThrow(() -> new NotFoundException("No merchant with that id."));
    }
}
