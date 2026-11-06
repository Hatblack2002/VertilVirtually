package com.lody.virtual.server.pm.parser;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Bundle;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;
import com.lody.virtual.helper.utils.VLog;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser de APKs independiente del framework, para Android 12/13/14.
 *
 * Por qué existe: el motor original (VirtualApp2022) parseaba los APKs con
 * android.content.pm.PackageParser vía mirror/reflexión. Google eliminó
 * PackageParser (y PackageParser$PackageUserState) del framework al migrar al
 * stack de ParsingPackageUtils; en el S20 (Android 14) el bloque estático de
 * PackageParserCompat moría en PackageUserState.ctor.newInstance() y con él
 * TODA la instalación virtual (ExceptionInInitializerError ->
 * NoClassDefFoundError en cada reintento; log del 11-05 21:21).
 *
 * Cómo funciona, sin APIs ocultas de PackageParser:
 *  1) PackageManager.getPackageArchiveInfo (API pública) produce los
 *     ApplicationInfo/ActivityInfo/ServiceInfo/ProviderInfo reales del APK,
 *     incluidos meta-data y firmas (GET_SIGNATURES).
 *  2) Los intent-filters, activity-alias, <uses-library>,
 *     <protected-broadcast> y los nombres de split NO los expone la API
 *     pública: se leen del AndroidManifest.xml binario con
 *     AssetManager.openXmlResourceParser (método público), creando el
 *     AssetManager con addAssetPath (oculto, cubierto por la exención
 *     "L" de HiddenApiBypass que el motor ya aplica al arrancar) o, si
 *     falla, con PackageManager.getResourcesForApplication (público).
 *
 * Mantiene el contrato de PackageParserEx.parsePackage: devuelve un VPackage
 * listo para addOwner() y el caché Parcel. Soporta APK único o directorio
 * clúster (base.apk + split_*.apk), que es lo que entrega AppInstaller.
 */
final class ApkParser {

    private static final String TAG = "ApkParser";

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** Firma falsa de respaldo (igual que el motor original). */
    private static final String FAKE_SIG = "308203553082023da0030201020204378edaaa300d06092a864886f70d01010b0500305a310d300b0603550406130466616b65310d300b0603550408130466616b65310d300b0603550407130466616b65310d300b060355040a130466616b65310d300b060355040b130466616b65310d300b0603550403130466616b653020170d3138303533303034343434385a180f32313237313230353034343434385a305a310d300b0603550406130466616b65310d300b0603550408130466616b65310d300b0603550407130466616b65310d300b060355040a130466616b65310d300b060355040b130466616b65310d300b0603550403130466616b6530820122300d06092a864886f70d01010105000382010f003082010a0282010100b766ff6afd8a53edd4cee4985bc90e0c515157b5e9f731818961f7250d0d1ac7c7fb80eb5aeb8c28478732e8ff38cff574bfa0eba8039f73af1532f939c4ef9684719efbaba2dd3c583a20907c1c55248a63098c6da23dcfc877763d5fe6061dddd399cf2f49e3250e23f9e687a4d182bcd0662179ba4c9983448e34b4c83e5abbf4f87e87add9157c75fd40de3416744507a3517915f35b6fcad78766e8e1879df8ab823a6ffa335e4790f6e29c87393732025b63ce3a38e42cb0d48cdceb902f191d7d45823db9a0678895e8bfc59b2af7526ca4c2dc3dbe7e70c7c840e666b9629d36e5ddf1d9a80c37f1ab1bc1fb30432914008fbde95d5d3db7853565510203010001a321301f301d0603551d0e04160414d8513e1ae21c64e9ebeee3507e24ea375eef958e300d06092a864886f70d01010b0500038201010088bf20b36428558359536dddcfff16fe233656a92364cb544d8acc43b0859f880a8da339dd430616085edf035e4e6e6dd2281ceb14adde2f05e9ac58d547a09083eece0c6d405289cb7918f85754ee545eefe35e30c103cad617905e94eb4fb68e6920a60d30577855f9feb6e3a664856f74aa9f824aa7d4a3adf85e162c67b9a4261e3185f038ead96112ae3e574d280425e90567352fb82bc9173302122025eaecfabd94d0f9be69a85c415f7cf7759c9651734300952027b316c37aaa1b2418865a3fc7b6bd1072c92ccaacdaa1cf9586d9b8310ceee066ce68859107dfc45ccce729ad9e75b53b584fa37dcd64da8673b1279c6c5861ed3792deac156c8a";

