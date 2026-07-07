package com.webglm.app.webview;

import android.os.IBinder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * InvocationHandler for the proxied IBinder stored in
 * {@code ServiceManager.sCache["webviewupdate"]}.
 *
 * Every IBinder method is delegated to the original binder EXCEPT
 * {@code queryLocalInterface("android.webkit.IWebViewUpdateService")}, which
 * returns our {@link IWebViewUpdateServiceProxy} — itself a Proxy that
 * intercepts {@code waitForAndGetProvider()} and swaps the packageInfo.
 */
public final class IServiceManagerProxy implements InvocationHandler {

    private final IBinder remoteBinder;

    public IServiceManagerProxy(IBinder remoteBinder) {
        this.remoteBinder = remoteBinder;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("queryLocalInterface".equals(method.getName())
                && args != null && args.length >= 1
                && "android.webkit.IWebViewUpdateService".equals(String.valueOf(args[0]))) {
            Class<?> iWVUCls = Class.forName("android.webkit.IWebViewUpdateService");
            Class<?> stubCls = Class.forName("android.webkit.IWebViewUpdateService$Stub");
            return Proxy.newProxyInstance(
                    remoteBinder.getClass().getClassLoader(),
                    new Class[]{iWVUCls},
                    new IWebViewUpdateServiceProxy(remoteBinder, stubCls)
            );
        }
        // Delegate everything else (transact, pingInterface, getInterfaceDescriptor,
        // asBinder, isBinderAlive, etc.) to the real binder.
        return method.invoke(remoteBinder, args == null ? new Object[0] : args);
    }
}
