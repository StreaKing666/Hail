package com.aistra.hail.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object AtomicFileStorage {
    fun write(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Target has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create storage")
        val temporary = File.createTempFile(".hail.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            // Android uses same-directory POSIX rename: old or new bytes remain visible.
            if (!temporary.renameTo(target)) throw IOException("Could not save storage")
        } finally {
            temporary.delete()
        }
    }
}
