package com.simonrowe.resume;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

  private final ResumeService resumeService;
  private final ResumePdfRenderer pdfRenderer;

  public ResumeController(
      ResumeService resumeService,
      ResumePdfRenderer pdfRenderer
  ) {
    this.resumeService = resumeService;
    this.pdfRenderer = pdfRenderer;
  }

  @GetMapping
  public ResponseEntity<byte[]> downloadResume() {
    ResumeData data = resumeService.assembleResumeData();
    byte[] pdf = pdfRenderer.render(data);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"simon-rowe-resume.pdf\"")
        .header(HttpHeaders.CACHE_CONTROL,
            "no-cache, no-store, must-revalidate")
        .contentType(MediaType.APPLICATION_PDF)
        .contentLength(pdf.length)
        .body(pdf);
  }
}
