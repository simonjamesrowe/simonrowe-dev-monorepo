package com.simonrowe.admin;

import com.simonrowe.common.Image;
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
    Image profileImage,
    Image sidebarImage,
    Image backgroundImage,
    Image mobileBackgroundImage,
    Instant createdAt,
    Instant updatedAt
) {
}
