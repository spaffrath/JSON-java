package org.json;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates a new instance of the internal map used to hold JSON objects.
 * HashMap is used as default to ensure that elements are unordered per
 * the specification.
 * JSON tends to be a portable transfer format to allow the container
 * implementations to rearrange their items for a faster element
 * retrieval based on associative access.
 * Therefore, an implementation ought not rely on the order of the item.
 */
public interface MapFactory {
    MapFactory DEFAULT = new MapFactory() {
        @Override
        public Map<String, Object> newMap() {
            return new HashMap<>();
        }

        @Override
        public Map<String, Object> newMap(int initialCapacity) {
            if (initialCapacity == -1) {
                return new HashMap<>();
            }
            return new HashMap<>(initialCapacity);
        }
    };

    Map<String, Object> newMap(int initialCapacity);
    Map<String, Object> newMap();
}