package dev.waypoint.visualizer.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.visualizer.traffic.RequestResult.Outcome;
import dev.waypoint.visualizer.traffic.RequestResult.Upstream;
import dev.waypoint.visualizer.traffic.StageMapper.Placement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StageMapperTest {

    private static final Upstream RECEIVED = new Upstream(true, true, "/x", Map.of(), null);

    @ParameterizedTest
    @CsvSource({
            "503, gateway.not_ready, LISTENER, ERROR",
            "413, request.too_large, SIZE, REJECTED",
            "400, request.bad_path, NORMALIZE, REJECTED",
            "401, auth.missing, AUTHENTICATE, REJECTED",
            "401, auth.invalid, AUTHENTICATE, REJECTED",
            "401, auth.expired, AUTHENTICATE, REJECTED",
            "401, auth.unknown_tenant, AUTHENTICATE, REJECTED",
            "403, policy.path_denied, ALLOWLIST, REJECTED",
            "404, route.not_found, ROUTE, REJECTED",
            "503, route.unavailable, ROUTE, ERROR",
            "405, policy.method_denied, METHOD, REJECTED",
            "403, policy.scope_missing, SCOPE, REJECTED",
            "429, policy.quota_exceeded, QUOTA, REJECTED",
            "502, upstream.unavailable, PROXY, ERROR",
            "504, upstream.timeout, PROXY, ERROR",
            "500, internal, PROXY, ERROR",
    })
    void everyGatewayCodeMapsToItsStage(int status, String code, Stage stage, Outcome outcome) {
        assertThat(StageMapper.isGatewayCode(code)).isTrue();
        assertThat(StageMapper.place(status, code, Upstream.notReceived(), true)).isEqualTo(new Placement(stage, outcome));
    }

    @Test
    void unknownCodesAreNotGatewayCodes() {
        assertThat(StageMapper.isGatewayCode(null)).isFalse();
        assertThat(StageMapper.isGatewayCode("upstream.custom")).isFalse();
    }

    @Test
    void anythingTheUpstreamReceivedWasAllowed() {
        assertThat(StageMapper.place(200, null, RECEIVED, true)).isEqualTo(new Placement(Stage.UPSTREAM, Outcome.ALLOWED));
        assertThat(StageMapper.place(503, "upstream.custom", RECEIVED, false))
                .isEqualTo(new Placement(Stage.UPSTREAM, Outcome.ALLOWED));
    }

    @Test
    void withoutAJournalANonErrorResponseIsAssumedToBeFromTheUpstream() {
        assertThat(StageMapper.place(204, null, Upstream.unchecked(), true)).isEqualTo(new Placement(Stage.UPSTREAM, Outcome.ALLOWED));
    }

    @Test
    void serverErrorsThatNeverReachedTheUpstreamBelongToTheFirstHop() {
        assertThat(StageMapper.place(503, null, Upstream.notReceived(), true))
                .isEqualTo(new Placement(Stage.LOAD_BALANCER, Outcome.ERROR));
        assertThat(StageMapper.place(503, null, Upstream.unchecked(), false))
                .isEqualTo(new Placement(Stage.LISTENER, Outcome.ERROR));
    }

    @Test
    void clientErrorsWithoutAGatewayCodeWereRejectedByTheHttpServer() {
        assertThat(StageMapper.place(400, null, Upstream.notReceived(), true))
                .isEqualTo(new Placement(Stage.LISTENER, Outcome.REJECTED));
    }

    @Test
    void unreachableTargetsStopAtTheFirstHop() {
        assertThat(StageMapper.unreachable(true)).isEqualTo(new Placement(Stage.LOAD_BALANCER, Outcome.ERROR));
        assertThat(StageMapper.unreachable(false)).isEqualTo(new Placement(Stage.LISTENER, Outcome.ERROR));
    }

    @Test
    void stageIdsAreLowercase() {
        assertThat(Stage.LOAD_BALANCER.id()).isEqualTo("load_balancer");
    }
}
