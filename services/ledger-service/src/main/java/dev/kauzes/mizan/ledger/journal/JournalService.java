package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.ledger.account.Account;
import dev.kauzes.mizan.ledger.account.AccountRepository;
import dev.kauzes.mizan.ledger.journal.JournalRequests.EntryResponse;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostEntryRequest;
import dev.kauzes.mizan.ledger.journal.JournalRequests.PostingRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writing to the books, and reading what is written. */
@Service
public class JournalService {

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;

    public JournalService(JournalEntryRepository entries, AccountRepository accounts) {
        this.entries = entries;
        this.accounts = accounts;
    }

    @Transactional
    public EntryResponse post(UUID merchantId, PostEntryRequest request) {
        JournalEntry entry = new JournalEntry(
                merchantId,
                request.description().trim(),
                request.occurredAt(),
                correctedEntry(merchantId, request.corrects()));

        for (PostingRequest posting : request.postings()) {
            entry.post(accountOf(merchantId, posting.accountId()), posting.amount());
        }

        // Checked here so the caller is told which currency is out and by how much. The
        // database checks it again at commit, which is the check that holds against anything
        // writing to the table, including something that is not this application.
        entry.requireBalanced();

        entries.save(entry);
        log.info(
                "posted entry {} for merchant {} with {} postings",
                entry.id(),
                merchantId,
                entry.postings().size());
        return EntryResponse.of(entry);
    }

    @Transactional(readOnly = true)
    public List<EntryResponse> list(UUID merchantId) {
        return entries.findByMerchantIdOrderByOccurredAtDescRecordedAtDesc(merchantId).stream()
                .map(EntryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public EntryResponse find(UUID merchantId, UUID entryId) {
        return entries
                .findByIdAndMerchantId(entryId, merchantId)
                .map(EntryResponse::of)
                .orElseThrow(() -> new NotFoundException("No entry with that id."));
    }

    /**
     * An account this merchant does not own is not an account this entry may name. Looked up
     * scoped rather than looked up and then checked, so a platform account is refused here
     * the same way another merchant's is: it is simply not one of this merchant's.
     */
    private Account accountOf(UUID merchantId, UUID accountId) {
        return accounts
                .findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new UnprocessableException(
                        "No account " + accountId + " in this merchant's books."));
    }

    private JournalEntry correctedEntry(UUID merchantId, UUID corrects) {
        if (corrects == null) {
            return null;
        }
        return entries
                .findByIdAndMerchantId(corrects, merchantId)
                .orElseThrow(() -> new UnprocessableException(
                        "No entry " + corrects + " to correct in this merchant's books."));
    }
}
