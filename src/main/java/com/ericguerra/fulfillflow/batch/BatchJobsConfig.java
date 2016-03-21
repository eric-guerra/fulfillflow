package com.ericguerra.fulfillflow.batch;

import com.ericguerra.fulfillflow.domain.OrderStatus;
import com.ericguerra.fulfillflow.domain.PickWave;
import com.ericguerra.fulfillflow.domain.Repositories.PickWaveRepository;
import com.ericguerra.fulfillflow.domain.Repositories.ShipmentRepository;
import com.ericguerra.fulfillflow.domain.Shipment;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Two batch jobs:
 *
 * <b>waveJob</b>: a tasklet that plans pick waves for every ALLOCATED shipment and moves them to WAVED
 * in one transaction (waves must be all-or-nothing for the floor).
 *
 * <b>manifestJob</b>: chunk-oriented export of shipped packages to the carrier's manifest CSV,
 * restartable from the last committed chunk if it dies mid-file.
 */
@Configuration
public class BatchJobsConfig {

    @Bean
    WavePlanner wavePlanner(@Value("${fulfillflow.wave.max-shipments:40}") int maxShipments,
                            @Value("${fulfillflow.wave.max-units:200}") int maxUnits) {
        // Demo bin locations: the SKU prefix encodes the aisle. Production would look this up in the WMS.
        return new WavePlanner(maxShipments, maxUnits, sku -> sku.length() >= 3 ? sku.substring(0, 3) : sku);
    }

    @Bean
    Step planWavesStep(JobRepository jobs, PlatformTransactionManager tx, ShipmentRepository shipments,
                       PickWaveRepository waves, WavePlanner planner, Clock clock) {
        return new StepBuilder("planWaves", jobs).tasklet((contribution, chunkContext) -> {
            Long executionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
            List<Shipment> allocated = shipments.findByStatus(OrderStatus.ALLOCATED);
            Instant now = clock.instant();
            for (WavePlanner.Wave w : planner.plan(allocated)) {
                String pickList = w.pickList().entrySet().stream().map(e -> e.getKey() + "x" + e.getValue()).collect(Collectors.joining(","));
                PickWave wave = waves.save(new PickWave(w.nodeId(), w.shipMethod(), w.shipments().size(), w.units(), pickList, executionId, now));
                for (Shipment s : w.shipments()) {
                    s.assignWave(wave.getId());
                    s.transition(OrderStatus.WAVED, "wave " + wave.getId().substring(0, 8), now);
                }
                contribution.incrementWriteCount(w.shipments().size());
            }
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Job waveJob(JobRepository jobs, Step planWavesStep) {
        return new JobBuilder("waveJob", jobs).incrementer(new RunIdIncrementer()).start(planWavesStep).build();
    }

    // ---------------- carrier manifest ----------------

    public record ManifestRow(String trackingNumber, String shipmentId, String orderId, String nodeId, String shipMethod, String destZip, int units) {}

    @Bean
    @StepScope
    JpaPagingItemReader<Shipment> shippedReader(EntityManagerFactory emf) {
        return new JpaPagingItemReaderBuilder<Shipment>()
                .name("shippedReader")
                .entityManagerFactory(emf)
                .queryString("select s from Shipment s where s.status = :status order by s.id")
                .parameterValues(Map.of("status", OrderStatus.SHIPPED))
                .pageSize(100)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemWriter<ManifestRow> manifestWriter(@Value("#{jobParameters['output']}") String output) {
        return new FlatFileItemWriterBuilder<ManifestRow>()
                .name("manifestWriter")
                .resource(new FileSystemResource(output))
                .headerCallback(w -> w.write("tracking_number,shipment_id,order_id,origin_node,service,dest_zip,units"))
                .delimited()
                .sourceType(ManifestRow.class)
                .names("trackingNumber", "shipmentId", "orderId", "nodeId", "shipMethod", "destZip", "units")
                .build();
    }

    @Bean
    Step manifestStep(JobRepository jobs, PlatformTransactionManager tx, JpaPagingItemReader<Shipment> shippedReader,
                      FlatFileItemWriter<ManifestRow> manifestWriter) {
        return new StepBuilder("manifest", jobs)
                .<Shipment, ManifestRow>chunk(100, tx)
                .reader(shippedReader)
                .processor(s -> s.getTrackingNumber() == null ? null // filtered: can't manifest without a label
                        : new ManifestRow(s.getTrackingNumber(), s.getId(), s.getOrderId(), s.getNodeId(), s.getShipMethod(), s.getDestZip(),
                                s.getLines().stream().mapToInt(l -> l.getQty()).sum()))
                .writer(manifestWriter)
                .build();
    }

    @Bean
    Job manifestJob(JobRepository jobs, Step manifestStep) {
        return new JobBuilder("manifestJob", jobs).start(manifestStep).build();
    }
}