    private ApkParser() {
    }

    static VPackage parse(File apkOrDir) throws Exception {
        File baseApk;
        File[] splitFiles = null;
        if (apkOrDir.isDirectory()) {
            baseApk = new File(apkOrDir, "base.apk");
            if (!baseApk.exists()) {
                throw new Exception("El directorio no contiene base.apk: " + apkOrDir);
            }
            File[] all = apkOrDir.listFiles();
            List<File> splits = new ArrayList<>();
            if (all != null) {
                for (File f : all) {
                    if (f.isFile() && f.getName().endsWith(".apk") && !f.getName().equals("base.apk")) {
                        splits.add(f);
                    }
                }
            }
            java.util.Collections.sort(splits);
            splitFiles = splits.toArray(new File[splits.size()]);
        } else {
            baseApk = apkOrDir;
        }
        if (!baseApk.exists()) {
            throw new Exception("El APK no existe: " + baseApk);
        }

        PackageInfo pi = getArchiveInfo(baseApk);
        if (pi == null || pi.applicationInfo == null) {
            throw new Exception("getPackageArchiveInfo devolvió null: " + baseApk);
        }

        VPackage pkg = new VPackage();
        pkg.packageName = pi.packageName;
        pkg.mVersionCode = pi.versionCode;
        pkg.mVersionName = pi.versionName;
        pkg.mSharedUserId = pi.sharedUserId;
        pkg.applicationInfo = pi.applicationInfo;
        pkg.mAppMetaData = pi.applicationInfo.metaData;
        if (pi.reqFeatures != null) {
            pkg.reqFeatures = new ArrayList<>(Arrays.asList(pi.reqFeatures));
        }
        if (pi.configPreferences != null) {
            pkg.configPreferences = new ArrayList<>(Arrays.asList(pi.configPreferences));
        }
        if (pi.requestedPermissions != null) {
            pkg.requestedPermissions = new ArrayList<>(Arrays.asList(pi.requestedPermissions));
        } else {
            pkg.requestedPermissions = new ArrayList<>(0);
        }
        pkg.mSignatures = pi.signatures;
        pkg.codePath = apkOrDir.isDirectory() ? apkOrDir.getPath() : baseApk.getPath();
        pkg.baseCodePath = baseApk.getPath();

        // Firma falsa por meta-data (función FAKE_PACKAGE_SIGNATURE del motor original).
        if (pkg.requestedPermissions.contains("android.permission.FAKE_PACKAGE_SIGNATURE")
                && pkg.mAppMetaData != null
                && pkg.mAppMetaData.containsKey("fake-signature")) {
            String sig = pkg.mAppMetaData.getString("fake-signature");
            if (sig != null) {
                pkg.mSignatures = new Signature[]{new Signature(sig)};
                VLog.d(TAG, "Using fake-signature feature on : " + pkg.packageName);
            }
        } else if (pkg.mSignatures == null
                && VirtualCore.get().getContext().getFileStreamPath(Constants.FAKE_SIGNATURE_FLAG).exists()) {
            pkg.mSignatures = new Signature[]{new Signature(FAKE_SIG)};
            VLog.w(TAG, "Using fake signature: " + pkg.packageName);
        }

        parseManifest(baseApk.getPath(), pi, pkg, splitFiles);
        return pkg;
    }

    private static PackageInfo getArchiveInfo(File apk) {
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_CONFIGURATIONS
                | PackageManager.GET_INSTRUMENTATION
                | PackageManager.GET_SIGNATURES;
        return VirtualCore.get().getUnHookPackageManager().getPackageArchiveInfo(apk.getPath(), flags);
    }

    // ------------------------------------------------------------------
    // Lectura del AndroidManifest.xml binario
    // ------------------------------------------------------------------

