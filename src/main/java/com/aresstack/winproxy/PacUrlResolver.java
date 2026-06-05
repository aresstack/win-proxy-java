package com.aresstack.winproxy;

/**
 * Resolves a PAC/WPAD URL.
 */
public interface PacUrlResolver {

    PacUrlResolution resolve();
}
