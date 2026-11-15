package com.voicepulse.routing.infrastructure.ws;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ClaimQueuedCallUseCase;
import com.voicepulse.routing.domain.ports.inbound.CompleteHandoffUseCase;
import com.voicepulse.routing.domain.ports.inbound.InspectRoutingQueueUseCase;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.ClaimCallRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.CompleteHandoffRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.ConsoleBootstrapConfigDto;
import com.voicepulse.routing.infrastructure.ws.dto.HandoffStateDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.QueueOverviewDto;
import com.voicepulse.routing.infrastructure.ws.mapper.OperatorConsoleMapper;
import com.voicepulse.routing.infrastructure.ws.mapper.TranscriptContextExtractor;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Primary inbound WebFlux REST control-plane adapter for Operator Web Console presence management,
 * priority queue inspection, manual call claiming, and handoff completion.
 *
 * <p>All synchronous domain port calls execute offloaded onto Java 21 Virtual Threads via {@link
 * VirtualThreadTaskOffloader}.
 */
@Slf4j
@RestController
@RequestMapping("/api/operators")
@RequiredArgsConstructor
public class OperatorRestController {

    private final @NonNull ManageOperatorPresenceUseCase manageOperatorPresenceUseCase;
    private final @NonNull ClaimQueuedCallUseCase claimQueuedCallUseCase;
    private final @NonNull CompleteHandoffUseCase completeHandoffUseCase;
    private final @NonNull InspectRoutingQueueUseCase inspectRoutingQueueUseCase;
    private final @NonNull OperatorConsoleSessionRegistry sessionRegistry;
    private final @NonNull OperatorConsoleMapper mapper;
    private final @NonNull TranscriptContextExtractor transcriptContextExtractor;
    private final @NonNull OperatorConsoleProperties consoleProperties;
    private final @NonNull VirtualThreadTaskOffloader taskOffloader;
    private final @NonNull Clock clock;

    /** Returns bootstrap metadata required by the single-page Operator Web Console UI. */
    @GetMapping("/console/config")
    public Mono<ConsoleBootstrapConfigDto> getConsoleConfig() {
        List<String> skills = Arrays.stream(SkillGroup.values()).map(SkillGroup::name).toList();
        List<String> statuses =
                Arrays.stream(OperatorAvailabilityStatus.values())
                        .map(OperatorAvailabilityStatus::name)
                        .toList();
        return Mono.just(
                new ConsoleBootstrapConfigDto(
                        consoleProperties.gatewayWsBaseUrl(),
                        consoleProperties.heartbeatInterval().toMillis(),
                        skills,
                        statuses));
    }

