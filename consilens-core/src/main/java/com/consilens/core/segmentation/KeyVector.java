package com.consilens.core.segmentation;

import lombok.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable vector representing multi-dimensional key values.
 * Used for intelligent table segmentation and checkpoint selection.
 */
@Value
public class KeyVector {

    List<Comparable> values;

    public KeyVector(Comparable... values) {
        this.values = Arrays.asList(values);
    }

    public KeyVector(List<Comparable> values) {
        this.values = new ArrayList<>(values);
    }

    /**
     * Get value at specified dimension.
     */
    public Comparable getValue(int index) {
        return values.get(index);
    }

    /**
     * Get number of dimensions.
     */
    public int getDimensions() {
        return values.size();
    }

    /**
     * Check if this vector is less than another vector in lexicographic order.
     */
    public boolean isLessThan(KeyVector other) {
        if (values.size() != other.values.size()) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }

        for (int i = 0; i < values.size(); i++) {
            int cmp = values.get(i).compareTo(other.values.get(i));
            if (cmp < 0) {
                return true;
            } else if (cmp > 0) {
                return false;
            }
        }
        return false; // vectors are equal
    }

    /**
     * Check if this vector is less than or equal to another vector.
     */
    public boolean isLessThanOrEqual(KeyVector other) {
        return isLessThan(other) || equals(other);
    }

    /**
     * Check if this vector is greater than another vector.
     */
    public boolean isGreaterThan(KeyVector other) {
        return !isLessThanOrEqual(other);
    }

    /**
     * Check if this vector is greater than or equal to another vector.
     */
    public boolean isGreaterThanOrEqual(KeyVector other) {
        return !isLessThan(other);
    }

    /**
     * Convert to string representation.
     */
    @Override
    public String toString() {
        return values.toString();
    }

    /**
     * Create a key vector from a raw bound value (a scalar or a list of scalars).
     */
    public static KeyVector fromKeyObject(Object keyObject) {
        if (keyObject == null) {
            return null;
        }
        if (keyObject instanceof List) {
            List<?> keyList = (List<?>) keyObject;
            List<Comparable> comparableValues = new ArrayList<>();
            for (Object value : keyList) {
                if (value instanceof Comparable) {
                    comparableValues.add((Comparable<?>) value);
                } else {
                    throw new IllegalArgumentException("Key values must be comparable: " + value);
                }
            }
            return new KeyVector(comparableValues);
        } else if (keyObject instanceof Comparable) {
            return new KeyVector((Comparable<?>) keyObject);
        } else {
            throw new IllegalArgumentException("Unsupported key object type: " + keyObject.getClass());
        }
    }

    /**
     * Convert to list.
     */
    public List<Comparable> toList() {
        return new ArrayList<>(values);
    }

    /**
     * Get hash code for this vector.
     */
    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /**
     * Check equality with another vector.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        KeyVector keyVector = (KeyVector) obj;
        return values.equals(keyVector.values);
    }
}