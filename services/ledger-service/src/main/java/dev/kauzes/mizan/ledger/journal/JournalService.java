package dev.kauzes.mizan.ledger.journal;

import dev.kauzes.mizan.common.error.ConflictException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Writing to the books, and reading what is written. */
@Service
public class JournalService {

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final TransactionTemplate transaction;

    public JournalService(
            JournalEntryRepository entries,
            AccountRepository accounts,
            PlatformTransactionManager transactions) {

        this.entries = entries;
        this.accounts = accounts;
        // Transactions are managed here rather than by an annotation, because a duplicate
        // reference is discovered by a constraint violation, which leaves the transaction
        // that hit it unusable. The replay has to be read in a new one.
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * Posts an entry, once, however many times it is asked for.
     *
     * <p>A caller that does not hear back does not know whether the money moved, so it
     * retries. The reference is what makes that safe: the second call is answered with the
     * first call's entry, identically, so a retry cannot be told from the original.
     */
    public EntryResponse post(UUID merchantId, PostEntryRequest request) {
        String fingerprint = RequestFingerprint.of(request);

        try {
            return transaction.execute(status -> write(merchantId, request, fingerprint));
        } catch (DataIntegrityViolationException alreadyPosted) {
            // Discovered by inserting rather than by asking first: a check would answer for
            // the moment before the insert, and two retries racing would both be told the
            // reference was free.
            // In a transaction of its own, and started here rather than by an annotation:
            // a @Transactional method called from inside the same bean is called directly,
            // not through the proxy, so it would run with no session at all.
            return transaction.execute(
                    status -> replay(merchantId, request.externalReference(), fingerprint));
        }
    }

    private EntryResponse write(UUID merchantId, PostEntryRequest request, String fingerprint) {
        JournalEntry entry = new JournalEntry(
                merchantId,
                request.externalReference().trim(),
                fingerprint,
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

        entries.saveAndFlush(entry);
        log.info(
                "posted entry {} for merchant {} as {} with {} postings",
                entry.id(),
                merchantId,
                entry.externalReference(),
                entry.postings().size());
        return EntryResponse.of(entry);
    }

    /**
     * Answers a retry with what the first call wrote.
     *
     * <p>Unless it is not the same request. A reference reused for different postings is
     * somebody's mistake, and answering it with an unrelated entry would hide the mistake
     * behind a success.
     */
    private EntryResponse replay(UUID merchantId, String reference, String fingerprint) {
        JournalEntry original = entries
                .findByMerchantIdAndExternalReference(merchantId, reference.trim())
                .orElseThrow(() -> new ConflictException(
                        "That reference is taken by an entry this merchant cannot see."));

        if (!original.wasWrittenFrom(fingerprint)) {
            throw new ConflictException(
                    "Reference " + reference.trim() + " was already used for a different entry.");
        }

        log.info("replayed entry {} for reference {}", original.id(), reference.trim());
        return EntryResponse.of(original);
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
