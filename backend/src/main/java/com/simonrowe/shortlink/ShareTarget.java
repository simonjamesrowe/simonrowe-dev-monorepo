package com.simonrowe.shortlink;

/**
 * Everything the share document needs about the content a slug points at.
 *
 * <p>Flattened out of the three source entities so {@link ShareDocumentRenderer} does not
 * have to know that a blog calls its summary {@code shortDescription} while an article
 * calls it {@code summary}.
 *
 * @param title the content title, unescaped
 * @param description the content summary, unescaped; may be null
 * @param imageUrl the content image as stored — a {@code /uploads/…} path, an absolute
 *     URL, or null. Resolved to something absolute by the renderer.
 * @param destinationPath the site-relative path the link redirects to
 */
public record ShareTarget(
    String title,
    String description,
    String imageUrl,
    String destinationPath
) {
}
