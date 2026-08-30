package dev.kauzes.mizan.common.identity;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who a service was told is calling, having been established at the gateway.
 *
 * <p>A service builds this from the headers the gateway set and never from anything else. It
 * is the only answer to "whose data is this", so a query that does not consult it is a query
 * that can read another merchant's rows.
 */
public record Caller(UUID userId, UUID merchantId, Set<Role> roles) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public boolean can(Permission permission) {
        return roles.stream().anyMatch(role -> role.can(permission));
    }

    public boolean actsFor(UUID merchant) {
        return merchantId.equals(merchant);
    }

    /**
     * Reads the comma separated roles a gateway sent.
     *
     * <p>A name this service does not know is ignored rather than refused. Refusing would
     * mean that deploying a gateway that knows a new role, before deploying the services,
     * locks everybody out; ignoring one can only ever grant less than intended.
     */
    public static Set<Role> rolesIn(String header) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (header == null || header.isBlank()) {
            return roles;
        }
        for (String name : header.split(",")) {
            String trimmed = name.trim();
            for (Role role : Role.values()) {
                if (role.name().equals(trimmed)) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}
