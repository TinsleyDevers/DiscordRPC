package com.tinsl.discordrpc.images;

/**
 * An upload or download that failed in a way worth telling the player about.
 * Carries a lang key plus arguments so the screen can show it translated.
 */
public class ImageException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String langKey;
    private final Object[] args;

    public ImageException(String langKey, Object... args) {
        super(langKey);
        this.langKey = langKey;
        this.args = args;
    }

    public String langKey() { return langKey; }
    public Object[] args() { return args; }
}