    /** Registers or updates an operator's availability status and skill-group memberships. */
    @PutMapping("/{operatorId}/presence")
    public Mono<OperatorPresenceDto> updatePresence(
            @PathVariable("operatorId") String rawOperatorId,
            @RequestBody OperatorPresenceRequestDto request) {
        OperatorId operatorId = OperatorId.fromString(rawOperatorId);
        if (request == null) {
            throw new IllegalArgumentException("Presence request body must not be null");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        OperatorAvailabilityStatus status = parseStatus(request.status());
        Set<SkillGroup> skills = parseSkillGroups(request.skillGroups());

        return taskOffloader.supply(
                () -> {
                    OperatorPresence updated =
                            manageOperatorPresenceUseCase.updatePresence(
                                    operatorId,
                                    request.displayName().trim(),
                                    status,
                                    skills,
                                    clock.instant());
                    return mapper.toPresenceDto(updated);
                });
    }

    /** Refreshes an active operator's Redis presence heartbeat TTL. */
    @PostMapping("/{operatorId}/heartbeat")
    public Mono<OperatorPresenceDto> refreshHeartbeat(
            @PathVariable("operatorId") String rawOperatorId) {
        OperatorId operatorId = OperatorId.fromString(rawOperatorId);
        return taskOffloader.supply(
                () ->
                        manageOperatorPresenceUseCase
                                .refreshHeartbeat(operatorId, clock.instant())
                                .map(mapper::toPresenceDto)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Operator not registered: " + operatorId)));
    }

    /** Retrieves the current presence snapshot for a registered operator. */
    @GetMapping("/{operatorId}/presence")
    public Mono<OperatorPresenceDto> getPresence(@PathVariable("operatorId") String rawOperatorId) {
        OperatorId operatorId = OperatorId.fromString(rawOperatorId);
        return taskOffloader.supply(
                () ->
                        manageOperatorPresenceUseCase
                                .getPresence(operatorId)
                                .map(mapper::toPresenceDto)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Operator not found: " + operatorId)));
    }

    /** Lists operators registered for a specific skill group or across all skill groups. */
    @GetMapping
    public Mono<List<OperatorPresenceDto>> listOperators(
            @RequestParam(name = "skillGroup", required = false) String rawSkillGroup) {
        SkillGroup filterSkill =
                rawSkillGroup == null || rawSkillGroup.isBlank()
                        ? null
                        : SkillGroup.fromCode(rawSkillGroup);

        return taskOffloader.supply(
                () -> {
                    if (filterSkill != null) {
                        return manageOperatorPresenceUseCase
                                .listOperatorsBySkillGroup(filterSkill)
                                .stream()
                                .map(mapper::toPresenceDto)
                                .toList();
                    }
                    Map<OperatorId, OperatorPresence> uniqueOperators = new LinkedHashMap<>();
                    for (SkillGroup skillGroup : SkillGroup.values()) {
                        for (OperatorPresence presence :
                                manageOperatorPresenceUseCase.listOperatorsBySkillGroup(
                                        skillGroup)) {
                            uniqueOperators.putIfAbsent(presence.operatorId(), presence);
                        }
                    }
                    return uniqueOperators.values().stream().map(mapper::toPresenceDto).toList();
                });
    }

    /** Inspects current queue depths and queued disruption calls (filtered or global). */
    @GetMapping("/queue")
    public Mono<QueueOverviewDto> getQueueOverview(
            @RequestParam(name = "skillGroup", required = false) String rawSkillGroup) {
        SkillGroup selectedSkill =
                rawSkillGroup == null || rawSkillGroup.isBlank()
                        ? null
                        : SkillGroup.fromCode(rawSkillGroup);

        return taskOffloader.supply(
                () -> {
                    Instant now = clock.instant();
                    Map<SkillGroup, Long> depths =
                            inspectRoutingQueueUseCase.queueDepthsBySkillGroup();
                    List<QueuedTicketItem> items =
                            selectedSkill == null
                                    ? inspectRoutingQueueUseCase.listAllQueued()
                                    : inspectRoutingQueueUseCase.listQueuedBySkillGroup(
                                            selectedSkill);
                    String label = selectedSkill == null ? "ALL" : selectedSkill.name();
                    return mapper.toQueueOverviewDto(
                            label, depths, items, now, transcriptContextExtractor);
                });
    }

    /**
     * Claims either a specific queued call (when {@code callId} is provided) or the
     * highest-priority queued call matching the operator's skills.
     */
    @PostMapping("/{operatorId}/claim")
    public Mono<ResponseEntity<OperatorAssignmentPayloadDto>> claimCall(
            @PathVariable("operatorId") String rawOperatorId,
            @RequestBody(required = false) Mono<ClaimCallRequestDto> requestBodyMono) {
        OperatorId operatorId = OperatorId.fromString(rawOperatorId);
        Mono<ClaimCallRequestDto> resolvedRequest =
                requestBodyMono == null
                        ? Mono.just(new ClaimCallRequestDto(null))
                        : requestBodyMono.defaultIfEmpty(new ClaimCallRequestDto(null));

        return resolvedRequest.flatMap(
                request -> {
                    Optional<CallId> requestedCallId = parseOptionalCallId(request.callId());
                    return taskOffloader.supply(() -> executeClaim(operatorId, requestedCallId));
                });
    }

    /** Marks an active operator handoff as completed and returns the terminal handoff state. */
    @PostMapping("/{operatorId}/calls/{callId}/complete")
    public Mono<HandoffStateDto> completeHandoff(
            @PathVariable("operatorId") String rawOperatorId,
            @PathVariable("callId") String rawCallId,
            @RequestBody(required = false) Mono<CompleteHandoffRequestDto> requestBodyMono) {
        OperatorId operatorId = OperatorId.fromString(rawOperatorId);
        CallId callId = CallId.fromString(rawCallId);
        Mono<CompleteHandoffRequestDto> resolvedRequest =
                requestBodyMono == null
                        ? Mono.just(new CompleteHandoffRequestDto(""))
                        : requestBodyMono.defaultIfEmpty(new CompleteHandoffRequestDto(""));

        return resolvedRequest.flatMap(
                request -> {
                    String notes =
                            request.resolutionNotes() == null ? "" : request.resolutionNotes();
                    return taskOffloader.supply(
                            () -> {
                                HandoffLifecycleState completed =
                                        completeHandoffUseCase.completeHandoff(
                                                callId, operatorId, notes, clock.instant());
                                return mapper.toHandoffStateDto(completed, consoleProperties);
                            });
                });
    }

    /** Retrieves the pre-populated disruption ticket and transcript payload for a call. */
    @GetMapping("/calls/{callId}/ticket")
    public Mono<OperatorAssignmentPayloadDto> getTicketByCallId(
            @PathVariable("callId") String rawCallId) {
        CallId callId = CallId.fromString(rawCallId);
        return taskOffloader.supply(
                () ->
                        findTicketEvent(callId)
                                .map(
                                        ticket ->
                                                mapper.toTicketPayload(
                                                        ticket, transcriptContextExtractor))
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Disruption ticket not found for callId: "
                                                                + callId)));
    }

    private ResponseEntity<OperatorAssignmentPayloadDto> executeClaim(
            OperatorId operatorId, Optional<CallId> requestedCallId) {
        OperatorPresence presence =
                manageOperatorPresenceUseCase
                        .getPresence(operatorId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Operator not registered: " + operatorId));
        if (!presence.status().canAcceptCall()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Operator "
                            + operatorId
                            + " cannot claim calls while in status "
                            + presence.status());
        }

        Instant claimedAt = clock.instant();
        Optional<HandoffAssignment> assignmentOpt =
                requestedCallId
                        .map(
                                callId ->
                                        claimQueuedCallUseCase.claimSpecificCall(
                                                operatorId, callId, claimedAt))
                        .orElseGet(
                                () ->
                                        claimQueuedCallUseCase.claimNextQueuedCall(
                                                operatorId, claimedAt));

        if (assignmentOpt.isEmpty()) {
            if (requestedCallId.isPresent()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Requested call is not currently queued: " + requestedCallId.get());
            }
            return ResponseEntity.noContent().build();
        }

        HandoffAssignment assignment = assignmentOpt.get();
        OperatorAssignmentPayloadDto payload = resolveClaimedPayload(operatorId, assignment);
        return ResponseEntity.ok(payload);
    }

    private OperatorAssignmentPayloadDto resolveClaimedPayload(
            OperatorId operatorId, HandoffAssignment assignment) {
        Optional<OperatorAssignmentPayloadDto> cached =
                sessionRegistry
                        .getActiveAssignment(operatorId)
                        .filter(dto -> assignment.callId().toString().equals(dto.callId()));
        if (cached.isPresent()) {
            return cached.get();
        }
        TicketFormFilledEvent ticketEvent =
                findTicketEvent(assignment.callId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Disruption ticket missing for claimed call: "
                                                        + assignment.callId()));
        return mapper.toAssignmentPayload(
                assignment, ticketEvent, consoleProperties, transcriptContextExtractor);
    }

    private Optional<TicketFormFilledEvent> findTicketEvent(CallId callId) {
        Optional<TicketFormFilledEvent> persisted =
                inspectRoutingQueueUseCase.findTicketByCallId(callId);
        if (persisted.isPresent()) {
            return persisted;
        }
        return inspectRoutingQueueUseCase.listAllQueued().stream()
                .filter(item -> callId.equals(item.callId()))
                .map(QueuedTicketItem::ticketEvent)
                .findFirst();
    }

    private static OperatorAvailabilityStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        try {
            return OperatorAvailabilityStatus.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown availability status: " + rawStatus, ex);
        }
    }

    private static Set<SkillGroup> parseSkillGroups(Set<String> rawSkills) {
        if (rawSkills == null || rawSkills.isEmpty()) {
            throw new IllegalArgumentException("skillGroups must contain at least one skill group");
        }
        return rawSkills.stream().map(SkillGroup::fromCode).collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<CallId> parseOptionalCallId(String rawCallId) {
        if (rawCallId == null || rawCallId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(CallId.fromString(rawCallId.trim()));
    }
}
