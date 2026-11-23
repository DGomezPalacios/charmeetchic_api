package com.charmeetchic.kafkaadmin.controller;

import com.charmeetchic.kafkaadmin.dto.AdminRequests;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.CreateTopicRequest;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.IncreasePartitionsRequest;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.UpdateConfigsRequest;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.OperationResult;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicSummary;
import com.charmeetchic.kafkaadmin.service.KafkaAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * Tópicos. Por el gateway: {@code /kafka-admin/topics/...}. Solo ADMIN (SecurityConfig).
 *
 * <pre>
 *   GET    /topics[?internos=true]          listar
 *   GET    /topics/{name}                   describir (particiones, líderes, ISR, configs)
 *   POST   /topics                          crear (particiones, RF, configs)
 *   PATCH  /topics/{name}/configs           modificar configs (retention.ms, cleanup.policy...)
 *   POST   /topics/{name}/partitions        aumentar particiones
 *   DELETE /topics/{name}                   borrar (los del sistema están protegidos)
 * </pre>
 */
@Validated
@RestController
@RequestMapping("/topics")
public class TopicController {

    private static final String NAME_MSG = "nombre de tópico inválido";

    private final KafkaAdminService service;

    public TopicController(KafkaAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<TopicSummary> list(@RequestParam(defaultValue = "false") boolean internos) {
        return service.listTopics(internos);
    }

    @GetMapping("/{name}")
    public TopicDetail describe(@PathVariable @Pattern(regexp = AdminRequests.TOPIC_NAME, message = NAME_MSG) String name) {
        return service.describeTopic(name);
    }

    /** 201 con Location del tópico creado. */
    @PostMapping
    public ResponseEntity<TopicDetail> create(@Valid @RequestBody CreateTopicRequest req) {
        TopicDetail created = service.createTopic(req);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}")
                .buildAndExpand(created.name()).toUri()).body(created);
    }

    @PatchMapping("/{name}/configs")
    public TopicDetail updateConfigs(@PathVariable @Pattern(regexp = AdminRequests.TOPIC_NAME, message = NAME_MSG) String name,
                                     @Valid @RequestBody UpdateConfigsRequest req) {
        return service.updateConfigs(name, req.configs());
    }

    @PostMapping("/{name}/partitions")
    public TopicDetail increasePartitions(@PathVariable @Pattern(regexp = AdminRequests.TOPIC_NAME, message = NAME_MSG) String name,
                                          @Valid @RequestBody IncreasePartitionsRequest req) {
        return service.increasePartitions(name, req.totalPartitions());
    }

    @DeleteMapping("/{name}")
    public OperationResult delete(@PathVariable @Pattern(regexp = AdminRequests.TOPIC_NAME, message = NAME_MSG) String name) {
        return service.deleteTopic(name);
    }
}
