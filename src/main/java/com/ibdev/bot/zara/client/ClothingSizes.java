package com.ibdev.bot.zara.client;

import lombok.Getter;

/**
 * @author i.bogatskii
 */
@Getter
public enum ClothingSizes {

    XXS("XXS"),
    XS("XS"),
    S("S"),
    M("M"),
    L("L"),
    XL("XL"),
    XXL("XXL"),
    XXXL("XXXL"),

    EU32("32"),
    EU34("34"),
    EU36("36"),
    EU39("39"),
    EU38("38"),
    EU40("40"),
    EU41("41"),
    EU42("42"),
    EU43("43"),
    EU44("44"),
    EU45("45"),
    EU46("46"),
    EU48("48"),
    EU50("50"),

    WHOLE("*"),

    NOTHING("-");

    private final String size;

    ClothingSizes(final String size) {
        this.size = size;
    }
}
