/*
 * Copyright 2018-2024 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.assertj.core.api.Assertions;

/**
 * Shared structural-diff helpers for V1 ↔ V2 parity integration tests.
 *
 * <p>The original parity ITs only compared a small hand-picked subset of fields (e.g., {@code
 * name}, {@code type}, {@code description}). That let real V2 contract regressions slip past — for
 * example, the V2 jobs list briefly returned {@code latestRuns} as a single-element list and {@code
 * dataset_facets: null}, while V1 returned up to 10 runs with populated facets. Neither field was
 * being asserted, so CI stayed green.
 *
 * <p>This helper enforces a stronger contract: V2 responses must match V1 structurally for every
 * shared field, with explicit allowlisted differences.
 *
 * <h2>What gets compared</h2>
 *
 * <ul>
 *   <li>Every field present in V1 must be present in V2 with an equal value (recursively).
 *   <li>V2 may add new fields V1 doesn't have — this is normal (e.g., {@code totalCount} on V2 list
 *       endpoints) — but only fields explicitly allowlisted in {@code v2OnlyKeys} are tolerated; an
 *       unexpected extra V2 field fails the assertion.
 *   <li>V1-only keys (e.g., V1 wraps lists under {@code "jobs"}, V2 may return raw arrays) can be
 *       allowlisted symmetrically via {@code v1OnlyKeys}.
 *   <li>For arrays, when {@code itemKey} is provided, items are matched by that key (e.g., {@code
 *       "name"}) — order-independent — and each pair compared structurally. Otherwise arrays are
 *       compared positionally.
 *   <li>Any key in {@code valueIgnoredKeys} is required to be present in both responses but its
 *       value is not compared (useful for timestamps, generated UUIDs).
 * </ul>
 *
 * <p>All assertion failures include the JSON path (e.g., {@code jobs[0].latestRuns[2].facets}) for
 * fast triage.
 */
public final class V1V2ParityAssertions {

  private V1V2ParityAssertions() {}

  /**
   * Asserts that {@code v2} structurally matches {@code v1}, allowing the configured exceptions.
   */
  public static void assertStructurallyEqual(JsonNode v1, JsonNode v2, ParityConfig config) {
    Deque<String> path = new ArrayDeque<>();
    path.push("$");
    compare(v1, v2, path, config);
  }

  /** Convenience overload — uses a default {@link ParityConfig} with no exceptions. */
  public static void assertStructurallyEqual(JsonNode v1, JsonNode v2) {
    assertStructurallyEqual(v1, v2, ParityConfig.defaults());
  }

  // ---------------------------------------------------------------------------
  // Core comparison
  // ---------------------------------------------------------------------------

  private static void compare(JsonNode v1, JsonNode v2, Deque<String> path, ParityConfig config) {
    String location = pathString(path);

    if (v1 == null || v1.isMissingNode() || v1.isNull()) {
      Assertions.assertThat(v2 == null || v2.isMissingNode() || v2.isNull())
          .as("at %s: V1 is null/missing — V2 must also be null/missing", location)
          .isTrue();
      return;
    }

    Assertions.assertThat(v2)
        .as("at %s: V2 must not be null when V1 is %s", location, v1.getNodeType())
        .isNotNull();
    Assertions.assertThat(v2.isMissingNode() || v2.isNull())
        .as("at %s: V2 is null/missing while V1 has value %s", location, abbreviate(v1))
        .isFalse();

    if (v1.isObject()) {
      Assertions.assertThat(v2.isObject())
          .as("at %s: V1 is object — V2 type was %s", location, v2.getNodeType())
          .isTrue();
      compareObjects((ObjectNode) v1, (ObjectNode) v2, path, config);
      return;
    }

    if (v1.isArray()) {
      Assertions.assertThat(v2.isArray())
          .as("at %s: V1 is array — V2 type was %s", location, v2.getNodeType())
          .isTrue();
      compareArrays((ArrayNode) v1, (ArrayNode) v2, path, config);
      return;
    }

    // Primitive / value node — direct equality.
    Assertions.assertThat(v2.asText())
        .as("at %s: scalar value mismatch (V1=%s, V2=%s)", location, v1, v2)
        .isEqualTo(v1.asText());
  }

  private static void compareObjects(
      ObjectNode v1, ObjectNode v2, Deque<String> path, ParityConfig config) {
    String location = pathString(path);

    // V1-only keys allowed.
    Iterator<Map.Entry<String, JsonNode>> v1Fields = v1.fields();
    while (v1Fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = v1Fields.next();
      String key = entry.getKey();
      if (config.v1OnlyKeys.contains(key)) continue;

      path.push(key);
      try {
        if (config.valueIgnoredKeys.contains(key)) {
          // Existence check only.
          Assertions.assertThat(v2.has(key))
              .as("at %s: V1 has '%s' — V2 must also have it (value not compared)", location, key)
              .isTrue();
        } else {
          Assertions.assertThat(v2.has(key))
              .as(
                  "at %s: V1 has '%s' (%s) — V2 must also have it",
                  location, key, abbreviate(entry.getValue()))
              .isTrue();
          compare(entry.getValue(), v2.get(key), path, config);
        }
      } finally {
        path.pop();
      }
    }

    // V2 must not introduce unexpected new keys.
    Iterator<String> v2Keys = v2.fieldNames();
    while (v2Keys.hasNext()) {
      String key = v2Keys.next();
      if (config.v2OnlyKeys.contains(key)) continue;
      if (v1.has(key)) continue;
      Assertions.fail(
          "at %s: V2 has unexpected key '%s' not present in V1 (allowlist via ParityConfig.v2OnlyKeys if intentional)",
          location, key);
    }
  }

