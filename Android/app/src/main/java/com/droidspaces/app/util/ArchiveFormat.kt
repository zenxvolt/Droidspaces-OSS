// SPDX-License-Identifier: GPL-3.0-or-later
package com.droidspaces.app.util

/**
 * The compressions a rootfs archive can come in. The picker check, the installer, the
 * config peek, the repo downloader and export all ask here, so a format added to this
 * enum cannot be forgotten in one of them.
 */
enum class ArchiveFormat(val ext: String, val mime: String) {
    GZIP(".gz", "application/gzip"),
    XZ(".xz", "application/x-xz"),
    ZSTD(".zst", "application/zstd");

    /** What an archive in this format is named with, e.g. ".tar.zst". */
    val suffix: String get() = ".tar$ext"

    /** The word export_container.sh takes after --format=, e.g. "zst". */
    val id: String get() = ext.drop(1)

    companion object {
        /** What export can write. BusyBox unpacks xz but has no compressor for it. */
        val exportable = listOf(GZIP, ZSTD)

        /** By the last extension, case ignored. Null for a name that ends in none of them. */
        fun fromName(name: String): ArchiveFormat? =
            entries.firstOrNull { name.endsWith(it.ext, ignoreCase = true) }

        /** By magic number, for bytes whose file name cannot be trusted. */
        fun fromMagic(head: ByteArray, size: Int): ArchiveFormat? {
            fun starts(vararg magic: Int) = size >= magic.size && magic.indices.all { head[it] == magic[it].toByte() }
            return when {
                starts(0x1F, 0x8B) -> GZIP
                starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> XZ
                starts(0x28, 0xB5, 0x2F, 0xFD) -> ZSTD
                else -> null
            }
        }
    }
}
