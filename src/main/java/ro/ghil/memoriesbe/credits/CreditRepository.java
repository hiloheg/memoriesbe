package ro.ghil.memoriesbe.credits;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access (JdbcClient) for the credit ledger: every purchase, free credit, spend and refund is one row. */
@Repository
public class CreditRepository {
    /** Credits left, by kind: `trial` is the free credit, `full` are purchased. */
    public record Balance(int trial, int full) {
        public int total() { return trial + full; }
    }

    public record LedgerRow(int delta, String kind, String reason, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    CreditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Waits for any other transaction spending this owner's credits; released at commit. */
    public void lock(String ownerSub) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param(ownerSub).query().singleRow();
    }

    public Balance balance(String ownerSub) {
        int[] n = new int[2];
        jdbc.sql("select kind, coalesce(sum(delta), 0) as n from credit_ledger where owner_sub = ? group by kind")
                .param(ownerSub)
                .query((rs, i) -> {
                    n["trial".equals(rs.getString("kind")) ? 0 : 1] = rs.getInt("n");
                    return 0;
                }).list();
        return new Balance(n[0], n[1]);
    }

    /** Adds one ledger row. Returns false (and adds nothing) when a row with the same {@code ref} already exists. */
    public boolean insert(String ownerSub, int delta, String kind, String reason, String ref, UUID eventId) {
        return jdbc.sql("""
                insert into credit_ledger (owner_sub, delta, kind, reason, ref, event_id) values (?, ?, ?, ?, ?, ?)
                on conflict (ref) do nothing""")
                .params(ownerSub, delta, kind, reason, ref, eventId).update() == 1;
    }

    /** True when the owner has a purchase that was not refunded. */
    public boolean hasUnrefundedPurchase(String ownerSub) {
        return jdbc.sql("""
                select exists (
                  select 1 from credit_ledger p
                  where p.owner_sub = ? and p.reason = 'purchase'
                    and not exists (select 1 from credit_ledger r
                                    where r.owner_sub = p.owner_sub and r.reason = 'refund'
                                      and r.ref = 'ls-refund:' || substr(p.ref, 10)))""")
                .param(ownerSub).query(Boolean.class).single();
    }

    /** The account a ledger row (found by its ref, for example {@code ls-order:123}) belongs to, or null. */
    public String ownerOfRef(String ref) {
        return jdbc.sql("select owner_sub from credit_ledger where ref = ?").param(ref).query(String.class).optional().orElse(null);
    }

    /** When credits were switched on; every account counts as unlocked for 30 days after this. */
    public Instant launchedAt() {
        return jdbc.sql("select launched_at from credit_launch limit 1")
                .query((rs, i) -> rs.getObject("launched_at", OffsetDateTime.class).toInstant()).single();
    }

    public List<LedgerRow> history(String ownerSub, int limit) {
        return jdbc.sql("select delta, kind, reason, created_at from credit_ledger where owner_sub = ? order by created_at desc limit ?")
                .params(ownerSub, limit).query(LedgerRow.class).list();
    }

    public void insertPaymentEvent(String orderId, String eventName, String outcome, String payloadJson) {
        jdbc.sql("insert into payment_events (order_id, event_name, outcome, payload) values (?, ?, ?, ?::jsonb)")
                .params(orderId, eventName, outcome, payloadJson).update();
    }
}
