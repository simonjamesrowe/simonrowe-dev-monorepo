package com.simonrowe.playlist;

import java.util.List;

/**
 * The whole playlist.
 *
 * @param items queued items in play order, then listened items, most recent first
 * @param autoVoice whether new summaries get audio without being asked
 */
public record PlaylistResponse(List<PlaylistEntry> items, boolean autoVoice) {
}
