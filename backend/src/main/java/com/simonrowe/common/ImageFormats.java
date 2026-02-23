package com.simonrowe.common;

public record ImageFormats(
    ImageFormat thumbnail,
    ImageFormat small,
    ImageFormat medium,
    ImageFormat large
) {
}
