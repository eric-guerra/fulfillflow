package com.ericguerra.fulfillflow.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which node(s) ship an order. Pure function of its inputs, so it's easy to test and to replay.
 *
 * Strategy, in priority order:
 *  1. Ship complete from a single node if any can (customers hate split boxes; carriers charge per box).
 *     Among those, prefer DCs over stores (store labor is expensive), then the closest.
 *  2. Otherwise split greedily: repeatedly pick the node that covers the most remaining units
 *     (ties → closer), until everything is covered.
 *  3. Anything no node can cover is backordered.
 * Nodes at or over today's capacity are skipped.
 */
public final class AllocationEngine {
    public record Node(String id, boolean isStore, double lat, double lng, int remainingCapacity) {}

    public record Allocation(String nodeId, Map<String, Integer> lines) {}

    public record Result(List<Allocation> allocations, Map<String, Integer> backordered) {
        public boolean isSplit() {
            return allocations.size() > 1;
        }

        public boolean isComplete() {
            return backordered.isEmpty();
        }
    }

    public Result allocate(Map<String, Integer> order, List<Node> nodes, Map<String, Map<String, Integer>> stockByNode,
                           double destLat, double destLng) {
        List<Node> usable = nodes.stream().filter(n -> n.remainingCapacity() > 0).toList();
        Comparator<Node> byPreference = Comparator.comparing(Node::isStore)
                .thenComparingDouble(n -> distanceKm(n.lat(), n.lng(), destLat, destLng))
                .thenComparing(Node::id);

        // 1. Single-node fulfillment.
        var single = usable.stream()
                .filter(n -> covers(stockByNode.getOrDefault(n.id(), Map.of()), order))
                .min(byPreference);
        if (single.isPresent()) {
            return new Result(List.of(new Allocation(single.get().id(), new LinkedHashMap<>(order))), Map.of());
        }

        // 2. Greedy split by coverage.
        Map<String, Integer> remaining = new LinkedHashMap<>(order);
        Map<String, Map<String, Integer>> stock = new HashMap<>();
        stockByNode.forEach((k, v) -> stock.put(k, new HashMap<>(v)));
        List<Allocation> allocations = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();

        while (!remaining.isEmpty()) {
            Node best = null;
            int bestUnits = 0;
            for (Node n : usable) {
                if (used.contains(n.id())) continue;
                int units = coverable(stock.getOrDefault(n.id(), Map.of()), remaining);
                if (units > bestUnits || (units == bestUnits && units > 0 && best != null && byPreference.compare(n, best) < 0)) {
                    best = n;
                    bestUnits = units;
                }
            }
            if (best == null) break;
            used.add(best.id());
            Map<String, Integer> lines = new LinkedHashMap<>();
            Map<String, Integer> nodeStock = stock.get(best.id());
            for (var it = remaining.entrySet().iterator(); it.hasNext(); ) {
                var line = it.next();
                int take = Math.min(line.getValue(), nodeStock.getOrDefault(line.getKey(), 0));
                if (take <= 0) continue;
                lines.put(line.getKey(), take);
                nodeStock.merge(line.getKey(), -take, Integer::sum);
                if (take == line.getValue()) it.remove();
                else line.setValue(line.getValue() - take);
            }
            allocations.add(new Allocation(best.id(), lines));
        }
        return new Result(allocations, remaining);
    }

    private static boolean covers(Map<String, Integer> stock, Map<String, Integer> order) {
        return order.entrySet().stream().allMatch(e -> stock.getOrDefault(e.getKey(), 0) >= e.getValue());
    }

    private static int coverable(Map<String, Integer> stock, Map<String, Integer> remaining) {
        return remaining.entrySet().stream().mapToInt(e -> Math.min(e.getValue(), stock.getOrDefault(e.getKey(), 0))).sum();
    }

    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Helper for callers: total units per SKU in a set of allocations. */
    public static Map<String, Integer> totals(List<Allocation> allocations) {
        return allocations.stream().flatMap(a -> a.lines().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum));
    }
}
