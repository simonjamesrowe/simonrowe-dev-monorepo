package com.simonrowe.tour;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tour")
public class TourController {

  private final TourService tourService;

  public TourController(final TourService tourService) {
    this.tourService = tourService;
  }

  @GetMapping("/steps")
  public List<TourStep> getSteps() {
    return tourService.getAllStepsOrdered();
  }
}
