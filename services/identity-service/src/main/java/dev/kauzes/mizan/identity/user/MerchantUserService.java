package dev.kauzes.mizan.identity.user;

import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.merchant.Merchant;
import dev.kauzes.mizan.identity.merchant.MerchantRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people acting for one merchant.
 *
 * <p>Every method here takes the merchant id and uses it in the query rather than checking it
 * afterwards. The interceptor has already refused a caller asking about a merchant that is
 * not theirs; this makes the boundary hold a second time, in the place a future endpoint is
 * most likely to forget it.
 */
@Service
public class MerchantUserService {

    private static final Logger log = LoggerFactory.getLogger(MerchantUserService.class);

    private final MerchantRepository merchants;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public MerchantUserService(
            MerchantRepository merchants,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder) {
        this.merchants = merchants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(UUID merchantId) {
        return users.findByMerchantIdOrderByCreatedAtAsc(merchantId).stream()
                .map(UserResponse::of)
                .toList();
    }

    @Transactional
    public UserResponse add(UUID merchantId, AddUserRequest request) {
        Merchant merchant = merchants
                .findById(merchantId)
                .orElseThrow(() -> new NotFoundException("No merchant with that id."));

        UserAccount user = new UserAccount(
                merchant,
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                first(request.roles()));
        user.holdOnly(request.roles());

        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException taken) {
            throw new ConflictException("An account already exists for that email address.");
        }

        log.info("added user {} to merchant {} with {}", user.id(), merchantId, request.roles());
        return UserResponse.of(user);
    }

    @Transactional
    public UserResponse setRoles(UUID merchantId, UUID userId, Set<Role> roles) {
        UserAccount user = mine(merchantId, userId);

        if (user.holds(Role.OWNER) && !roles.contains(Role.OWNER)) {
            refuseToStrandTheMerchant(merchantId);
        }

        user.holdOnly(roles);
        log.info("user {} of merchant {} now holds {}", userId, merchantId, roles);
        return UserResponse.of(user);
    }

    @Transactional
    public void remove(UUID merchantId, UUID userId) {
        UserAccount user = mine(merchantId, userId);

        if (user.holds(Role.OWNER)) {
            refuseToStrandTheMerchant(merchantId);
        }

        users.delete(user);
        log.info("removed user {} from merchant {}", userId, merchantId);
    }

    /**
     * A merchant with no owner is an account nobody can administer, recoverable only by hand
     * in the database. Cheaper to refuse the last step than to undo it.
     */
    private void refuseToStrandTheMerchant(UUID merchantId) {
        if (users.countHolding(merchantId, Role.OWNER) <= 1) {
            throw new UnprocessableException(
                    "A merchant must always have an owner. Make somebody else an owner first.");
        }
    }

    private UserAccount mine(UUID merchantId, UUID userId) {
        return users.findByIdAndMerchantId(userId, merchantId)
                .orElseThrow(() -> new NotFoundException("No user with that id."));
    }

    private static Role first(Set<Role> roles) {
        return roles.iterator().next();
    }
}
