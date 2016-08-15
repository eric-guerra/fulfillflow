package com.ericguerra.fulfillflow.batch;

import com.ericguerra.fulfillflow.domain.Shipment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Groups allocated shipments into pick waves: same node and ship method, earliest promise first,
 * capped by shipment count and units (the size of a pick cart). Each wave's pick list aggregates
 * units per SKU in bin order, so a picker walks the aisles once.
 */
public final class WavePlanner {
    public record Wave(String nodeId, String shipMethod, List<Shipment> shipments, Map<String, Integer> pickList, int units) {}

    private final int maxShipments;
    private final int maxUnits;
    private final Function<String, String> binLocation;

    public WavePlanner(int maxShipments, int maxUnits, Function<String, String> binLocation) {
        this.maxShipments = maxShipments;
        this.maxUnits = maxUnits;
        this.binLocation = binLocation;
    }

    public List<Wave> plan(List<Shipment> allocated) {
        Map<String, List<Shipment>> groups = new TreeMap<>();
        for (Shipment s : allocated) groups.computeIfAbsent(s.getNodeId() + "|" + methodRank(s.getShipMethod()) + s.getShipMethod(), k -> new ArrayList<>()).add(s);

        List<Wave> waves = new ArrayList<>();
        for (List<Shipment> group : groups.values()) {
            group.sort(Comparator.comparing(Shipment::getPromisedBy).thenComparing(Shipment::getCreatedAt).thenComparing(Shipment::getId));
            List<Shipment> current = new ArrayList<>();
            int units = 0;
            for (Shipment s : group) {
                int u = s.getLines().stream().mapToInt(l -> l.getQty()).sum();
                boolean full = !current.isEmpty() && (current.size() >= maxShipments || units + u > maxUnits);
                if (full) {
                    waves.add(build(current, units));
                    current = new ArrayList<>();
                    units = 0;
                }
                current.add(s);
                units += u;
            }
            if (!current.isEmpty()) waves.add(build(current, units));
        }
        return waves;
    }

    /** Faster services are released first within a node. */
    private static int methodRank(String method) {
        return switch (method) {
            case "OVERNIGHT" -> 0;
            case "TWO_DAY" -> 1;
            default -> 2;
        };
    }

    private Wave build(List<Shipment> shipments, int units) {
        Map<String, Integer> bySku = new TreeMap<>();
        shipments.forEach(s -> s.getLines().forEach(l -> bySku.merge(l.getSku(), l.getQty(), Integer::sum)));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        bySku.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Integer> e) -> binLocation.apply(e.getKey())).thenComparing(Map.Entry::getKey))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        Shipment first = shipments.get(0);
        return new Wave(first.getNodeId(), first.getShipMethod(), List.copyOf(shipments), ordered, units);
    }
}
