package dev.kauzes.mizan.ledger.journal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /** Scoped, so another merchant's entry is not found rather than found and hidden. */
    Optional<JournalEntry> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** How a replay finds the entry the first call already wrote. */
    Optional<JournalEntry> findByMerchantIdAndExternalReference(UUID merchantId, String reference);

    /** Most recent movement first, which is what somebody looking at a ledger wants. */
    Page<JournalEntry> findByMerchantIdOrderByOccurredAtDescRecordedAtDesc(
            UUID merchantId, Pageable page);

    /**
     * The entries that touched one account, most recent movement first.
     *
     * <p>An {@code exists} rather than a join. Joining postings would return an entry once per
     * posting it has, which a page then counts as several — and the fix for that, a distinct
     * over a fetched collection, is the shape that makes a database page in memory.
     */
    @Query("""
            select e from JournalEntry e
            where e.merchantId = :merchantId
              and exists (
                select 1 from Posting p
                where p.entry = e and p.account.id = :accountId)
            order by e.occurredAt desc, e.recordedAt desc
            """)
    Page<JournalEntry> touchingAccount(
            @Param("merchantId") UUID merchantId,
            @Param("accountId") UUID accountId,
            Pageable page);
}
