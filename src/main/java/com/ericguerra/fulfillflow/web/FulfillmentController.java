package com.ericguerra.fulfillflow.web;

import com.ericguerra.fulfillflow.domain.Repositories.PickWaveRepository;
import com.ericguerra.fulfillflow.domain.Repositories.ShipmentRepository;
import com.ericguerra.fulfillflow.domain.PickWave;
import com.ericguerra.fulfillflow.domain.Shipment;
import com.ericguerra.fulfillflow.service.FulfillmentService;
import com.ericguerra.fulfillflow.service.OrderPlaced;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class FulfillmentController {
    private final FulfillmentService fulfillment;
    private final ShipmentRepository shipments;
    private final PickWaveRepository waves;
    private final JobLauncher launcher;
    private final Job waveJob;
    private final Job manifestJob;

    public FulfillmentController(FulfillmentService fulfillment, ShipmentRepository shipments, PickWaveRepository waves, JobLauncher launcher,
                                 @Qualifier("waveJob") Job waveJob, @Qualifier("manifestJob") Job manifestJob) {
        this.fulfillment = fulfillment;
        this.shipments = shipments;
        this.waves = waves;
        this.launcher = launcher;
        this.waveJob = waveJob;
        this.manifestJob = manifestJob;
    }

    /** HTTP intake (the message listener is the primary path). */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<Shipment> receive(@RequestBody OrderPlaced order) {
        return fulfillment.receive(order);
    }

    @GetMapping("/orders/{orderId}")
    public List<Shipment> order(@PathVariable String orderId) {
        List<Shipment> list = shipments.findByOrderIdOrderByCreatedAt(orderId);
        if (list.isEmpty()) throw new NoSuchElementException("order " + orderId);
        return list;
    }

    @PostMapping("/orders/{orderId}/cancel")
    public List<Shipment> cancel(@PathVariable String orderId, @RequestBody(required = false) Map<String, String> body) {
        return fulfillment.cancel(orderId, body == null ? "customer request" : body.getOrDefault("reason", "customer request"));
    }

    @PostMapping("/shipments/{id}/pick")
    public Shipment pick(@PathVariable String id, @RequestBody Map<String, Integer> picked) {
        return fulfillment.recordPick(id, picked);
    }

    @PostMapping("/shipments/{id}/pack")
    public Shipment pack(@PathVariable String id) {
        return fulfillment.pack(id);
    }

    @PostMapping("/shipments/{id}/ship")
    public Shipment ship(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return fulfillment.ship(id, body == null ? null : body.get("trackingNumber"));
    }

    @PostMapping("/jobs/waves")
    public Map<String, Object> runWaves() throws Exception {
        var exec = launcher.run(waveJob, new JobParametersBuilder().addLong("ts", System.currentTimeMillis()).toJobParameters());
        List<PickWave> created = waves.findByJobExecutionId(exec.getId());
        return Map.of("execution", exec.getId(), "status", exec.getStatus().toString(), "waves", created);
    }

    @PostMapping("/jobs/manifest")
    public Map<String, Object> runManifest(@RequestParam String output) throws Exception {
        var exec = launcher.run(manifestJob, new JobParametersBuilder().addString("output", output).addLong("ts", System.currentTimeMillis()).toJobParameters());
        long written = exec.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
        return Map.of("execution", exec.getId(), "status", exec.getStatus().toString(), "written", written);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }
}
