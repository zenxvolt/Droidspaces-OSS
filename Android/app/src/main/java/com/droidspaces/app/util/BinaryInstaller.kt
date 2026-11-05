package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object BinaryInstaller {
    private const val INSTALL_PATH = Constants.INSTALL_PATH

    /**
     * Install the droidspaces and busybox binaries for this device's arch, and zstd when
     * the build ships it.
     */
    suspend fun install(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val droidspacesBinaryName = "droidspaces-${DeviceArch.suffix(context)}"
            val busyboxBinaryName = "busybox-${DeviceArch.suffix(context)}"

            // Always install to the canonical path. The daemon's g_self_path fix
            // means this is safe even while the daemon is running - the mv is
            // atomic and the daemon automatically re-execs the new binary.
            val droidspacesTargetPath = Constants.DROIDSPACES_BINARY_PATH
            val busyboxTargetPath = Constants.BUSYBOX_BINARY_PATH

            // Step 2: Create directories
            val mkdirResult = Shell.cmd("mkdir -p $INSTALL_PATH").exec()
            if (!mkdirResult.isSuccess) {
                return@withContext Result.failure(
                    Exception("Failed to create directory: ${mkdirResult.err.joinToString()}")
                )
            }

            // Step 3: Install droidspaces binary
            installBinary(context, droidspacesBinaryName, droidspacesTargetPath, "droidspaces")
                .getOrElse { error -> return@withContext Result.failure(error) }

            // Step 4: Install busybox binary
            installBinary(context, busyboxBinaryName, busyboxTargetPath, "busybox")
                .getOrElse { error -> return@withContext Result.failure(error) }

            // Step 4.5: zstd only serves .tar.zst, so a build or device that cannot supply it
            // still gets a working backend. ensureZstd tries again when something needs it.
            runCatching { installZstd(context) }

            // Step 5: Verification (scripts are handled by ModuleInstaller)

            if (!Shell.cmd("test -x $droidspacesTargetPath").exec().isSuccess)
                return@withContext Result.failure(Exception("Droidspaces binary verification failed: file is not executable"))
            if (!Shell.cmd("test -x $busyboxTargetPath").exec().isSuccess)
                return@withContext Result.failure(Exception("Busybox binary verification failed: file is not executable"))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Copy a bundled binary to [targetPath] as root.
     * Uses atomic move operation to avoid "text file busy" error when binary is running.
     * Strategy: Copy to temp file in same directory, then use mv (atomic on same filesystem).
     * This works even if the target binary is currently executing.
     */
    private fun installBinary(context: Context, assetName: String, targetPath: String, displayName: String): Result<Unit> {
        val assetManager = context.assets
        val inputStream = assetManager.open("binaries/$assetName")

        // Write to a temp file in app cache first (we can write here without root)
        val tempFile = File("${context.cacheDir}/$assetName")
        FileOutputStream(tempFile).use { output ->
            inputStream.copyTo(output)
        }
        inputStream.close()

        // The rename inside installFileDurably also dodges "text file busy" while the
        // daemon is running the old binary.
        val installResult = ContainerManager.installFileDurably(tempFile.absolutePath, targetPath, "755")
        tempFile.delete()
        if (!installResult.isSuccess) {
            return Result.failure(
                Exception("Failed to install $displayName: ${(installResult.out + installResult.err).joinToString()}")
            )
        }

        return Result.success(Unit)
    }

    private fun installZstd(context: Context): Result<Unit> =
        installBinary(context, "zstd-${DeviceArch.suffix(context)}", Constants.ZSTD_BINARY_PATH, "zstd")

    /**
     * Installs zstd if it is not there. The backend update check only compares the droidspaces
     * binary, so an app update that brings zstd would never reach a phone that already has the
     * backend. Export and import call this before they need it.
     */
    suspend fun ensureZstd(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (Shell.cmd("test -x ${Constants.ZSTD_BINARY_PATH}").exec().isSuccess) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            Shell.cmd("mkdir -p $INSTALL_PATH").exec()
            installZstd(context).getOrThrow()
        }
    }

    /**
     * After a live binary swap, send SIGUSR2 to the running daemon
     * so it acknowledges the update (used for logging).
     */
    suspend fun signalDaemon(): Unit = withContext(Dispatchers.IO) {
        val pidResult = Shell.cmd("cat ${Constants.DAEMON_PID_FILE} 2>/dev/null").exec()
        if (pidResult.isSuccess && pidResult.out.isNotEmpty()) {
            val pid = pidResult.out[0].trim()
            if (pid.isNotEmpty()) {
                Shell.cmd("kill -USR2 $pid 2>/dev/null").exec()
            }
        }
    }

}

