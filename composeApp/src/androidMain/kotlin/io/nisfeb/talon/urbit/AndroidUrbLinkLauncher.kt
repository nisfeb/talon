package io.nisfeb.talon.urbit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.nisfeb.talon.util.Log

/**
 * Android [UrbLinkLauncher]. Fires an `ACTION_VIEW` intent for the
 * urb:// URI; Lattice (or any app that registers the scheme) catches
 * it. Resolves installed-or-not via the package manager so a tap on a
 * link with no handler surfaces the install prompt instead of an
 * ActivityNotFoundException.
 */
class AndroidUrbLinkLauncher(context: Context) : UrbLinkLauncher {
    // Application context — the launcher outlives any one Activity.
    private val appContext = context.applicationContext

    override fun open(url: String): UrbLaunchResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // NEW_TASK: launcher is held at application scope, not an
            //   Activity, so a new task is required or startActivity throws.
            // CLEAR_TOP | SINGLE_TOP: defensive against the receiver's
            //   launchMode. Lattice's MainActivity is `standard`, which
            //   means a bare NEW_TASK on a running Lattice silently
            //   foregrounds the task without delivering the new intent
            //   — Lattice opens but doesn't navigate. CLEAR_TOP + SINGLE_TOP
            //   routes the URI through onNewIntent on the existing
            //   instance with state preserved (per FLAG_ACTIVITY_CLEAR_TOP
            //   docs: "if FLAG_ACTIVITY_SINGLE_TOP is set then this Intent
            //   will be delivered to the current instance's onNewIntent()").
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        // queryIntentActivities is the reliable "is there a handler?"
        // check — resolveActivity can return the system resolver even
        // when nothing concrete handles the scheme.
        val handlers = appContext.packageManager.queryIntentActivities(intent, 0)
        if (handlers.isEmpty() && !isLatticeInstalled()) {
            return UrbLaunchResult.NotInstalled
        }
        return try {
            appContext.startActivity(intent)
            UrbLaunchResult.Opened
        } catch (e: Exception) {
            Log.w("UrbLinkLauncher", "startActivity failed for $url: ${e.message}")
            UrbLaunchResult.Failed
        }
    }

    /**
     * Fallback for when queryIntentActivities comes back empty even
     * though Lattice is installed (observed on some OEM ROMs where the
     * <queries><intent> visibility matcher silently mis-matches). The
     * <queries><package> entry in AndroidManifest.xml makes this lookup
     * succeed regardless of intent-filter resolution quirks. If Lattice
     * is present we attempt startActivity anyway — worst case it throws
     * ActivityNotFoundException and we report Failed.
     */
    private fun isLatticeInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(LATTICE_PACKAGE, 0)
        true
    }.getOrElse { e ->
        // PackageManager.NameNotFoundException → Lattice not installed,
        // which is the only expected miss path. Anything else (security
        // exception, etc.) we also treat as a miss so we don't shadow
        // the original NotInstalled report.
        if (e !is PackageManager.NameNotFoundException) {
            Log.w("UrbLinkLauncher", "getPackageInfo($LATTICE_PACKAGE) failed: ${e.message}")
        }
        false
    }

    private companion object {
        const val LATTICE_PACKAGE = "io.nisfeb.lattice"
    }
}
