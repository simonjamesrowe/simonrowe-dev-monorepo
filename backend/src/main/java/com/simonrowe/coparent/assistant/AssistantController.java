package com.simonrowe.coparent.assistant;

import com.simonrowe.coparent.shared.CoparentIds;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTTP boundary for private CoParent assistant proposals and individual decisions. */
@RestController
@RequestMapping("/api/coparent")
public class AssistantController {

  private final AssistantProposalService proposals;
  private final AssistantActionExecutor executor;

  public AssistantController(
      final AssistantProposalService proposals,
      final AssistantActionExecutor executor) {
    this.proposals = proposals;
    this.executor = executor;
  }

  @GetMapping("/assistant/config")
  Map<String, Boolean> config() {
    return Map.of("enabled", proposals.enabled());
  }

  @PostMapping(path = "/families/{familyId}/assistant/batches",
      consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  AssistantDtos.Batch analyse(
      @PathVariable final String familyId,
      @RequestParam(required = false) final String text,
      @RequestParam(required = false) final MultipartFile image) {
    return proposals.analyse(CoparentIds.parse(familyId), text, image);
  }

  @GetMapping("/families/{familyId}/assistant/batches")
  List<AssistantDtos.BatchSummary> list(@PathVariable final String familyId) {
    return proposals.list(CoparentIds.parse(familyId));
  }

  @GetMapping("/families/{familyId}/assistant/batches/{batchId}")
  AssistantDtos.Batch get(
      @PathVariable final String familyId,
      @PathVariable final String batchId) {
    return proposals.get(CoparentIds.parse(familyId), CoparentIds.parse(batchId));
  }

  @PatchMapping("/families/{familyId}/assistant/batches/{batchId}/actions/{actionId}")
  AssistantDtos.Action edit(
      @PathVariable final String familyId,
      @PathVariable final String batchId,
      @PathVariable final String actionId,
      @RequestBody final AssistantDtos.EditAction request) {
    return proposals.edit(CoparentIds.parse(familyId), CoparentIds.parse(batchId),
        CoparentIds.parse(actionId), request);
  }

  @PostMapping("/families/{familyId}/assistant/batches/{batchId}/actions/{actionId}/approve")
  AssistantDtos.Action approve(
      @PathVariable final String familyId,
      @PathVariable final String batchId,
      @PathVariable final String actionId,
      @RequestBody final AssistantDtos.VersionRequest request) {
    return executor.approve(CoparentIds.parse(familyId), CoparentIds.parse(batchId),
        CoparentIds.parse(actionId), request.version());
  }

  @PostMapping("/families/{familyId}/assistant/batches/{batchId}/actions/{actionId}/reject")
  AssistantDtos.Action reject(
      @PathVariable final String familyId,
      @PathVariable final String batchId,
      @PathVariable final String actionId,
      @RequestBody final AssistantDtos.VersionRequest request) {
    return proposals.reject(CoparentIds.parse(familyId), CoparentIds.parse(batchId),
        CoparentIds.parse(actionId), request.version());
  }
}