    private static XmlResourceParser openManifest(String apkPath) throws Exception {
        // Vía 1: pública (Resources del APK a partir de un ApplicationInfo sintético).
        try {
            ApplicationInfo probe = new ApplicationInfo();
            probe.packageName = VirtualCore.get().getHostPkg();
            probe.sourceDir = apkPath;
            probe.publicSourceDir = apkPath;
            android.content.res.Resources res =
                    VirtualCore.get().getUnHookPackageManager().getResourcesForApplication(probe);
            XmlResourceParser xml = res.getAssets().openXmlResourceParser("AndroidManifest.xml");
            if (xml != null) {
                return xml;
            }
        } catch (Throwable t) {
            VLog.d(TAG, "openManifest vía getResourcesForApplication falló: " + t);
        }
        // Vía 2: addAssetPath (oculto; cubierto por la exención "L" de HiddenApiBypass).
        AssetManager am = mirror.android.content.res.AssetManager.ctor.newInstance();
        if (am == null) {
            throw new Exception("No se pudo crear AssetManager para " + apkPath);
        }
        mirror.android.content.res.AssetManager.addAssetPath.call(am, apkPath);
        XmlResourceParser xml = am.openXmlResourceParser("AndroidManifest.xml");
        if (xml == null) {
            throw new Exception("openXmlResourceParser devolvió null para " + apkPath);
        }
        return xml;
    }

