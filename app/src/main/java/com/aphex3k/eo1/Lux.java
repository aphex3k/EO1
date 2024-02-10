package com.aphex3k.eo1;

/**
 * link: <a href="https://en.wikipedia.org/wiki/Lux">LUX</a>
 */
public enum Lux {
    /**
     * Moonless, overcast night sky
     */
    MOONLESS_OVERCAST(0.0001f),
    /**
     * Moonless, overcast night sky
     */
    MOONLESS(0.002f),
    /**
     * Full moon on a clear night
     */
    FULL_MOON(0.175f),
    /**
     * Dark limit of civil twilight under a clear sky
     */
    TWILIGHT(3.4f),
    /**
     * Public areas with dark surroundings
     */
    PUBLIC(35f),
    /**
     * Family living room lights (Australia, 1998)
     */
    FAMILY_ROOM(50f),
    /**
     * Office building hallway/toilet lighting
     */
    HALLWAY(80f),
    /**
     * Very dark overcast day
     */
    OVERCAST(100f),
    /**
     * Train station platforms
     */
    TRAIN_STATION(150f),
    /**
     * Office lighting
     */
    OFFICE(410f),
    /**
     * Sunrise or sunset on a clear day.
     */
    SUNSET(400f),
    /**
     * Typical TV studio lighting
     */
    TV_STUDIO(1000f),
    /**
     * Full daylight (not direct sun)
     */
    DAYLIGHT(17500f),
    /**
     * Direct sunlight
     */
    SUNLIGHT(32000f),
    MAX(100000f);

    public final float value;
    Lux(float value) {
        this.value = value;
    }
}
