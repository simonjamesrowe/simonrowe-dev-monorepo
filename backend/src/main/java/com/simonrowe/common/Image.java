package com.simonrowe.common;

public record Image(
    String url,
    String name,
    Integer width,
    Integer height,
    String mime,
    ImageFormats formats
) {
}
