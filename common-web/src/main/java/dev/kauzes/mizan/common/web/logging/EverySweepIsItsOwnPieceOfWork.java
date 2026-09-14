package dev.kauzes.mizan.common.web.logging;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Gives each run of a scheduled sweep an id of its own.
 *
 * <p>Nine services run timers: the outbox relay, the webhook dispatcher, the settlement close,
 * the reconciliation complaint, the ledger integrity check, every metrics count. None of them
 * is serving a request, so none of them inherits an id, and the lines they write — which are
 * the lines about money not moving — arrive with nothing to group them by.
 *
 * <p>So one is generated per execution. Not per thread and not per service: a sweep is a piece
 * of work with a beginning and an end, and what somebody wants when a run goes wrong is every
 * line that run wrote and nothing from the run before it.
 *
 * <p>It connects to nothing upstream, and it should not pretend to. A relay publishing an
 * event recorded by a request an hour ago is doing its own work; what ties it back to that
 * request is the trace the outbox row carried (MIZ-78), which is the tool for that job.
 */
public class EverySweepIsItsOwnPieceOfWork implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable work) {
        return () -> {
            CorrelationContext.set(CorrelationContext.generate());
            try {
                work.run();
            } finally {
                // A scheduler thread is reused for every timer in the service. An id left
                // behind would attribute the next sweep's lines to this one.
                CorrelationContext.clear();
            }
        };
    }
}
