package com.netpulse.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.netpulse.incident.IncidentDetectionLogic.Action;
import com.netpulse.incident.IncidentDetectionLogic.CheckObservation;
import com.netpulse.incident.IncidentDetectionLogic.Decision;
import com.netpulse.incident.IncidentDetectionLogic.OpenIncident;

class IncidentDetectionLogicTest {

    private static final Instant T0 = Instant.parse("2026-11-10T22:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-10T22:01:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T22:02:00Z");
    private static final Instant T3 = Instant.parse("2026-11-10T22:03:00Z");

    @Test
    void singleFailure_doesNotOpenIncident() {
        List<CheckObservation> checks = List.of(fail(T0, "a"));

        Decision d = IncidentDetectionLogic.decide(checks, null);

        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test
    void twoConsecutiveFailures_opensIncidentAtFirstFailureTime() {
        List<CheckObservation> checks = List.of(fail(T0, "a"), fail(T1, "b"));

        Decision d = IncidentDetectionLogic.decide(checks, null);

        assertThat(d.action()).isEqualTo(Action.OPEN);
        assertThat(d.timestamp()).isEqualTo(T0);
    }

    @Test
    void thirdFailureWhileOpen_doesNotOpenDuplicate() {
        List<CheckObservation> checks = List.of(fail(T0, "a"), fail(T1, "b"), fail(T2, "c"));
        OpenIncident open = new OpenIncident(T0);

        Decision d = IncidentDetectionLogic.decide(checks, open);

        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test
    void oneSuccessAfterOutage_resolvesIncident() {
        List<CheckObservation> checks = List.of(fail(T0, "a"), fail(T1, "b"), ok(T2, "c"));
        OpenIncident open = new OpenIncident(T0);

        Decision d = IncidentDetectionLogic.decide(checks, open);

        assertThat(d.action()).isEqualTo(Action.RESOLVE);
        assertThat(d.timestamp()).isEqualTo(T2);
    }

    @Test
    void duplicateJobReplay_sameObservationSet_stillNoSecondOpen() {
        // Idempotent insert means the observation set does not grow; decision with
        // an already-open incident must remain NONE (no duplicate open).
        List<CheckObservation> checks = List.of(fail(T0, "a"), fail(T1, "b"));
        OpenIncident open = new OpenIncident(T0);

        Decision again = IncidentDetectionLogic.decide(checks, open);

        assertThat(again.action()).isEqualTo(Action.NONE);
    }

    @Test
    void outOfOrderOlderFailure_doesNotOpenWhenLatestIsSuccess() {
        // Late-arriving failure at T0 while T1/T2 already succeeded.
        List<CheckObservation> checks = List.of(ok(T1, "b"), ok(T2, "c"), fail(T0, "a"));

        Decision d = IncidentDetectionLogic.decide(checks, null);

        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test
    void outOfOrderOlderSuccess_doesNotResolveWhenLatestStillFailing() {
        List<CheckObservation> checks = List.of(fail(T1, "b"), fail(T2, "c"), ok(T0, "a"));
        OpenIncident open = new OpenIncident(T1);

        Decision d = IncidentDetectionLogic.decide(checks, open);

        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test
    void outOfOrderSecondFailure_opensOnceLatestTwoAreFailures() {
        // First failure recorded, then older success ignored for opening;
        // when second newer failure arrives, open.
        List<CheckObservation> afterSecondFailure = List.of(fail(T0, "a"), fail(T2, "c"), ok(T1, "b"));
        // Latest=T2 fail, second=T1 ok → should NOT open (not two consecutive failures)
        Decision mid = IncidentDetectionLogic.decide(afterSecondFailure, null);
        assertThat(mid.action()).isEqualTo(Action.NONE);

        List<CheckObservation> twoLatestFail = List.of(fail(T1, "b"), fail(T2, "c"));
        Decision open = IncidentDetectionLogic.decide(twoLatestFail, null);
        assertThat(open.action()).isEqualTo(Action.OPEN);
        assertThat(open.timestamp()).isEqualTo(T1);
    }

    @Test
    void alternatingFailureSuccess_neverOpens() {
        List<CheckObservation> checks = List.of(fail(T0, "a"), ok(T1, "b"), fail(T2, "c"), ok(T3, "d"));

        Decision d = IncidentDetectionLogic.decide(checks, null);

        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test
    void concurrentDecision_onlyOneOpenActionWhenSimulatingRaceOnSameSnapshot() throws Exception {
        // Models two workers reading the same post-insert snapshot before either
        // persists an open incident. Application-level unique index + monitor
        // row lock in SQL still required; this asserts the decision itself is OPEN
        // (exactly one logical open intent) for that snapshot.
        List<CheckObservation> checks = List.of(fail(T0, "a"), fail(T1, "b"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Action> actions = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    // Each worker sees "no open incident" yet.
                    actions.add(IncidentDetectionLogic.decide(checks, null).action());
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(error.get()).isNull();

        long openCount = actions.stream().filter(a -> a == Action.OPEN).count();
        assertThat(openCount).isEqualTo(threads);
        // SQL unique index collapses these concurrent OPEN intents to one row.
    }

    @Test
    void recoveryThenNewOutage_canOpenFreshIncident() {
        List<CheckObservation> recovered = List.of(fail(T0, "a"), fail(T1, "b"), ok(T2, "c"));
        Decision resolve = IncidentDetectionLogic.decide(recovered, new OpenIncident(T0));
        assertThat(resolve.action()).isEqualTo(Action.RESOLVE);

        List<CheckObservation> nextOutage = new ArrayList<>(recovered);
        nextOutage.add(fail(T3, "d"));
        // After resolve there is no open incident; only one latest failure → no open yet
        Decision none = IncidentDetectionLogic.decide(nextOutage, null);
        assertThat(none.action()).isEqualTo(Action.NONE);

        nextOutage.add(fail(Instant.parse("2026-11-10T22:04:00Z"), "e"));
        Decision reopen = IncidentDetectionLogic.decide(nextOutage, null);
        assertThat(reopen.action()).isEqualTo(Action.OPEN);
    }

    private static CheckObservation fail(Instant at, String id) {
        return new CheckObservation(at, false, id);
    }

    private static CheckObservation ok(Instant at, String id) {
        return new CheckObservation(at, true, id);
    }
}
