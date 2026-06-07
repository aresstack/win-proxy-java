package com.aresstack.winproxy;

/**
 * Always resolves to DIRECT. Backs {@link ProxyMode#DISABLED}.
 */
public final class DirectProxyResolver {

    public ProxyResult resolve() {
        return ProxyResult.direct("disabled");
    }
}
