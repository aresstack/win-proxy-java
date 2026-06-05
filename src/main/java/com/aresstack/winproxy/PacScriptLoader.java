package com.aresstack.winproxy;

/**
 * Loads PAC script content.
 */
public interface PacScriptLoader {

    String load(String pacUrl);
}
