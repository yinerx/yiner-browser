package io.yiner.browser.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.yiner.browser.core.update.ArchiveFacts
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 读取已安装应用与 APK 文件的包名、versionCode、签名证书。 */
object PackageFacts {

    fun installedSignerDigests(context: Context): Set<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(signingFlag().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, signingFlag())
        }
        return signerDigests(info)
    }

    fun archive(context: Context, apk: File): ArchiveFacts? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(signingFlag().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, signingFlag())
        } ?: return null
        return ArchiveFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), signerDigests(info))
    }

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /**
     * 当前签名证书集合。API 28+ 用 apkContentsSigners（只含当前签名者，不含轮换前的旧证书），
     * 更早的系统用 signatures。
     */
    @SuppressLint("PackageManagerGetSignatures")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
