package com.simonrowe.profile;

public record Image(
    String url,
    String name,
    Integer width,
    Integer height,
    String mime,
    ImageFormats formats
) {
}
