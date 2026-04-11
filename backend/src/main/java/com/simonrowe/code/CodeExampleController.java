package com.simonrowe.code;

import com.simonrowe.admin.AdminCodeExampleRepository;
import com.simonrowe.admin.CodeExample;
import com.simonrowe.admin.Skill;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/code-examples")
public class CodeExampleController {

  private final AdminCodeExampleRepository codeExampleRepository;

  public CodeExampleController(final AdminCodeExampleRepository codeExampleRepository) {
    this.codeExampleRepository = codeExampleRepository;
  }

  @GetMapping("/{id}")
  public Map<String, Object> getById(@PathVariable final String id) {
    CodeExample example = codeExampleRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Code example not found"));
    return toDto(example);
  }

  private Map<String, Object> toDto(final CodeExample example) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", example.id());
    dto.put("title", example.title());
    dto.put("description", example.description());
    dto.put("language", example.language());
    dto.put("code", example.code());
    dto.put("skills", example.skills() != null
        ? example.skills().stream().map(Skill::name).toList()
        : List.of());
    dto.put("createdAt", example.createdAt());
    dto.put("updatedAt", example.updatedAt());
    return dto;
  }
}