    private static void parseManifest(String baseApkPath, PackageInfo pi, VPackage pkg, File[] splitFiles)
            throws Exception {
        Map<String, ActivityInfo> actMap = new HashMap<>();
        Map<String, ActivityInfo> recvMap = new HashMap<>();
        Map<String, ServiceInfo> servMap = new HashMap<>();
        Map<String, ProviderInfo> provMap = new HashMap<>();
        if (pi.activities != null) {
            for (ActivityInfo ai : pi.activities) actMap.put(ai.name, ai);
        }
        if (pi.receivers != null) {
            for (ActivityInfo ai : pi.receivers) recvMap.put(ai.name, ai);
        }
        if (pi.services != null) {
            for (ServiceInfo si : pi.services) servMap.put(si.name, si);
        }
        if (pi.providers != null) {
            for (ProviderInfo pri : pi.providers) provMap.put(pri.name, pri);
        }

        ArrayList<VPackage.ActivityComponent> activities = new ArrayList<>();
        ArrayList<VPackage.ActivityComponent> receivers = new ArrayList<>();
        ArrayList<VPackage.ServiceComponent> services = new ArrayList<>();
        ArrayList<VPackage.ProviderComponent> providers = new ArrayList<>();
        ArrayList<String> protectedBroadcasts = new ArrayList<>(0);
        ArrayList<String> usesLibraries = new ArrayList<>(0);
        ArrayList<String> usesOptionalLibraries = new ArrayList<>(0);

        final int S_NONE = 0, S_ACTIVITY = 1, S_ALIAS = 2, S_RECEIVER = 3, S_SERVICE = 4, S_PROVIDER = 5;
        int state = S_NONE;
        String name = null, target = null, exported = null, label = null, icon = null;
        Bundle metaData = null;
        List<VPackage.IntentInfo> filters = new ArrayList<>();
        IntentFilter filter = null;
        boolean filterHasDefault = false;
        int filterLabelRes = 0;
        String filterNonLocalizedLabel = null;
        int filterIcon = 0;
        boolean inApplication = false;

        XmlResourceParser xml = openManifest(baseApkPath);
        currentParser = xml;
        try {
            int type;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    String tag = xml.getName();
                    if ("application".equals(tag)) {
                        inApplication = true;
                    } else if ("protected-broadcast".equals(tag)) {
                        String v = attr("name");
                        if (v != null) protectedBroadcasts.add(v);
                    } else if ("uses-library".equals(tag)) {
                        String v = attr("name");
                        if (v != null) {
                            if ("false".equals(attr("required"))) {
                                usesOptionalLibraries.add(v);
                            } else {
                                usesLibraries.add(v);
                            }
                        }
                    } else if (inApplication) {
                        if ("activity".equals(tag)) {
                            state = S_ACTIVITY;
                            name = attr("name");
                            target = null;
                            exported = attr("exported");
                            label = attr("label");
                            icon = attr("icon");
                            metaData = null;
                            filters.clear();
                        } else if ("activity-alias".equals(tag)) {
                            state = S_ALIAS;
                            name = attr("name");
                            target = attr("targetActivity");
                            exported = attr("exported");
                            label = attr("label");
                            icon = attr("icon");
                            metaData = null;
                            filters.clear();
                        } else if ("receiver".equals(tag)) {
                            state = S_RECEIVER;
                            name = attr("name");
                            exported = null;
                            label = null;
                            icon = null;
                            metaData = null;
                            filters.clear();
                        } else if ("service".equals(tag)) {
                            state = S_SERVICE;
                            name = attr("name");
                            exported = null;
                            label = null;
                            icon = null;
                            metaData = null;
                            filters.clear();
                        } else if ("provider".equals(tag)) {
                            state = S_PROVIDER;
                            name = attr("name");
                            exported = null;
                            label = null;
                            icon = null;
                            metaData = null;
                            filters.clear();
                        } else if ("intent-filter".equals(tag) && state != S_NONE) {
                            filter = new IntentFilter();
                            filterHasDefault = false;
                            filterLabelRes = 0;
                            filterNonLocalizedLabel = null;
                            filterIcon = 0;
                            String pr = attr("priority");
                            if (pr != null) {
                                try {
                                    filter.setPriority(Integer.parseInt(pr));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        } else if (filter != null && "action".equals(tag)) {
                            String v = attr("name");
                            if (v != null) filter.addAction(v);
                        } else if (filter != null && "category".equals(tag)) {
                            String v = attr("name");
                            if (v != null) {
                                filter.addCategory(v);
                                if (Intent.CATEGORY_DEFAULT.equals(v)) {
                                    filterHasDefault = true;
                                }
                            }
                        } else if (filter != null && "data".equals(tag)) {
                            addData(filter, attr("mimeType"), attr("scheme"), attr("host"), attr("port"),
                                    attr("path"), attr("pathPrefix"), attr("pathPattern"));
                        } else if ("meta-data".equals(tag) && state != S_NONE) {
                            String k = attr("name");
                            if (k != null) {
                                Object v = metaValue(xml);
                                if (v != null) {
                                    if (metaData == null) metaData = new Bundle();
                                    putMeta(metaData, k, v);
                                }
                            }
                        }
                    }
                } else if (type == XmlPullParser.END_TAG) {
                    String tag = xml.getName();
                    if ("intent-filter".equals(tag)) {
                        if (filter != null && state != S_NONE) {
                            filters.add(new VPackage.IntentInfo(filter, filterHasDefault, filterLabelRes,
                                    filterNonLocalizedLabel, filterIcon, 0, 0));
                        }
                        filter = null;
                    } else if ("activity".equals(tag) && state == S_ACTIVITY) {
                        String cls = normalize(pi.packageName, name);
                        ActivityInfo info = actMap.get(cls);
                        if (info != null) {
                            activities.add(new VPackage.ActivityComponent(info, cls, info.metaData != null
                                    ? info.metaData : metaData, copyFilters(filters)));
                        }
                        state = S_NONE;
                    } else if ("activity-alias".equals(tag) && state == S_ALIAS) {
                        finishAlias(activities, pi, actMap, name, target, exported, label, icon, metaData, filters);
                        state = S_NONE;
                    } else if ("receiver".equals(tag) && state == S_RECEIVER) {
                        String cls = normalize(pi.packageName, name);
                        ActivityInfo info = recvMap.get(cls);
                        if (info != null) {
                            receivers.add(new VPackage.ActivityComponent(info, cls, info.metaData != null
                                    ? info.metaData : metaData, copyFilters(filters)));
                        }
                        state = S_NONE;
                    } else if ("service".equals(tag) && state == S_SERVICE) {
                        String cls = normalize(pi.packageName, name);
                        ServiceInfo info = servMap.get(cls);
                        if (info != null) {
                            services.add(new VPackage.ServiceComponent(info, cls, info.metaData != null
                                    ? info.metaData : metaData, copyServiceFilters(filters)));
                        }
                        state = S_NONE;
                    } else if ("provider".equals(tag) && state == S_PROVIDER) {
                        String cls = normalize(pi.packageName, name);
                        ProviderInfo info = provMap.get(cls);
                        if (info != null) {
                            providers.add(new VPackage.ProviderComponent(info, cls, info.metaData != null
                                    ? info.metaData : metaData, copyProviderFilters(filters)));
                        }
                        state = S_NONE;
                    } else if ("application".equals(tag)) {
                        inApplication = false;
                        if (state != S_NONE) {
                            state = S_NONE; // componente sin cierre esperado
                        }
                    }
                }
            }
        } finally {
            currentParser = null;
            xml.close();
        }

        pkg.activities = activities;
        pkg.receivers = receivers;
        pkg.services = services;
        pkg.providers = providers;
        pkg.protectedBroadcasts = protectedBroadcasts;
        pkg.usesLibraries = new ArrayList<>(usesLibraries);
        pkg.usesOptionalLibraries = new ArrayList<>(usesOptionalLibraries);

        // Permissions declarados por el APK (la API pública los da; los grupos de
        // permisos se eliminaron de PackageInfo en API 26 y quedan vacíos).
        pkg.permissions = new ArrayList<>(0);
        if (pi.permissions != null) {
            for (PermissionInfo pinfo : pi.permissions) {
                pkg.permissions.add(new VPackage.PermissionComponent(pinfo, pinfo.name,
                        pinfo.metaData, new ArrayList<VPackage.IntentInfo>(0)));
            }
        }
        pkg.permissionGroups = new ArrayList<>(0);
        pkg.instrumentation = new ArrayList<>(0);
        if (pi.instrumentation != null) {
            for (InstrumentationInfo iinfo : pi.instrumentation) {
                pkg.instrumentation.add(new VPackage.InstrumentationComponent(iinfo, iinfo.name,
                        iinfo.metaData, new ArrayList<VPackage.IntentInfo>(0)));
            }
        }

        // Splits: nombre real desde el atributo android:split de cada manifest.
        if (splitFiles != null && splitFiles.length > 0) {
            String[] splitNames = new String[splitFiles.length];
            String[] splitCodePaths = new String[splitFiles.length];
            for (int i = 0; i < splitFiles.length; i++) {
                String sn = readSplitName(splitFiles[i].getPath());
                splitNames[i] = sn != null ? sn
                        : splitFiles[i].getName().substring(0, splitFiles[i].getName().length() - 4);
                splitCodePaths[i] = splitFiles[i].getPath();
            }
            pkg.splitNames = splitNames;
            pkg.splitCodePaths = splitCodePaths;
        }
    }

