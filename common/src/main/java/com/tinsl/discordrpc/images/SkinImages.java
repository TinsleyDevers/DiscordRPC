package com.tinsl.discordrpc.images;

import java.util.List;

/**
 * Presence images drawn from the player's own skin. Each one is a link to
 * mc-heads.net with a {uuid} placeholder, which the presence fills in with
 * the logged-in account right before sending, so every player who picks it
 * shows their own skin and it follows skin changes without re-picking.
 */
public final class SkinImages {

    /** Flat face with the hat layer, square. */
    public static final String FACE = "https://mc-heads.net/avatar/{uuid}/512";
    /** Isometric 3D head on a transparent background. */
    public static final String HEAD = "https://mc-heads.net/head/{uuid}/512";

    public static final List<String> ALL = List.of(FACE, HEAD);

    private SkinImages() {}

    /** Lang key for the short label of a skin image ("Face", "3D Head"), or null if it isn't one. */
    public static String labelKey(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.equals(FACE)) return "discordrpc.images.skin.face";
        if (k.equals(HEAD)) return "discordrpc.images.skin.head";
        return null;
    }

    /** True for any image link that still has placeholders to fill in. */
    public static boolean isTemplate(String key) {
        return ImageLibrary.isLink(key) && key.indexOf('{') >= 0;
    }
}