  private static void compareArrays(
      ArrayNode v1, ArrayNode v2, Deque<String> path, ParityConfig config) {
    String location = pathString(path);

    Assertions.assertThat(v2.size())
        .as("at %s: array size mismatch (V1=%d, V2=%d)", location, v1.size(), v2.size())
        .isEqualTo(v1.size());

    if (v1.isEmpty()) return;

    String itemKey = config.arrayItemKeyOverride.get(location);
    if (itemKey == null) itemKey = guessItemKey(v1);

    if (itemKey != null) {
      // Order-independent match by key.
      List<JsonNode> v1Sorted = sortByKey(v1, itemKey);
      List<JsonNode> v2Sorted = sortByKey(v2, itemKey);
      for (int i = 0; i < v1Sorted.size(); i++) {
        path.push("[" + v1Sorted.get(i).path(itemKey).asText() + "]");
        try {
          compare(v1Sorted.get(i), v2Sorted.get(i), path, config);
        } finally {
          path.pop();
        }
      }
    } else {
      // Positional.
      for (int i = 0; i < v1.size(); i++) {
        path.push("[" + i + "]");
        try {
          compare(v1.get(i), v2.get(i), path, config);
        } finally {
          path.pop();
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static List<JsonNode> sortByKey(ArrayNode array, String key) {
    return StreamSupport.stream(array.spliterator(), false)
        .sorted(Comparator.comparing(n -> n.path(key).asText("")))
        .toList();
  }

  private static String guessItemKey(ArrayNode array) {
    if (array.isEmpty() || !array.get(0).isObject()) return null;
    JsonNode first = array.get(0);
    for (String candidate : List.of("name", "id", "uuid", "runId", "namespace")) {
      if (first.has(candidate) && first.get(candidate).isValueNode()) return candidate;
    }
    return null;
  }

  private static String pathString(Deque<String> path) {
    StringBuilder sb = new StringBuilder();
    Iterator<String> it = path.descendingIterator();
    while (it.hasNext()) {
      String segment = it.next();
      if (segment.startsWith("[") || sb.length() == 0) sb.append(segment);
      else sb.append('.').append(segment);
    }
    return sb.toString();
  }

  private static String abbreviate(JsonNode node) {
    String s = String.valueOf(node);
    return s.length() > 80 ? s.substring(0, 77) + "..." : s;
  }

  // ---------------------------------------------------------------------------
  // Configuration
  // ---------------------------------------------------------------------------

  /** Per-endpoint configuration of legitimate V1 ↔ V2 differences. */
  public static final class ParityConfig {
    final Set<String> v1OnlyKeys;
    final Set<String> v2OnlyKeys;
    final Set<String> valueIgnoredKeys;
    final Map<String, String> arrayItemKeyOverride;

    private ParityConfig(
        Set<String> v1OnlyKeys,
        Set<String> v2OnlyKeys,
        Set<String> valueIgnoredKeys,
        Map<String, String> arrayItemKeyOverride) {
      this.v1OnlyKeys = v1OnlyKeys;
      this.v2OnlyKeys = v2OnlyKeys;
      this.valueIgnoredKeys = valueIgnoredKeys;
      this.arrayItemKeyOverride = arrayItemKeyOverride;
    }

    public static ParityConfig defaults() {
      return new Builder().build();
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private Set<String> v1OnlyKeys = Set.of();
      private Set<String> v2OnlyKeys = Set.of("totalCount");
      // Timestamps and version-y identifiers naturally vary across runs/test invocations; existence
      // is what we want to enforce, not byte equality.
      private Set<String> valueIgnoredKeys =
          Set.of(
              "createdAt",
              "updatedAt",
              "lastModifiedAt",
              "transitionedAt",
              "startedAt",
              "endedAt",
              "nominalStartTime",
              "nominalEndTime",
              "currentVersion",
              "version");
      private Map<String, String> arrayItemKeyOverride = Map.of();

      public Builder v1OnlyKeys(String... keys) {
        this.v1OnlyKeys = Set.of(keys);
        return this;
      }

      public Builder v2OnlyKeys(String... keys) {
        this.v2OnlyKeys = Set.of(keys);
        return this;
      }

      public Builder valueIgnoredKeys(String... keys) {
        this.valueIgnoredKeys = Set.of(keys);
        return this;
      }

      public Builder arrayItemKeyOverride(Map<String, String> overrides) {
        this.arrayItemKeyOverride = Map.copyOf(overrides);
        return this;
      }

      public ParityConfig build() {
        return new ParityConfig(v1OnlyKeys, v2OnlyKeys, valueIgnoredKeys, arrayItemKeyOverride);
      }
    }
  }
}