    private static void finishAlias(List<VPackage.ActivityComponent> activities, PackageInfo pi,
                                    Map<String, ActivityInfo> actMap, String aliasName, String targetName,
                                    String exported, String label, String icon, Bundle aliasMetaData,
                                    List<VPackage.IntentInfo> filters) {
        String aliasCls = normalize(pi.packageName, aliasName);
        String targetCls = normalize(pi.packageName, targetName);
        ActivityInfo target = actMap.get(targetCls);
        if (aliasCls == null || target == null) {
            VLog.w(TAG, "activity-alias sin target válido: " + aliasName + " -> " + targetName);
            return;
        }
        ActivityInfo clone = new ActivityInfo(target);
        clone.name = aliasCls;
        clone.targetActivity = targetCls;
        if ("true".equals(exported)) {
            clone.exported = true;
        } else if ("false".equals(exported)) {
            clone.exported = false;
        }
        if (label != null) {
            if (label.startsWith("@")) {
                clone.labelRes = parseResourceId(label);
                clone.nonLocalizedLabel = null;
            } else {
                clone.nonLocalizedLabel = label;
                clone.labelRes = 0;
            }
        }
        if (icon != null && icon.startsWith("@")) {
            clone.icon = parseResourceId(icon);
        }
        activities.add(new VPackage.ActivityComponent(clone, aliasCls,
                aliasMetaData != null ? aliasMetaData : clone.metaData, copyFilters(filters)));
    }

    private static ArrayList<VPackage.ActivityIntentInfo> copyFilters(List<VPackage.IntentInfo> src) {
        ArrayList<VPackage.ActivityIntentInfo> out = new ArrayList<>(src.size());
        for (VPackage.IntentInfo info : src) {
            out.add(new VPackage.ActivityIntentInfo(info.filter, info.hasDefault, info.labelRes,
                    info.nonLocalizedLabel, info.icon, info.logo, info.banner));
        }
        return out;
    }

