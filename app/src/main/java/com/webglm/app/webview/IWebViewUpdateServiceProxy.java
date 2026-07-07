package com.webglm.app.webview;

import android.content.pm.PackageInfo;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * InvocationHandler for {@code android.webkit.IWebViewUpdateService}.
 *
 * The constructor receives the original IBinder and immediately calls
 * {@code IWebViewUpdateService.Stub.asInterface(binder)} on it to obtain the
 * real service handle (which is itself a BinderProxy wrapper).
 *
 * Every method is delegated to that real service EXCEPT
 * {@code waitForAndGetProvider()} — whose returned object's
 * {@code packageInfo} field is reflectively overwritten with the user's chosen
 * PackageInfo before being returned. WebViewFactory then loads our chosen
 * WebView APK instead of the system default.
 */
public final class IWebViewUpdateServiceProxy implements InvocationHandler {

    private Object remoteService;  // IWebViewUpdateService (after asInterface)

    public IWebViewUpdateServiceProxy(Object remoteBinder, Class<?> stubClass) {
        // remoteBinder is an IBinder at this point. Convert to IWebViewUpdateService.
        this.remoteService = remoteBinder;
        try {
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object svc = asInterface.invoke(null, remoteBinder);
            if (svc != null) {
                this.remoteService = svc;
            }
        } catch (Throwable t) {
            android.util.Log.e("IWebViewUpdateServiceProxy",
                    "asInterface failed; will delegate raw calls to the IBinder", t);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("waitForAndGetProvider".equals(name)) {
            // Call the real service to get the WebViewProviderResponse, then
            // reflectively overwrite its `packageInfo` field with the user's
            // chosen PackageInfo. WebViewFactory then loads our chosen WebView
            // APK instead of the system default.
            Object result = method.invoke(remoteService, args);
            if (result == null) return null;
            PackageInfo chosen = WebViewUtil.getCustomProvider();
            if (chosen != null) {
                try {
                    Field piField = result.getClass().getDeclaredField("packageInfo");
                    piField.setAccessible(true);
                    piField.set(result, chosen);
                    android.util.Log.i("IWebViewUpdateServiceProxy",
                            "Swapped packageInfo to " + chosen.packageName);
                } catch (Throwable t) {
                    android.util.Log.e("IWebViewUpdateServiceProxy",
                            "Failed to swap packageInfo", t);
                }
            } else {
                android.util.Log.w("IWebViewUpdateServiceProxy",
                        "waitForAndGetProvider: no custom provider chosen, returning default");
            }
            return result;
        }
        // Delegate every other IWebViewUpdateService method (getWebViewPackages,
        // getCurrentWebViewPackage, waitForAndGetProviderForUser, Object.equals,
        // Object.hashCode, etc.) to the real service untouched. Pass `args`
        // directly so methods with arguments work correctly — using
        // `(Object[]) null` here would throw IllegalArgumentException for any
        // method that takes args (e.g. equals(Object), getWebViewPackagesForUser(int))
        // and crash the app.
        return method.invoke(remoteService, args);
    }
}
