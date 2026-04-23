package com.dualframe.ui

import android.content.Context
import android.content.Intent

fun buildGalleryIntent(context: Context): Intent {
    val samsungPackages = listOf("com.sec.android.gallery3d", "com.samsung.android.gallery")
    val pm = context.packageManager
    for (pkg in samsungPackages) {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) return launchIntent
    }
    val galleryIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_GALLERY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (galleryIntent.resolveActivity(pm) != null) return galleryIntent
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
    }
}
