package dev.waypoint.visualizer.health;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses HAProxy's {@code ;csv} stats into server name → status (UP, DOWN, MAINT, "UP 1/2", ...). */
final class HaproxyStats {

    static final String BACKEND = "gateways";

    private HaproxyStats() {
    }

    static Map<String, String> serverStatuses(String csv) {
        List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty() || !lines.get(0).startsWith("# ")) {
            throw new IllegalArgumentException("not HAProxy CSV stats");
        }
        List<String> columns = Arrays.asList(lines.get(0).substring(2).split(",", -1));
        int proxy = columns.indexOf("pxname");
        int server = columns.indexOf("svname");
        int status = columns.indexOf("status");
        if (proxy < 0 || server < 0 || status < 0) {
            throw new IllegalArgumentException("HAProxy CSV is missing pxname, svname or status");
        }
        Map<String, String> statuses = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",", -1);
            if (fields.length <= status || !BACKEND.equals(fields[proxy])) {
                continue;
            }
            String name = fields[server];
            if (!name.equals("FRONTEND") && !name.equals("BACKEND")) {
                statuses.put(name, fields[status]);
            }
        }
        return statuses;
    }
}
