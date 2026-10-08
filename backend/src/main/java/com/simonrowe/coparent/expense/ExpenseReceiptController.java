package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.shared.CoparentIds;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Upload, download and removal of expense receipts. Membership is checked on every call. */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/expenses/{expenseId}/receipts")
public class ExpenseReceiptController {

  private final ExpenseReceiptService receipts;

  public ExpenseReceiptController(final ExpenseReceiptService receipts) {
    this.receipts = receipts;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  ExpenseController.ExpenseResponse upload(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @RequestParam("file") final MultipartFile file) {
    final byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The receipt could not be read");
    }
    return ExpenseController.ExpenseResponse.from(receipts.add(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), file.getOriginalFilename(), bytes));
  }

  /**
   * Serves a receipt inline. {@code nosniff} and a sandboxing CSP stop a browser treating the
   * bytes as anything but the type they were proven to be on upload; {@code no-store} comes
   * from {@code CoparentFeatureFilter}.
   */
  @GetMapping("/{receiptId}")
  ResponseEntity<byte[]> download(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @PathVariable final String receiptId) {
    final ExpenseReceiptService.ReceiptFile file = receipts.read(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), CoparentIds.parse(receiptId));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.receipt().contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"receipt-%d.%s\""
            .formatted(file.position(), ExpenseReceiptService.extension(
                file.receipt().contentType())))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox")
        .body(file.bytes());
  }

  @DeleteMapping("/{receiptId}")
  ExpenseController.ExpenseResponse remove(
      @PathVariable final String familyId,
      @PathVariable final String expenseId,
      @PathVariable final String receiptId) {
    return ExpenseController.ExpenseResponse.from(receipts.remove(CoparentIds.parse(familyId),
        CoparentIds.parse(expenseId), CoparentIds.parse(receiptId)));
  }
}
