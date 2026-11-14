package codes;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** A converter with a public no-arg constructor, in a package without entity. */
@Converter
public class Trim implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String text) {
        return text == null ? null : text.strip();
    }

    @Override
    public String convertToEntityAttribute(String column) {
        return column;
    }
}
