package com.axalotl.async.common.network.login;

/** Binds one native login transport transaction to its player and connection. */
public interface LoginSessionAccess {
    LoginSession tickweave$loginSession();
    void tickweave$loginSession(LoginSession session);
}
