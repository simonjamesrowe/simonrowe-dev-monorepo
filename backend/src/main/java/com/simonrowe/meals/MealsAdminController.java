package com.simonrowe.meals;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Loading recipe bundles into the library. */
@RestController
@RequestMapping("/api/meals/admin")
public class MealsAdminController {

  private final MealBundleImporter importer;

  public MealsAdminController(final MealBundleImporter importer) {
    this.importer = importer;
  }

  @PostMapping("/bundles")
  public MealBundleImporter.ImportReport importBundle(
      @RequestParam("file") final MultipartFile file
  ) {
    if (file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
    }
    try {
      return importer.importBundle(file.getBytes());
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the upload");
    }
  }
}
