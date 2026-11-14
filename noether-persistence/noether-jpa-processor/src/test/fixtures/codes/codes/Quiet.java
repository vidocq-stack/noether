package codes;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** A converter whose no-arg constructor is private: the access calls it through a handle. */
@Converter
public final class Quiet implements AttributeConverter<Integer, Integer> {
    private Quiet() {
    }

    @Override
    public Integer convertToDatabaseColumn(Integer value) {
        return value == null ? null : -value;
    }

    @Override
    public Integer convertToEntityAttribute(Integer column) {
        return column == null ? null : -column;
    }
}
