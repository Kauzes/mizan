package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.payment.PaymentRequests.PaymentResponse;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finding one payment among a merchant's thousands.
 *
 * <p>Every predicate here is composed onto the same first one: this merchant. That is not a
 * filter a caller can influence and it is added first rather than last, so there is no ordering
 * of the others that leaves it off. Tenancy is not one condition among several.
 *
 * <p>Newest first, always. Not a choice a caller makes: sorting is what makes a page number
 * mean anything, and a sort a caller can compose is a sort over a column with no index.
 */
@Component
public class PaymentSearch {

    private final PaymentRepository payments;

    public PaymentSearch(PaymentRepository payments) {
        this.payments = payments;
    }

    /** A page of payments, and enough to know where in the answer it sits. */
    public record Found(List<PaymentResponse> payments, long total, int page, int size) {

        public int totalPages() {
            return size == 0 ? 0 : (int) Math.ceil((double) total / size);
        }

        public boolean hasMore() {
            return (long) (page + 1) * size < total;
        }
    }

    @Transactional(readOnly = true)
    public Found find(UUID merchantId, PaymentQuery query) {
        var page = payments.findAll(
                matching(merchantId, query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));

        return new Found(
                page.getContent().stream().map(PaymentResponse::of).toList(),
                page.getTotalElements(),
                query.page(),
                query.size());
    }

    private static Specification<Payment> matching(UUID merchantId, PaymentQuery query) {
        return (payment, criteria, builder) -> {
            List<Predicate> conditions = new ArrayList<>();

            // First, and never optional. Everything below narrows a set that is already this
            // merchant's; nothing below can widen it back.
            conditions.add(builder.equal(payment.get("merchantId"), merchantId));

            if (!query.statuses().isEmpty()) {
                conditions.add(payment.get("status").in(query.statuses()));
            }
            if (!query.verdicts().isEmpty()) {
                conditions.add(payment.get("riskVerdict").in(query.verdicts()));
            }
            if (query.currency() != null) {
                conditions.add(builder.equal(payment.get("currency"), query.currency()));
            }
            if (query.minAmount() != null) {
                conditions.add(
                        builder.greaterThanOrEqualTo(payment.get("amount"), query.minAmount()));
            }
            if (query.maxAmount() != null) {
                conditions.add(
                        builder.lessThanOrEqualTo(payment.get("amount"), query.maxAmount()));
            }
            if (query.from() != null) {
                conditions.add(
                        builder.greaterThanOrEqualTo(payment.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                // Exclusive, so that asking for a day twice in a row does not show the
                // midnight payment on both.
                conditions.add(builder.lessThan(payment.get("createdAt"), query.to()));
            }
            if (query.reference() != null) {
                // Exact, not a prefix and not a substring. A reference is the merchant's own
                // identifier for one payment and is unique within the merchant; matching
                // loosely would turn "find order-1" into "find order-1, order-10, order-100".
                conditions.add(builder.equal(payment.get("reference"), query.reference()));
            }

            return builder.and(conditions.toArray(new Predicate[0]));
        };
    }
}
