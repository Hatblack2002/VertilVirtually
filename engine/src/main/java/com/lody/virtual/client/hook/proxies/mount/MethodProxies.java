package com.lody.virtual.client.hook.proxies.mount;

import android.os.Build;

import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.os.VUserHandle;

import java.io.File;
import java.lang.reflect.Method;

/**
 * @author Lody
 */

class MethodProxies {

    static class GetVolumeList extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getVolumeList";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args == null || args.length == 0) {
                return super.beforeCall(who, method, args);
            }
            if (args[0] instanceof Integer) {
                if (Build.VERSION.SDK_INT >= 29) {
                    // Android 10+: IStorageManager.getVolumeList(userId, callingPackage, flags).
                    // El primer parámetro es USER ID, no uid. Asignarle getRealUid() (p. ej. 10265)
                    // producía userId=10265 != callingUserId=0 y el servicio real lanzaba
                    // SecurityException("Need INTERACT_ACROSS_USERS to get volumes for another user")
                    // en CADA bind del clon: crash loop en com.vertil.virtualapp:p0 observado en
                    // Galaxy S20 (Android 13) vía VEnvironment.getVirtualStorageBaseDir
                    // <- VClientImpl.setupVirtualStorage. El clon nunca llegaba a arrancar.
                    args[0] = VUserHandle.getUserId(getRealUid());
                } else {
                    // Android 9-: getVolumeList(uid, packageName, flags) — uid real del host.
                    args[0] = getRealUid();
                }
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            return super.beforeCall(who, method, args);
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            return result;
        }
    }

    static class Mkdirs extends MethodProxy {

        @Override
        public String getMethodName() {
            return "mkdirs";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return super.beforeCall(who, method, args);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                return super.call(who, method, args);
            }
            String path;
            if (args.length == 1) {
                path = (String) args[0];
            } else {
                path = (String) args[1];
            }
            File file = new File(path);
            if (!file.exists() && !file.mkdirs()) {
                return -1;
            }
            return 0;
        }
    }
}
