package com.simonrowe.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "profiles")
public record Profile(
    @Id String id,
    String name,
    String title,
    String headline,
    String description,
    String location,
    String phoneNumber,
    String primaryEmail,
    String secondaryEmail,
    String profileImage,
    String sidebarImage,
    String backgroundImage,
    String mobileBackgroundImage,
    Instant createdAt,
    Instant updatedAt
) {
}
