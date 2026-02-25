package com.simonrowe.admin;

import java.util.List;

public record ReorderRequest(
    List<String> orderedIds
) {
}
