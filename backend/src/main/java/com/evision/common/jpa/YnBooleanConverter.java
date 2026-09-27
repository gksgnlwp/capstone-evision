package com.evision.common.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * CHAR(1) 'Y'/'N' 컬럼을 boolean으로 매핑한다. (예: del_yn → deleted)
 */
@Converter
public class YnBooleanConverter implements AttributeConverter<Boolean, String> {

    @Override
    public String convertToDatabaseColumn(Boolean attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute ? "Y" : "N";
    }

    @Override
    public Boolean convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return "Y".equalsIgnoreCase(dbData.trim());
    }
}
