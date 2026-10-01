package com.droiddeck.launcher.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import com.droiddeck.launcher.MainActivity

/**
 * Where PackageInstaller answers an update, invisibly. Android ends the app to replace it and a
 * receiver can't open it again from the background, but an activity started by the installer's
 * own answer may: on success this opens the new build. Every answer also goes on to the Updates
 * page as an [SelfInstaller.ACTION_STATUS] broadcast, for the confirm prompt and the errors.
 */
class InstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        sendBroadcast(Intent(intent).setAction(SelfInstaller.ACTION_STATUS).setComponent(null).setPackage(packageName))
        if (status == PackageInstaller.STATUS_SUCCESS) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
    }
}
