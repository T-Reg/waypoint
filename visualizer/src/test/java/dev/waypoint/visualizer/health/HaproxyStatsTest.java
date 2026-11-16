package dev.waypoint.visualizer.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HaproxyStatsTest {

    static final String CSV = """
            # pxname,svname,qcur,qmax,scur,smax,slim,stot,bin,bout,dreq,dresp,ereq,econ,eresp,wretr,wredis,status,weight
            public,FRONTEND,,,0,1,262124,4,0,0,0,0,0,,,,,OPEN,
            gateways,gateway-1,0,0,0,1,,2,0,0,,0,,0,0,0,0,UP,1
            gateways,gateway-2,0,0,0,1,,2,0,0,,0,,0,0,0,0,DOWN 1/2,1
            gateways,BACKEND,0,0,0,1,26213,4,0,0,0,0,,0,0,0,0,UP,1
            stats,FRONTEND,,,1,1,262124,3,0,0,0,0,0,,,,,OPEN,

            """;

    @Test
    void readsGatewayServerStatusesOnly() {
        assertThat(HaproxyStats.serverStatuses(CSV))
                .containsExactly(java.util.Map.entry("gateway-1", "UP"), java.util.Map.entry("gateway-2", "DOWN 1/2"));
    }

    @Test
    void rejectsNonCsv() {
        assertThatThrownBy(() -> HaproxyStats.serverStatuses("<html>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HaproxyStats.serverStatuses("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HaproxyStats.serverStatuses("# pxname,svname\ngateways,gateway-1"))
                .hasMessageContaining("missing");
    }

    @Test
    void skipsShortRows() {
        assertThat(HaproxyStats.serverStatuses("# pxname,svname,status\ngateways,gateway-1\ngateways,gateway-2,UP"))
                .containsOnlyKeys("gateway-2");
    }
}
