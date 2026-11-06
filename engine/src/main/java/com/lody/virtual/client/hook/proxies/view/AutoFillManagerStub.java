package com.lody.virtual.client.hook.proxies.view;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.util.Log;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.android.view.IAutoFillManager;

/**
 * @author 陈磊.
 */

public class AutoFillManagerStub extends BinderInvocationProxy {

    private static final String TAG = "AutoFillManagerStub";

    private static final String AUTO_FILL_NAME = "autofill";
    public AutoFillManagerStub() {
        super(IAutoFillManager.Stub.asInterface, AUTO_FILL_NAME);
    }

    @SuppressLint("WrongConstant")
    @Override
    public void inject() throws Throwable {
        super.inject();
        try {
            Object AutoFillManagerInstance = getContext().getSystemService(AUTO_FILL_NAME);
            if (AutoFillManagerInstance == null) {
                throw new NullPointerException("AutoFillManagerInstance is null.");
            }
            Object AutoFillManagerProxy = getInvocationStub().getProxyInterface();
            if (AutoFillManagerProxy == null) {
                throw new NullPointerException("AutoFillManagerProxy is null.");
            }
            Field AutoFillManagerServiceField = AutoFillManagerInstance.getClass().getDeclaredField("mService");
            AutoFillManagerServiceField.setAccessible(true);
            AutoFillManagerServiceField.set(AutoFillManagerInstance, AutoFillManagerProxy);
        } catch (Throwable tr) {
            // (VERTIL) En Android 12+ AutofillManager ya no expone mService mutable y la
            // inyección deja de aplicar; el autofill no es crítico para el espacio
            // virtual. El stack completo en cada arranque de clon ensuciaba las
            // capturas de logcat del propietario → se degrada a aviso de una línea.
            Log.w(TAG, "AutoFillManager no virtualizado en esta API: " + tr);
            return;
        }

        addMethodProxy(new ReplacePkgAndComponentProxy("startSession"));
        addMethodProxy(new ReplacePkgAndComponentProxy("updateOrRestartSession"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("isServiceEnabled"));
    }

    static class ReplacePkgAndComponentProxy extends ReplaceLastPkgMethodProxy {

        ReplacePkgAndComponentProxy(String name) {
            super(name);
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            replaceLastAppComponent(args, getHostPkg());
            return super.beforeCall(who, method, args);
        }

        static ComponentName replaceLastAppComponent(Object[] args, String hostPkg) {
            int index = ArrayUtils.indexOfLast(args, ComponentName.class);
            if (index != -1) {
                ComponentName orig = (ComponentName) args[index];
                ComponentName newComponent = new ComponentName(hostPkg, orig.getClassName());
                args[index] = newComponent;
                return newComponent;
            }
            return null;
        }
    }


}
