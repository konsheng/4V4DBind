package com.vvvvdddd.bind.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinTypesTest {
    @Test
    void definesTheFourFixedBindingTypes() {
        List<BindingType> expected = List.of(
                new BindingType("qq", "QQ号", true, 10,
                        List.of(new FieldDefinition("value", "QQ号", "qq", true, 12, Mask.PARTIAL)),
                        List.of("value")),
                new BindingType("email", "邮箱", true, 10,
                        List.of(new FieldDefinition("value", "邮箱", "email", true, 254, Mask.PARTIAL)),
                        List.of("value")),
                new BindingType("identity", "户籍", true, 10,
                        List.of(new FieldDefinition("name", "姓名", "name", true, 64, Mask.PARTIAL),
                                new FieldDefinition("id_number", "身份证号码", "cn_id", true, 18, Mask.HIDDEN)),
                        List.of("id_number")));
        assertEquals(expected, BuiltinTypes.defaults().subList(0, 3));
        assertEquals(List.of("qq", "email", "identity", "phone"),
                BuiltinTypes.defaults().stream().map(BindingType::id).toList());
        BindingType phone = BuiltinTypes.defaults().getLast();
        assertEquals(new BindingType("phone", "手机号", true, 10,
                List.of(new FieldDefinition("value", "手机号", "phone", true, 11, Mask.PARTIAL)),
                List.of("value")), phone);
    }

    @Test
    void defaultsAreImmutableAndAllValidatorsAreRegistered() {
        assertThrows(UnsupportedOperationException.class, () -> BuiltinTypes.defaults().clear());
        assertThrows(UnsupportedOperationException.class, () -> BuiltinTypes.defaults().getFirst().fields().clear());
        ValidatorRegistry validators = new ValidatorRegistry();
        assertDoesNotThrow(() -> validators.validateTypes(new TypeRegistry(BuiltinTypes.defaults())));
    }
}
