package com.tronprotocol.app.plugins

/**
 * Represents the type of physical quantity a unit measures.
 */
enum class UnitType {
    LENGTH,
    WEIGHT,
    TIME,
    DATA,
    TEMPERATURE
}

/**
 * Represents a unit of measurement.
 *
 * @property type The physical quantity this unit measures.
 * @property baseFactor The factor to multiply by to convert to the base unit of this type.
 *                    (e.g., for Length, base is meters. 1 km = 1000.0 meters).
 *                    For Temperature, this is not used directly in linear conversion.
 * @property names A list of string representations for this unit (e.g., "m", "meter").
 */
enum class Unit(val type: UnitType, val baseFactor: Double, vararg val names: String) {
    // Length (Base: Meter)
    METER(UnitType.LENGTH, 1.0, "m", "meter", "meters"),
    KILOMETER(UnitType.LENGTH, 1000.0, "km", "kilometer", "kilometers"),
    CENTIMETER(UnitType.LENGTH, 0.01, "cm", "centimeter", "centimeters"),
    MILLIMETER(UnitType.LENGTH, 0.001, "mm", "millimeter", "millimeters"),
    MILE(UnitType.LENGTH, 1609.344, "mi", "mile", "miles"),
    FOOT(UnitType.LENGTH, 0.3048, "ft", "foot", "feet"),
    INCH(UnitType.LENGTH, 0.0254, "in", "inch", "inches"),
    YARD(UnitType.LENGTH, 0.9144, "yd", "yard", "yards"),
    NAUTICAL_MILE(UnitType.LENGTH, 1852.0, "nm", "nautical_mile", "nautical_miles"),

    // Weight (Base: Kilogram)
    KILOGRAM(UnitType.WEIGHT, 1.0, "kg", "kilogram", "kilograms"),
    GRAM(UnitType.WEIGHT, 0.001, "g", "gram", "grams"),
    MILLIGRAM(UnitType.WEIGHT, 0.000001, "mg", "milligram", "milligrams"),
    POUND(UnitType.WEIGHT, 0.453592, "lb", "lbs", "pound", "pounds"),
    OUNCE(UnitType.WEIGHT, 0.0283495, "oz", "ounce", "ounces"),
    TON(UnitType.WEIGHT, 1000.0, "ton", "tons", "tonne", "tonnes"),
    STONE(UnitType.WEIGHT, 6.35029, "st", "stone", "stones"),

    // Time (Base: Second)
    SECOND(UnitType.TIME, 1.0, "s", "sec", "second", "seconds"),
    MILLISECOND(UnitType.TIME, 0.001, "ms", "millisecond", "milliseconds"),
    MINUTE(UnitType.TIME, 60.0, "min", "minute", "minutes"),
    HOUR(UnitType.TIME, 3600.0, "h", "hr", "hour", "hours"),
    DAY(UnitType.TIME, 86400.0, "d", "day", "days"),
    WEEK(UnitType.TIME, 604800.0, "w", "week", "weeks"),

    // Data (Base: Byte)
    BYTE(UnitType.DATA, 1.0, "b", "byte", "bytes"),
    KILOBYTE(UnitType.DATA, 1024.0, "kb", "kilobyte", "kilobytes"),
    MEGABYTE(UnitType.DATA, 1048576.0, "mb", "megabyte", "megabytes"),
    GIGABYTE(UnitType.DATA, 1073741824.0, "gb", "gigabyte", "gigabytes"),
    TERABYTE(UnitType.DATA, 1099511627776.0, "tb", "terabyte", "terabytes"),

    // Temperature (Base: Celsius - logical base, handled separately)
    CELSIUS(UnitType.TEMPERATURE, 1.0, "c", "celsius"),
    FAHRENHEIT(UnitType.TEMPERATURE, 1.0, "f", "fahrenheit"),
    KELVIN(UnitType.TEMPERATURE, 1.0, "k", "kelvin");

    companion object {
        private val lookup = entries.flatMap { unit ->
            unit.names.map { name -> name to unit }
        }.toMap()

        fun fromString(name: String): Unit? = lookup[name.lowercase()]
    }
}
