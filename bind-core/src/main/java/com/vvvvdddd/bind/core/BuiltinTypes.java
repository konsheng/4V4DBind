package com.vvvvdddd.bind.core;

import java.util.List;

public final class BuiltinTypes {
    private static final List<BindingType> DEFAULTS = List.of(
            new BindingType("qq", 1, "QQ号", true, 10,
                    List.of(new FieldDefinition("value", "QQ号", "qq", true, 12, Mask.PARTIAL)),
                    List.of("value")),
            new BindingType("email", 1, "邮箱", true, 10,
                    List.of(new FieldDefinition("value", "邮箱", "email", true, 254, Mask.PARTIAL)),
                    List.of("value")),
            new BindingType("identity", 1, "户籍", true, 10,
                    List.of(new FieldDefinition("name", "姓名", "name", true, 64, Mask.PARTIAL),
                            new FieldDefinition("id_number", "身份证号码", "cn_id", true, 18, Mask.HIDDEN)),
                    List.of("id_number")),
            new BindingType("phone", 1, "手机号", true, 10,
                    List.of(new FieldDefinition("value", "手机号", "phone", true, 11, Mask.PARTIAL)),
                    List.of("value"))
    );

    private BuiltinTypes() {
    }

    public static List<BindingType> defaults() {
        return DEFAULTS;
    }
}
