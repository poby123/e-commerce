package com.ecommerce.order.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Finds sagas whose current step got no reply in time. This is what step 2 lacked: an order can no longer be
 * left PENDING forever while a participant may be holding its stock.
 */
@Component
@ConditionalOnProperty(name = "ecommerce.saga.timeout-job.enabled", matchIfMissing = true)
public class SagaTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutJob.class);
    private static final int BATCH = 50;

    private final OrderSagaRepository sagas;
    private final OrderSagaManager manager;
    private final TransactionTemplate transaction;

    public SagaTimeoutJob(OrderSagaRepository sagas, OrderSagaManager manager, TransactionTemplate transaction) {
        this.sagas = sagas;
        this.manager = manager;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${ecommerce.saga.poll-interval:2s}")
    public void run() {
        runAt(Instant.now());
    }

    /** One transaction: the rows stay locked (SKIP LOCKED for other instances) while they are handled. */
    public int runAt(Instant now) {
        Integer handled = transaction.execute(status -> {
            List<String> overdue = sagas.lockOverdue(now, BATCH);
            overdue.forEach(sagaId -> manager.onTimeout(sagaId, now));
            return overdue.size();
        });
        if (handled != null && handled > 0) {
            log.info("Handled {} overdue sagas", handled);
        }
        return handled == null ? 0 : handled;
    }
}
