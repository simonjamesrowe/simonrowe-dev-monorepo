package com.simonrowe.portfolio;

import com.simonrowe.admin.ReorderRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Managing portfolio projects. Admin-only through the {@code /api/admin/**} matcher. */
@RestController
@RequestMapping("/api/admin/portfolio")
public class PortfolioAdminController {

  private final PortfolioService service;

  public PortfolioAdminController(final PortfolioService service) {
    this.service = service;
  }

  @GetMapping
  public List<PortfolioProject> list() {
    return service.adminList();
  }

  @GetMapping("/{id}")
  public PortfolioProject get(@PathVariable final String id) {
    return service.adminGet(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PortfolioProject create(@RequestBody final ProjectRequest request) {
    return service.create(request);
  }

  @PutMapping("/{id}")
  public PortfolioProject update(
      @PathVariable final String id,
      @RequestBody final ProjectRequest request
  ) {
    return service.update(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable final String id) {
    service.delete(id);
  }

  @PatchMapping("/reorder")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reorder(@RequestBody final ReorderRequest request) {
    service.reorder(request.orderedIds());
  }
}