    private static ArrayList<VPackage.ServiceIntentInfo> copyServiceFilters(List<VPackage.IntentInfo> src) {
        ArrayList<VPackage.ServiceIntentInfo> out = new ArrayList<>(src.size());
        for (VPackage.IntentInfo info : src) {
            out.add(new VPackage.ServiceIntentInfo(info.filter, info.hasDefault, info.labelRes,
                    info.nonLocalizedLabel, info.icon, info.logo, info.banner));
        }
        return out;
    }

    private static ArrayList<VPackage.ProviderIntentInfo> copyProviderFilters(List<VPackage.IntentInfo> src) {
        ArrayList<VPackage.ProviderIntentInfo> out = new ArrayList<>(src.size());
        for (VPackage.IntentInfo info : src) {
            out.add(new VPackage.ProviderIntentInfo(info.filter, info.hasDefault, info.labelRes,
                    info.nonLocalizedLabel, info.icon, info.logo, info.banner));
        }
        return out;
    }

    private static void addData(IntentFilter filter, String mimeType, String scheme, String host, String port,
                                String path, String pathPrefix, String pathPattern) {
        try {
            if (mimeType != null) {
                filter.addDataType(mimeType);
            }
        } catch (IntentFilter.MalformedMimeTypeException ignored) {
        }
        if (scheme != null) {
            filter.addDataScheme(scheme);
        }
        if (host != null) {
            filter.addDataAuthority(host, port);
        }
        if (path != null) {
            filter.addDataPath(path, android.os.PatternMatcher.PATTERN_LITERAL);
        }
        if (pathPrefix != null) {
            filter.addDataPath(pathPrefix, android.os.PatternMatcher.PATTERN_PREFIX);
        }
        if (pathPattern != null) {
            filter.addDataPath(pathPattern, android.os.PatternMatcher.PATTERN_SIMPLE_GLOB);
        }
    }

    private static String attr(String localName) {
        return xmlParserAttr(localName);
    }

    private static XmlResourceParser currentParser;

    private static String xmlParserAttr(String localName) {
        if (currentParser == null) {
            return null;
        }
        return currentParser.getAttributeValue(ANDROID_NS, localName);
    }

    /**
     * Nombre de clase normalizado como hace PackageParser: ".Foo" -> "pkg.Foo",
     * "foo.Bar" -> tal cual, "Foo" -> "pkg.Foo".
     */
    private static String normalize(String pkg, String cls) {
        if (cls == null) {
            return null;
        }
        if (cls.startsWith(".")) {
            return pkg + cls;
        }
        if (cls.contains(".")) {
            return cls;
        }
        return pkg + "." + cls;
    }

    /** Valor de <meta-data>: recurso -> Integer(id), "true/false" -> Boolean, número -> Integer/Long, resto String. */
    private static Object metaValue(XmlResourceParser parser) {
        String value = parser.getAttributeValue(ANDROID_NS, "value");
        String resource = parser.getAttributeValue(ANDROID_NS, "resource");
        if (value != null) {
            if ("true".equals(value)) return Boolean.TRUE;
            if ("false".equals(value)) return Boolean.FALSE;
            if (value.startsWith("@")) {
                int id = parseResourceId(value);
                if (id != 0) return id;
                return value;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
            }
            return value;
        }
        if (resource != null) {
            int id = parseResourceId(resource);
            if (id != 0) return id;
        }
        return null;
    }

    private static void putMeta(Bundle b, String key, Object v) {
        if (v instanceof Integer) b.putInt(key, (Integer) v);
        else if (v instanceof Long) b.putLong(key, (Long) v);
        else if (v instanceof Boolean) b.putBoolean(key, (Boolean) v);
        else if (v instanceof String) b.putString(key, (String) v);
        else b.putString(key, String.valueOf(v));
    }

    /** "@ref/0x7f0b0001", "@2131623945", "0x7f0b0001" o "2131623945" -> int; si no, 0. */
    private static int parseResourceId(String v) {
        try {
            String s = v;
            if (s.startsWith("@ref/")) s = s.substring(5);
            else if (s.startsWith("@")) s = s.substring(1);
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) Long.parseLong(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readSplitName(String apkPath) {
        try {
            XmlResourceParser xml = openManifest(apkPath);
            try {
                while (xml.next() != XmlPullParser.START_TAG) {
                    if (xml.getEventType() == XmlPullParser.END_DOCUMENT) {
                        return null;
                    }
                }
                return xml.getAttributeValue(ANDROID_NS, "split");
            } finally {
                xml.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
