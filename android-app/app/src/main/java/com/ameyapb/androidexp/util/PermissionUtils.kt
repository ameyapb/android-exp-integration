package com.ameyapb.androidexp.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
