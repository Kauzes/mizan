package dev.kauzes.mizan.ledger.journal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /** Scoped, so another merchant's entry is not found rather than found and hidden. */
    Optional<JournalEntry> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Most recent movement first, which is what somebody looking at a ledger wants. */
    List<JournalEntry> findByMerchantIdOrderByOccurredAtDescRecordedAtDesc(UUID merchantId);
}
