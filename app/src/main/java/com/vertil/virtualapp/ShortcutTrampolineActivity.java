package com.vertil.virtualapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;

/**
 * Trampolín de los accesos directos del espacio virtual en la pantalla de inicio
 * de Android (petición del propietario 11-06: "no veo una nueva ventana").
 *
 * Cada clon puede fijar un icono en el home con SU nombre y SU icono; al tocarlo,
 * el launcher de Android inicia esta actividad (translúcida, sin historia) con el
 * paquete del clon en el extra, abre SU ventana y se cierra. Así el espacio virtual
 * se usa desde el Android normal como si fueran apps independientes, igual que hace
 * Parallel Space con sus clones.
 *
 * La actividad es exported porque la inicia el launcher del sistema; no expone nada
 * por sí misma: solo reenvía el paquete al motor del espacio virtual.
 */
public class ShortcutTrampolineActivity extends Activity {

    public static final String EXTRA_PACKAGE = "vertil.pkg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pkg = getIntent() != null ? getIntent().getStringExtra(EXTRA_PACKAGE) : null;
        if (pkg != null) {
            try {
                Intent launch = VirtualCore.get().getLaunchIntent(pkg, 0);
                if (launch != null) {
                    // Mismo camino obligatorio que en MainActivity: binder virtual del
                    // motor (servidor :x arranca el stub en :p). Un startActivity crudo
                    // abriría la app ORIGINAL instalada en el teléfono, no el clon.
                    VActivityManager.get().startActivity(launch, 0);
                }
            } catch (Throwable ignored) {
                // Regla del propietario: nunca un crash del propio trampolín;
                // el usuario puede iniciar el clon desde el panel de todos modos.
            }
        }
        finish();
    }
}
