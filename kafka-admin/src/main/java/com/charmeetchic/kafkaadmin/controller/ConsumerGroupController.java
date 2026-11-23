package com.charmeetchic.kafkaadmin.controller;

import com.charmeetchic.kafkaadmin.dto.AdminRequests.ResetOffsetsRequest;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupSummary;
import com.charmeetchic.kafkaadmin.service.KafkaAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Consumer groups. Por el gateway: {@code /kafka-admin/consumer-groups/...}. Solo ADMIN.
 *
 * <pre>
 *   GET  /consumer-groups                              listar con su estado
 *   GET  /consumer-groups/{groupId}                    miembros, offsets y LAG por partición
 *   POST /consumer-groups/{groupId}/reset-offsets      mover offsets (el grupo debe estar detenido)
 * </pre>
 */
@Validated
@RestController
@RequestMapping("/consumer-groups")
public class ConsumerGroupController {

    private static final String GROUP_ID = "^[a-zA-Z0-9._-]{1,249}$";
    private static final String GROUP_MSG = "groupId inválido";

    private final KafkaAdminService service;

    public ConsumerGroupController(KafkaAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<GroupSummary> list() {
        return service.listGroups();
    }

    @GetMapping("/{groupId}")
    public GroupDetail describe(@PathVariable @Pattern(regexp = GROUP_ID, message = GROUP_MSG) String groupId) {
        return service.describeGroup(groupId);
    }

    @PostMapping("/{groupId}/reset-offsets")
    public GroupDetail resetOffsets(@PathVariable @Pattern(regexp = GROUP_ID, message = GROUP_MSG) String groupId,
                                    @Valid @RequestBody ResetOffsetsRequest req) {
        return service.resetOffsets(groupId, req);
    }
}
