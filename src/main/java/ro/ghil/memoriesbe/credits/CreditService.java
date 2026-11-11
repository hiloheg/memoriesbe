package ro.ghil.memoriesbe.credits;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.credits.CreditRepository.Balance;
import ro.ghil.memoriesbe.credits.CreditRepository.LedgerRow;

/** Free credit, spending, purchases, refunds and whether an account is unlocked. Balances only ever change by adding ledger rows. */
@Service
public class CreditService {
    /** Every account counts as unlocked this long after credits were switched on, so current users are not cut off at once. */
    private static final Duration LAUNCH_WINDOW = Duration.ofDays(30);

    private final CreditRepository repo;
    private final TransactionOperations tx;
    private final Clock clock;

    @Autowired
    public CreditService(CreditRepository repo, PlatformTransactionManager txManager) {
        this(repo, new TransactionTemplate(txManager), Clock.systemUTC());
    }

    public CreditService(CreditRepository repo, TransactionOperations tx, Clock clock) {
        this.repo = repo;
        this.tx = tx;
        this.clock = clock;
    }

    /** One free credit per account; calling it again does nothing. */
    public void grantWelcome(String sub) {
        repo.insert(sub, 1, "trial", "welcome", "welcome:" + sub, null);
    }

    public Balance balance(String sub) {
        return repo.balance(sub);
    }

    public boolean unlocked(String sub) {
        return repo.hasUnrefundedPurchase(sub) || clock.instant().isBefore(repo.launchedAt().plus(LAUNCH_WINDOW));
    }

    /**
     * Spends one credit and runs {@code work} with the plan that credit pays for, all in one transaction: if the work fails the credit
     * comes back. A purchased credit is used before the free one. Throws 402 when there is nothing to spend.
     */
    public <T> T runSpending(String sub, UUID eventId, Function<Plan, T> work) {
        return tx.execute(status -> {
            repo.lock(sub); // one spender per account at a time, so a double click cannot spend the same credit twice
            Balance balance = repo.balance(sub);
            Plan plan;
            if (balance.full() > 0) plan = Plan.FULL;
            else if (balance.trial() > 0) plan = Plan.TRIAL;
            else throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "You have no credits left. Buy a pack to create an event.");
            repo.insert(sub, -1, plan.key(), "event", null, eventId);
            return work.apply(plan);
        });
    }

    /** False when this order was already added. */
    public boolean applyPurchase(String sub, int credits, String orderId) {
        return repo.insert(sub, credits, "full", "purchase", "ls-order:" + orderId, null);
    }

    public boolean applyRefund(String sub, int credits, String orderId) {
        return repo.insert(sub, -credits, "full", "refund", "ls-refund:" + orderId, null);
    }

    public List<LedgerRow> history(String sub) {
        return repo.history(sub, 50);
    }
}
