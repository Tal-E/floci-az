package io.floci.az.services.policy.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How Resource Manager applies a PATCH to a stored resource, so that a policy judges the resource the
 * write produces rather than the fragment it carries: {@code properties} are merged recursively (a null
 * removes a property), {@code tags} are replaced as a whole, and any other top-level value is replaced.
 */
final class PatchMerge {

    private PatchMerge() {
    }

    /** The resource {@code patch} produces from {@code stored}. */
    static Map<String, Object> apply(Map<String, Object> stored, Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>(stored);
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                merged.remove(key);
            } else if (!key.equalsIgnoreCase("tags") && value instanceof Map<?, ?> given
                    && merged.get(key) instanceof Map<?, ?> existing) {
                merged.put(key, mergeObjects(existing, given));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    /**
     * The PATCH that turns {@code stored} into {@code after}: what the original PATCH changed plus whatever an
     * append wrote, in the same shape {@link #apply} reads ({@code tags} whole, {@code properties} as a merge).
     */
    static Map<String, Object> diff(Map<String, Object> stored, Map<String, Object> after) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object previous = stored.get(key);
            if (Objects.equals(previous, value)) {
                continue;
            }
            if (!key.equalsIgnoreCase("tags") && value instanceof Map<?, ?> now && previous instanceof Map<?, ?> was) {
                patch.put(key, diffObjects(was, now));
            } else {
                patch.put(key, value);
            }
        }
        for (String key : stored.keySet()) {
            if (!after.containsKey(key)) {
                patch.put(key, null);
            }
        }
        return patch;
    }

    private static Map<String, Object> mergeObjects(Map<?, ?> existing, Map<?, ?> given) {
        Map<String, Object> merged = new LinkedHashMap<>();
        existing.forEach((key, value) -> merged.put(String.valueOf(key), value));
        for (Map.Entry<?, ?> entry : given.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                merged.remove(key);
            } else if (value instanceof Map<?, ?> nested && merged.get(key) instanceof Map<?, ?> current) {
                merged.put(key, mergeObjects(current, nested));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private static Map<String, Object> diffObjects(Map<?, ?> was, Map<?, ?> now) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : now.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            Object previous = was.get(key);
            if (Objects.equals(previous, value)) {
                continue;
            }
            if (value instanceof Map<?, ?> nested && previous instanceof Map<?, ?> current) {
                patch.put(key, diffObjects(current, nested));
            } else {
                patch.put(key, value);
            }
        }
        for (Object key : was.keySet()) {
            if (!now.containsKey(key)) {
                patch.put(String.valueOf(key), null);
            }
        }
        return patch;
    }
}
