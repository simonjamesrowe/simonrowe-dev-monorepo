package com.simonrowe.profile;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "profiles")
public record Profile(
    @Id String id,
    String name,
    String firstName,
    String lastName,
    String title,
    String headline,
    String description,
    Image profileImage,
    Image sidebarImage,
    Image backgroundImage,
    Image mobileBackgroundImage,
    String location,
    String phoneNumber,
    String primaryEmail,
    String secondaryEmail,
    String cvUrl,
    Instant createdAt,
    Instant updatedAt
) {
}
