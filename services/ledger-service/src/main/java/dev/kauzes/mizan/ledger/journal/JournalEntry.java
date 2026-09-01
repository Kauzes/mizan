package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.ledger.account.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One movement of money, as a set of postings that sum to zero.
 *
 * <p>Nothing here changes after it is written, and the database says so as well: an update or
 * a delete against these tables raises. A mistake is corrected by a new entry that names the
 * one it corrects, which leaves both the mistake and the correction visible, because that is
 * what a ledger is for.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corrects", updatable = false)
    private JournalEntry corrects;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST, fetch = FetchType.EAGER)
    private List<Posting> postings = new ArrayList<>();

    protected JournalEntry() {
        // for JPA
    }

    public JournalEntry(
            UUID merchantId, String description, Instant occurredAt, JournalEntry corrects) {

        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.description = Objects.requireNonNull(description, "description");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.recordedAt = Instant.now();
        this.corrects = corrects;
    }

    public void post(Account account, long amount) {
        postings.add(new Posting(this, account, amount));
    }

    /**
     * Refuses an entry that is not a movement of money.
     *
     * <p>The database checks this too, at commit. Both are wanted: this one can say which
     * currency is out and by how much while the request is still in hand, and that one holds
     * whatever writes to the table, including something that is not this application.
     */
    public void requireBalanced() {
        if (postings.size() < 2) {
            throw new UnprocessableException(
                    "An entry moves money between at least two accounts.");
        }

        Map<Currency, Long> byCurrency = new HashMap<>();
        for (Posting posting : postings) {
            if (posting.amount() == 0L) {
                // A posting of nothing is not a movement, and an entry of them balances
                // perfectly while saying nothing at all.
                throw new UnprocessableException("A posting of zero moves nothing.");
            }
            byCurrency.merge(posting.account().currency(), posting.amount(), Math::addExact);
        }

        byCurrency.forEach((currency, total) -> {
            if (total != 0L) {
                throw new UnprocessableException(
                        "The postings do not balance in "
                                + currency.getCurrencyCode()
                                + ": they sum to "
                                + total
                                + " rather than zero.");
            }
        });
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String description() {
        return description;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public UUID correctsId() {
        return corrects == null ? null : corrects.id();
    }

    public List<Posting> postings() {
        return List.copyOf(postings);
    }
}
